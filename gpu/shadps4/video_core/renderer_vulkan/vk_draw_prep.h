// SPDX-License-Identifier: GPL-2.0-or-later
// gow3: speculative draw preparation on worker threads (docs/parallel_gpu.md, steps 1-2).
//
// Every submitted graphics command buffer is copied. A scanner thread replays the register
// writes of the whole stream in order (ApplyGraphicsRegisterPacket, the same code the GPU
// thread runs) and records, per buffer, its starting checksum and a delta of the register
// blocks it wrote. Any number of workers (one per spare hardware thread) claim the nearest
// buffers ahead of the GPU thread, reach their starting state by applying deltas, and select
// the graphics pipeline and resource sharps of each direct draw. All helpers run as
// SCHED_IDLE: they only use idle cores. The GPU thread uses a prepared draw only when the
// running register checksums match and the flattened user data it computes itself equals the
// worker's; otherwise it takes the regular path. Workers never create or compile anything.

#pragma once

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <span>
#include <thread>
#include <vector>

#include "common/types.h"
#include "shader_recompiler/ir/passes/srt.h"
#include "video_core/amdgpu/regs.h"
#include "video_core/renderer_vulkan/vk_pipeline_cache.h"

namespace Vulkan {

struct PreparedStage {
    const Program* program;
    u64 hash;
    Shader::HwStage hw_stage;
    VAddr pgm_base;
    const u32* flat; ///< flattened user data the worker computed, owned by the submission
    u32 flat_size;
    // Resource sharps read from `flat` in the order of the program's resource lists, and the
    // texture description hash of each image (docs/parallel_gpu.md, step 2). Valid for the
    // GPU thread exactly when `flat` matched: sharps depend on nothing else.
    const AmdGpu::Image* image_sharps;
    const u64* image_hashes;
    const AmdGpu::Sampler* sampler_sharps;
    const AmdGpu::Buffer* buffer_sharps;
    u32 num_images, num_samplers, num_buffers;
};

/// Key of Rasterizer's texture description cache for a T# bound through `res`.
inline u64 ImageDescHash(const AmdGpu::Image& sharp, const Shader::ImageResource& res) {
    std::array<u64, 4> key;
    std::memcpy(key.data(), &sharp, sizeof(key));
    const u32 flags = u32(res.is_written) | u32(res.is_depth) << 1 | u32(res.is_array) << 2;
    u64 hash = flags * 0x9E3779B97F4A7C15ull;
    for (const u64 word : key) {
        hash = (hash ^ word) * 0xFF51AFD7ED558CCDull;
        hash ^= hash >> 32;
    }
    return hash;
}

/// Vertex inputs of a draw (the dynamic vertex input path): the attribute and binding
/// descriptions, the V# of each stream, and the stream memory merged into ranges.
struct PreparedVertexInputs {
    struct Range {
        VAddr base, end;
    };
    const vk::VertexInputAttributeDescription2EXT* attributes;
    const vk::VertexInputBindingDescription2EXT* bindings;
    const AmdGpu::Buffer* buffers;
    const u8* range_index; ///< per stream with memory: its entry in `ranges`
    const Range* ranges;
    u32 count, num_ranges;
    u64 buffers_hash; ///< XXH3 of `buffers` (object motion stream identity)
    bool valid;
};

/// uc3 R.20/R.21 (GOW3_PREP_TRUST): which guest memory changed since a prepared draw's worker
/// walked its resource tables. Every command-stream write and every written binding of a draw
/// or dispatch takes a sequence number and stamps the pages it covers (direct-mapped and
/// untagged, so a collision only invalidates more). Writes over 64 pages and events that can
/// release CPU-written data (waits) move the big/global stamps instead.
inline constexpr u32 SrtPageSlots = 1u << 16;
inline std::atomic<u64> g_srt_write_seq{0};
inline std::atomic<u64> g_srt_global_seq{0};
inline std::atomic<u64> g_srt_big_seq{0};
inline std::atomic<u64> g_srt_page_seq[SrtPageSlots];
/// Diagnostics: 0 waits (global), 1 page writes, 2 big writes, 3 packets that wrote without a
/// noted range (global).
inline std::atomic<u64> g_srt_bumps[4];
inline void SrtGlobalEvent(u32 source = 0) noexcept {
    const u64 seq = g_srt_write_seq.fetch_add(1, std::memory_order_acq_rel) + 1;
    g_srt_global_seq.store(seq, std::memory_order_release);
    g_srt_bumps[source].fetch_add(1, std::memory_order_relaxed);
}
/// uc3 R.22: the last big writes with their page ranges, so a big write only invalidates the
/// prepared draws that read inside it. Written and read on the command processor thread.
inline constexpr u32 SrtBigRing = 256;
struct SrtBigWrite {
    std::atomic<u64> seq{0};
    std::atomic<u64> first{0}, last{0};
};
inline SrtBigWrite g_srt_big_ring[SrtBigRing];
inline std::atomic<u64> g_srt_big_count{0};
inline void SrtNoteWrite(u64 address, u64 size) noexcept {
    if (size == 0) {
        return;
    }
    const u64 seq = g_srt_write_seq.fetch_add(1, std::memory_order_acq_rel) + 1;
    const u64 first = address >> 12, last = (address + size - 1) >> 12;
    if (last - first >= 64) {
        auto& slot = g_srt_big_ring[g_srt_big_count.fetch_add(1, std::memory_order_acq_rel) %
                                    SrtBigRing];
        slot.first.store(first, std::memory_order_relaxed);
        slot.last.store(last, std::memory_order_relaxed);
        slot.seq.store(seq, std::memory_order_release);
        g_srt_big_seq.store(seq, std::memory_order_release);
        g_srt_bumps[2].fetch_add(1, std::memory_order_relaxed);
        return;
    }
    for (u64 page = first; page <= last; ++page) {
        g_srt_page_seq[page % SrtPageSlots].store(seq, std::memory_order_release);
    }
    g_srt_bumps[1].fetch_add(1, std::memory_order_relaxed);
}
/// 0: unchanged since `snap`; 1 only a global event (R.22: the values may still be checked),
/// 2 a big write, 3 a page write.
inline u32 SrtChangedSince(u64 snap, const u64* pages, u32 count) noexcept {
    if (g_srt_big_seq.load(std::memory_order_acquire) > snap) {
        // Newest first, down to the first big write before the snapshot.
        const u64 total = g_srt_big_count.load(std::memory_order_acquire);
        u64 scanned = 0;
        for (u64 n = total; n > 0; --n, ++scanned) {
            if (scanned == SrtBigRing) {
                return 2;
            }
            const auto& slot = g_srt_big_ring[(n - 1) % SrtBigRing];
            if (slot.seq.load(std::memory_order_acquire) <= snap) {
                break;
            }
            const u64 first = slot.first.load(std::memory_order_relaxed);
            const u64 last = slot.last.load(std::memory_order_relaxed);
            for (u32 i = 0; i < count; ++i) {
                if (pages[i] >= first && pages[i] <= last) {
                    return 2;
                }
            }
        }
    }
    for (u32 i = 0; i < count; ++i) {
        if (g_srt_page_seq[pages[i] % SrtPageSlots].load(std::memory_order_acquire) > snap) {
            return 3;
        }
    }
    if (g_srt_global_seq.load(std::memory_order_acquire) > snap) {
        return 1;
    }
    return 0;
}

struct PreparedDraw {
    enum : u32 { Pending = 0, Ready = 1, Unavailable = 2 };
    std::atomic<u32> state{Pending};
    u64 reg_checksum{};
    u64 srt_snap{};        ///< R.21: g_srt_write_seq read before the worker's walk
    bool no_gpu_leaves{};  ///< R.20: the worker's walk found no GPU-written dwords
    u32 num_read_pages{};  ///< R.21: guest pages the walks read (0xFF: too many, untrusted)
    std::array<u64, 24> read_pages{};
    const Shader::SrtCheck* checks{}; ///< R.22: values the walks read, owned by the submission
    u32 num_checks{};
    GraphicsPipelineKey key{};
    u32 num_stages{};
    std::array<PreparedStage, MaxShaderStages> stages{};
    PreparedVertexInputs vertex{};
};

class DrawPreparation {
public:
    explicit DrawPreparation(PipelineCache& pipeline_cache);
    ~DrawPreparation();

