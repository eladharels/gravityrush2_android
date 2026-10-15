// SPDX-FileCopyrightText: Copyright 2024 shadPS4 Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <chrono>
#include <limits>
#include <array>
#include <atomic>
#include <map>
#include <unordered_map>
#include <algorithm>
#include <bit>
#include <cstdlib>
#include <magic_enum/magic_enum.hpp>
#include "gow3_copy.h"
#include "gow3_toggles.h"
#include "gow3_write_log.h"
#include "common/alignment.h"
#include "core/debug_state.h"
#include "core/emulator_settings.h"
#include "core/memory.h"
#include "video_core/amdgpu/liverpool.h"
#include "video_core/buffer_cache/buffer.h"
#include "video_core/buffer_cache/buffer_cache.h"
#include "video_core/buffer_cache/memory_tracker.h"
#include "video_core/buffer_cache/region_definitions.h"
#include "video_core/renderer_vulkan/vk_graphics_pipeline.h"
#include "video_core/renderer_vulkan/vk_instance.h"
#include "video_core/renderer_vulkan/vk_platform.h"
#include "video_core/renderer_vulkan/vk_runtime.h"
#include "video_core/renderer_vulkan/vk_scheduler.h"
#include "video_core/texture_cache/texture_cache.h"

#include <vk_mem_alloc.h>

