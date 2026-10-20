// gow3: the game window. Created by the VideoOut driver on first open; the
// event pump runs on the port's window thread (see window.cpp).
#pragma once
#include <atomic>
#include <mutex>
#include <string>
#include "common/types.h"

#ifndef __ANDROID__
struct SDL_Window;
#endif

namespace Frontend {

enum class WindowSystemType : u8 { Headless, Windows, X11, Wayland, Metal };

struct WindowSystemInfo {
    void* display_connection = nullptr;
    void* render_surface = nullptr;
    float render_surface_scale = 1.0f;
    WindowSystemType type = WindowSystemType::Headless;
};

#ifdef __ANDROID__
/// Bionic：没有窗口也没有 SDL。游戏进程没有 Surface：帧经 AHB swapchain
/// （vk_swapchain_ahb.cpp）送给 app。这个类只承载输出尺寸（GOW3_SCREEN_W x GOW3_SCREEN_H
/// 来自启动脚本，缺省 1920x1080）；输入来自 app 经 runtime（src/runtime_pad.c），
/// 不来自窗口事件（window_android.cpp）。
class WindowSDL {
public:
    WindowSDL(s32 width, s32 height, const char* title);
    s32 GetWidth() const { return width.load(std::memory_order_relaxed); }
    s32 GetHeight() const { return height.load(std::memory_order_relaxed); }
    WindowSystemInfo GetWindowInfo() const { return window_info; }
    bool IsOpen() const { return true; }
    /// 没有事件要泵；返回 true（进程由 app 结束）。
    bool PollEvents();
    /// 没有键盘对话框：文字请求直接回答「已取消」（状态 2）。
    void BeginTextInput(const std::string& initial, const std::string& prompt);
    int PollTextInput(std::string& text);

private:
    std::atomic<s32> width, height;
    WindowSystemInfo window_info{};
};
#else
class WindowSDL {
public:
    WindowSDL(s32 width, s32 height, const char* title);
    ~WindowSDL();
    s32 GetWidth() const { return width.load(std::memory_order_relaxed); }
    s32 GetHeight() const { return height.load(std::memory_order_relaxed); }
    SDL_Window* GetSDLWindow() const { return window; }
    WindowSystemInfo GetWindowInfo() const { return window_info; }
    bool IsOpen() const { return is_open.load(std::memory_order_relaxed); }
    /// Processes pending window events. Returns false once the user closed the window.
    bool PollEvents();
    /// Keyboard text entry for the system IME dialog; typed text shows in the title bar.
    void BeginTextInput(const std::string& initial, const std::string& prompt);
    /// 0 while typing, 1 confirmed (Enter), 2 cancelled (Escape); text is UTF-8.
    int PollTextInput(std::string& text);

private:
    /// Hides the OS cursor while the touch overlay mouse is off (overlay_mouse=0), so the game
    /// picture shows no stuck X11 pointer. Called every frame; only toggles on state changes.
    void UpdateCursor();
    std::atomic<s32> width, height;
    std::atomic<bool> is_open{true};
    std::mutex text_mutex;
    bool text_requested{}, text_active{};
    bool cursor_hidden{}; ///< OS cursor visibility applied last (window thread)
    int display_mode = -1; ///< applied Gow3Settings display mode (window thread)
    void ApplyDisplayMode(int mode);
    int text_state{};
    std::string text, text_prompt, base_title;
    void UpdateTextTitle();
    SDL_Window* window{};
    WindowSystemInfo window_info{};
};
#endif

} // namespace Frontend
