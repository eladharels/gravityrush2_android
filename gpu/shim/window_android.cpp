// gow3port: Bionic 游戏进程的「窗口」：没有 SDL、没有 surface。见 sdl_window.h。
#ifdef __ANDROID__
#include <cstdio>
#include <cstdlib>
#include "sdl_window.h"

namespace Frontend {

namespace {
/// GOW3_SCREEN_W / GOW3_SCREEN_H：app 的屏幕像素，由启动脚本设置；也是 AHB swapchain 的尺寸。
/// 不是两者都有效（320..16384）：回退 1920x1080。
bool ScreenFromEnv(s32& width, s32& height) {
    const char* w = std::getenv("GOW3_SCREEN_W");
    const char* h = std::getenv("GOW3_SCREEN_H");
    if (!w || !h) {
        return false;
    }
    const long wl = std::strtol(w, nullptr, 10);
    const long hl = std::strtol(h, nullptr, 10);
    if (wl < 320 || hl < 180 || wl > 16384 || hl > 16384) {
        return false;
    }
    width = static_cast<s32>(wl);
    height = static_cast<s32>(hl);
    return true;
}
} // namespace

WindowSDL::WindowSDL(s32, s32, const char*) : width{1920}, height{1080} {
    s32 w = 1920, h = 1080;
    const bool from_env = ScreenFromEnv(w, h);
    width = w;
    height = h;
    // 对实例是 Headless：没有 VK_KHR_*_surface 扩展、没有 surface（用 AHB swapchain）。
    window_info.type = WindowSystemType::Headless;
    std::printf("Output size %dx%d (%s)\n", w, h,
                from_env ? "GOW3_SCREEN_W/GOW3_SCREEN_H" : "default, GOW3_SCREEN_W/GOW3_SCREEN_H unset");
}

bool WindowSDL::PollEvents() {
    return true;
}

void WindowSDL::BeginTextInput(const std::string&, const std::string&) {}

int WindowSDL::PollTextInput(std::string&) {
    return 2;
}

} // namespace Frontend

// android port: no mouse on the phone (window.cpp keeps the desktop mouse look). runtime_pad.c
// asks for it: nothing to configure, no motion, no buttons.
extern "C" void gow3gpu_mouse_configure(int) {}
extern "C" int gow3gpu_mouse_sample(float* dx, float* dy, uint32_t* buttons, int* wheel) {
    if (dx) *dx = 0.0f;
    if (dy) *dy = 0.0f;
    if (buttons) *buttons = 0;
    if (wheel) *wheel = 0;
    return 0;
}
#endif
