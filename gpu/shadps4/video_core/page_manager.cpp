// SPDX-FileCopyrightText: Copyright 2024 shadPS4 Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <unordered_set>
#include <utility>
#include "common/adaptive_mutex.h"
#include "common/assert.h"
#include "common/debug.h"
#include "common/div_ceil.h"
#include "common/error.h"
#include "common/multi_level_page_table.h"
#include "common/signal_context.h"
#include "common/thread.h"
#include "gow3_toggles.h"
#include "core/emulator_settings.h"
#include "core/memory.h"
#include "core/signals.h"
#include "video_core/page_manager.h"
#include "video_core/page_state.h"
#include "video_core/renderer_vulkan/vk_rasterizer.h"
#include "shader_recompiler/ir/passes/srt.h"

extern "C" int runtime_memory_region_backing(uintptr_t address, uintptr_t* start, uintptr_t* end,
                                             int* mapped, const unsigned char** backing);

#ifndef _WIN64
#include <sys/mman.h>
#include "common/adaptive_mutex.h"
#else
#include <windows.h>
#endif

#ifdef __linux__
#include <thread>
#include <fcntl.h>
#include <linux/userfaultfd.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <mutex>
#else
#include "common/spin_lock.h"
#endif

namespace VideoCore {

struct PageManager::Impl {
    using PageState = VideoCore::PageState;

    static constexpr size_t ADDRESS_BITS = 40;
    static constexpr size_t NUM_ADDRESS_PAGES = 1ULL << (40 - PM_PAGE_BITS);
    static constexpr size_t NUM_ADDRESS_LOCKS = NUM_ADDRESS_PAGES / NUM_REGION_PAGES;
    inline static Vulkan::Rasterizer* rasterizer;

    inline static Impl* instance;

    Impl() {
        instance = this;
        static const bool enabled = [] {
            const char* value = std::getenv("GOW3_SRT_BACKING");
            return !value || value[0] != '0';
        }();
        if (enabled) {
            Shader::SetSrtWalkerHooks(SrtLoadPtr, SrtFixup, SrtReadDword);
        }
    }
    virtual ~Impl() {
        Shader::SetSrtWalkerHooks(nullptr, nullptr);
        instance = nullptr;
    }

    // uc3 (Fable R.8.1): the SRT walker no longer reads guest pages (B.0): it follows table
    // pointers in the backing view and records the guest source of each flattened dword
    // (B.1'). Bytes of a read-protected page that are GPU-written are read back here, after
    // the walk, instead of in a fault per load (B.2').
    static constexpr u64 SrtTableReach = 64_KB;

    // Only read-watched pages can hold GPU-written bytes the guest copy lacks (the guest's own
    // loads would fault there): a cheap filter before IsGpuWritten.
    static bool SrtPagesReadTracked(VAddr addr, u64 size) {
        const u64 first = addr >> PM_PAGE_BITS;
        const u64 last = (addr + size - 1) >> PM_PAGE_BITS;
        for (u64 page = first; page <= last; ++page) {
            const PageState* state = instance->cached_pages.find(page);
            if (state && state->num_read_watchers != 0) {
                return true;
            }
        }
        return false;
    }

    static bool SrtReadBack(VAddr addr, u64 size) {
        if (!Shader::SrtWalkerSyncAllowed() || !SrtPagesReadTracked(addr, size) ||
            !rasterizer->IsGpuWritten(addr, size)) {
            return false;
        }
        rasterizer->ReadMemory(addr, size, rasterizer->IsGpuSideThread());
        return true;
    }

    static const u8* SrtBacking(VAddr addr, u64 need) {
        uintptr_t start = 0, end = 0;
        int mapped = 0;
        const unsigned char* backing = nullptr;
        if (addr == 0 || !runtime_memory_region_backing(addr, &start, &end, &mapped, &backing) ||
            !mapped || !backing || end - addr < need) {
            return nullptr;
        }
        return backing;
    }

    // Diagnosis: GOW3_SRT_MODE bits (default 7): 1 = pointers read from the backing view,
    // 2 = tables read from the backing view, 4 = fixup after the walk, 8 = also on threads
    // that are not GPU-side.
    static u32 SrtMode() {
        static const u32 mode = [] {
            const char* value = std::getenv("GOW3_SRT_MODE");
            return value ? static_cast<u32>(std::strtoul(value, nullptr, 0)) : 7u;
        }();
        return mode;
    }

