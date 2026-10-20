// gow3: the pad snapshot the overlay dialogs read (gpu/shim/gow3_pad_input.h).
#include <cassert>
#include <cstdio>
#include <atomic>
#include "gow3_pad_input.h"

namespace P = Gow3Pad;

int main() {
    // A press fires once; holding the key does not repeat it.
    std::atomic<std::uint32_t> seen{0};
    assert(P::Edges(P::kCross, seen) == P::kCross);
    assert(P::Edges(P::kCross, seen) == 0);
    assert(P::Edges(P::kCross | P::kUp, seen) == P::kUp);
    // Releasing and pressing again fires again.
    assert(P::Edges(0, seen) == 0);
    assert(P::Edges(P::kCross, seen) == P::kCross);
    // Two keys released and pressed together come out together...
    assert(P::Edges(0, seen) == 0);
    assert(P::Edges(P::kCross | P::kCircle, seen) == (P::kCross | P::kCircle));
    // ...and holding both afterwards fires neither again.
    assert(P::Edges(P::kCross | P::kCircle, seen) == 0);
    // Only the newly pressed key is an edge while the other stays down.
    assert(P::Edges(P::kCross | P::kCircle | P::kUp, seen) == P::kUp);
    assert(P::Edges(0, seen) == 0);

    // Opening a dialog forgets what was already held, so it is not a press on the new list.
    std::atomic<std::uint32_t> reopened{0};
    P::Forget(reopened, P::kCross);
    assert(P::Edges(P::kCross, reopened) == 0);
    assert(P::Edges(P::kCross | P::kDown, reopened) == P::kDown);
    // No pad file at all (desktop): nothing is ever reported.
    assert(P::Edges(0, reopened) == 0);

    // Directions, accept and cancel, and nothing else.
    int move = 0, finish = 0;
    P::Map(P::kUp, move, finish);      assert(move == -1 && finish == 0);
    P::Map(P::kDown, move, finish);    assert(move == 1 && finish == 0);
    P::Map(P::kCross, move, finish);   assert(move == 0 && finish == 1);
    P::Map(P::kCircle, move, finish);  assert(move == 0 && finish == 2);
    P::Map(0, move, finish);           assert(move == 0 && finish == 0);
    // A direction held together with accept still moves and accepts.
    P::Map(P::kDown | P::kCross, move, finish); assert(move == 1 && finish == 1);
    // Buttons the list does not use (shoulders, sticks) never act.
    P::Map(0x400 | 0x800 | 0x2 | 0x4, move, finish); assert(move == 0 && finish == 0);

    // ---- android-overlay-input-capture: 多来源去重（tasks 4.4）----
    // Overlay 对话框有两个输入来源：SDL 事件（桌面/手柄）与 pad 状态注入（Android）。
    // 去重契约分两层，各测其一：
    //
    //   a) pad 注入侧：一次按下只有一个边沿。就算 SDL 在同一轮采样里也报了确认
    //      （HandleChoiceEvent -> ApplyChoiceAction），pad 侧再次采样同一电平也
    //      不会产生第二个边沿——pad_seen 已跟上。这是同一轮"只计一次"的 pad 半边。
    //   b) SDL 侧：ApplyChoiceAction 是唯一入口，且它关闭对话框（choice_active=false），
    //      所以另一来源随后到达的同一动作会被路由门挡掉。这一半在 gow3_overlay.cpp 里，
    //      依赖 ImGui 编译不了单测，靠 PollChoice/HandleEvent 的 choice_active 判断
    //      与这里的 (a) 共同成立。
    std::atomic<std::uint32_t> both{0};
    // pad 先被采样，SDL 的确认随后到达：pad 只报一次边沿。
    assert(P::Edges(P::kCross, both) == P::kCross);   // pad 注入确认
    assert(P::Edges(P::kCross, both) == 0);           // 同一轮 SDL 再确认：无新边沿
    assert(P::Edges(P::kCross, both) == 0);           // 持续按住也不重复
    assert(P::Edges(0, both) == 0);                   // 释放
    // SDL 先（对话框已被它关掉），pad 注入随后采样：同样只有一个边沿。
    std::atomic<std::uint32_t> sdl_first{0};
    assert(P::Edges(P::kCircle, sdl_first) == P::kCircle);
    assert(P::Edges(P::kCircle, sdl_first) == 0);
    // 对话框重开时 Forget 掉按住的键，两个来源各自的旧电平都不会变成新按下。
    P::Forget(both, P::kCross);
    assert(P::Edges(P::kCross, both) == 0);
    assert(P::Edges(P::kCross | P::kDown, both) == P::kDown);

    puts("PASS: pad snapshot edges, dialog reset, choice key mapping, multi-source dedup");
}
