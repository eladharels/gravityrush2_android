// SPDX-FileCopyrightText: Copyright 2020 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <chrono>
#include <cstdio>
#include <limits>
#include <mutex>
#include <vector>
#include "video_core/renderer_vulkan/vk_instance.h"
#include "video_core/renderer_vulkan/vk_semaphore.h"

#include "common/assert.h"

namespace Vulkan {

constexpr u64 WAIT_TIMEOUT = std::numeric_limits<u64>::max();
/// uc3 (Fable R.8.4): waits are cut in slices so a stuck one is reported.
constexpr u64 WAIT_SLICE_NS = 2'000'000'000ULL;

namespace {
std::mutex registry_mutex;
std::vector<Semaphore*>& Registry() {
    static std::vector<Semaphore*> registry;
    return registry;
}
} // namespace

Semaphore::Semaphore(const Instance& instance_, const char* name_)
    : instance{instance_}, name{name_} {
    {
        std::scoped_lock lk{registry_mutex};
        Registry().push_back(this);
    }
    const vk::StructureChain semaphore_chain = {
        vk::SemaphoreCreateInfo{},
        vk::SemaphoreTypeCreateInfo{
            .semaphoreType = vk::SemaphoreType::eTimeline,
            .initialValue = 0,
        },
    };
    auto [semaphore_result, sem] =
        instance.GetDevice().createSemaphoreUnique(semaphore_chain.get());
    ASSERT_MSG(semaphore_result == vk::Result::eSuccess, "Failed to create master semaphore: {}",
               vk::to_string(semaphore_result));
    semaphore = std::move(sem);
}

Semaphore::~Semaphore() {
    std::scoped_lock lk{registry_mutex};
    std::erase(Registry(), this);
}

void Semaphore::SettleAll(u32 timeout_ms) {
    std::scoped_lock lk{registry_mutex};
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds{timeout_ms};
    for (Semaphore* sem : Registry()) {
        const u64 last = sem->CurrentTick() - 1;
        if (last == 0) {
            continue;
        }
        const auto left = std::chrono::duration_cast<std::chrono::nanoseconds>(
                              deadline - std::chrono::steady_clock::now())
                              .count();
        const vk::SemaphoreWaitInfo wait_info = {
            .semaphoreCount = 1,
            .pSemaphores = &sem->semaphore.get(),
            .pValues = &last,
        };
        const vk::Result result =
            sem->instance.GetDevice().waitSemaphores(&wait_info, left > 0 ? u64(left) : 0);
        auto [counter_result, counter] =
            sem->instance.GetDevice().getSemaphoreCounterValue(*sem->semaphore);
        if (result != vk::Result::eSuccess) {
            std::printf("gow3: at exit, GPU semaphore %s stuck: counter %llu, last submitted "
                        "tick %llu, known %llu\n",
                        sem->name, static_cast<unsigned long long>(counter),
                        static_cast<unsigned long long>(last),
                        static_cast<unsigned long long>(sem->KnownGpuTick()));
        }
    }
    std::fflush(stdout);
}

void Semaphore::Refresh() {
    u64 this_tick{};
    u64 counter{};
    do {
        this_tick = gpu_tick.load(std::memory_order_acquire);
        auto [counter_result, cntr] = instance.GetDevice().getSemaphoreCounterValue(*semaphore);
        ASSERT_MSG(counter_result == vk::Result::eSuccess,
                   "Failed to get master semaphore value: {}", vk::to_string(counter_result));
        counter = cntr;
        if (counter < this_tick) {
            return;
        }
    } while (!gpu_tick.compare_exchange_weak(this_tick, counter, std::memory_order_release,
                                             std::memory_order_relaxed));
}

void Semaphore::Wait(u64 tick) {
    // No need to wait if the GPU is ahead of the tick
    if (IsFree(tick)) {
        return;
    }
    // Update the GPU tick and try again
    Refresh();
    if (IsFree(tick)) {
        return;
    }

    // If none of the above is hit, fallback to a regular wait
    const vk::SemaphoreWaitInfo wait_info = {
        .semaphoreCount = 1,
        .pSemaphores = &semaphore.get(),
        .pValues = &tick,
    };

    bool reported = false;
    while (instance.GetDevice().waitSemaphores(&wait_info, WAIT_SLICE_NS) != vk::Result::eSuccess) {
        if (!reported) {
            reported = true;
            auto [counter_result, counter] = instance.GetDevice().getSemaphoreCounterValue(*semaphore);
            std::printf("gow3: GPU wait on %s stuck over 2 s: waiting for tick %llu, counter %llu, "
                        "known %llu, current %llu\n",
                        name, static_cast<unsigned long long>(tick),
                        static_cast<unsigned long long>(counter),
                        static_cast<unsigned long long>(KnownGpuTick()),
                        static_cast<unsigned long long>(CurrentTick()));
            std::fflush(stdout);
        }
    }
    Refresh();
}

} // namespace Vulkan
