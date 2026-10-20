import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RECOMPILER_CPP = ROOT / "gpu/shadps4/shader_recompiler/recompiler.cpp"


class ShaderEmptyGuardContractTest(unittest.TestCase):
    """上游 368d899：TranslateProgram 入口的空指令流防护契约。"""

    def test_guard_exists_at_translate_program_entry(self):
        source = RECOMPILER_CPP.read_text()
        start = source.index("IR::Program TranslateProgram(")
        # 防护必须位于入口、首 token 校验之前
        guard = source.index("if (code.empty()) {", start)
        token_check = source.index("token_mov_vcchi", start)
        self.assertLess(guard, token_check)
        self.assertIn("TranslateProgram received an empty instruction stream", source)

    def test_guard_keeps_upstream_attribution_marker(self):
        source = RECOMPILER_CPP.read_text()
        self.assertIn("368d899", source)


if __name__ == "__main__":
    unittest.main()
