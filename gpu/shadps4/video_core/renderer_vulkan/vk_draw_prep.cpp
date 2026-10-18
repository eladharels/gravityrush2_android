// SPDX-License-Identifier: GPL-2.0-or-later
// gow3: speculative draw preparation on worker threads (see vk_draw_prep.h).

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "gow3_threads.h"
#include "gow3_toggles.h"
#include "common/thread.h"
#include "core/memory.h"
#include "video_core/amdgpu/liverpool.h"
#include "video_core/amdgpu/pm4_cmds.h"
#include "video_core/renderer_vulkan/vk_draw_prep.h"
#include "video_core/renderer_vulkan/liverpool_to_vk.h"
#include <xxhash.h>

namespace Vulkan {

namespace {

bool IsDirectDraw(AmdGpu::PM4ItOpcode opcode) {
    using AmdGpu::PM4ItOpcode;
    return opcode == PM4ItOpcode::DrawIndex2 || opcode == PM4ItOpcode::DrawIndexOffset2 ||
           opcode == PM4ItOpcode::DrawIndexAuto;
}

/// Walks the type-3 packets of a command buffer (type-2 padding skipped).
template <typename Func>
void ForEachPacket(std::span<const u32> commands, Func&& func) {
    for (size_t at = 0; at < commands.size();) {
        const auto* header = reinterpret_cast<const AmdGpu::PM4Header*>(commands.data() + at);
        if (header->type == 2) {
            ++at;
            continue;
        }
        if (header->type != 3) {
            return;
        }
        const size_t words = header->type3.NumWords() + 1;
        if (at + words > commands.size()) {
            return;
        }
        func(header);
        at += words;
    }
}

u32 DefaultWorkerCount() {
    if (const char* env = std::getenv("GOW3_PREP_WORKERS")) {
        return static_cast<u32>(std::clamp(std::atoi(env), 0, 16));
    }
    // Workers are SCHED_IDLE and claim whole buffers, so more of them only use more idle
    // cores: half the hardware threads (Steam Deck 4, 16-thread desktop 8).
    return std::clamp<u32>(Gow3Threads::Available() / 2, 1, 8);
}

/// Flattened user data of one submission: chunks never move, so the GPU thread can read a
/// prepared draw's data while the worker keeps appending.
struct FlatArena {
    static constexpr size_t ChunkWords = 16 * 1024;
    std::vector<std::unique_ptr<u32[]>> chunks;
    size_t used = ChunkWords;

    const u32* Append(const std::vector<u32>& data) {
        return AppendArray(data.data(), data.size());
    }