    static Shader::SrtPtr PS4_SYSV_ABI SrtLoadPtr(const u8* host_base, u64 guest_base, u64 off) {
        const u8* src = host_base + off;
        // Other threads keep the guest view: their faults read back in full (no R.7 there).
        const bool gpu_side = (SrtMode() & 8) || rasterizer->IsGpuSideThread();
        if (guest_base != 0 && (SrtMode() & 1) && gpu_side) {
            const VAddr guest_src = guest_base + off;
            if (SrtReadBack(guest_src, sizeof(u64))) {
                Gow3Stats::uc3_srt_ptr_sync.fetch_add(1, std::memory_order_relaxed);
            }
            if (const u8* backing = SrtBacking(guest_src, sizeof(u64))) {
                src = backing;
            }
        }
        u64 value;
        std::memcpy(&value, src, sizeof(value));
        value &= 0xFFFFFFFFFFFFULL;
        // A table near the end of its mapping keeps the guest address (its loads fault).
        const u8* table = (SrtMode() & 2) && gpu_side ? SrtBacking(value, SrtTableReach) : nullptr;
        static const bool trace = std::getenv("GOW3_SRT_TRACE") != nullptr;
        static std::atomic<int> traced{0};
        if (trace && traced.fetch_add(1, std::memory_order_relaxed) < 30) {
            std::printf("srt ptr: host_base=%p guest_base=%#llx off=%#llx -> value=%#llx table=%p\n",
                        host_base, (unsigned long long)guest_base, (unsigned long long)off,
                        (unsigned long long)value, table);
        }
        return {table ? reinterpret_cast<u64>(table) : value, value};
    }

    // uc3 (Fable R.8.2): GOW3_LEAF_COPY=1 leaves GPU-written dwords 0 in the flat and lists
    // them; the draw recording thread copies the 4 bytes into the constant ring on the GPU.
    static bool LeafCopy() {
        static const bool enabled = [] {
            const char* value = std::getenv("GOW3_LEAF_COPY");
            return value && value[0] == '1';
        }();
        return enabled;
    }

    static bool SrtTraceOn() {
        static const bool trace = std::getenv("GOW3_SRT_TRACE") != nullptr;
        return trace;
    }

    static void SrtReadDword(u64 src, u32* out) {
        Gow3Stats::uc3_srt_leaf_sharp.fetch_add(1, std::memory_order_relaxed);
        if (rasterizer->IsGpuWritten(src, sizeof(u32))) {
            rasterizer->ReadMemory(src, sizeof(u32), rasterizer->IsGpuSideThread());
        }
        if (const u8* backing = SrtBacking(src, sizeof(u32))) {
            std::memcpy(out, backing, sizeof(u32));
        }
    }

    static void SrtFixup(const u64* src, u32* flat, size_t count,
                         std::vector<Shader::SrtGpuLeaf>* leaves) {
        if (!(SrtMode() & 4)) {
            return;
        }
        const bool copy = leaves && LeafCopy() && rasterizer->IsGpuSideThread();
        const bool sync = Shader::SrtWalkerSyncAllowed();
        if (!copy && !sync) {
            return;
        }
        u64 last_page = ~0ULL;
        bool tracked = false;
        u64 synced = 0, copied = 0;
        for (size_t i = 0; i < count; ++i) {
            const VAddr addr = src[i];
            if (addr == 0) {
                continue;
            }
            if ((addr >> PM_PAGE_BITS) != last_page) {
                last_page = addr >> PM_PAGE_BITS;
                tracked = SrtPagesReadTracked(addr, sizeof(u32));
            }
            if (!tracked || !rasterizer->IsGpuWritten(addr, sizeof(u32))) {
                continue;
            }
            // Bytes an image still has to read back are not in a buffer: those read back.
            if (copy && !rasterizer->HasImageReadbackBytes(addr, sizeof(u32))) {
                flat[i] = 0;
                leaves->push_back({static_cast<u32>(i), 0, addr});
                ++copied;
                if (!rasterizer->IsGpuModifiedBytes(addr, sizeof(u32))) {
                    Gow3Stats::uc3_leaf_by_window.fetch_add(1, std::memory_order_relaxed);
                }
                if (SrtTraceOn()) {
                    static std::mutex seen_mutex;
                    static std::unordered_set<VAddr> seen;
                    std::scoped_lock lk{seen_mutex};
                    if (seen.insert(addr).second) {
                        Gow3Stats::uc3_leaf_new_addr.fetch_add(1, std::memory_order_relaxed);
                    }
                }
                continue;
            }
            if (!sync) {
                continue;
            }
            rasterizer->ReadMemory(addr, sizeof(u32), rasterizer->IsGpuSideThread());
            if (const u8* backing = SrtBacking(addr, sizeof(u32))) {
                std::memcpy(&flat[i], backing, sizeof(u32));
            }
            ++synced;
            last_page = ~0ULL;
        }
        if (synced) {
            Gow3Stats::uc3_srt_leaf_sync.fetch_add(synced, std::memory_order_relaxed);
            Gow3Stats::uc3_srt_sync_walks.fetch_add(1, std::memory_order_relaxed);
        }
        if (copied) {
            Gow3Stats::uc3_srt_leaf_zeroed.fetch_add(copied, std::memory_order_relaxed);
        }
    }

