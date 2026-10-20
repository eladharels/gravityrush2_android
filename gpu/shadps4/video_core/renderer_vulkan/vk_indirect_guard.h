// SPDX-License-Identifier: GPL-2.0-or-later
// 移植自 Bloodborne-Android（GPL-2.0）的 vk_indirect_guard.h，按 gow3_pc 结构适配。
// indirect dispatch 的 group counts 来自 guest shader 写入的内存，竞态下可能是垃圾值
// （数十亿个 group）：GPU 跑不完，内核超时后设备被 reset。每次 indirect dispatch 前，
// 一个单线程 compute shader 把 counts 拷到自有槽位，越界（超过设备限制或
// GOW3_INDIRECT_GUARD_MAX 总量，默认 4M）则清零，dispatch 改从该槽位读取。
// 被跳过的 dispatch 计数并按周期报告。GOW3_INDIRECT_GUARD=0 恢复游戏原始计数直通。
#pragma once

#include <atomic>
#include <chrono>
#include <memory>
#include <utility>

#include "common/types.h"
#include "video_core/renderer_vulkan/vk_common.h"

namespace VideoCore {
struct Buffer;
}

namespace Vulkan {

class Instance;
class Scheduler;

class IndirectGuard {
public:
    IndirectGuard(const Instance& instance_, Scheduler& scheduler_);
    ~IndirectGuard();

    static bool Enabled();

    /// 总量上限解析：环境变量值为空/非法时用默认值（1<<22，约 4M groups）。
    /// 独立成纯函数以便宿主单测（vk-diagnostics-test）。
    static u32 ParseMaxTotal(const char* env_value);

    /// 在 render pass 之外录制对 `offset` 处参数的校验；返回 dispatch 应改用的缓冲与偏移。
    std::pair<vk::Buffer, u64> CheckArgs(const VideoCore::Buffer& args, u64 offset);

private:
    static constexpr u32 NumSlots = 1024;

    void Report();

    const Instance& instance;
    Scheduler& scheduler;
    std::unique_ptr<VideoCore::Buffer> checked; ///< NumSlots x 16 字节的已校验 group counts
    std::unique_ptr<VideoCore::Buffer> skipped; ///< 跳过计数与最近计数值（host 可读）
    u32 max_count[3]{};
    u32 max_total = 0;
    vk::UniqueDescriptorSetLayout set_layout;
    vk::UniquePipelineLayout pipeline_layout;
    vk::UniquePipeline pipeline;
    std::atomic<u32> next_slot{0};
    std::atomic<u32> reported{0};
    std::atomic<s64> last_report_ns{0};
};

} // namespace Vulkan