namespace VideoCore {

static constexpr size_t GDS_BUFFER_SIZE = 64_KB;
static constexpr size_t NULL_INDEX_BUFFER_SIZE = 16;
static constexpr size_t STREAM_BUFFER_SIZE = 128_MB;
constexpr std::optional<u32> CmaskColorExpandedValue(u32 num_samples) {
    switch (num_samples) {
    case 1:
        return 0xFFFFFFFF;
    case 2:
        return 0xDDDDDDDD;
    case 4:
        return 0xEEEEEEEE;
    case 8:
        return 0xFFFFFFFF;
    default:
        return 0;
    }
}
static_assert(CmaskColorExpandedValue(1) == 0xFFFFFFFF);
static_assert(CmaskColorExpandedValue(2) == 0xDDDDDDDD);
static_assert(CmaskColorExpandedValue(4) == 0xEEEEEEEE);
static_assert(CmaskColorExpandedValue(8) == 0xFFFFFFFF);

static constexpr auto ARENA_USAGE =
    vk::BufferUsageFlagBits::eTransferSrc | vk::BufferUsageFlagBits::eTransferDst |
    vk::BufferUsageFlagBits::eUniformBuffer | vk::BufferUsageFlagBits::eStorageBuffer |
    vk::BufferUsageFlagBits::eIndexBuffer | vk::BufferUsageFlagBits::eVertexBuffer |
    vk::BufferUsageFlagBits::eIndirectBuffer | vk::BufferUsageFlagBits::eShaderDeviceAddress;

std::optional<u32> FindMemoryType(const vk::PhysicalDeviceMemoryProperties& properties,
                                  vk::MemoryPropertyFlags wanted, u32 memory_type_bits) {
    for (u32 i = 0; i < properties.memoryTypeCount; ++i) {
        if (((memory_type_bits >> i) & 1) == 0) {
            continue;
        }
        const auto flags = properties.memoryTypes[i].propertyFlags;
        if ((flags & wanted) == wanted) {
            return i;
        }
    }
    return std::nullopt;
}

BufferCache::BufferCache(const Vulkan::Instance& instance_, Vulkan::Scheduler& scheduler_,
                         Vulkan::Runtime& runtime_, AmdGpu::Liverpool* liverpool_,
                         TextureCache& texture_cache_, PageManager& tracker)
    : instance{instance_}, scheduler{scheduler_}, runtime{runtime_},
      staging_pool{runtime_.GetStagingPool()}, liverpool{liverpool_},
      memory{Core::Memory::Instance()}, texture_cache{texture_cache_},
      memory_tracker{std::make_unique<MemoryTracker>(tracker)},
      stream_buffer{instance, scheduler, MemoryType::Stream, STREAM_BUFFER_SIZE},
      gds_buffer{instance, 0, GDS_BUFFER_SIZE, MemoryType::Stream, "GDS Buffer"},
      memory_semaphore{instance, "memory"} {
    while (arena_page_bits > MIN_ARENA_PAGE_BITS &&
           (u64{2} << arena_page_bits) > instance.GetMaxBufferSize()) {
        --arena_page_bits;
    }
    arena_page_size = u64{1} << arena_page_bits;
    num_arena_pages = u64{1} << (ADDRESS_SPACE_BITS - arena_page_bits);
    if (arena_page_bits != MAX_ARENA_PAGE_BITS) {
        LOG_INFO(Render_Vulkan, "Buffer arenas of {} MiB (driver buffer limit {} MiB)",
                 arena_page_size >> 20, instance.GetMaxBufferSize() >> 20);
    }
    const vk::BufferCreateInfo probe_ci = {
        .flags =
            vk::BufferCreateFlagBits::eSparseBinding | vk::BufferCreateFlagBits::eSparseResidency,
        .size = arena_page_size,
        .usage = ARENA_USAGE,
        .sharingMode = vk::SharingMode::eExclusive,
    };
    const vk::DeviceBufferMemoryRequirements req_info = {
        .pCreateInfo = &probe_ci,
    };
    const auto device = instance.GetDevice();
    const auto reqs = device.getBufferMemoryRequirements(req_info).memoryRequirements;
    block_size = Common::AlignUp(std::max<u64>(reqs.alignment, MIN_BLOCK_SIZE), reqs.alignment);
    ASSERT_MSG(std::popcount(block_size) == 1, "Sparse block size {} is not a power of 2",
               block_size);
    block_shift = std::bit_width(block_size) - 1;
    blocks_per_arena_page = arena_page_size / block_size;
    blocks_per_arena_page_shift = arena_page_bits - block_shift;
    arena_memory_type_index =
        FindMemoryType(instance.GetMemoryProperties(), vk::MemoryPropertyFlagBits::eDeviceLocal,
                       reqs.memoryTypeBits)
            .value();

    const u64 bda_pagetable_size =
        (blocks_per_arena_page * num_arena_pages) * sizeof(vk::DeviceAddress);
    fault_manager = std::make_unique<FaultManager>(instance, scheduler, *this, block_shift,
                                                   blocks_per_arena_page * num_arena_pages);
    bda_pagetable_buffer = std::make_unique<Buffer>(
        instance, 0, bda_pagetable_size, MemoryType::DeviceLocal, "BDA Page Table Buffer");
    runtime.FillBuffer(bda_pagetable_buffer.get(), 0u, bda_pagetable_size, 0u);
    if (!instance.IsMaintenance6Supported()) {
        null_index_buffer = std::make_unique<Buffer>(instance, 0, NULL_INDEX_BUFFER_SIZE,
                                                     MemoryType::DeviceLocal, "Null Index Buffer");
        runtime.FillBuffer(null_index_buffer.get(), 0u, NULL_INDEX_BUFFER_SIZE, 0u);
    }
}

BufferCache::~BufferCache() {
    const vk::Device device = instance.GetDevice();
    if (sideq_pool) {
        if (instance.GetSideQueue()) {
            (void)instance.GetSideQueue().waitIdle();
        }
        for (auto& slot : sideq_slots) {
            if (slot.fence) {
                device.destroyFence(slot.fence);
            }
        }
        device.destroyCommandPool(sideq_pool);
    }
    if (side_fence) {
        device.destroyFence(side_fence);
    }
    if (side_pool) {
        device.destroyCommandPool(side_pool);
    }
}

void BufferCache::TickFrame() {
    ++frame_index;
    WaitAsyncReadbacks(1, 1); // an empty range: only frees finished readbacks' staging
    if (std::exchange(fault_process_pending, false)) {
        fault_manager->ProcessFaultBuffer();
    }
}

void BufferCache::InvalidateMemory(VAddr device_addr, u64 size, bool assume_locks,
                                   bool from_fault) {
    memory_tracker->InvalidateRegion(device_addr, size,
                                     [this, device_addr, size, assume_locks, from_fault] {
        ReadMemory(device_addr, size, true, assume_locks, from_fault);
    });
}

// gow3: the game fills its per-frame buffers (constants, skinning output) sequentially and
// every 4 KiB page cost a protection fault (~110k/s in the test scene, a fifth of each
// render worker's time in the kernel). A fault unprotects the aligned window around it instead;
// pages marked CPU-modified without being written only cost an upload when bound.
// Item: context = BufferCache, source = guest address, destination = host pointer, size,
// extra = the Buffer whose mapping holds the destination (flushed after the copy).
void BufferCache::RunGuestCopy(const Gow3Copy::Item& item) {
    auto* cache = static_cast<BufferCache*>(item.context);
    auto* dst = reinterpret_cast<u8*>(item.destination);
    cache->memory->CopySparseMemory(item.source, dst, item.size);
    auto* buffer = reinterpret_cast<Buffer*>(item.extra);
    buffer->Flush(dst - buffer->mapped_data.data(), item.size);
    NotePendingHostRead(item.source, item.size, -1);
}

// uc3 (Fable R.8.5): deferred host copies read guest memory later, on the recording and copy
// threads. A counter per 64 KiB guest page (hashed; a collision only costs an extra wait) says
// whether a read of that page is still pending, so the scalar copy HLE waits only when it is
// about to overwrite bytes an earlier upload has not read yet.
static constexpr u32 PendingReadBuckets = 1u << 16;
static std::array<std::atomic<u32>, PendingReadBuckets> pending_host_reads{};

static u32 PendingReadBucket(VAddr page) {
    return static_cast<u32>((page * 0x9E3779B97F4A7C15ULL) >> 48) & (PendingReadBuckets - 1);
}

bool BufferCache::PreciseHostReadWait() {
    static const bool enabled = [] {
        const char* env = std::getenv("GOW3_HLE_PRECISE_WAIT");
        return env && env[0] == '1';
    }();
    return enabled;
}

void BufferCache::NotePendingHostRead(VAddr addr, u64 size, int delta) {
    if (!PreciseHostReadWait() || size == 0) {
        return;
    }
    for (VAddr page = addr >> 16; page <= (addr + size - 1) >> 16; ++page) {
        auto& bucket = pending_host_reads[PendingReadBucket(page)];
        if (delta > 0) {
            bucket.fetch_add(1, std::memory_order_release);
        } else {
            bucket.fetch_sub(1, std::memory_order_release);
        }
    }
}

bool BufferCache::HasPendingHostReads(VAddr addr, u64 size) {
    if (size == 0) {
        return false;
    }
    for (VAddr page = addr >> 16; page <= (addr + size - 1) >> 16; ++page) {
        if (pending_host_reads[PendingReadBucket(page)].load(std::memory_order_acquire) != 0) {
            return true;
        }
    }
    return false;
}

// Small guest copies run on the recording thread (it spins for work: no wakeup, and it is
// idle most of the time); PoolSmallCopies (toggle 524288) batches them for the copy threads.
void BufferCache::SmallGuestCopy(const Gow3Copy::Item& item) {
    NotePendingHostRead(item.source, item.size, +1);
    if (scheduler.IsRecordingDeferred() && !Gow3Toggle::Disabled(Gow3Toggle::PoolSmallCopies)) {
        scheduler.RecordHostCopy([item] { item.run(item); });
        return;
    }
    Gow3Copy::QueueCopy(item);
}

void BufferCache::ExtendWriteFault(VAddr device_addr) {
    static const u64 window = [] {
        const char* env = std::getenv("GOW3_FAULT_WINDOW");
        const u64 kib = env ? std::strtoull(env, nullptr, 10) : 256;
        return std::bit_ceil(std::clamp<u64>(kib, 4, 1024)) * 1024;
    }();
    if (window <= BYTES_PER_PAGE || Gow3Toggle::Disabled(Gow3Toggle::FaultWindow)) {
        return;
    }
    memory_tracker->ExtendWriteFault(Common::AlignDown(device_addr, window), window);
}

// uc3: precise readbacks cost a fault, a 512 KiB window download and a full GPU wait per CPU
// access to a page the GPU wrote. GOW3_READBACK_ALL=1 makes one readback bring back every
// GPU-modified range of every arena with one wait, so the accesses that follow find nothing
// pending. OFF by default: it also brings back ranges the CPU never asked for, and a range
// marked GPU-modified at bind time whose memory the game has since reused (textures loaded
// from disk after a level reload) gets the arena's stale bytes written over the new data:
// corrupted textures and lightmaps after "menu -> new game" in Uncharted 3. The window
// download only touches what the CPU is reading, where the GPU's data is what it wants.
static bool ReadbackAll() {
    static const bool all = EmulatorSettings.GetReadbacksMode() == GpuReadbacksMode::Precise &&
                            EmulatorSettingsImpl::Flag("GOW3_READBACK_ALL", false);
    return all;
}

// uc3: the CPU reads the same GPU-written windows frame after frame (7 faults, 20 ms in one
// 50 ms frame at The Chateau), and each fault drains the GPU queue on its own.
// GOW3_READBACK_HOT=1: a fault also brings back the other windows faulted on in the last
// few frames, if the GPU wrote them again, under the same wait; the faults that would follow
// in this frame find them clean. Unlike GOW3_READBACK_ALL it only touches windows the CPU has
// been reading, where the GPU's data is what it wants. OFF by default until measured.
static bool ReadbackHot() {
    static const bool hot = EmulatorSettings.GetReadbacksMode() == GpuReadbacksMode::Precise &&
                            EmulatorSettingsImpl::Flag("GOW3_READBACK_HOT", false);
    return hot;
}

void BufferCache::NoteHotWindow(VAddr start, boost::container::small_vector<VAddr, 16>& others) {
    constexpr u64 HotFrames = 4;
    HotWindow* slot = nullptr;
    for (auto& window : hot_windows) {
        if (window.start == start) {
            slot = &window;
        } else if (window.start && frame_index - window.frame <= HotFrames &&
                   others.size() < others.capacity()) {
            others.push_back(window.start);
        }
    }
    if (!slot) {
        // Reuse the oldest slot.
        slot = &*std::ranges::min_element(hot_windows, {}, &HotWindow::frame);
    }
    *slot = HotWindow{start, frame_index};
}

void BufferCache::NoteGpuWrite(VAddr device_addr, u64 size) {
    const u64 tick = scheduler.CurrentTick();
    const VAddr first = device_addr >> WriteTickShift;
    const VAddr last = (device_addr + size - 1) >> WriteTickShift;
    // Huge bindings (whole arenas) would touch every slot; the readback windows are small.
    if (last - first >= 64) {
        big_write_tick.store(tick, std::memory_order_relaxed);
        return;
    }
    for (VAddr window = first; window <= last; ++window) {
        auto& slot = write_ticks[window % write_ticks.size()];
        slot.window.store(window, std::memory_order_relaxed);
        slot.tick.store(tick, std::memory_order_relaxed);
    }
}

u64 BufferCache::LastWriteTick(VAddr start, u64 size) const {
    u64 tick = big_write_tick.load(std::memory_order_relaxed);
    const VAddr last = (start + size - 1) >> WriteTickShift;
    for (VAddr window = start >> WriteTickShift; window <= last; ++window) {
        const auto& slot = write_ticks[window % write_ticks.size()];
        if (slot.window.load(std::memory_order_relaxed) != window) {
            return ~0ULL;
        }
        tick = std::max(tick, slot.tick.load(std::memory_order_relaxed));
    }
    return tick;
}

// uc3: most precise readback faults (about 85% in the streets of Cartagena) read windows whose
// last GPU write has already finished; the fault still submitted the command buffer being
// recorded and waited for all of it. GOW3_READBACK_FAST=1 copies those on the side: a command
// buffer of its own on the graphics queue runs right after the finished work. The noted tick
// is the one current when the buffer was bound; the draw can land in the next command buffer
// if a submit comes between, so the tick after it must be finished too.
static bool ReadbackFast() {
    static const bool fast = EmulatorSettings.GetReadbacksMode() == GpuReadbacksMode::Precise &&
                             EmulatorSettingsImpl::Flag("GOW3_READBACK_FAST", false);
    return fast;
}

bool BufferCache::SideDownload(std::span<const SideCopies> work, const Buffer* staging,
                               u64 write_tick) {
    if (!ReadbackFast() || write_tick == ~0ULL) {
        return false;
    }
    auto* semaphore = scheduler.GetWorkSemaphore();
    if (semaphore->KnownGpuTick() < write_tick + 1) {
        semaphore->Refresh();
        if (semaphore->KnownGpuTick() < write_tick + 1) {
            return false;
        }
    }
    const vk::Device device = instance.GetDevice();
    if (!side_pool) {
        side_pool = Vulkan::Check<"create readback command pool">(device.createCommandPool({
            .flags = vk::CommandPoolCreateFlagBits::eTransient |
                     vk::CommandPoolCreateFlagBits::eResetCommandBuffer,
            .queueFamilyIndex = instance.GetGraphicsQueueFamilyIndex(),
        }));
        const vk::CommandBufferAllocateInfo alloc_info = {
            .commandPool = side_pool,
            .level = vk::CommandBufferLevel::ePrimary,
            .commandBufferCount = 1U,
        };
        Vulkan::Check<"allocate readback command buffer">(
            device.allocateCommandBuffers(&alloc_info, &side_cmdbuf));
        side_fence = Vulkan::Check<"create readback fence">(device.createFence({}));
    }
    const auto start = std::chrono::steady_clock::now();
    Vulkan::Check(side_cmdbuf.reset());
    Vulkan::Check(side_cmdbuf.begin({.flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit}));
    const vk::MemoryBarrier2 before = {
        .srcStageMask = vk::PipelineStageFlagBits2::eAllCommands,
        .srcAccessMask = vk::AccessFlagBits2::eMemoryWrite,
        .dstStageMask = vk::PipelineStageFlagBits2::eCopy,
        .dstAccessMask = vk::AccessFlagBits2::eTransferRead,
    };
    side_cmdbuf.pipelineBarrier2(vk::DependencyInfo{
        .memoryBarrierCount = 1U,
        .pMemoryBarriers = &before,
    });
    for (const auto& entry : work) {
        side_cmdbuf.copyBuffer(entry.arena->Handle(), staging->Handle(),
                               static_cast<u32>(entry.copies.size()), entry.copies.data());
    }
    const vk::MemoryBarrier2 after = {
        .srcStageMask = vk::PipelineStageFlagBits2::eCopy,
        .srcAccessMask = vk::AccessFlagBits2::eTransferWrite,
        .dstStageMask = vk::PipelineStageFlagBits2::eHost,
        .dstAccessMask = vk::AccessFlagBits2::eHostRead,
    };
    side_cmdbuf.pipelineBarrier2(vk::DependencyInfo{
        .memoryBarrierCount = 1U,
        .pMemoryBarriers = &after,
    });
    Vulkan::Check(side_cmdbuf.end());
    scheduler.SubmitSide(side_cmdbuf, side_fence);
    Vulkan::Check(device.waitForFences(side_fence, true, std::numeric_limits<u64>::max()));
    Vulkan::Check(device.resetFences(side_fence));
    Gow3Stats::uc3_rb_side.fetch_add(1, std::memory_order_relaxed);
    Gow3Stats::uc3_rb_side_ns.fetch_add(
        std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() -
                                                             start)
            .count(),
        std::memory_order_relaxed);
    return true;
}