    virtual void OnMap(VAddr address, size_t size) {
        // No-op
        EnsurePages(address, address + size);
    }

    virtual void OnUnmap(VAddr address, size_t size) {
        // No-op
    }

    virtual void Protect(VAddr address, size_t size, Core::MemoryPermission perms) = 0;

    // uc3: the SRT walker (descriptor table copy, on the command processor and the shader
    // capture threads) reads pages it shares with GPU-written data: in the streets of
    // Cartagena, descriptors sit in the 4 KiB page where compute shader 2d215c3b writes 12
    // bytes of dispatch arguments, and each walk drained the GPU (~120 per second). When the
    // bytes the walker loads are not GPU-written (gpu_modified_ranges is byte-precise), guest
    // memory has them: do the load from the backing view, set the register and step over the
    // instruction. The page stays protected for the guest. GOW3_SRT_BACKING=0 turns it off.
    static bool EmulateSrtWalkerLoad(void* context, VAddr addr) {
        static const bool enabled = [] {
            const char* value = std::getenv("GOW3_SRT_BACKING");
            return !value || value[0] != '0';
        }();
        Shader::SrtWalkerLoad load;
        if (!enabled || !Shader::DecodeSrtWalkerLoad(Common::GetRip(context), load)) {
            return false;
        }
        // uc3 (experiment, Fable R.8 A): GOW3_SRT_STALE=1 also lets 4-byte leaf loads of
        // GPU-written bytes read the stale guest copy (what upstream without precise readbacks
        // flattens), to see whether those dwords change the picture.
        static const bool stale = [] {
            const char* value = std::getenv("GOW3_SRT_STALE");
            return value && value[0] == '1';
        }();
        const bool crosses = PageManager::GetNextPageAddr(addr) - addr < load.size;
        const bool gpu_written = !crosses && rasterizer->IsGpuWritten(addr, load.size);
        if (gpu_written && stale && !load.to_rdi) {
            Gow3Stats::uc3_srt_stale.fetch_add(1, std::memory_order_relaxed);
        } else if (crosses || gpu_written) {
            Gow3Stats::uc3_srt_sync.fetch_add(1, std::memory_order_relaxed);
            static const bool trace = [] {
                const char* value = std::getenv("GOW3_FAULT_TRACE");
                return value && value[0] == '1';
            }();
            static std::atomic<int> traced{0};
            if (trace && traced.fetch_add(1, std::memory_order_relaxed) < 40) {
                std::printf("srt walker load read back: addr=%#llx size=%u %s\n",
                            (unsigned long long)addr, load.size,
                            crosses ? "crosses a page" : rasterizer->DescribeGpuWritten(addr, load.size));
            }
            return false;
        }
        uintptr_t start = 0, end = 0;
        int mapped = 0;
        const unsigned char* backing = nullptr;
        if (!runtime_memory_region_backing(addr, &start, &end, &mapped, &backing) || !mapped ||
            !backing || end - addr < load.size) {
            Gow3Stats::uc3_srt_sync.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        u64 value = 0;
        std::memcpy(&value, backing, load.size);
#ifdef _WIN32
        auto* regs = static_cast<EXCEPTION_POINTERS*>(context)->ContextRecord;
        if (load.to_rdi) {
            regs->Rdi = value;
        } else {
            regs->R10 = value; // mov r10d zero-extends
        }
        regs->Rip += load.length;
#elif defined(__linux__) && defined(ARCH_X86_64)
        auto& gregs = static_cast<ucontext_t*>(context)->uc_mcontext.gregs;
        gregs[load.to_rdi ? REG_RDI : REG_R10] = static_cast<greg_t>(value);
        gregs[REG_RIP] += load.length;
#else
        return false;
#endif
        Gow3Stats::uc3_srt_emulated.fetch_add(1, std::memory_order_relaxed);
        return true;
    }

    static bool GuestFaultSignalHandler(void* context, void* fault_address) {
        const auto addr = reinterpret_cast<VAddr>(fault_address);
        const auto size = std::min<u64>(8, PageManager::GetNextPageAddr(addr) - addr);
        // gow3: the draw recording thread handles its faults inline too (vk_draw_pipe.h).
        const auto is_gpu_thread = rasterizer->IsGpuSideThread();
        if (is_gpu_thread) {
            Gow3Stats::gpu_signal_faults.fetch_add(1, std::memory_order_relaxed);
        }
        if (Common::IsWriteError(context)) {
            Gow3Stats::tracker_faults.fetch_add(1, std::memory_order_relaxed);
            Gow3Stats::Timer timer{Gow3Stats::t_write_faults};
            return rasterizer->OnWriteFault(addr, is_gpu_thread);
        } else {
            Gow3Stats::read_faults.fetch_add(1, std::memory_order_relaxed);
            Gow3Stats::Timer timer{Gow3Stats::t_read_faults};
            if (is_gpu_thread && EmulateSrtWalkerLoad(context, addr)) {
                return true;
            }
            // uc3: GOW3_FAULT_TRACE=1 prints the first 100 read faults of GPU-side threads
            // (they read back synchronously, draining the GPU): who faults, where, from what code.
            static const bool trace = [] {
                const char* value = std::getenv("GOW3_FAULT_TRACE");
                return value && value[0] == '1';
            }();
            static std::atomic<int> traced{0};
            if (trace && is_gpu_thread) {
                Gow3Stats::uc3_last_gpu_fault.store(addr, std::memory_order_relaxed);
            }
            if (trace && is_gpu_thread && traced.fetch_add(1, std::memory_order_relaxed) < 100) {
                void* base = nullptr;
                char module[260] = "?";
#ifdef _WIN32
                HMODULE owner = nullptr;
                if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                                           GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                                       static_cast<LPCSTR>(Common::GetRip(context)), &owner)) {
                    base = owner;
                    GetModuleFileNameA(owner, module, sizeof(module));
                }
#endif
                std::printf("gpu-side read fault: thread %c addr=%#llx rip=%p module=%p %s\n",
                            rasterizer->GpuSideThreadKind(), (unsigned long long)addr,
                            Common::GetRip(context), base, module);
                if (traced.load(std::memory_order_relaxed) <= 3) {
                    const auto* code = static_cast<const u8*>(Common::GetRip(context)) - 32;
                    std::printf("  code at rip-32:");
                    for (int i = 0; i < 64; ++i) {
                        std::printf(" %02x", code[i]);
                    }
                    std::printf("\n");
                }
            }
            return rasterizer->ReadMemory(addr, size, is_gpu_thread);
        }
        return false;
    }