    DrawPreparation(const DrawPreparation&) = delete;
    DrawPreparation& operator=(const DrawPreparation&) = delete;

    [[nodiscard]] bool Enabled() const noexcept {
        return worker_count != 0;
    }

    struct Submission;
    /// Game submit thread, outside the queue lock: copies and scans a top-level graphics
    /// command buffer. Null when disabled.
    std::shared_ptr<Submission> Build(std::span<const u32> commands);
    /// Game submit thread, under the queue lock: hands it to the workers in submission order.
    void Enqueue(u64 seq, std::shared_ptr<Submission> submission);

    /// GPU thread: brackets the processing of submission `seq`. The first call hands the
    /// workers the exact register state and checksum they start replaying from.
    void BeginSubmission(u64 seq, const AmdGpu::Regs& regs, u64 reg_checksum);
    void EndSubmission();

    /// GPU thread: the prepared state for the next direct draw of the current submission.
    const PreparedDraw* NextDraw();

    /// GPU thread: counts whether a prepared draw was used; prints every 5 s (GOW3_FRAME_STATS).
    void Count(bool used);

    struct Submission {
        u64 seq{};
        std::vector<u32> commands;
        std::unique_ptr<PreparedDraw[]> draws;
        u32 num_draws{};
        std::vector<std::unique_ptr<u32[]>> flat_chunks; ///< from the claiming worker
        u64 start_checksum{}, end_checksum{}; ///< set by the scanner
        AmdGpu::RegDelta delta;                ///< registers written, values at the end
        std::atomic<bool> scanned{false};
        std::atomic<bool> claimed{false};
        std::atomic<bool> gpu_done{false};
    };

private:
    void ScannerLoop(std::stop_token stop);
    void WorkerLoop(std::stop_token stop, u32 index);
    void Collect();