// uc3: where the last GPU write to a faulting window stands (see Gow3Stats::uc3_rb_*);
// GOW3_READBACK_TRACE=1 also prints the first 200 faults.
void BufferCache::ClassifyFault(VAddr window_start) {
    const VAddr window = window_start >> WriteTickShift;
    const auto& slot = write_ticks[window % write_ticks.size()];
    const u64 write_tick =
        slot.window.load(std::memory_order_relaxed) == window ? slot.tick.load(std::memory_order_relaxed)
                                                              : 0;
    const u64 current = scheduler.CurrentTick();
    auto* semaphore = scheduler.GetWorkSemaphore();
    semaphore->Refresh();
    const u64 gpu = semaphore->KnownGpuTick();
    if (write_tick == 0) {
        Gow3Stats::uc3_rb_unknown.fetch_add(1, std::memory_order_relaxed);
    } else if (write_tick >= current) {
        Gow3Stats::uc3_rb_open.fetch_add(1, std::memory_order_relaxed);
    } else if (write_tick > gpu) {
        Gow3Stats::uc3_rb_inflight.fetch_add(1, std::memory_order_relaxed);
        Gow3Stats::uc3_rb_inflight_ticks.fetch_add(current - write_tick, std::memory_order_relaxed);
    } else {
        Gow3Stats::uc3_rb_done.fetch_add(1, std::memory_order_relaxed);
    }
    static const bool trace = EmulatorSettingsImpl::Flag("GOW3_READBACK_TRACE", false);
    static std::atomic<int> traced{0};
    if (trace && traced.fetch_add(1, std::memory_order_relaxed) < 200) {
        std::printf("readback fault: window=0x%llx write_tick=%llu current_tick=%llu gpu_tick=%llu "
                    "frame=%llu\n",
                    (unsigned long long)window_start, (unsigned long long)write_tick,
                    (unsigned long long)current, (unsigned long long)gpu,
                    (unsigned long long)frame_index);
    }
}

// uc3: GOW3_READBACK_ASYNC=1 — a read fault no longer drains the GPU from the GPU thread
// (scheduler.Finish() left the GPU idle while that thread waited, then while it recorded
// again: ~34 of ~50 ms per frame in the streets). See AsyncReadback.
static bool ReadbackAsync() {
    static const bool async = EmulatorSettings.GetReadbacksMode() == GpuReadbacksMode::Precise &&
                              EmulatorSettingsImpl::Flag("GOW3_READBACK_ASYNC", false);
    return async;
}

void BufferCache::ReadMemory(VAddr device_addr, u64 size, bool is_write, bool assume_locks,
                             bool from_fault) {
    const bool async = from_fault && !assume_locks && ReadbackAsync();
    if (!async) {
        (assume_locks ? Gow3Stats::uc3_rb_sync_gpu
         : is_write   ? Gow3Stats::uc3_rb_sync_write
                      : Gow3Stats::uc3_rb_sync_other)
            .fetch_add(1, std::memory_order_relaxed);
    }
    std::shared_ptr<AsyncReadback> pending;
    const auto flush_request = [this, device_addr, size, is_write, async, &pending] {
        if (ReadbackAll()) {
            WaitAsyncReadbacks(0, 0);
            DownloadAllGpuModified();
            if (is_write) {
                memory_tracker->MarkRegionAsCpuModified(device_addr, size);
            }
            return;
        }
        const u32 first_block = device_addr >> block_shift;
        const u32 last_block = (device_addr + size - 1) >> block_shift;
        const auto* arena = GetArena(first_block, last_block);

        // GPU-modified ranges come as many small scattered islands,
        // so the download is widened to a window around the request
        constexpr u64 WindowSize = 512_KB;
        const VAddr arena_end = arena->cpu_addr + arena->size_bytes;
        const VAddr window_start =
            std::max<VAddr>(Common::AlignDown(device_addr, WindowSize), arena->cpu_addr);
        const VAddr window_end = std::min<VAddr>(
            std::max<VAddr>(window_start + WindowSize, device_addr + size), arena_end);
        ClassifyFault(Common::AlignDown(device_addr, WindowSize));
        boost::container::small_vector<DownloadWindow, 17> windows;
        windows.push_back({arena, window_start, window_end - window_start});
        if (ReadbackHot()) {
            boost::container::small_vector<VAddr, 16> others;
            NoteHotWindow(window_start, others);
            for (const VAddr start : others) {
                const auto* other = GetArena(start >> block_shift,
                                             (start + WindowSize - 1) >> block_shift);
                const VAddr begin = std::max<VAddr>(start, other->cpu_addr);
                const VAddr end = std::min<VAddr>(start + WindowSize,
                                                  other->cpu_addr + other->size_bytes);
                if (end <= begin || !memory_tracker->IsRegionGpuModified(begin, end - begin)) {
                    continue;
                }
                windows.push_back({other, begin, end - begin});
            }
            Gow3Stats::uc3_readback_hot.fetch_add(1, std::memory_order_relaxed);
            Gow3Stats::uc3_readback_hot_windows.fetch_add(windows.size() - 1,
                                                          std::memory_order_relaxed);
        }
        // A window still being read back asynchronously: its ranges are no longer in
        // gpu_modified_ranges, so let that readback land first.
        for (const auto& window : windows) {
            WaitAsyncReadbacks(window.start, window.start + window.size);
        }
        if (async) {
            // The faulting thread marks the written range itself, after the data lands.
            pending = DownloadWindowsAsync(windows);
            if (pending) {
                return;
            }
        } else if (windows.size() > 1) {
            DownloadWindows(windows);
        } else {
            DownloadMemory(arena, window_start, window_end - window_start);
        }
        if (is_write) {
            memory_tracker->MarkRegionAsCpuModified(device_addr, size);
        }
    };
    if (assume_locks) {
        flush_request();
    } else {
        liverpool->SendCommand<true>(std::move(flush_request));
    }
    if (pending) {
        CompleteAsyncReadback(*pending, device_addr, size, is_write);
    }
}

