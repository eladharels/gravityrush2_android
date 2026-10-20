// SPDX-License-Identifier: GPL-2.0-or-later
// GPU 诊断模块（vk_breadcrumbs / vk_indirect_guard）的宿主可测部分：
// 开关解析、未初始化（无设备）时的安全降级。标记写入与卡死报告的真值依赖支持
// VK_AMD_buffer_marker 的真机（见 change port-indirect-guard-and-breadcrumbs 任务 5.x）。
#include <cstdio>
#include <cstdlib>

#include "video_core/renderer_vulkan/vk_breadcrumbs.h"
#include "video_core/renderer_vulkan/vk_indirect_guard.h"

int main() {
    using namespace Vulkan;

    // 1. IndirectGuard 开关：环境变量未设置时默认开启（静态缓存，进程内一次解析）。
    if (!IndirectGuard::Enabled()) {
        std::printf("FAIL: IndirectGuard::Enabled() should default to true "
                    "(GOW3_INDIRECT_GUARD unset)\n");
        return 1;
    }

    // 2. Breadcrumbs 未初始化（本测试不创建 Vulkan 实例、不调用 NewStream）时必须关闭，
    //    且所有入口都是安全空操作：不发 GPU 命令、不输出卡死报告。
    if (Breadcrumbs::Enabled()) {
        std::printf("FAIL: Breadcrumbs::Enabled() should be false before NewStream\n");
        return 1;
    }
    Breadcrumbs::Mark(vk::CommandBuffer{}, 0, 1, false);
    Breadcrumbs::CopyArgs(vk::CommandBuffer{}, vk::Buffer{}, 0, 0);
    Breadcrumbs::ReportStuck("test");
    Breadcrumbs::ReportDeviceLost("test");
    if (Breadcrumbs::ArgsSlot() != ~0u) {
        std::printf("FAIL: ArgsSlot() should be ~0u while disabled\n");
        return 1;
    }

    // 3. IndirectGuard::Enabled() 的门闩语义：置 0 环境变量不影响已缓存的进程内结果。
    setenv("GOW3_INDIRECT_GUARD", "0", 1);
    if (!IndirectGuard::Enabled()) {
        std::printf("FAIL: Enabled() is cached per process; env change must not flip it\n");
        return 1;
    }

    // 4. 总量上限解析：未设置/空串用默认 4M；十六进制与十进制都能解析。
    if (IndirectGuard::ParseMaxTotal(nullptr) != (1u << 22) ||
        IndirectGuard::ParseMaxTotal("") != (1u << 22)) {
        std::printf("FAIL: ParseMaxTotal should default to 4M groups\n");
        return 1;
    }
    if (IndirectGuard::ParseMaxTotal("1000000") != 1000000u ||
        IndirectGuard::ParseMaxTotal("0x10000") != 0x10000u) {
        std::printf("FAIL: ParseMaxTotal should parse decimal and hex values\n");
        return 1;
    }

    std::printf("vk-diagnostics-test PASS\n");
    return 0;
}
