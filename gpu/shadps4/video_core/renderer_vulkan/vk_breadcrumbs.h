// SPDX-License-Identifier: GPL-2.0-or-later
// 移植自 Bloodborne-Android（GPL-2.0）的 vk_breadcrumbs.h，按 gow3_pc 结构适配。
// GPU breadcrumbs：给 GPU 挂死（ring 超时 / device lost）时指认卡在哪条命令。
// 每个命令流按录制顺序给 draw/dispatch/宿主 pass 编号，GPU 在命令 n 前后写入 2n/2n+1 到
// host 可见缓冲（VK_AMD_buffer_marker，管线最底端写入：队列先前的工作全部完成后才写）。
// 挂死时最后的值即未完成的命令，配合这里登记的描述即可定位。GOW3_BREADCRUMBS=0 关闭。
#pragma once

#include "common/types.h"
#include "video_core/renderer_vulkan/vk_common.h"

namespace Vulkan {
class Instance;
}

namespace Vulkan::Breadcrumbs {

enum class Kind : u8 { Pass, Draw, DrawIndexed, DrawIndirect, Dispatch, DispatchIndirect };

struct Crumb {
    Kind kind = Kind::Pass;
    const char* name = nullptr; ///< 宿主 pass：字符串字面量
    u64 hash[2]{};              ///< guest 着色器：顶点/像素，或 compute 的 pgm_hash
    u64 program[2]{};           ///< 对应 guest 代码地址（pgm_base）
    u64 address = 0;            ///< indirect 参数的 guest 地址
    u32 count[3]{};             ///< 索引/顶点数与实例数，或 group 数
    u32 args_slot = ~0u;        ///< indirect dispatch：GPU 实际读取的参数槽位
};

/// 为一个命令流（每个 Scheduler 一个）登记名字（字符串字面量）；首次调用创建标记缓冲。
u32 NewStream(const Instance& instance, const char* name);
[[nodiscard]] bool Enabled() noexcept;
/// 录制侧：按命令顺序给 `stream` 的下一条命令编号。
u32 Note(u32 stream, const Crumb& crumb);
/// 在编号 `id` 的命令之前（`after` 为 false）与之后录制标记。
void Mark(vk::CommandBuffer cmdbuf, u32 stream, u32 id, bool after);
/// indirect dispatch：host 可见缓冲里给 GPU 读到的参数留的槽位（关闭时 ~0u）。
/// CopyArgs 在 dispatch 之前录制，把游戏写的原始参数拷进来供报告展示。
u32 ArgsSlot();
void CopyArgs(vk::CommandBuffer cmdbuf, vk::Buffer args, u64 offset, u32 slot);
/// device lost：按流打印 GPU 未完成的命令及其描述。
void ReportDeviceLost(const char* where);
/// 同一份报告：GPU 长时间无进展但尚未 device lost 时打印一次（可能永远等不到 reset）。
void ReportStuck(const char* where);

/// 给当前（非延迟）录制在 `cmdbuf` 上的命令段加 breadcrumbs 的作用域。
class Scope {
public:
    Scope(vk::CommandBuffer cmdbuf_, u32 stream_, const char* name)
        : cmdbuf{cmdbuf_}, stream{stream_} {
        if (Enabled()) {
            id = Note(stream, {.name = name});
            Mark(cmdbuf, stream, id, false);
        }
    }
    ~Scope() {
        if (id) {
            Mark(cmdbuf, stream, id, true);
        }
    }
    Scope(const Scope&) = delete;
    Scope& operator=(const Scope&) = delete;

private:
    vk::CommandBuffer cmdbuf;
    u32 stream;
    u32 id = 0;
};

} // namespace Vulkan::Breadcrumbs