std::shared_ptr<BufferCache::AsyncReadback> BufferCache::DownloadWindowsAsync(
    std::span<const DownloadWindow> windows) {
    struct WindowCopies {
        const DownloadWindow* window;
        boost::container::small_vector<vk::BufferCopy, 4> copies;
    };
    boost::container::small_vector<WindowCopies, 17> work;
    u64 total_size_bytes = 0;
    for (const auto& window : windows) {
        WindowCopies entry{&window};
        const VAddr arena_base = window.arena->cpu_addr;
        memory_tracker->ForEachDownloadRange<false>(
            window.start, window.size, [&](u64 address, u64 size) {
                gpu_modified_ranges.ForEachInRange(address, size, [&](VAddr start, VAddr end) {
                    entry.copies.push_back(vk::BufferCopy{
                        .srcOffset = start - arena_base,
                        .dstOffset = total_size_bytes,
                        .size = end - start,
                    });
                    constexpr u64 align = 64ULL;
                    total_size_bytes += (end - start + align - 1) & ~(align - 1);
                });
                gpu_modified_ranges.Subtract(address, size);
            });
        if (!entry.copies.empty()) {
            work.push_back(std::move(entry));
        }
    }
    if (total_size_bytes == 0) {
        return nullptr;
    }
    auto readback = std::make_shared<AsyncReadback>();
    readback->download =
        staging_pool.Request(total_size_bytes, VideoCore::MemoryType::HostCached, 0, true);
    for (auto& entry : work) {
        const VAddr arena_base = entry.window->arena->cpu_addr;
        for (auto& copy : entry.copies) {
            readback->copies.push_back({arena_base + copy.srcOffset, copy.dstOffset, copy.size});
            copy.dstOffset += readback->download.offset;
        }
    }
    // gow3 R.9.6: windows whose last GPU write (and the tick after it, see SideDownload) has
    // finished are copied on the side queue: no wait behind the graphics queue's work.
    readback->windows.assign(windows.begin(), windows.end());
    bool side = false;
    if (instance.GetSideQueue()) {
        boost::container::small_vector<std::pair<const Buffer*, std::span<const vk::BufferCopy>>, 17>
            side_work;
        for (const auto& entry : work) {
            side_work.emplace_back(entry.window->arena,
                                   std::span<const vk::BufferCopy>(entry.copies.data(),
                                                                   entry.copies.size()));
        }
        side = SideQueueCopy(*readback, side_work);
    }
    if (!side) {
        for (auto& entry : work) {
            runtime.CopyBuffer(entry.window->arena, readback->download.buffer, entry.copies);
        }
    }
    readback->tick = scheduler.CurrentTick();
    {
        std::scoped_lock lk{async_mutex};
        async_pending.push_back(readback);
    }
    async_count.fetch_add(1, std::memory_order_release);
    if (side) {
        Gow3Stats::uc3_rb_side.fetch_add(1, std::memory_order_relaxed);
        Gow3Stats::uc3_rb_async.fetch_add(1, std::memory_order_relaxed);
        return readback;
    }
    scheduler.Flush();
    Gow3Stats::uc3_rb_async.fetch_add(1, std::memory_order_relaxed);
    return readback;
}

bool BufferCache::SideQueueCopy(
    AsyncReadback& readback,
    std::span<const std::pair<const Buffer*, std::span<const vk::BufferCopy>>> work) {
    u64 write_tick = 0;
    for (const auto& window : readback.windows) {
        const u64 tick = LastWriteTick(window.start, window.size);
        if (tick == ~0ULL) {
            return false;
        }
        write_tick = std::max(write_tick, tick);
    }
    auto* semaphore = scheduler.GetWorkSemaphore();
    if (semaphore->KnownGpuTick() < write_tick + 1) {
        semaphore->Refresh();
        if (semaphore->KnownGpuTick() < write_tick + 1) {
            return false;
        }
    }
    const vk::Device device = instance.GetDevice();
    std::scoped_lock lk{sideq_mutex};
    if (!sideq_pool) {
        sideq_pool = Vulkan::Check<"create side queue command pool">(device.createCommandPool({
            .flags = vk::CommandPoolCreateFlagBits::eTransient |
                     vk::CommandPoolCreateFlagBits::eResetCommandBuffer,
            .queueFamilyIndex = instance.GetSideQueueFamilyIndex(),
        }));
    }
    u32 index = ~0u;
    for (u32 i = 0; i < sideq_slots.size(); ++i) {
        if (!sideq_slots[i].busy) {
            index = i;
            break;
        }
    }
    if (index == ~0u) {
        return false;
    }
    auto& slot = sideq_slots[index];
    if (!slot.cmdbuf) {
        const vk::CommandBufferAllocateInfo alloc_info = {
            .commandPool = sideq_pool,
            .level = vk::CommandBufferLevel::ePrimary,
            .commandBufferCount = 1U,
        };
        Vulkan::Check<"allocate side queue command buffer">(
            device.allocateCommandBuffers(&alloc_info, &slot.cmdbuf));
        slot.fence = Vulkan::Check<"create side queue fence">(device.createFence({}));
    }
    Vulkan::Check(slot.cmdbuf.reset());
    Vulkan::Check(slot.cmdbuf.begin({.flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit}));
    for (const auto& [arena, copies] : work) {
        slot.cmdbuf.copyBuffer(arena->Handle(), readback.download.buffer->Handle(),
                               static_cast<u32>(copies.size()), copies.data());
    }
    const vk::MemoryBarrier2 after = {
        .srcStageMask = vk::PipelineStageFlagBits2::eCopy,
        .srcAccessMask = vk::AccessFlagBits2::eTransferWrite,
        .dstStageMask = vk::PipelineStageFlagBits2::eHost,
        .dstAccessMask = vk::AccessFlagBits2::eHostRead,
    };
    slot.cmdbuf.pipelineBarrier2(vk::DependencyInfo{
        .memoryBarrierCount = 1U,
        .pMemoryBarriers = &after,
    });
    Vulkan::Check(slot.cmdbuf.end());
    // The finished tick carries the writes' memory dependency (already signalled: no wait).
    const u64 wait_value = write_tick + 1;
    const vk::Semaphore wait_semaphore = semaphore->Handle();
    const vk::PipelineStageFlags wait_stage = vk::PipelineStageFlagBits::eAllCommands;
    const vk::TimelineSemaphoreSubmitInfo timeline_si = {
        .waitSemaphoreValueCount = 1U,
        .pWaitSemaphoreValues = &wait_value,
    };
    const vk::SubmitInfo submit_info = {
        .pNext = &timeline_si,
        .waitSemaphoreCount = 1U,
        .pWaitSemaphores = &wait_semaphore,
        .pWaitDstStageMask = &wait_stage,
        .commandBufferCount = 1U,
        .pCommandBuffers = &slot.cmdbuf,
    };
    const auto result = instance.GetSideQueue().submit(submit_info, slot.fence);
    ASSERT_MSG(result != vk::Result::eErrorDeviceLost, "Device lost during side submit");
    slot.busy = true;
    readback.side_slot = index;
    return true;
}

void BufferCache::CompleteAsyncReadback(AsyncReadback& readback, VAddr device_addr, u64 size,
                                        bool is_write) {
    const auto start = std::chrono::steady_clock::now();
    if (readback.side_slot != ~0u) {
        auto& slot = sideq_slots[readback.side_slot];
        const vk::Device device = instance.GetDevice();
        Vulkan::Check(device.waitForFences(slot.fence, true, std::numeric_limits<u64>::max()));
        Vulkan::Check(device.resetFences(slot.fence));
        std::scoped_lock lk{sideq_mutex};
        slot.busy = false;
    } else {
        scheduler.GetWorkSemaphore()->Wait(readback.tick);
    }
    const auto& download = readback.download;
    download.buffer->Invalidate(download.offset, download.size);
    for (const auto& copy : readback.copies) {
        auto* dst_addr = std::bit_cast<u8*>(copy.address);
        Gow3WriteLog::NoteDownload(copy.address, download.mapped + copy.staging_offset, copy.size);
        memory->TryWriteBacking(dst_addr, download.mapped + copy.staging_offset, copy.size);
    }
    {
        std::scoped_lock lk{async_mutex};
        if (!readback.dirty) {
            for (const auto& window : readback.windows) {
                memory_tracker->UnmarkRegionAsGpuModified(window.start, window.size, false);
            }
            if (is_write) {
                memory_tracker->MarkRegionAsCpuModified(device_addr, size);
            }
        } else {
            Gow3Stats::uc3_rb_async_dirty.fetch_add(1, std::memory_order_relaxed);
        }
        std::erase_if(async_pending, [&](const auto& entry) { return entry.get() == &readback; });
        async_free.push_back(download);
        async_free_count.fetch_add(1, std::memory_order_release);
        async_count.fetch_sub(1, std::memory_order_release);
    }
    readback.done.store(true, std::memory_order_release);
    readback.done.notify_all();
    Gow3Stats::uc3_rb_async_ns.fetch_add(
        std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() -
                                                             start)
            .count(),
        std::memory_order_relaxed);
}

void BufferCache::WaitAsyncReadbacks(VAddr start, VAddr end) {
    if (async_count.load(std::memory_order_acquire) == 0 &&
        async_free_count.load(std::memory_order_acquire) == 0) {
        return;
    }
    for (;;) {
        std::vector<Vulkan::StagingBufferRef> to_free;
        std::shared_ptr<AsyncReadback> overlap;
        {
            std::scoped_lock lk{async_mutex};
            to_free.swap(async_free);
            for (const auto& entry : async_pending) {
                const bool hit =
                    end == 0 || std::ranges::any_of(entry->windows, [&](const DownloadWindow& w) {
                        return w.start < end && start < w.start + w.size;
                    });
                if (hit) {
                    overlap = entry;
                    break;
                }
            }
        }
        for (const auto& ref : to_free) {
            staging_pool.FreeDeferred(ref);
        }
        async_free_count.fetch_sub(static_cast<u32>(to_free.size()), std::memory_order_release);
        if (!overlap) {
            return;
        }
        Gow3Stats::uc3_rb_async_overlap.fetch_add(1, std::memory_order_relaxed);
        overlap->done.wait(false, std::memory_order_acquire);
    }
}

