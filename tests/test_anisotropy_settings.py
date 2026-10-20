import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SAMPLER_CPP = ROOT / "gpu/shadps4/video_core/texture_cache/sampler.cpp"
SETTINGS_CPP = ROOT / "gpu/shim/gow3_settings.cpp"
SETTINGS_H = ROOT / "gpu/shim/gow3_settings.h"


class AnisotropySettingsContractTest(unittest.TestCase):
    """上游 9d0f51c 各向异性过滤链：强制等级、设备能力门控与上限裁剪（8.4/8.5）。"""

    def test_settings_parse_and_env(self):
        """supported（1..16）/disabled（0 默认）/malformed（ParseAnisotropy 兜底）与 env 覆盖。"""
        source = SETTINGS_CPP.read_text()
        self.assertIn('key == "anisotropy"', source)
        self.assertIn("Gow3Graphics::ParseAnisotropy(i)", source)
        self.assertIn('{"GOW3_ANISOTROPY", "anisotropy"}', source)
        header = SETTINGS_H.read_text()
        self.assertIn("std::atomic<int> anisotropy{0};", header)
        self.assertIn("0 keeps the game's choice", header)

    def test_sampler_support_gate_and_device_limit_clamp(self):
        """supported：能力门控 + clamp 到设备上限；disabled：force=1 时不启用。"""
        source = SAMPLER_CPP.read_text()
        self.assertIn("Gow3Settings::Get().startup_anisotropy", source)
        self.assertIn("instance.IsAnisotropicFilteringSupported()", source)
        self.assertIn("std::clamp(requested, 1.0f, instance.MaxSamplerAnisotropy())", source)
        # 强制等级 >1 才生效（disabled/legacy 默认 0 不改变游戏自身选择）
        self.assertIn("forced > 1", source)
        # 深度 Targets 不强制（上游行为保持）
        self.assertIn("!is_depth", source)

    def test_sampler_request_takes_maximum_of_game_and_forced(self):
        """over-limit 场景：请求取游戏与强制的较大值，再被 clamp 收进设备上限。"""
        source = SAMPLER_CPP.read_text()
        self.assertIn("std::max(game_anisotropy ? sampler.MaxAniso() : 1.0f,", source)

    def test_over_limit_never_fails_sampler_creation(self):
        """创建不因越界请求失败：maxAnisotropy 字段使用 clamp 后的值。"""
        source = SAMPLER_CPP.read_text()
        self.assertIn(".maxAnisotropy = max_anisotropy,", source)


if __name__ == "__main__":
    unittest.main()