    void EnsurePages(VAddr begin, VAddr end) {
        end = std::min(end, VAddr{1} << ADDRESS_BITS) - 1;
        const size_t start_page = begin >> PM_PAGE_BITS;
        const size_t end_page = end >> PM_PAGE_BITS;
        cached_pages.reserve(start_page, end_page);
        locks.reserve(start_page, end_page);
    }

    void UpdatePageWatchers(VAddr addr, u64 size, PageOp write_op, PageOp read_op) {
        const u64 page_start = addr >> PM_PAGE_BITS;
        const u64 page_end = Common::DivCeil(addr + size, PM_PAGE_SIZE);

        Core::MemoryPermission perms{};
        u64 range_begin = page_start;
        u64 range_pages = 0;
        u64 potential_pages = 0;

        const auto release_pending = [&] {
            if (range_pages > 0) {
                Protect(range_begin << PM_PAGE_BITS, range_pages << PM_PAGE_BITS, perms);
                range_pages = 0;
                potential_pages = 0;
            }
        };

        // Iterate requested pages
        const u64 aligned_addr = page_start << PM_PAGE_BITS;
        const u64 aligned_end = page_end << PM_PAGE_BITS;
        if (!rasterizer->IsMapped(aligned_addr, aligned_end - aligned_addr)) {
            LOG_WARNING(Render,
                        "Tracking memory region {:#x} - {:#x} which is not fully GPU mapped.",
                        aligned_addr, aligned_end);
            EnsurePages(aligned_addr, aligned_end);
        }

        for (u64 page = page_start; page != page_end; ++page) {
            PageState* state = cached_pages.find(page);
            if (!state) {
                continue;
            }

            locks[page].lock();

            const auto old_perms = state->Perms();
            if (page == page_start) {
                perms = old_perms;
            }

            // Apply the change to the page state
            const auto new_perms = state->Update(write_op, write_op != PageOp::None, read_op,
                                                 read_op != PageOp::None);
            if (new_perms != perms) [[unlikely]] {
                // If the protection changed add pending (un)protect action
                release_pending();
                perms = new_perms;
            } else if (range_pages != 0) {
                ++potential_pages;
            }

            // If the page must be (un)protected
            if (new_perms != old_perms) {
                if (range_pages == 0) {
                    // Start a new potential range
                    range_begin = page;
                    potential_pages = 1;
                }
                // Extend current range up to potential range
                range_pages = potential_pages;
            }
        }

        // Add pending (un)protect action
        release_pending();

        for (u64 page = page_start; page != page_end; ++page) {
            if (auto* lock = locks.find(page)) {
                lock->unlock();
            }
        }
    }