    /// Trivially copyable elements, 8-byte aligned (sharps hold u64 bit fields).
    template <typename T>
    const T* AppendArray(const T* data, size_t count) {
        static_assert(std::is_trivially_copyable_v<T> && alignof(T) <= 8);
        const size_t words = (count * sizeof(T) + sizeof(u32) - 1) / sizeof(u32);
        used = (used + 1) & ~size_t(1);
        if (chunks.empty() || words > ChunkWords - std::min(used, ChunkWords)) {
            chunks.push_back(std::make_unique<u32[]>(std::max(ChunkWords, words)));
            used = 0;
        }
        u32* dst = chunks.back().get() + used;
        if (count) {
            std::memcpy(dst, data, count * sizeof(T));
        }
        used += words;
        return reinterpret_cast<const T*>(dst);
    }
};

/// Reads the stage's resource sharps from the worker's flattened user data, so the GPU thread
/// only binds them (the sharp fetches and texture-description hashing were ~7% of it).
void PrepareResources(const Shader::Info& info, PreparedStage& stage, FlatArena& arena) {
    thread_local std::vector<AmdGpu::Image> images;
    thread_local std::vector<u64> hashes;
    thread_local std::vector<AmdGpu::Sampler> samplers;
    thread_local std::vector<AmdGpu::Buffer> buffers;
    images.clear();
    hashes.clear();
    samplers.clear();
    buffers.clear();
    for (const auto& res : info.images) {
        images.push_back(res.GetSharp(info));
        hashes.push_back(ImageDescHash(images.back(), res));
    }
    for (const auto& res : info.samplers) {
        samplers.push_back(res.GetSharp(info));
    }
    for (const auto& res : info.buffers) {
        buffers.push_back(res.IsSpecial() ? AmdGpu::Buffer::Null() : res.GetSharp(info));
    }
    stage.image_sharps = arena.AppendArray(images.data(), images.size());
    stage.image_hashes = arena.AppendArray(hashes.data(), hashes.size());
    stage.sampler_sharps = arena.AppendArray(samplers.data(), samplers.size());
    stage.buffer_sharps = arena.AppendArray(buffers.data(), buffers.size());
    stage.num_images = static_cast<u32>(images.size());
    stage.num_samplers = static_cast<u32>(samplers.size());
    stage.num_buffers = static_cast<u32>(buffers.size());
}

/// Rasterizer::BindVertexBuffers' pure part (GraphicsPipeline::GetVertexInputs for the dynamic
/// vertex input path, and the merge of the stream memory into ranges).
void PrepareVertexInputs(const Shader::Info& vs, const Shader::Gcn::FetchShaderData& fetch,
                         const AmdGpu::Regs& regs, PreparedVertexInputs& out, FlatArena& arena) {
    using InstanceIdType = Shader::Gcn::VertexAttribute::InstanceIdType;
    thread_local std::vector<vk::VertexInputAttributeDescription2EXT> attributes;
    thread_local std::vector<vk::VertexInputBindingDescription2EXT> bindings;
    thread_local std::vector<AmdGpu::Buffer> buffers;
    thread_local std::vector<PreparedVertexInputs::Range> ranges, merged;
    thread_local std::vector<u8> range_index;
    attributes.clear();
    bindings.clear();
    buffers.clear();
    ranges.clear();
    merged.clear();
    range_index.clear();
    for (u32 semantic = 0; semantic < fetch.attributes.size(); ++semantic) {
        const auto& attrib = fetch.attributes[semantic];
        const auto step_rate = attrib.GetStepRate();
        const auto buffer = attrib.GetSharp(vs);
        // The V# may come from user data the guest is still writing: an unknown format leaves
        // the inputs to the GPU thread, which reads them once the draw is submitted (the
        // assertion in SurfaceFormat stopped the process here).
        const vk::Format format =
            LiverpoolToVK::TrySurfaceFormat(buffer.GetDataFmt(), buffer.GetNumberFmt());
        if (format == vk::Format::eUndefined &&
            buffer.GetDataFmt() != AmdGpu::DataFormat::FormatInvalid) {
            out.valid = false;
            return;
        }
        attributes.push_back({
            .location = semantic,
            .binding = semantic,
            .format = format,
            .offset = 0,
        });
        bindings.push_back({
            .binding = semantic,
            .stride = buffer.GetStride(),
            .inputRate = step_rate == InstanceIdType::None ? vk::VertexInputRate::eVertex
                                                           : vk::VertexInputRate::eInstance,
            .divisor = step_rate == InstanceIdType::OverStepRate0 ? regs.vgt_instance_step_rate_0
                       : step_rate == InstanceIdType::OverStepRate1
                           ? regs.vgt_instance_step_rate_1
                           : 1u,
        });
        buffers.push_back(buffer);
        if (buffer.base_address != 0 && buffer.GetSize() > 0) {
            ranges.push_back({buffer.base_address, buffer.base_address + buffer.GetSize()});
        }
    }
    std::ranges::sort(ranges, {}, &PreparedVertexInputs::Range::base);
    for (const auto& range : ranges) {
        if (merged.empty() || merged.back().end < range.base) {
            merged.push_back(range);
        } else {
            merged.back().end = std::max(merged.back().end, range.end);
        }
    }
    for (const auto& buffer : buffers) {
        if (buffer.base_address != 0 && buffer.GetSize() > 0) {
            const auto it = std::ranges::find_if(merged, [&](const auto& range) {
                return buffer.base_address >= range.base && buffer.base_address < range.end;
            });
            range_index.push_back(static_cast<u8>(it - merged.begin()));
        }
    }
    out.attributes = arena.AppendArray(attributes.data(), attributes.size());
    out.bindings = arena.AppendArray(bindings.data(), bindings.size());
    out.buffers = arena.AppendArray(buffers.data(), buffers.size());
    out.range_index = arena.AppendArray(range_index.data(), range_index.size());
    out.ranges = arena.AppendArray(merged.data(), merged.size());
    out.count = static_cast<u32>(attributes.size());
    out.num_ranges = static_cast<u32>(merged.size());
    out.buffers_hash =
        buffers.empty() ? 0 : XXH3_64bits(buffers.data(), buffers.size() * sizeof(AmdGpu::Buffer));
    out.valid = true;
}

} // namespace

DrawPreparation::DrawPreparation(PipelineCache& pipeline_cache_)
    : pipeline_cache{pipeline_cache_} {
    worker_count = DefaultWorkerCount();
    if (worker_count) {
        scanner = std::jthread([this](std::stop_token stop) { ScannerLoop(stop); });
    }
    for (u32 i = 0; i < worker_count; ++i) {
        workers.emplace_back([this, i](std::stop_token stop) { WorkerLoop(stop, i); });
    }
    std::printf("GPU: draw preparation workers: %u (+1 scanner, %u hardware threads)\n",
                worker_count, Gow3Threads::Available());
}

DrawPreparation::~DrawPreparation() {
    for (auto& worker : workers) {
        worker.request_stop();
    }
    if (scanner.joinable()) {
        scanner.request_stop();
    }
    cv.notify_all();
    workers.clear();
    scanner = {};
}

std::shared_ptr<DrawPreparation::Submission> DrawPreparation::Build(
    std::span<const u32> commands) {
    if (!Enabled()) {
        return {};
    }
    auto submission = std::make_shared<Submission>();
    submission->commands.assign(commands.begin(), commands.end());
    ForEachPacket(submission->commands, [&](const AmdGpu::PM4Header* header) {
        submission->num_draws += IsDirectDraw(header->type3.opcode);
    });
    submission->draws = std::make_unique<PreparedDraw[]>(submission->num_draws);
    return submission;
}

void DrawPreparation::Enqueue(u64 seq, std::shared_ptr<Submission> submission) {
    if (!submission) {
        return;
    }
    submission->seq = seq;
    {
        std::scoped_lock lk{mutex};
        submissions.push_back(std::move(submission));
    }
    cv.notify_all();
}

void DrawPreparation::BeginSubmission(u64 seq, const AmdGpu::Regs& regs, u64 reg_checksum) {
    current_draw = 0;
    current.reset();
    if (!Enabled()) {
        return;
    }
    gpu_seq.store(seq, std::memory_order_release);
    std::scoped_lock lk{mutex};
    if (!baseline_ready) {
        initial_regs = std::make_unique<AmdGpu::Regs>(regs);
        tail_regs = std::make_unique<AmdGpu::Regs>(regs);
        initial_checksum = tail_checksum = reg_checksum;
        baseline_seq = tail_seq = seq;
        baseline_ready = true;
        cv.notify_all();
    }
    // Idle helpers can starve on a busy CPU. Rather than queueing without bound, restart
    // them from the GPU thread's own state (one register copy) once the scanner lags.
    constexpr u64 MaxScanLag = 64;
    if (!submissions.empty() && seq > tail_seq + MaxScanLag &&
        submissions.front()->seq < seq && !submissions.front()->scanned.load()) {
        *tail_regs = regs;
        tail_seq = seq;
        tail_checksum = reg_checksum;
        while (!submissions.empty() && submissions.front()->seq < seq) {
            submissions.pop_front();
        }
        ++rebases;
        cv.notify_all();
    }
    if (!submissions.empty() && seq >= submissions.front()->seq &&
        seq - submissions.front()->seq < submissions.size()) {
        current = submissions[seq - submissions.front()->seq];
    }
}

void DrawPreparation::EndSubmission() {
    if (current) {
        current->gpu_done.store(true, std::memory_order_release);
        current.reset();
    }
    if (Enabled()) {
        Collect();
    }
}

const PreparedDraw* DrawPreparation::NextDraw() {
    if (!current || current_draw >= current->num_draws) {
        return nullptr;
    }
    const PreparedDraw* draw = &current->draws[current_draw++];
    if (Gow3Toggle::Disabled(Gow3Toggle::DrawPreparation)) {
        return nullptr;
    }
    return draw;
}

void DrawPreparation::Collect() {
    std::scoped_lock lk{mutex};
    while (!submissions.empty()) {
        const auto& front = submissions.front();
        if (!baseline_ready || !front->gpu_done.load(std::memory_order_acquire)) {
            break;
        }
        if (front->seq >= baseline_seq) {
            // Its delta moves the restart state to the start of the next buffer.
            if (!front->scanned.load(std::memory_order_acquire)) {
                break;
            }
            front->delta.Apply(*tail_regs);
            tail_seq = front->seq + 1;
            tail_checksum = front->end_checksum;
        }
        submissions.pop_front();
    }
}

void DrawPreparation::ScannerLoop(std::stop_token stop) {
    Common::SetCurrentThreadName("gow3:DrawScan");
    Gow3Threads::MakeBackground();
    std::unique_ptr<AmdGpu::Regs> regs;
    u64 checksum = 0;
    u64 next_seq = 0;
    {
        std::unique_lock lk{mutex};
        cv.wait(lk, stop, [&] { return baseline_ready; });
        if (stop.stop_requested()) {
            return;
        }
        regs = std::make_unique<AmdGpu::Regs>(*initial_regs);
        checksum = initial_checksum;
        next_seq = baseline_seq;
    }
    AmdGpu::RegDirty dirty;
    while (!stop.stop_requested()) {
        std::shared_ptr<Submission> submission;
        {
            std::unique_lock lk{mutex};
            cv.wait(lk, stop, [&] {
                return next_seq < tail_seq ||
                       (!submissions.empty() && submissions.back()->seq >= next_seq);
            });
            if (stop.stop_requested()) {
                return;
            }
            if (next_seq < tail_seq) {
                // Rebased by the GPU thread: continue from its state.
                *regs = *tail_regs;
                checksum = tail_checksum;
                next_seq = tail_seq;
                continue;
            }
            // Unscanned buffers are only collected by a rebase, so `next_seq` is still queued.
            submission = submissions[next_seq - submissions.front()->seq];
        }
        submission->start_checksum = checksum;
        dirty.Clear();
        ForEachPacket(submission->commands, [&](const AmdGpu::PM4Header* header) {
            AmdGpu::Liverpool::ApplyGraphicsRegisterPacket(*regs, header, checksum, &dirty);
        });
        submission->delta.Capture(*regs, dirty);
        submission->end_checksum = checksum;
        {
            std::scoped_lock lk{mutex};
            submission->scanned.store(true, std::memory_order_release);
        }
        ++next_seq;
        cv.notify_all();
    }
}

void DrawPreparation::WorkerLoop(std::stop_token stop, u32 index) {
    Common::SetCurrentThreadName(("gow3:DrawPrep" + std::to_string(index)).c_str());
    Gow3Threads::MakeBackground();
    auto regs = std::make_unique<AmdGpu::Regs>();
    constexpr u64 NoPosition = ~0ull;
    u64 position = NoPosition; ///< `regs` holds the state at the start of this buffer
    PipelineSelection sel{};
    sel.regs = regs.get();
    PrepWorker prep_worker{};
    sel.worker = &prep_worker;
    FlatArena arena;
    std::vector<std::shared_ptr<Submission>> path;

    // The nearest scanned buffer the GPU thread has not reached and no worker has claimed.
    const auto claimable = [&]() -> std::shared_ptr<Submission> {
        const u64 gpu = gpu_seq.load(std::memory_order_acquire);
        for (const auto& submission : submissions) {
            if (submission->seq >= gpu && submission->seq >= tail_seq &&
                submission->scanned.load(std::memory_order_acquire) &&
                !submission->claimed.load(std::memory_order_relaxed)) {
                return submission;
            }
        }
        return {};
    };

    while (!stop.stop_requested()) {
        std::shared_ptr<Submission> target;
        {
            std::unique_lock lk{mutex};
            // gpu_seq moves without the lock: keep the candidate the predicate found.
            cv.wait(lk, stop, [&] {
                target = baseline_ready ? claimable() : nullptr;
                return target != nullptr;
            });
            if (stop.stop_requested() || !target) {
                return;
            }
            target->claimed.store(true, std::memory_order_relaxed);
            // Buffers between this worker's state and the target: their deltas. A position
            // already collected (or past the target) restarts from the queue's tail state.
            if (position == NoPosition || position < tail_seq || position > target->seq) {
                *regs = *tail_regs;
                position = tail_seq;
            }
            const u64 front = submissions.front()->seq;
            path.clear();
            for (u64 seq = position; seq < target->seq; ++seq) {
                path.push_back(submissions[seq - front]);
            }
        }
        for (const auto& step : path) {
            step->delta.Apply(*regs);
        }
        path.clear();
        u64 checksum = target->start_checksum;
        u32 ordinal = 0;
        arena = FlatArena{};
        bool after_wait = false;
        // R.10 A1: 1 after a CPU WaitRegMem only (the values the walk read decide), 2 after a
        // MemSemaphore or Rewind (not trusted).
        u8 wait_kind = 0;
        // Waits found met and the pages of their labels, copied to the arena for the draws
        // after them when they changed: when a page was written since the snapshot (taken
        // before the label is read), the draw re-tests the waits on it.
        thread_local std::vector<u64> wait_pages;
        thread_local std::vector<SrtMetWait> met_waits;
        wait_pages.clear();
        met_waits.clear();
        bool waits_changed = false;
        const u64* arena_wait_pages = nullptr;
        const SrtMetWait* arena_met_waits = nullptr;
        u64 wait_snap = ~0ULL;
        // R.10 A1 (Fable): consecutive prepared draws with no CPU wait between them, whose
        // equal runs need comparing once. Draw k held by value at t_k, so a run of k+1 with
        // k's bytes matched memory then; after t_k packet writes and landings are on the page
        // stamps (why 2/3 first), and the CPU only writes across a wait not yet met here,
        // which clears prev_draw. A wait met here was signalled before both walks.
        const PreparedDraw* prev_draw = nullptr;
        const auto met_wait = [&](const SrtMetWait& wait, u64 snap) {
            wait_snap = std::min(wait_snap, snap);
            bool known = false;
            for (const auto& other : met_waits) {
                known |= other.address == wait.address && other.ref == wait.ref &&
                         other.mask == wait.mask && other.function == wait.function &&
                         other.semaphore == wait.semaphore;
            }
            if (!known) {
                met_waits.push_back(wait);
                waits_changed = true;
            }
            const u64 page = wait.address >> 12;
            if (std::find(wait_pages.begin(), wait_pages.end(), page) == wait_pages.end()) {
                wait_pages.push_back(page);
                waits_changed = true;
            }
        };
        ForEachPacket(target->commands, [&](const AmdGpu::PM4Header* header) {
            AmdGpu::Liverpool::ApplyGraphicsRegisterPacket(*regs, header, checksum);
            if (!IsDirectDraw(header->type3.opcode)) {
                // uc3 R.10 A1: a wait on memory the CPU has not released yet may release data
                // written after this walk: the draws after it in this submission are not
                // trusted. A wait already met released its data before (no race on PS4).
                // R.10 A1 (a): labels this submission writes, for the waits after them.
                if (SrtA1()) {
                    AmdGpu::Liverpool::SrtNoteGpuLabelPacket(header, target->seq);
                }
                if (SrtA1() && wait_kind < 2) {
                    using AmdGpu::PM4ItOpcode;
                    const auto opcode = header->type3.opcode;
                    if (opcode == PM4ItOpcode::WaitRegMem) {
                        const auto* wait = reinterpret_cast<const AmdGpu::PM4CmdWaitRegMem*>(header);
                        if (wait->mem_space.Value() ==
                            AmdGpu::PM4CmdWaitRegMem::MemSpace::Memory) {
                            const u64 snap = g_srt_write_seq.load(std::memory_order_acquire);
                            u32 value = 0;
                            Core::Memory::Instance()->CopySparseMemoryBacking(
                                wait->Address<VAddr>(), reinterpret_cast<u8*>(&value),
                                sizeof(value));
                            if (wait->TestValue(value)) {
                                met_wait({.address = wait->Address<VAddr>(),
                                          .ref = wait->ref,
                                          .mask = wait->mask,
                                          .function = static_cast<u8>(wait->function.Value()),
                                          .semaphore = 0},
                                         snap);
                            } else {
                                bool cpu_wait = true;
                                // R.10 A1 (a): met by a value a label write of this submission,
                                // or of one the GPU thread has not started, writes: GPU-GPU, it
                                // releases no CPU write.
                                g_srt_waits_unmet.fetch_add(1, std::memory_order_relaxed);
                                u64 label = 0, label_seq = 0;
                                if (SrtGpuLabelValue(wait->Address<VAddr>(), label, label_seq) &&
                                    (label_seq == target->seq ||
                                     (label_seq < target->seq &&
                                      label_seq > gpu_seq.load(std::memory_order_acquire))) &&
                                    wait->TestValue(static_cast<u32>(label))) {
                                    cpu_wait = false;
                                    g_srt_waits_gpu.fetch_add(1, std::memory_order_relaxed);
                                }
                                if (cpu_wait) {
                                    after_wait = true;
                                    prev_draw = nullptr;
                                    wait_kind = std::max<u8>(wait_kind, 1);
                                    g_srt_wait_kinds[0].fetch_add(1, std::memory_order_relaxed);
                                    SrtNoteCpuWait(wait->Address<VAddr>(),
                                                   static_cast<u32>(wait->function.Value()),
                                                   wait->ref);
                                }
                            }
                        }
                    } else if (opcode == PM4ItOpcode::MemSemaphore) {
                        const auto* sem = reinterpret_cast<const AmdGpu::PM4CmdMemSemaphore*>(header);
                        if (!sem->IsSignaling()) {
                            const u64 snap = g_srt_write_seq.load(std::memory_order_acquire);
                            u64 value = 0;
                            Core::Memory::Instance()->CopySparseMemoryBacking(
                                sem->Address<VAddr>(), reinterpret_cast<u8*>(&value),
                                sizeof(value));
                            if (value != 0) {
                                met_wait({.address = sem->Address<VAddr>(), .semaphore = 1},
                                         snap);
                            } else {
                                after_wait = true;
                                wait_kind = 2;
                                prev_draw = nullptr;
                                g_srt_waits_unmet.fetch_add(1, std::memory_order_relaxed);
                                g_srt_wait_kinds[1].fetch_add(1, std::memory_order_relaxed);
                            }
                        }
                    } else if (opcode == PM4ItOpcode::Rewind) {
                        after_wait = true;
                        wait_kind = 2;
                        g_srt_wait_kinds[2].fetch_add(1, std::memory_order_relaxed);
                    }
                }
                return;
            }
            // Past the GPU thread the draws are no longer worth preparing.
            if (ordinal < target->num_draws &&
                target->seq >= gpu_seq.load(std::memory_order_acquire)) {
                auto& draw = target->draws[ordinal];
                prep_worker.stages.clear();
                prep_worker.failed = false;
                sel.draw_indirect_params = {};
                prep_worker.read_pages.clear();
                prep_worker.ptr_pages.clear();
                prep_worker.guest_runs.clear();
                prep_worker.checks.clear();
                prep_worker.check_bytes.clear();
                const u64 srt_snap = g_srt_write_seq.load(std::memory_order_acquire);
                g_srt_walk_gpu_leaf = false;
                if (pipeline_cache.PrepareGraphicsPipeline(sel)) {
                    draw.reg_checksum = checksum;
                    draw.srt_snap = std::min(srt_snap, wait_snap);
                    draw.no_gpu_leaves = !(SrtA1() && g_srt_walk_gpu_leaf);
                    draw.after_wait = after_wait;
                    draw.wait_kind = wait_kind;
                    if (waits_changed) {
                        arena_wait_pages = arena.AppendArray(wait_pages.data(), wait_pages.size());
                        arena_met_waits = arena.AppendArray(met_waits.data(), met_waits.size());
                        waits_changed = false;
                    }
                    draw.num_wait_pages = static_cast<u32>(wait_pages.size());
                    draw.wait_pages = arena_wait_pages;
                    draw.num_met_waits = static_cast<u32>(met_waits.size());
                    draw.met_waits = arena_met_waits;
                    auto& pages = prep_worker.read_pages;
                    std::sort(pages.begin(), pages.end());
                    pages.erase(std::unique(pages.begin(), pages.end()), pages.end());
                    if (pages.size() > draw.read_pages.size()) {
                        draw.num_read_pages = 0xFF;
                    } else {
                        draw.num_read_pages = static_cast<u32>(pages.size());
                        std::copy(pages.begin(), pages.end(), draw.read_pages.begin());
                    }
                    draw.ptr_page_mask = 0;
                    if (draw.num_read_pages <= draw.read_pages.size()) {
                        for (const u64 page : prep_worker.ptr_pages) {
                            const auto it = std::lower_bound(pages.begin(), pages.end(), page);
                            if (it != pages.end() && *it == page) {
                                draw.ptr_page_mask |= 1u << u32(it - pages.begin());
                            }
                        }
                    }
                    // R.10 A1 (Fable): runs with the same range and bytes as in the previous
                    // draw of the same wait epoch.
                    auto& checks = prep_worker.checks;
                    const auto& bytes = prep_worker.check_bytes;
                    draw.dedup_prev = nullptr;
                    if (prev_draw) {
                        for (auto& check : checks) {
                            for (u32 j = 0; j < prev_draw->num_checks; ++j) {
                                const auto& other = prev_draw->checks[j];
                                if (other.host == check.host &&
                                    (other.size & ~Shader::SrtCheckSameAsPrev) == check.size &&
                                    std::memcmp(prev_draw->check_bytes + other.raw_off,
                                                bytes.data() + check.raw_off, check.size) == 0) {
                                    check.size |= Shader::SrtCheckSameAsPrev;
                                    draw.dedup_prev = prev_draw;
                                    break;
                                }
                            }
                        }
                    }
                    draw.checks = arena.AppendArray(checks.data(), checks.size());
                    draw.num_checks = static_cast<u32>(checks.size());
                    draw.check_bytes = arena.AppendArray(bytes.data(), bytes.size());
                    draw.guest_runs = arena.AppendArray(prep_worker.guest_runs.data(),
                                                        prep_worker.guest_runs.size());
                    draw.num_guest_runs = static_cast<u32>(prep_worker.guest_runs.size());
                    draw.key = sel.graphics_key;
                    draw.key_hash = std::hash<GraphicsPipelineKey>{}(draw.key);
                    draw.num_stages = static_cast<u32>(prep_worker.stages.size());
                    for (u32 i = 0; i < draw.num_stages; ++i) {
                        const auto& stage = prep_worker.stages[i];
                        draw.stages[i] = {
                            .program = stage.program,
                            .hash = stage.hash,
                            .hw_stage = stage.hw_stage,
                            .pgm_base = stage.pgm_base,
                            .flat = arena.Append(*stage.flat),
                            .flat_size = static_cast<u32>(stage.flat->size()),
                        };
                        const auto& stage_info = prep_worker.infos.at(stage.program);
                        draw.no_gpu_leaves = draw.no_gpu_leaves && stage_info.gpu_leaves.empty();
                        PrepareResources(stage_info, draw.stages[i], arena);
                    }
                    draw.vertex.valid = false;
                    const auto* vs = sel.infos[static_cast<u32>(Shader::SwStage::Vertex)];
                    if (vs && sel.fetch_shader) {
                        PrepareVertexInputs(*vs, *sel.fetch_shader, *regs, draw.vertex, arena);
                    }
                    draw.state.store(PreparedDraw::Ready, std::memory_order_release);
                    prev_draw = &draw;
                } else {
                    draw.state.store(PreparedDraw::Unavailable, std::memory_order_release);
                    prev_draw = nullptr;
                }
            } else {
                prev_draw = nullptr;
            }
            ++ordinal;
        });
        // The arena's chunks live as long as the submission.
        target->flat_chunks = std::move(arena.chunks);
        position = target->seq + 1;
    }
}

void DrawPreparation::Count(bool was_used) {
    ++(was_used ? used : unused);
    static const bool stats = std::getenv("GOW3_FRAME_STATS") != nullptr;
    // The clock is read every 1024 draws: per draw it was 8% of the GPU thread.
    if (!stats || ((used + unused) & 1023) != 0) {
        return;
    }
    static auto window = std::chrono::steady_clock::now();
    const auto now = std::chrono::steady_clock::now();
    if (now - window < std::chrono::seconds(5)) {
        return;
    }
    window = now;
    std::printf("Draw preparation: %llu prepared pipelines used, %llu not (%u workers, "
                "%llu scanner rebases)\n",
                static_cast<unsigned long long>(used), static_cast<unsigned long long>(unused),
                worker_count, static_cast<unsigned long long>(rebases));
    used = unused = 0;
}

bool SrtMetWaitsHold(const PreparedDraw& draw, u64 page) {
    for (u32 i = 0; i < draw.num_met_waits; ++i) {
        const auto& wait = draw.met_waits[i];
        if ((wait.address >> 12) != page) {
            continue;
        }
        if (wait.semaphore) {
            u64 value = 0;
            Core::Memory::Instance()->CopySparseMemoryBacking(
                wait.address, reinterpret_cast<u8*>(&value), sizeof(value));
            if (value == 0) {
                return false;
            }
            continue;
        }
        u32 value = 0;
        Core::Memory::Instance()->CopySparseMemoryBacking(
            wait.address, reinterpret_cast<u8*>(&value), sizeof(value));
        using Function = AmdGpu::PM4CmdWaitRegMem::Function;
        const u32 masked = value & wait.mask;
        bool holds;
        switch (static_cast<Function>(wait.function)) {
        case Function::Always:
            holds = true;
            break;
        case Function::LessThan:
            holds = masked < wait.ref;
            break;
        case Function::LessThanEqual:
            holds = masked <= wait.ref;
            break;
        case Function::Equal:
            holds = masked == wait.ref;
            break;
        case Function::NotEqual:
            holds = masked != wait.ref;
            break;
        case Function::GreaterThanEqual:
            holds = masked >= wait.ref;
            break;
        case Function::GreaterThan:
            holds = masked > wait.ref;
            break;
        default:
            holds = false;
            break;
        }
        if (!holds) {
            return false;
        }
    }
    return true;
}

} // namespace Vulkan
