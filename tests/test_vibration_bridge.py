import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNTIME_PAD = ROOT / "src/runtime_pad.c"
PAD_BRIDGE = ROOT / "android-launcher/app/src/main/java/com/gow3/android/game/PadBridge.java"
GAME_ACTIVITY = ROOT / "android-launcher/app/src/main/java/com/gow3/android/game/GameActivity.java"
RUN_BIONIC = ROOT / "tools/android/device/run-bionic.sh"
SETTINGS_CPP = ROOT / "gpu/shim/gow3_settings.cpp"
SETTINGS_H = ROOT / "gpu/shim/gow3_settings.h"
GOW3_GPU = ROOT / "gpu/shim/gow3gpu.cpp"


class VibrationSettingsContractTest(unittest.TestCase):
    """上游 9d0f51c 震动设置链：解析、裁剪、env 覆盖与缩放（full/reduced/disabled 场景）。"""

    def test_settings_parse_clamps_and_defaults(self):
        """full=100 默认；reduced 按值；disabled=0；越界裁剪到 0..100。"""
        source = SETTINGS_CPP.read_text()
        self.assertIn('key == "vibration"', source)
        self.assertIn("value.empty() ? 100 : std::clamp(i, 0, 100)", source)
        header = SETTINGS_H.read_text()
        self.assertIn("std::atomic<int> vibration{100};", header)
        self.assertIn("0 turns it off", header)

    def test_env_override_is_registered(self):
        """GOW3_VIBRATION env 覆盖注册存在（missing env 时回落 ini/默认）。"""
        self.assertIn('{"GOW3_VIBRATION", "vibration"}', SETTINGS_CPP.read_text())

    def test_scaling_applies_percent_and_clamps(self):
        """scePadSetVibration 参数按百分比缩放，percent>100 裁剪。"""
        source = RUNTIME_PAD.read_text()
        self.assertIn("strength * 257u * percent / 100u", source)
        self.assertIn("if (percent > 100) percent = 100;", source)

    def test_vibration_percent_bridge_symbol_exists(self):
        """GPU shim 导出 gow3gpu_vibration_percent（设置 -> pad 运行时的桥）。"""
        self.assertIn("gow3gpu_vibration_percent(void)", GOW3_GPU.read_text())


class RumbleBridgeContractTest(unittest.TestCase):
    """Android 震动回程：游戏侧文件写入 <-> Java 轮询振动（design.md 决策 9 的两端）。"""

    def test_native_writes_rumble_file_atomically_with_seq(self):
        source = RUNTIME_PAD.read_text()
        self.assertIn("GOW3_PAD_RUMBLE_FILE", source)
        # 单行协议带递增 seq：行变化才触发，效果停止后自然衰减不重放
        self.assertIn('"rumble %d %d 300 %u\\n"', source)
        # 原子替换：tmp + rename
        self.assertIn('snprintf(tmp,sizeof tmp,"%s.tmp",path)', source)
        self.assertIn("rename(tmp,path);", source)
        # 100ms 重写门（心跳）；vibration=0 或零强度不写
        self.assertIn("t-last_us<100000", source)
        self.assertIn("if (!s && !w) return;", source)

    def test_sdl_desktop_path_unchanged(self):
        """桌面路径保持：SDL_RumbleGamepad 仍以 scale_vibration 结果调用。"""
        source = RUNTIME_PAD.read_text()
        self.assertIn("SDL_RumbleGamepad(g,scale_vibration(param[0], percent),", source)
        # 回程写在 SDL 分支之后，桌面无 env 时不生效
        self.assertLess(source.index("SDL_RumbleGamepad"), source.index("rumble_report(param[0]"))

    def test_run_bionic_exports_rumble_path_next_to_pad_state(self):
        source = RUN_BIONIC.read_text()
        self.assertIn('export GOW3_PAD_RUMBLE_FILE="$base/android-pad.rumble"', source)

    def test_java_poller_parses_and_triggers_vibrator(self):
        source = PAD_BRIDGE.read_text()
        # 40ms 轮询、行去重（seq 心跳）、振幅取 max、API 26 分支与无振幅控制回退
        self.assertIn("RUMBLE_POLL_MS=40", source)
        self.assertIn("!text.equals(rumbleSeen)", source)
        self.assertIn("Math.max(strong,weak)", source)
        self.assertIn("VibrationEffect.createOneShot(ms,a)", source)
        self.assertIn("v.hasAmplitudeControl()", source)
        self.assertIn("VibrationEffect.DEFAULT_AMPLITUDE", source)
        # 只摘轮询回调，不破坏 write() 的 8ms 合并
        self.assertIn("handler.removeCallbacks(rumbleLoop)", source)
        self.assertNotIn("removeCallbacksAndMessages(null)", source)

    def test_activity_lifecycle_pairs_start_and_stop(self):
        source = GAME_ACTIVITY.read_text()
        self.assertIn('pad.startRumble(this,new File(BASE,"android-pad.rumble"))', source)
        self.assertIn("pad.stopRumble()", source)
        self.assertIn("pad.resumeRumble()", source)
        # onPause 停止在先，onResume 恢复在后
        self.assertLess(source.index("pad.stopRumble()"), source.index("pad.resumeRumble()"))


if __name__ == "__main__":
    unittest.main()