    void UpdatePageWatchersForRegion(VAddr base_addr, const Bounds& bounds,
                                     const RegionBits& write_mask, const RegionBits& read_mask,
                                     PageOp write_op, PageOp read_op) {
        const u64 base_page = base_addr >> PM_PAGE_BITS;
        const u64 page_start = bounds.start_word * PAGES_PER_WORD + bounds.start_page;
        const u64 page_end = bounds.end_word * PAGES_PER_WORD + bounds.end_page + 1;

        Core::MemoryPermission perms{};
        u64 range_begin = base_page + page_start;
        u64 range_pages = 0;
        u64 potential_pages = 0;

        const auto release_pending = [&] {
            if (range_pages > 0) {
                Protect(range_begin << PM_PAGE_BITS, range_pages << PM_PAGE_BITS, perms);
                range_pages = 0;
                potential_pages = 0;
            }
        };

        for (u64 page = page_start; page != page_end; ++page) {
            PageState* state = cached_pages.find(base_page + page);
            if (!state) {
                continue;
            }

            locks[base_page + page].lock();

            const auto old_perms = state->Perms();
            if (page == page_start) {
                perms = old_perms;
            }

            // Apply the change to the page state
            const bool update_write = write_op != PageOp::None && write_mask.GetPage(page);
            const bool update_read = read_op != PageOp::None && read_mask.GetPage(page);
            const auto new_perms = state->Update(write_op, update_write, read_op, update_read);

            if (new_perms != perms) [[unlikely]] {
                // If the protection changed add pending (un)protect action
                release_pending();
                perms = new_perms;
            } else if (range_pages != 0) {
                // If the protection did not change, extend the potential range
                ++potential_pages;
            }

            // If the page must be (un)protected
            if (new_perms != old_perms) {
                if (range_pages == 0) {
                    // Start a new potential range
                    range_begin = base_page + page;
                    potential_pages = 1;
                }
                // Extend current rango up to potential range
                range_pages = potential_pages;
            }
        }

        // Add pending (un)protect action
        release_pending();

        for (u64 page = page_start; page != page_end; ++page) {
            if (auto* lock = locks.find(base_page + page)) {
                lock->unlock();
            }
        }
    }

    struct PageTraits {
        using Entry = PageState;
        static constexpr size_t ADDRESS_SPACE_BITS = ADDRESS_BITS;
        static constexpr size_t L1_BITS = 16;
        static constexpr size_t PAGE_BITS = PM_PAGE_BITS;
        static constexpr bool NULL_CHECK = false;
    };
    Common::MultiLevelPageTable<PageTraits> cached_pages;
    struct MutexTraits {
#ifdef PTHREAD_ADAPTIVE_MUTEX_INITIALIZER_NP
        using Entry = Common::AdaptiveMutex;
#else
        using Entry = std::mutex;
#endif
        static constexpr size_t ADDRESS_SPACE_BITS = ADDRESS_BITS;
        static constexpr size_t L1_BITS = 16;
        static constexpr size_t PAGE_BITS = PM_PAGE_BITS;
        static constexpr bool NULL_CHECK = false;
    };
    Common::MultiLevelPageTable<MutexTraits> locks;
};

