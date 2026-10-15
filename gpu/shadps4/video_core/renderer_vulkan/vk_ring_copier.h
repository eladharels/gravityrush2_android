// SPDX-License-Identifier: GPL-2.0-or-later
// gow3 R.9.12 (GOW3_RING_COPY_THREAD=1): stage A (the GPU command thread) spends about a
// quarter of its time copying constants from guest memory into the constant ring
// (vk_constant_ring.h, ~2 GB/s in the streets). With this on, stage A only queues each copy
// here and a copy thread runs them in order. Before stage B runs a packet it waits until the
// copies queued before that packet are done, so stage B and the GPU see the same bytes as
// before. Guest memory writes stage A makes itself (MemSemaphore signal, DumpConstRam) first
// wait for every queued copy (Rasterizer::WaitRingCopies); writes handed to stage B
// (RunInOrder) run after their packet's wait, and other packets drain stage B first.

#pragma once

#include <algorithm>
#include <array>
#include <string>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <memory>
#include <thread>
#include <x86intrin.h>

#include "common/thread.h"
#include "common/types.h"

namespace Vulkan {

class RingCopier {
public:
    /// Copies `size` bytes of guest memory at `address` to `dst` (ring offset `offset`).
    using CopyFn = void (*)(void* context, VAddr address, u8* dst, u64 size, u64 offset);

    RingCopier(CopyFn fn_, void* context_) : fn{fn_}, context{context_} {
        jobs = std::make_unique<Job[]>(Capacity);
        // R.9.14: GOW3_RING_COPY_THREADS=N (1-4) splits the copies over N threads, copy j on
        // thread j % N: a packet waits for less (each thread copies its share in order).
        if (const char* env = std::getenv("GOW3_RING_COPY_THREADS")) {
            num_threads = std::clamp(std::atoi(env), 1, int(MaxThreads));
        }
        for (u32 i = 0; i < num_threads; ++i) {
            threads[i] = std::jthread([this, i](std::stop_token stop) { Run(stop, i); });
        }
    }

    ~RingCopier() {
        for (u32 i = 0; i < num_threads; ++i) {
            threads[i].request_stop();
        }
        wake.fetch_add(1, std::memory_order_seq_cst);
        wake.notify_all();
    }

    static bool Wanted() {
        static const bool on = [] {
            const char* env = std::getenv("GOW3_RING_COPY_THREAD");
            return env && env[0] == '1';
        }();
        return on;
    }

    /// Stage A: queues a copy.
    void Push(VAddr address, u8* dst, u64 size, u64 offset) {
        while (head >= Capacity && !IsDone(head - Capacity + 1)) {
            __builtin_ia32_pause();
        }
        jobs[head % Capacity] = {address, dst, size, offset};
        ++head;
        enqueued.store(head, std::memory_order_seq_cst);
        if (sleeping.load(std::memory_order_seq_cst) != 0) {
            wake.fetch_add(1, std::memory_order_seq_cst);
            wake.notify_all();
        }
    }

    /// The number of copies queued so far (a packet's sequence number).
    [[nodiscard]] u64 Enqueued() const noexcept {
        return enqueued.load(std::memory_order_acquire);
    }

    /// True once the first `seq` queued copies are done.
    [[nodiscard]] bool IsDone(u64 seq) const noexcept {
        for (u32 i = 0; i < num_threads; ++i) {
            // Copies below seq that thread i runs: i, i + N, ...
            const u64 count = seq > i ? (seq - i + num_threads - 1) / num_threads : 0;
            if (done[i].value.load(std::memory_order_acquire) < count) {
                return false;
            }
        }
        return true;
    }

    /// Any thread: waits until the first `seq` queued copies are done.
    void WaitDone(u64 seq, bool stage_b = false) {
        if (IsDone(seq)) {
            return;
        }
        const u64 start = __rdtsc();
        for (u32 spins = 0; !IsDone(seq); ++spins) {
            if (spins < 4096) {
                __builtin_ia32_pause();
            } else {
                std::this_thread::yield();
            }
        }
        (stage_b ? b_wait_cycles : wait_cycles).fetch_add(__rdtsc() - start, std::memory_order_relaxed);
        (stage_b ? b_waits : waits).fetch_add(1, std::memory_order_relaxed);
    }

    /// Any thread: waits for every copy queued so far.
    void WaitAll() {
        WaitDone(Enqueued());
    }

    /// Statistics: cycles spent waiting for copies (and how many waits), cycles copying.
    std::atomic<u64> wait_cycles{0}, waits{0}, busy_cycles{0};
    std::atomic<u64> b_wait_cycles{0}, b_waits{0}; ///< stage B's share

private:
    struct Job {
        VAddr address;
        u8* dst;
        u64 size;
        u64 offset;
    };
    static constexpr u64 Capacity = 1ull << 16;
    static constexpr u32 MaxThreads = 4;

    /// Thread `index` runs copies index, index + N, ...
    void Run(std::stop_token stop, u32 index) {
        Common::SetCurrentThreadName(("gow3:RingCopy" + std::to_string(index)).c_str());
        const u64 step = num_threads;
        u64 at = index;
        u64 count = 0;
        while (true) {
            u64 available = enqueued.load(std::memory_order_acquire);
            if (available <= at) {
                // Copies come every few microseconds while a frame is decoded: spin, and
                // sleep only after a longer pause (as the draw pipe does).
                const auto spin_until =
                    std::chrono::steady_clock::now() + std::chrono::microseconds(200);
                for (u32 spins = 1; available <= at; ++spins) {
                    if (stop.stop_requested()) {
                        return;
                    }
                    __builtin_ia32_pause();
                    if (!(spins & 255) && std::chrono::steady_clock::now() >= spin_until) {
                        const u32 seen = wake.load(std::memory_order_seq_cst);
                        sleeping.fetch_add(1, std::memory_order_seq_cst);
                        if (enqueued.load(std::memory_order_seq_cst) <= at &&
                            !stop.stop_requested()) {
                            wake.wait(seen, std::memory_order_seq_cst);
                        }
                        sleeping.fetch_sub(1, std::memory_order_relaxed);
                    }
                    available = enqueued.load(std::memory_order_acquire);
                }
            }
            const u64 start = __rdtsc();
            while (at < available) {
                const Job& job = jobs[at % Capacity];
                fn(context, job.address, job.dst, job.size, job.offset);
                at += step;
                done[index].value.store(++count, std::memory_order_release);
            }
            busy_cycles.fetch_add(__rdtsc() - start, std::memory_order_relaxed);
        }
    }

    CopyFn fn;
    void* context;
    std::unique_ptr<Job[]> jobs;
    u64 head = 0; ///< stage A
    u32 num_threads = 1;
    alignas(64) std::atomic<u64> enqueued{0};
    struct alignas(64) Done {
        std::atomic<u64> value{0}; ///< copies done by one thread
    };
    std::array<Done, MaxThreads> done{};
    alignas(64) std::atomic<u32> wake{0};
    std::atomic<u32> sleeping{0};
    std::array<std::jthread, MaxThreads> threads;
};

} // namespace Vulkan