    PipelineCache& pipeline_cache;
    std::unique_ptr<AmdGpu::Regs> initial_regs; ///< state at the start of `baseline_seq`
    u64 initial_checksum{};
    u64 baseline_seq{};
    /// State at the start of the oldest queued buffer (`tail_seq`): a worker whose position
    /// was collected restarts from here. Guarded by `mutex`.
    std::unique_ptr<AmdGpu::Regs> tail_regs;
    u64 tail_seq{};
    u64 tail_checksum{};
    u64 rebases = 0; ///< scanner restarts from the GPU thread state (GOW3_FRAME_STATS)
    bool baseline_ready{}; ///< guarded by `mutex`
    std::mutex mutex;
    std::condition_variable_any cv;
    std::deque<std::shared_ptr<Submission>> submissions; ///< ordered by seq
    std::atomic<u64> gpu_seq{0};
    std::shared_ptr<Submission> current;

public:
    /// gow3: the current submission (prepared draws), for the draw recording thread to keep
    /// it alive while it still records its draws.
    [[nodiscard]] std::shared_ptr<const void> KeepCurrent() const {
        return current;
    }

private:
    u32 current_draw = 0;
    u64 used = 0, unused = 0;
    u32 worker_count = 0; ///< fixed before the worker threads start
    std::jthread scanner;
    std::vector<std::jthread> workers;
};

} // namespace Vulkan