#ifdef __linux__
// gow3: write tracking with userfaultfd write-protection instead of mprotect. mprotect takes
// the address space lock for writing and splits mappings; while guest threads fault on
// per-frame buffers and the GPU thread re-protects uploaded pages, every page fault in the
// process (copy threads included) waits for it. UFFDIO_WRITEPROTECT changes page table bits
// under the lock for reading. Read protection (readbacks) still uses mprotect: userfaultfd
// write-protection cannot deny reads. GOW3_UFFD=1.
struct UffdImpl : public PageManager::Impl {
private:
    std::jthread ufd_thread;
    int uffd;
    std::mutex read_revoked_mutex;
    std::unordered_set<u64> read_revoked_pages; ///< 4 KiB pages denied reads with mprotect
    std::atomic<size_t> num_read_revoked{0};

public:
    UffdImpl(Vulkan::Rasterizer* rasterizer_) : Impl() {
        rasterizer = rasterizer_;
        uffd = syscall(SYS_userfaultfd, O_CLOEXEC | O_NONBLOCK | UFFD_USER_MODE_ONLY);
        if (uffd == -1) {
            LOG_ERROR(Common_Memory,
                      "userfaultfd syscall failed: {}, falling back to signal implementation",
                      Common::GetLastErrorMsg());
            throw std::runtime_error("userfaultfd");
        }

        // Guest memory is a shared memfd mapping: write-protection there needs the shmem
        // feature, and unpopulated pages must be protectable too.
        uffdio_api api{};
        api.api = UFFD_API;
        api.features =
            UFFD_FEATURE_THREAD_ID | UFFD_FEATURE_WP_HUGETLBFS_SHMEM | UFFD_FEATURE_WP_UNPOPULATED;
        if (ioctl(uffd, UFFDIO_API, &api) != 0) {
            LOG_ERROR(Common_Memory,
                      "uffdio_api call failed: {}, falling back to signal implementation",
                      Common::GetLastErrorMsg());
            close(uffd);
            throw std::runtime_error("uffdio_api");
        }

        // Read faults (readbacks) still arrive as signals.
        Core::Signals::Instance()->RegisterAccessViolationHandler(
            GuestFaultSignalHandler, std::numeric_limits<u32>::min());

        ufd_thread = std::jthread([this](std::stop_token token) { UffdHandler(token); });
        std::printf("GPU: memory tracking with userfaultfd write-protection\n");
    }

    ~UffdImpl() = default;

    void OnMap(VAddr address, size_t size) override {
        PageManager::Impl::OnMap(address, size);
        uffdio_register reg{};
        reg.range.start = address;
        reg.range.len = size;
        reg.mode = UFFDIO_REGISTER_MODE_WP;
        if (ioctl(uffd, UFFDIO_REGISTER, &reg) == -1) {
            LOG_ERROR(Common_Memory, "Uffdio register {:#x}+{:#x} failed: {}", address, size,
                      Common::GetLastErrorMsg());
        }
    }

    void OnUnmap(VAddr address, size_t size) override {
        uffdio_range range{};
        range.start = address;
        range.len = size;
        ioctl(uffd, UFFDIO_UNREGISTER, &range);
    }

    void Protect(VAddr address, size_t size, Core::MemoryPermission perms) override {
        auto& address_space = Core::Memory::Instance()->GetAddressSpace();
        const bool allow_read = True(perms & Core::MemoryPermission::Read);
        const bool allow_write = True(perms & Core::MemoryPermission::Write);
        const u64 first = address >> 12, last = (address + size + 4095) >> 12;
        if (!allow_read) {
            // Readbacks: deny all access with mprotect.
            address_space.Protect(address, size, Core::MemoryPermission::None);
            std::scoped_lock lk{read_revoked_mutex};
            for (u64 page = first; page < last; ++page) {
                read_revoked_pages.insert(page);
            }
            num_read_revoked.store(read_revoked_pages.size(), std::memory_order_release);
            return;
        }
        if (num_read_revoked.load(std::memory_order_acquire) != 0) {
            std::scoped_lock lk{read_revoked_mutex};
            bool restore = false;
            for (u64 page = first; page < last; ++page) {
                restore |= read_revoked_pages.erase(page) != 0;
            }
            num_read_revoked.store(read_revoked_pages.size(), std::memory_order_release);
            if (restore) {
                address_space.Protect(address, size, Core::MemoryPermission::ReadWrite);
            }
        }
        uffdio_writeprotect wp{};
        wp.range.start = address;
        wp.range.len = size;
        wp.mode = allow_write ? UFFDIO_WRITEPROTECT_MODE_DONTWAKE : UFFDIO_WRITEPROTECT_MODE_WP;
        if (ioctl(uffd, UFFDIO_WRITEPROTECT, &wp) == -1) {
            LOG_ERROR(Common_Memory, "Uffdio writeprotect {:#x}+{:#x} failed: {}", address, size,
                      Common::GetLastErrorMsg());
        }
    }