void BufferCache::QueueLeafDownload(const Buffer* buffer, u64 offset, VAddr address, u32 size) {
    if (size > LeafSlotSize) {
        return;
    }
    u32 slot;
    {
        std::scoped_lock lk{leaf_mutex};
        for (const auto& leaf : leaf_pending) {
            if (leaf.address == address && !leaf.dirty) {
                return; // already on its way
            }
        }
        if (!leaf_staging.buffer) {
            leaf_staging = staging_pool.Request(u64{LeafSlotSize} * LeafSlots,
                                                VideoCore::MemoryType::HostCached, 0, true);
            leaf_free_slots.reserve(LeafSlots);
            for (u32 i = LeafSlots; i-- > 0;) {
                leaf_free_slots.push_back(i);
            }
        }
        if (leaf_free_slots.empty()) {
            Gow3Stats::uc3_leaf_dl_full.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        slot = leaf_free_slots.back();
        leaf_free_slots.pop_back();
        leaf_pending.push_back({address, size, slot, scheduler.CurrentTick(), false});
        leaf_pending_count.fetch_add(1, std::memory_order_release);
    }
    const vk::BufferCopy copy{offset, leaf_staging.offset + u64{slot} * LeafSlotSize, size};
    runtime.CopyBuffer(buffer, leaf_staging.buffer, {&copy, 1});
    Gow3Stats::uc3_leaf_dl_queued.fetch_add(1, std::memory_order_relaxed);
}

void BufferCache::CompleteLeafDownloads() {
    if (leaf_pending_count.load(std::memory_order_acquire) == 0) {
        return;
    }
    auto* semaphore = scheduler.GetWorkSemaphore();
    semaphore->Refresh();
    std::scoped_lock lk{leaf_mutex};
    bool invalidated = false;
    std::erase_if(leaf_pending, [&](const LeafDownload& leaf) {
        if (!semaphore->IsFree(leaf.tick)) {
            return false;
        }
        leaf_free_slots.push_back(leaf.slot);
        leaf_pending_count.fetch_sub(1, std::memory_order_release);
        if (leaf.dirty) {
            Gow3Stats::uc3_leaf_dl_dirty.fetch_add(1, std::memory_order_relaxed);
            return true;
        }
        if (!invalidated) {
            invalidated = true;
            leaf_staging.Invalidate();
        }
        const u8* data = leaf_staging.mapped + u64{leaf.slot} * LeafSlotSize;
        auto* dst = std::bit_cast<u8*>(leaf.address);
        Gow3WriteLog::NoteDownload(leaf.address, data, leaf.size);
        memory->TryWriteBacking(dst, data, leaf.size);
        gpu_modified_ranges.Subtract(leaf.address, leaf.size);
        // The page keeps its GPU state while other GPU bytes on it are still not downloaded.
        const VAddr page = leaf.address & ~VAddr{0xfff};
        if (!gpu_modified_ranges.Intersects(page, 0x1000)) {
            memory_tracker->UnmarkRegionAsGpuModified(page, 0x1000, false);
        }
        Gow3Stats::uc3_leaf_dl_landed.fetch_add(1, std::memory_order_relaxed);
        return true;
    });
}

bool BufferCache::IsGpuWritten(VAddr addr, u64 size) {
    if (gpu_modified_ranges.Intersects(addr, size)) {
        return true;
    }
    if (async_count.load(std::memory_order_acquire) == 0) {
        return false;
    }
    std::scoped_lock lk{async_mutex};
    for (const auto& entry : async_pending) {
        for (const auto& w : entry->windows) {
            if (w.start < addr + size && addr < w.start + w.size) {
                return true;
            }
        }
    }
    return false;
}

const char* BufferCache::DescribeGpuWritten(VAddr addr, u64 size) {
    static thread_local char text[256];
    if (gpu_modified_ranges.Intersects(addr, size)) {
        // The GPU-written pieces of the whole page, to see what the load shares it with.
        int length = std::snprintf(text, sizeof(text), "gpu_modified_ranges in page:");
        gpu_modified_ranges.ForEachInRange(addr & ~0xfffULL, 0x1000, [&](VAddr start, VAddr end) {
            if (length < 200) {
                length += std::snprintf(text + length, sizeof(text) - length, " [%#llx,%#llx)",
                                        (unsigned long long)start, (unsigned long long)end);
            }
        });
        return text;
    }
    return async_count.load(std::memory_order_acquire) ? "async readback (or image)"
                                                       : "image readback";
}

void BufferCache::NoteAsyncWrite(VAddr device_addr, u64 size) {
    if (async_count.load(std::memory_order_acquire) == 0) {
        return;
    }
    std::scoped_lock lk{async_mutex};
    for (const auto& entry : async_pending) {
        for (const auto& w : entry->windows) {
            if (w.start < device_addr + size && device_addr < w.start + w.size) {
                entry->dirty = true;
            }
        }
    }
}

void BufferCache::DownloadMemory(const Buffer* arena, VAddr device_addr, u64 size) {
    boost::container::small_vector<vk::BufferCopy, 1> copies;
    u64 total_size_bytes = 0;
    const VAddr arena_base = arena->cpu_addr;
    memory_tracker->ForEachDownloadRange<false>(device_addr, size, [&](u64 address, u64 size) {
        const auto add_download = [&](VAddr start, VAddr end) {
            const u64 new_offset = start - arena_base;
            const u64 new_size = end - start;
            copies.push_back(vk::BufferCopy{
                .srcOffset = new_offset,
                .dstOffset = total_size_bytes,
                .size = new_size,
            });
            // Align up to avoid cache conflicts
            constexpr u64 align = 64ULL;
            constexpr u64 mask = ~(align - 1ULL);
            total_size_bytes += (new_size + align - 1) & mask;
        };
        gpu_modified_ranges.ForEachInRange(address, size, add_download);
        gpu_modified_ranges.Subtract(address, size);
    });
    if (total_size_bytes == 0) {
        return;
    }
    const auto download = staging_pool.Request(total_size_bytes, VideoCore::MemoryType::HostCached);
    for (auto& copy : copies) {
        copy.dstOffset += download.offset;
    }
    const SideCopies side{arena, copies};
    if (!SideDownload(std::span{&side, 1}, download.buffer, LastWriteTick(device_addr, size))) {
        runtime.CopyBuffer(arena, download.buffer, copies);
        scheduler.Finish();
        if (ReadbackFast()) {
            Gow3Stats::uc3_rb_full.fetch_add(1, std::memory_order_relaxed);
        }
    }

    download.buffer->Invalidate(download.offset, download.size);
    for (const auto& copy : copies) {
        auto* dst_addr = std::bit_cast<u8*>(arena_base + copy.srcOffset);
        Gow3WriteLog::NoteDownload(reinterpret_cast<u64>(dst_addr),
                                   download.mapped + (copy.dstOffset - download.offset), copy.size);
        memory->TryWriteBacking(dst_addr, download.mapped + (copy.dstOffset - download.offset),
                                copy.size);
    }
    memory_tracker->UnmarkRegionAsGpuModified(device_addr, size, false);
}

void BufferCache::DownloadWindows(std::span<const DownloadWindow> windows) {
    struct WindowCopies {
        const DownloadWindow* window;
        boost::container::small_vector<vk::BufferCopy, 4> copies;
    };
    boost::container::small_vector<WindowCopies, 17> work;
    u64 total_size_bytes = 0;
    for (const auto& window : windows) {
        WindowCopies entry{&window};
        const VAddr arena_base = window.arena->cpu_addr;
        memory_tracker->ForEachDownloadRange<false>(
            window.start, window.size, [&](u64 address, u64 size) {
                gpu_modified_ranges.ForEachInRange(address, size, [&](VAddr start, VAddr end) {
                    entry.copies.push_back(vk::BufferCopy{
                        .srcOffset = start - arena_base,
                        .dstOffset = total_size_bytes,
                        .size = end - start,
                    });
                    constexpr u64 align = 64ULL;
                    total_size_bytes += (end - start + align - 1) & ~(align - 1);
                });
                gpu_modified_ranges.Subtract(address, size);
            });
        if (!entry.copies.empty()) {
            work.push_back(std::move(entry));
        }
    }
    if (total_size_bytes == 0) {
        return;
    }
    const auto download = staging_pool.Request(total_size_bytes, VideoCore::MemoryType::HostCached);
    u64 write_tick = 0;
    boost::container::small_vector<SideCopies, 17> side;
    for (auto& entry : work) {
        for (auto& copy : entry.copies) {
            copy.dstOffset += download.offset;
        }
        write_tick = std::max(write_tick, LastWriteTick(entry.window->start, entry.window->size));
        side.push_back({entry.window->arena, entry.copies});
    }
    if (!SideDownload(side, download.buffer, write_tick)) {
        for (auto& entry : work) {
            runtime.CopyBuffer(entry.window->arena, download.buffer, entry.copies);
        }
        scheduler.Finish();
        if (ReadbackFast()) {
            Gow3Stats::uc3_rb_full.fetch_add(1, std::memory_order_relaxed);
        }
    }

    download.buffer->Invalidate(download.offset, download.size);
    for (const auto& entry : work) {
        const VAddr arena_base = entry.window->arena->cpu_addr;
        for (const auto& copy : entry.copies) {
            auto* dst_addr = std::bit_cast<u8*>(arena_base + copy.srcOffset);
            Gow3WriteLog::NoteDownload(reinterpret_cast<u64>(dst_addr),
                                       download.mapped + (copy.dstOffset - download.offset), copy.size);
            memory->TryWriteBacking(dst_addr, download.mapped + (copy.dstOffset - download.offset),
                                    copy.size);
        }
    }
    for (const auto& window : windows) {
        memory_tracker->UnmarkRegionAsGpuModified(window.start, window.size, false);
    }
}

void BufferCache::DownloadAllGpuModified() {
    struct ArenaCopies {
        const Buffer* arena;
        boost::container::small_vector<vk::BufferCopy, 16> copies;
    };
    boost::container::small_vector<ArenaCopies, 8> work;
    u64 total_size_bytes = 0;
    for (const Buffer& arena : arenas) {
        ArenaCopies entry{&arena};
        const VAddr arena_base = arena.cpu_addr;
        memory_tracker->ForEachDownloadRange<false>(
            arena_base, arena.size_bytes, [&](u64 address, u64 size) {
                gpu_modified_ranges.ForEachInRange(address, size, [&](VAddr start, VAddr end) {
                    entry.copies.push_back(vk::BufferCopy{
                        .srcOffset = start - arena_base,
                        .dstOffset = total_size_bytes,
                        .size = end - start,
                    });
                    constexpr u64 align = 64ULL;
                    total_size_bytes += (end - start + align - 1) & ~(align - 1);
                });
                gpu_modified_ranges.Subtract(address, size);
            });
        if (!entry.copies.empty()) {
            work.push_back(std::move(entry));
        }
    }
    if (total_size_bytes == 0) {
        return;
    }
    Gow3Stats::uc3_readback_all.fetch_add(1, std::memory_order_relaxed);
    Gow3Stats::uc3_readback_all_bytes.fetch_add(total_size_bytes, std::memory_order_relaxed);
    const auto download = staging_pool.Request(total_size_bytes, VideoCore::MemoryType::HostCached);
    for (auto& entry : work) {
        for (auto& copy : entry.copies) {
            copy.dstOffset += download.offset;
        }
        runtime.CopyBuffer(entry.arena, download.buffer, entry.copies);
    }
    scheduler.Finish();

    download.buffer->Invalidate(download.offset, download.size);
    for (const auto& entry : work) {
        const VAddr arena_base = entry.arena->cpu_addr;
        for (const auto& copy : entry.copies) {
            auto* dst_addr = std::bit_cast<u8*>(arena_base + copy.srcOffset);
            Gow3WriteLog::NoteDownload(reinterpret_cast<u64>(dst_addr),
                                       download.mapped + (copy.dstOffset - download.offset), copy.size);
            memory->TryWriteBacking(dst_addr, download.mapped + (copy.dstOffset - download.offset),
                                    copy.size);
        }
        memory_tracker->UnmarkRegionAsGpuModified(entry.arena->cpu_addr, entry.arena->size_bytes,
                                                  false);
    }
}

namespace {
// gow3: GOW3_BUFFER_STATS=1 — how buffer bindings reach the GPU, by guest region (256 MiB),
// printed every 5 s: small read-only copies into the stream buffer, arena bindings, and the
// bytes those re-upload after CPU writes. Input for engine-level short paths.
struct BufferStats {
    struct Region {
        u64 stream_count{}, stream_bytes{}, arena_count{}, arena_bytes{}, upload_bytes{};
    };
    std::map<u64, Region> regions;
    /// Hot window: frame of the last binding per 64 KiB block, and the distribution of the
    /// frames between uses (1, 2, 3, 4, 5-8, 9-16, 17+): the ring's reuse distance.
    std::unordered_map<u64, u64> last_use;
    std::array<u64, 7> reuse{};
    std::chrono::steady_clock::time_point window = std::chrono::steady_clock::now();
    u64 calls = 0;
};
/// 256 MiB regions; 1 MiB inside the hot 0x104xxxxxxx window (the engine's frame data).
BufferStats& Stats();
void NoteHotUse(VAddr address, u32 size) {
    if ((address >> 28) != 0x104) {
        return;
    }
    auto& st = Stats();
    const u64 frame = Gow3Stats::gpu_frames.load(std::memory_order_relaxed);
    for (u64 block = address >> 16; block <= (address + size - 1) >> 16; ++block) {
        auto [it, inserted] = st.last_use.try_emplace(block, frame);
        if (!inserted && it->second != frame) {
            const u64 d = frame - it->second;
            ++st.reuse[d <= 4 ? d - 1 : d <= 8 ? 4 : d <= 16 ? 5 : 6];
            it->second = frame;
        }
    }
}
u64 RegionKey(VAddr address) {
    return (address >> 28) == 0x104 ? (address >> 20) | (1ull << 40) : address >> 28;
}
bool BufferStatsEnabled() {
    static const bool enabled = [] {
        const char* value = std::getenv("GOW3_BUFFER_STATS");
        return value && value[0] == '1';
    }();
    return enabled;
}
BufferStats& Stats() {
    static BufferStats stats;
    return stats;
}
void PrintBufferStats() {
    auto& st = Stats();
    if ((++st.calls & 4095) != 0) {
        return;
    }
    const auto now = std::chrono::steady_clock::now();
    const double seconds = std::chrono::duration<double>(now - st.window).count();
    if (seconds < 5.0) {
        return;
    }
    std::printf("Buffer stats (%.1f s, per second): region, stream copies/bytes, arena bindings, "
                "re-uploaded bytes\n", seconds);
    std::printf("  hot window reuse distance in frames (1,2,3,4,5-8,9-16,17+): %llu %llu %llu %llu "
                "%llu %llu %llu\n",
                (unsigned long long)st.reuse[0], (unsigned long long)st.reuse[1],
                (unsigned long long)st.reuse[2], (unsigned long long)st.reuse[3],
                (unsigned long long)st.reuse[4], (unsigned long long)st.reuse[5],
                (unsigned long long)st.reuse[6]);
    st.reuse = {};
    for (const auto& [region, r] : st.regions) {
        const u64 base = (region >> 40) ? (region & ((1ull << 40) - 1)) << 20 : region << 28;
        if ((region >> 40) && r.stream_bytes + r.upload_bytes < 1e6 * seconds) {
            continue; // quiet MiB of the hot window
        }
        std::printf("  %#012llx: %8.0f copies %8.2f MB, %8.0f arena %8.2f MB bound, %8.2f MB uploads\n",
                    static_cast<unsigned long long>(base), r.stream_count / seconds,
                    r.stream_bytes / seconds / 1e6, r.arena_count / seconds,
                    r.arena_bytes / seconds / 1e6,
                    r.upload_bytes / seconds / 1e6);
    }
    st.regions.clear();
    st.window = now;
}
} // namespace

std::pair<const Buffer*, u64> BufferCache::ObtainBuffer(VAddr device_addr, u32 size,
                                                        bool is_written, bool is_texel_buffer) {
    texture_cache.ResolveReadbacks(device_addr, size, true, ReadbackSource::BufferObtain);
    const bool stats = BufferStatsEnabled();
    if (stats) {
        PrintBufferStats();
        NoteHotUse(device_addr, size);
    }
    // For read-only buffers use device local stream buffer to reduce renderpass breaks.
    if (!is_written && size <= STREAM_THRESHOLD && !IsRegionGpuModified(device_addr, size)) {
        if (stats) {
            auto& region = Stats().regions[RegionKey(device_addr)];
            ++region.stream_count;
            region.stream_bytes += size;
        }
        // gow3: the guest data is copied on a copy thread, started now; submission and
        // guest-visible fences wait for it (Scheduler::WaitHostCopies).
        if (!stream_buffer.mapped_data.empty() &&
            !Gow3Toggle::Disabled(Gow3Toggle::DeferredStreamCopies)) {
            if (const auto offset = stream_buffer.Reserve(size, instance.UniformMinAlignment())) {
                SmallGuestCopy({
                    .run = &RunGuestCopy,
                    .context = this,
                    .source = device_addr,
                    .destination = reinterpret_cast<u64>(stream_buffer.mapped_data.data() + *offset),
                    .size = size,
                    .extra = reinterpret_cast<u64>(static_cast<Buffer*>(&stream_buffer)),
                });
                return {&stream_buffer, *offset};
            }
        }
        const auto [data, offset] = stream_buffer.Map(size, instance.UniformMinAlignment());
        memory->CopySparseMemory(device_addr, data, size);
        stream_buffer.Commit();
        return {&stream_buffer, offset};
    }
    const u64 first_block = device_addr >> block_shift;
    const u64 last_block = (device_addr + size - 1) >> block_shift;
    const auto* arena = GetArena(first_block, last_block);
    EnsureResident(arena, first_block, last_block);
    const u64 uploaded_before = Gow3Stats::buffer_upload_bytes.load(std::memory_order_relaxed);
    if (is_written) {
        NoteAsyncWrite(device_addr, size);
        if (leaf_pending_count.load(std::memory_order_acquire)) {
            std::scoped_lock lk{leaf_mutex};
            for (auto& leaf : leaf_pending) {
                if (leaf.address < device_addr + size && device_addr < leaf.address + leaf.size) {
                    leaf.dirty = true;
                }
            }
        }
    }
    SynchronizeMemory(arena, device_addr, size, is_written, is_texel_buffer);
    if (stats) {
        auto& region = Stats().regions[RegionKey(device_addr)];
        ++region.arena_count;
        region.arena_bytes += size;
        region.upload_bytes +=
            Gow3Stats::buffer_upload_bytes.load(std::memory_order_relaxed) - uploaded_before;
    }
    if (is_written) {
        gpu_modified_ranges.Add(device_addr, size);
        NoteGpuWrite(device_addr, size);
    }
    return {arena, arena->Offset(device_addr)};
}

std::pair<const Buffer*, u64> BufferCache::ObtainBufferForImage(VAddr device_addr, u32 size) {
    texture_cache.ResolveReadbacks(device_addr, size, true, ReadbackSource::BufferForImage);
    if (IsRegionGpuModified(device_addr, size)) {
        return ObtainBuffer(device_addr, size, false);
    }
    const auto staging = staging_pool.Request(size, VideoCore::MemoryType::HostUncached,
                                              instance.StorageMinAlignment());
    if (!Gow3Toggle::Disabled(Gow3Toggle::DeferredUploads)) {
        // gow3: texture data is copied on the copy threads (streaming: 100+ MB per frame);
        // the upload reads the staging only after submission, which waits for the copies.
        constexpr u64 Chunk = 1_MB;
        for (u64 offset = 0; offset < staging.size; offset += Chunk) {
            const Gow3Copy::Item item{
                .run = &RunGuestCopy,
                .context = this,
                .source = device_addr + offset,
                .destination = reinterpret_cast<u64>(staging.mapped + offset),
                .size = std::min(Chunk, staging.size - offset),
                .extra = reinterpret_cast<u64>(staging.buffer),
            };
            if (staging.size < Chunk) {
                SmallGuestCopy(item);
            } else {
                NotePendingHostRead(item.source, item.size, +1);
                Gow3Copy::Async([item] { item.run(item); });
            }
        }
        return {staging.buffer, staging.offset};
    }
    memory->CopySparseMemory(device_addr, staging.mapped, staging.size);
    staging.Flush();
    return {staging.buffer, staging.offset};
}

bool BufferCache::IsRegionCpuModified(VAddr addr, size_t size) {
    return memory_tracker->IsRegionCpuModified(addr, size);
}

bool BufferCache::IsRegionGpuModified(VAddr addr, size_t size) {
    return memory_tracker->IsRegionGpuModified(addr, size);
}

void BufferCache::SynchronizeDmaBuffers() {
    fault_process_pending = true;
    for (const auto& range : resident_ranges) {
        const u64 page = range.start >> (arena_page_bits - block_shift);
        const VAddr device_addr = range.start << block_shift;
        const u64 size = (range.end - range.start) << block_shift;
        SynchronizeMemory(address_space[page], device_addr, size, false, false);
    }
}

const Buffer* BufferCache::GetArena(u64 first_block, u64 last_block) {
    const u64 first_page = first_block >> blocks_per_arena_page_shift;
    const u64 last_page = last_block >> blocks_per_arena_page_shift;
    ASSERT_MSG(last_page - first_page <= 1,
               "Buffer request cannot span more than two VA arena pages");

    const auto* first_arena = address_space[first_page];
    const auto* last_arena = address_space[last_page];
    if (first_arena == last_arena) {
        if (!first_arena) {
            const u64 base_block = Common::AlignDownPow2<u64>(first_block, blocks_per_arena_page);
            const u64 num_pages = last_page - first_page + 1;
            const auto* new_arena =
                &arenas.emplace_back(instance, base_block << block_shift,
                                     num_pages << arena_page_bits, MemoryType::Sparse);
            address_space[first_page] = new_arena;
            address_space[last_page] = new_arena;
        }
        return address_space[first_page];
    }

    LOG_WARNING(Render, "Migrating arena");

    const u64 first_addr = first_arena ? first_arena->cpu_addr : (first_page << arena_page_bits);
    const u64 first_size = first_arena ? first_arena->size_bytes : arena_page_size;
    const u64 last_size = last_arena ? last_arena->size_bytes : arena_page_size;

    const u64 base_block = first_addr >> block_shift;
    const u64 total_size = first_size + last_size;
    const u64 end_block = (first_addr + total_size) >> block_shift;
    auto* new_arena = &arenas.emplace_back(instance, first_addr, total_size, MemoryType::Sparse);
    auto* bind = BindsForArena(new_arena);
    resident_ranges.ForEachInRange(base_block, end_block, [&](const Backing& backing) {
        const u64 start = std::max(base_block, backing.start);
        const u64 end = std::min(end_block, backing.end);
        bind->binds.push_back(vk::SparseMemoryBind{
            .resourceOffset = (start - base_block) << block_shift,
            .size = (end - start) << block_shift,
            .memory = backing.memory,
            .memoryOffset = (backing.offset + start - backing.start) << block_shift,
        });
    });

    u64 base_page = first_addr >> arena_page_bits;
    for (u32 page = 0; page < (first_size >> arena_page_bits); ++page) {
        address_space[base_page + page] = new_arena;
    }
    base_page = last_page;
    for (u32 page = 0; page < (last_size >> arena_page_bits); ++page) {
        address_space[base_page + page] = new_arena;
    }
    return new_arena;
}

void BufferCache::EnsureResident(const Buffer* arena, u64 first_block, u64 last_block) {
    u32 resident_blocks{};
    IntervalList bind_ranges;
    resident_ranges.ForEachGap(first_block, last_block + 1, [&](u64 start, u64 end) {
        resident_blocks += end - start;
        bind_ranges.Add({start, end});
    });

    if (bind_ranges.Empty()) {
        return;
    }
    Gow3Stats::Timer timer{Gow3Stats::t_resident};

    const vk::MemoryAllocateFlagsInfo alloc_flags = {
        .flags = vk::MemoryAllocateFlagBits::eDeviceAddress,
    };
    const vk::MemoryAllocateInfo alloc_info = {
        .pNext = &alloc_flags,
        .allocationSize = resident_blocks << block_shift,
        .memoryTypeIndex = arena_memory_type_index,
    };
    const auto device_memory = Vulkan::Check(instance.GetDevice().allocateMemory(alloc_info));

    boost::container::small_vector<vk::BufferCopy, 8> copies;
    const auto staging =
        staging_pool.Request(resident_blocks * sizeof(vk::DeviceAddress), MemoryType::HostUncached);

    u64 memory_offset{};
    ArenaBinds* binds = BindsForArena(arena);
    auto* bda_addrs = reinterpret_cast<vk::DeviceAddress*>(staging.mapped);
    u64 offset = staging.offset;
    for (const auto& range : bind_ranges) {
        Backing backing;
        backing.start = range.start;
        backing.end = range.end;
        backing.memory = device_memory;
        backing.offset = memory_offset >> block_shift;
        resident_ranges.Add(backing);

        LOG_INFO(Render, "Making range start={}, end={} resident", backing.start, backing.end);

        const auto& bind = binds->binds.emplace_back(vk::SparseMemoryBind{
            .resourceOffset = (range.start << block_shift) - arena->cpu_addr,
            .size = (range.end - range.start) << block_shift,
            .memory = device_memory,
            .memoryOffset = memory_offset,
        });
        memory_offset += bind.size;

        for (u32 block = 0; block < bind.size; block += block_size) {
            *(bda_addrs++) = arena->BufferDeviceAddress() + bind.resourceOffset + block;
        }
        const u64 copy_size = (backing.end - backing.start) * sizeof(vk::DeviceAddress);
        copies.emplace_back(offset, backing.start * sizeof(vk::DeviceAddress), copy_size);
        offset += copy_size;
    }

    staging.Flush();
    runtime.CopyBuffer(staging.buffer, bda_pagetable_buffer.get(), copies);
}

bool BufferCache::SynchronizeMemory(const Buffer* arena, VAddr device_addr, u32 size,
                                    bool is_written, bool is_texel_buffer) {
    boost::container::small_vector<vk::BufferCopy, 4> copies;
    size_t total_size_bytes{};
    memory_tracker->ForEachUploadRange(device_addr, size, is_written, [&](u64 addr, u64 size) {
        copies.emplace_back(total_size_bytes, addr, size);
        total_size_bytes += size;
    });
    if (const Buffer* src_buffer = UploadCopies(arena, copies, total_size_bytes)) {
        runtime.CopyBuffer(src_buffer, arena, copies);
    }
    if (is_texel_buffer && !is_written) {
        return SynchronizeMemoryFromImage(arena, device_addr, size);
    }
    return false;
}

const Buffer* BufferCache::UploadCopies(const Buffer* arena, std::span<vk::BufferCopy> copies,
                                        size_t total_size_bytes) {
    if (copies.empty()) {
        return nullptr;
    }
    // Resolve before launching host copies: a copy-thread fault could deadlock submission.
    for (const auto& copy : copies) {
        texture_cache.ResolveReadbacks(copy.dstOffset, copy.size, true, ReadbackSource::BufferUpload);
    }
    Gow3Stats::buffer_upload_bytes.fetch_add(total_size_bytes, std::memory_order_relaxed);
    const auto staging = staging_pool.Request(total_size_bytes, MemoryType::HostUncached);
    // gow3: the guest memory is copied into staging on the copy threads, started now in
    // groups of about 1 MiB; submission and guest-visible fences wait for them
    // (Scheduler::WaitHostCopies).
    if (!Gow3Toggle::Disabled(Gow3Toggle::DeferredUploads) && total_size_bytes < 1_MB) {
        // Small uploads join the calling thread's batch.
        for (auto& copy : copies) {
            SmallGuestCopy({
                .run = &RunGuestCopy,
                .context = this,
                .source = copy.dstOffset,
                .destination = reinterpret_cast<u64>(staging.mapped + copy.srcOffset),
                .size = copy.size,
                .extra = reinterpret_cast<u64>(staging.buffer),
            });
            copy.srcOffset += staging.offset;
            copy.dstOffset -= arena->cpu_addr;
        }
        return staging.buffer;
    }
    if (!Gow3Toggle::Disabled(Gow3Toggle::DeferredUploads)) {
        struct HostCopy {
            VAddr source;
            u8* destination;
            u64 size;
        };
        using Group = boost::container::small_vector<HostCopy, 8>;
        auto group = std::make_shared<Group>();
        u64 group_bytes = 0;
        const auto launch = [&] {
            Gow3Copy::Async([group, memory = memory, staging] {
                for (const auto& copy : *group) {
                    memory->CopySparseMemory(copy.source, copy.destination, copy.size);
                }
                staging.Flush();
                for (const auto& copy : *group) {
                    NotePendingHostRead(copy.source, copy.size, -1);
                }
            });
        };
        for (auto& copy : copies) {
            NotePendingHostRead(copy.dstOffset, copy.size, +1);
            group->push_back({copy.dstOffset, staging.mapped + copy.srcOffset, copy.size});
            group_bytes += copy.size;
            copy.srcOffset += staging.offset;
            copy.dstOffset -= arena->cpu_addr;
            if (group_bytes >= 1_MB) {
                launch();
                group = std::make_shared<Group>();
                group_bytes = 0;
            }
        }
        if (!group->empty()) {
            launch();
        }
        return staging.buffer;
    }
    const auto copy = [&](std::size_t i) {
        memory->CopySparseMemory(copies[i].dstOffset, staging.mapped + copies[i].srcOffset,
                                 copies[i].size);
    };
    if (total_size_bytes >= 2_MB && copies.size() > 1) {
        Gow3Copy::ParallelFor(copies.size(), copy);
    } else {
        for (std::size_t i = 0; i < copies.size(); ++i) {
            copy(i);
        }
    }
    for (auto& copy : copies) {
        copy.srcOffset += staging.offset;
        copy.dstOffset -= arena->cpu_addr;
    }
    staging.Flush();
    return staging.buffer;
}

bool BufferCache::SyncImageToBuffer(VAddr addr, u32 size) {
    if (!texture_cache.FindImageFromRange(addr, size)) {
        return false;
    }
    const auto [arena, offset] = ObtainBuffer(addr, size, true);
    return SynchronizeMemoryFromImage(arena, addr, size);
}

bool BufferCache::SynchronizeMemoryFromImage(const Buffer* arena, VAddr device_addr, u32 size) {
    if (const auto meta = texture_cache.GetMetaInfo(device_addr)) {
        if (meta->type == TextureCache::MetaType::HTile) {
            static constexpr u32 ZmaskUncompressed = 0xf;
            runtime.FillBuffer(arena, arena->Offset(device_addr), size, ZmaskUncompressed);
            return true;
        } else if (meta->type == TextureCache::MetaType::CMask) {
            const auto expanded_value = CmaskColorExpandedValue(meta->num_samples);
            if (!expanded_value) {
                LOG_WARNING(Render_Vulkan,
                            "Unsupported CMask sample count: address={:#x}, size={:#x}, "
                            "samples={}, owner={}:{}",
                            device_addr, size, meta->num_samples, meta->owner_id.index,
                            meta->owner_uid);
                return false;
            }
            runtime.FillBuffer(arena, arena->Offset(device_addr), size, *expanded_value);
            return true;
        } else {
            LOG_WARNING(Render_Vulkan, "Unhandled metadata type {}",
                        magic_enum::enum_name(meta->type));
        }
    }
    // gow3: most texel buffers alias no image; remember misses until images change.
    const u64 generation = texture_cache.RegistryGeneration();
    auto& miss = image_miss_cache[((device_addr >> 6) ^ size * 0x9E3779B1u) % image_miss_cache.size()];
    if (miss.address == device_addr && miss.size == size && miss.generation == generation &&
        !Gow3Toggle::Disabled(Gow3Toggle::TextureBindingMemo)) {
        return false;
    }
    const ImageId image_id = texture_cache.FindImageFromRange(device_addr, size);
    if (!image_id) {
        miss = {device_addr, size, generation};
        return false;
    }
    // gow3: the lookups above only read what the texture binding helper leaves alone; the
    // copy below changes image state, so the helper finishes first.
    runtime.BeforeImageAccess();
    Image& image = texture_cache.GetImage(image_id);
    ASSERT_MSG(device_addr == image.info.guest_address,
               "Texel buffer aliases image subresources {:x} : {:x}", device_addr,
               image.info.guest_address);
    const u64 arena_offset = arena->Offset(device_addr);
    boost::container::small_vector<vk::BufferImageCopy, 8> buffer_copies;
    for (u32 mip = 0; mip < image.info.resources.levels; mip++) {
        const auto& mip_info = image.info.mips_layout[mip];
        const u32 width = std::max(image.info.size.width >> mip, 1u);
        const u32 height = std::max(image.info.size.height >> mip, 1u);
        const u32 depth = std::max(image.info.size.depth >> mip, 1u);
        if (arena_offset + mip_info.offset + mip_info.size > arena->size_bytes) {
            break;
        }
        buffer_copies.push_back(vk::BufferImageCopy{
            .bufferOffset = mip_info.offset,
            .bufferRowLength = mip_info.pitch,
            .bufferImageHeight = mip_info.height,
            .imageSubresource{
                .aspectMask = image.aspect_mask & ~vk::ImageAspectFlagBits::eStencil,
                .mipLevel = mip,
                .baseArrayLayer = 0,
                .layerCount = image.info.resources.layers,
            },
            .imageOffset = {0, 0, 0},
            .imageExtent = {width, height, depth},
        });
    }
    if (buffer_copies.empty()) {
        return false;
    }
    auto& tile_manager = texture_cache.GetTileManager();
    tile_manager.TileImage(image, buffer_copies, arena, arena_offset);
    return true;
}

void BufferCache::SubmitPendingArenaBinds(Vulkan::SubmitInfo& info) {
    if (pending_binds.empty()) {
        return;
    }

    std::vector<vk::SparseBufferMemoryBindInfo> buffer_binds;
    buffer_binds.reserve(pending_binds.size());

    for (const auto& binds : pending_binds) {
        buffer_binds.emplace_back(vk::SparseBufferMemoryBindInfo{
            .buffer = binds.arena->Handle(),
            .bindCount = static_cast<u32>(binds.binds.size()),
            .pBinds = binds.binds.data(),
        });
    }

    const u64 signal_tick = memory_semaphore.NextTick();
    const auto signal_sema = memory_semaphore.Handle();

    const vk::TimelineSemaphoreSubmitInfo timeline_si = {
        .signalSemaphoreValueCount = 1u,
        .pSignalSemaphoreValues = &signal_tick,
    };

    const vk::BindSparseInfo sparse_info = {
        .pNext = &timeline_si,
        .bufferBindCount = static_cast<u32>(buffer_binds.size()),
        .pBufferBinds = buffer_binds.data(),
        .signalSemaphoreCount = 1u,
        .pSignalSemaphores = &signal_sema,
    };

    info.AddWait(signal_sema, signal_tick);
    auto submit_result = instance.GetGraphicsQueue().bindSparse(sparse_info);
    ASSERT_MSG(submit_result != vk::Result::eErrorDeviceLost, "Device lost during submit");

    pending_binds.clear();
}

} // namespace VideoCore
