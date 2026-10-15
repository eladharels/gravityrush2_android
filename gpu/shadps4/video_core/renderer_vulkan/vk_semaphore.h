// SPDX-FileCopyrightText: Copyright 2020 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <atomic>
#include <condition_variable>
#include <thread>
#include <queue>
#include "common/types.h"
#include "video_core/renderer_vulkan/vk_common.h"

namespace Vulkan {

class Instance;
class Scheduler;

class Semaphore {
public:
    explicit Semaphore(const Instance& instance_, const char* name_ = "semaphore");
    ~Semaphore();

    [[nodiscard]] u64 CurrentTick() const noexcept {
        return current_tick.load(std::memory_order_acquire);
    }

    [[nodiscard]] u64 KnownGpuTick() const noexcept {
        return gpu_tick.load(std::memory_order_acquire);
    }

    [[nodiscard]] bool IsFree(u64 tick) const noexcept {
        return KnownGpuTick() >= tick;
    }

    [[nodiscard]] u64 NextTick() noexcept {
        return current_tick.fetch_add(1, std::memory_order_release);
    }

    [[nodiscard]] vk::Semaphore Handle() const noexcept {
        return semaphore.get();
    }

    /// Refresh the known GPU tick
    void Refresh();

    /// Waits for a tick to be hit on the GPU
    void Wait(u64 tick);

    /// uc3 (Fable R.8.4): at exit, waits up to `timeout_ms` for every semaphore to reach the
    /// last submitted tick and prints the ones that did not (a GPU queue waiting on a signal
    /// that will never be submitted keeps the process from terminating).
    static void SettleAll(u32 timeout_ms);

protected:
    const Instance& instance;
    vk::UniqueSemaphore semaphore;    ///< Timeline semaphore.
    std::atomic<u64> gpu_tick{0};     ///< Current known GPU tick.
    std::atomic<u64> current_tick{1}; ///< Current logical tick.
    const char* name;
};

} // namespace Vulkan