    void UffdHandler(std::stop_token token) {
        Common::SetCurrentThreadName("gow3:Uffd");
        while (!token.stop_requested()) {
            pollfd pollfd{};
            pollfd.fd = uffd;
            pollfd.events = POLLIN;
            // Short timeout so the stop request is seen.
            const int pollres = poll(&pollfd, 1, 100);
            if (pollres <= 0 || !(pollfd.revents & POLLIN)) {
                continue;
            }
            uffd_msg msg;
            const int readret = read(uffd, &msg, sizeof(msg));
            if (readret != sizeof(msg)) {
                continue;
            }
            if (msg.event != UFFD_EVENT_PAGEFAULT) {
                continue;
            }
            const VAddr addr = msg.arg.pagefault.address;
            const auto ptid = msg.arg.pagefault.feat.ptid;
            {
                Gow3Stats::Timer timer{Gow3Stats::t_write_faults};
                rasterizer->OnWriteFault(addr, rasterizer->IsGpuSideThreadId(ptid));
            }
            // Protect() clears with DONTWAKE (it may run for pages nobody waits on).
            uffdio_range wake{};
            wake.start = addr & ~u64(PageManager::PM_PAGE_SIZE - 1);
            wake.len = PageManager::PM_PAGE_SIZE;
            ioctl(uffd, UFFDIO_WAKE, &wake);
        }
    }
};
#endif // __linux__

struct SignalImpl : public PageManager::Impl {
    SignalImpl(Vulkan::Rasterizer* rasterizer_) : Impl() {
        rasterizer = rasterizer_;

        // Should be called first.
        constexpr auto priority = std::numeric_limits<u32>::min();
        Core::Signals::Instance()->RegisterAccessViolationHandler(GuestFaultSignalHandler,
                                                                  priority);
    }

    void Protect(VAddr address, size_t size, Core::MemoryPermission perms) override {
        RENDERER_TRACE;
        auto* memory = Core::Memory::Instance();
        auto& impl = memory->GetAddressSpace();
        ASSERT_MSG(perms != Core::MemoryPermission::Write,
                   "Attempted to protect region as write-only which is not a valid permission");
        impl.Protect(address, size, perms);
    }
};

PageManager::PageManager(Vulkan::Rasterizer* rasterizer_) {
#ifdef __linux__
    if (std::getenv("GOW3_UFFD") && std::getenv("GOW3_UFFD")[0] == '1') {
        try {
            impl = std::make_unique<UffdImpl>(rasterizer_);
            LOG_INFO(Config, "Memory tracking method: userfaultfd");
            return;
        } catch (const std::runtime_error& e) {
            // if uffd is unsupported, falls back to SignalImpl
        }
    }
    LOG_INFO(Config, "Memory tracking method: signals");
#endif
    impl = std::make_unique<SignalImpl>(rasterizer_);
}

PageManager::~PageManager() = default;

void PageManager::OnGpuMap(VAddr address, size_t size) {
    impl->OnMap(address, size);
}

void PageManager::OnGpuUnmap(VAddr address, size_t size) {
    impl->OnUnmap(address, size);
}

void PageManager::UpdatePageWatchers(VAddr addr, u64 size, PageOp write_op,
                                     PageOp read_op) const {
    impl->UpdatePageWatchers(addr, size, write_op, read_op);
}

void PageManager::UpdatePageWatchersForRegion(VAddr base_addr, const Bounds& bounds,
                                              const RegionBits& write_mask,
                                              const RegionBits& read_mask, PageOp write_op,
                                              PageOp read_op) const {
    impl->UpdatePageWatchersForRegion(base_addr, bounds, write_mask, read_mask, write_op, read_op);
}

} // namespace VideoCore
