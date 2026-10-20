// SPDX-License-Identifier: GPL-2.0-or-later
// gow3: Qualcomm Snapdragon Game Super Resolution 2（BSD-3-Clause，gpu/third_party/snapdragon-gsr2）
// 作为 TemporalUpscaler 里 FSR 3.1 context 的替代 provider：2-pass compute 变体
// （convert：深度裁剪 + YCoCg 色彩 + motion；upscale：重投影历史 + Lanczos 核）。
// 输入与 FSR 4 hook 相同：场景颜色、深度、本仓运动矢量（render 像素、previous - current）
// 与 jitter 偏移。历史与中间图像由本类自持，跟随 render / output 尺寸。
// GOW3_GSR2_PROFILE=1 时按 pass 打印 GPU 耗时。

#pragma once

#include <array>
#include <memory>
#include "common/types.h"
#include "video_core/renderer_vulkan/vk_common.h"

namespace Vulkan {

class Instance;
class Scheduler;

class GsrUpscaler {
public:
    GsrUpscaler(const Instance& instance, Scheduler& scheduler);
    ~GsrUpscaler();

    struct Frame {
        vk::CommandBuffer cmdbuf;
        /// 处于 General 布局的视图：color 与 depth（depth aspect）被采样，motion（RG16F）
        /// 被采样，output（RGBA16F）被写入。color 与 depth 可大于场景。
        vk::ImageView color, depth, motion, output;
        u32 render_width, render_height, output_width, output_height;
        std::array<float, 2> jitter; ///< FSR 的约定：像素、投影的位移
        float vertical_fov;          ///< 弧度
        bool camera_still, reset;
    };

    /// 把两个 pass 录进 `frame.cmdbuf`；输出停在 General 布局。管线建不出来时返回 false。
    bool Record(const Frame& frame);

private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};

} // namespace Vulkan
