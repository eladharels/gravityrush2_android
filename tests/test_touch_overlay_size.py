import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GAME_PREFS = ROOT / "android-launcher/app/src/main/java/com/gow3/android/core/settings/GamePrefs.kt"
OVERLAY = ROOT / "android-launcher/app/src/main/java/com/gow3/android/game/TouchControllerOverlay.java"
GAME_ACTIVITY = ROOT / "android-launcher/app/src/main/java/com/gow3/android/game/GameActivity.java"


class OverlaySizePrefsContractTest(unittest.TestCase):
    """GamePrefs 尺寸持久化：缺省 1.0（向后兼容）、clamp、reset 一并清除。"""

    def test_overlay_size_defaults_to_one_for_legacy_layouts(self):
        source = GAME_PREFS.read_text()
        self.assertIn('fun overlaySize(name: String, default: Float = 1f)', source)
        self.assertIn('OVERLAY_KEY_PREFIX + "s_" + name', source)

    def test_set_overlay_size_clamps(self):
        source = GAME_PREFS.read_text()
        self.assertIn("value.coerceIn(0.6f, 2f)", source)

    def test_clear_overlay_layout_covers_size_keys(self):
        """clearOverlayLayout 按 overlay_ 前缀清除，s_ 键随之删除（reset 恢复默认）。"""
        source = GAME_PREFS.read_text()
        self.assertIn('it.startsWith(OVERLAY_KEY_PREFIX)', source)


class OverlaySizeEditingContractTest(unittest.TestCase):
    """编辑态按钮缩放（参考 Bloodborne-Android：RESET | − | DONE | + 工具条，不用捏合）。"""

    def test_sizes_loaded_from_prefs_in_constructor(self):
        source = OVERLAY.read_text()
        self.assertIn("sizes.put(name,prefs.overlaySize(name,1f));", source)
        self.assertIn("private float size(String name)", source)
        self.assertIn("private static float clampSize(float s)", source)
        self.assertIn("Math.max(MIN_SIZE,Math.min(MAX_SIZE,s))", source)
        self.assertIn("MIN_SIZE=0.6f, MAX_SIZE=2f, SIZE_STEP=1.1f;", source)

    def test_bar_buttons_layout_and_hit(self):
        """工具条四按钮与命中：RESET | − | DONE | +，y 边界 BAR_BOTTOM+10。"""
        source = OVERLAY.read_text()
        self.assertIn('RESET={358,458}, MINUS={468,538}, DONE={548,732}, PLUS={742,812};', source)
        self.assertIn("x>box[0] && x<box[1] && y<BAR_BOTTOM+10", source)
        self.assertIn("if (in(DONE,x,y)) { finishEdit(); return true; }", source)
        self.assertIn("if (in(RESET,x,y)) { confirmReset(); return true; }", source)
        self.assertIn("resize(selected,in(PLUS,x,y) ? SIZE_STEP : 1f/SIZE_STEP);", source)

    def test_resize_steps_and_persists_immediately(self):
        source = OVERLAY.read_text()
        self.assertIn("private void resize(String name,float factor)", source)
        self.assertIn("sizes.put(name,clampSize(size(name)*factor));", source)
        self.assertIn("prefs.setOverlaySize(name,size(name));", source)
        # 未选中时 −/+ 不动作
        self.assertIn("if (selected!=null) resize(", source)

    def test_selection_by_tap_and_hint_text(self):
        source = OVERLAY.read_text()
        self.assertIn("if (dragged!=null) selected=dragged;", source)
        self.assertIn("barButton(c,MINUS,\"−\",selected!=null)", source)
        self.assertIn("barButton(c,PLUS,\"+\",selected!=null)", source)
        # 无捏合残留
        self.assertNotIn("pinch", source)

    def test_reset_asks_then_restores(self):
        source = OVERLAY.read_text()
        self.assertIn("AlertDialog.Builder(getContext())", source)
        self.assertIn("setPositiveButton(\"Reset\"",(source))
        self.assertIn("resetLayout(); selected=null; invalidate();", source)
        self.assertIn("sizes.put(NAMES[i],1f);", source)


class EditHidesPerfHudContractTest(unittest.TestCase):
    """编辑虚拟按键时隐藏性能 HUD，DONE 后按设置恢复（用户反馈遮挡编辑视图）。"""

    def test_edit_entry_hides_hud_and_done_restores_pref_state(self):
        source = GAME_ACTIVITY.read_text()
        self.assertIn("perfHud.setActive(false);", source)
        self.assertIn("controllerOverlay.edit(() -> perfHud.setActive(perfHudEnabled()));", source)
        # 先隐藏再进入编辑
        self.assertLess(source.index("perfHud.setActive(false);"),
                        source.index("controllerOverlay.edit(() ->"))


class OverlayScalingConsistencyContractTest(unittest.TestCase):
    """命中/绘制/发布用同一因子，缩放后可点区域与视觉一致。"""

    def test_hit_testing_scales(self):
        source = OVERLAY.read_text()
        self.assertIn("float k=dpadSize();", source)
        self.assertIn("distance(x,y,dpad)<112*k", source)
        self.assertIn("dx<(name.equals(\"touchpad\") ? 72 : 62)*s && dy<38*s", source)
        self.assertIn("stick(name) ? 70 : name.equals(\"l3\") || name.equals(\"r3\") ? 39 : 48)*s", source)
        # 十字键因子 = 四键均值
        self.assertIn('return (size("up")+size("left")+size("right")+size("down"))/4f;', source)

    def test_publish_travel_scales(self):
        source = OVERLAY.read_text()
        self.assertIn("float t=22*dpadSize();", source)
        self.assertEqual(source.count("float range=56*size(control);"), 2,
                         "左右摇杆行程都应随因子缩放")

    def test_drawing_scales(self):
        source = OVERLAY.read_text()
        self.assertIn("PointF p=center(name); float s=size(name); r*=s;", source)
        self.assertIn("float half=width/2,halfH=25*s,radius=14*s;", source)
        self.assertIn("c.drawCircle(center.x,center.y,57*s,paint);", source)
        self.assertIn("float knobX=center.x,knobY=center.y,travel=27*s;", source)
        self.assertIn("c.drawCircle(knobX,knobY,28*s,paint);", source)

    def test_non_size_behaviors_unchanged(self):
        """DONE 命中区改走 in(DONE)；touchpad→touchpad_right 语义不被本变更影响。"""
        source = OVERLAY.read_text()
        self.assertIn("if (in(DONE,x,y)) { finishEdit(); return true; }", source)
        self.assertIn('pressed.add("touchpad_right")', source)


if __name__ == "__main__":
    unittest.main()
