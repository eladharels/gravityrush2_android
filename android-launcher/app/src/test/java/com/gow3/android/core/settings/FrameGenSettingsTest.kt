package com.gow3.android.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 帧生成 ini 层（change add-native-frame-generation，task 4.2）：新键/旧键兼容/
 * 缺键/非法值回退、保存的整行保留语义、vsync 联动表。
 */
class FrameGenSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private var seq = 0
    private fun ini(vararg lines: String): File =
        tmp.newFile("gow3_" + (seq++) + ".ini").apply { writeText(lines.joinToString("\n") + "\n") }

    // ---- read ----

    @Test fun read_defaultsWhenKeysMissing() {
        val c = FrameGenSettings.read(ini("patches_x="))
        assertFalse(c.enabled)
        assertEquals(FrameGenSettings.DEFAULT_MULTIPLIER, c.multiplier)
        assertEquals(FrameGenSettings.DEFAULT_FLOW_SCALE, c.flowScale, 0.0001f)
    }

    @Test fun read_newKeysWinOverLegacy() {
        val c = FrameGenSettings.read(ini(
            "framegen_enabled=1", "framegen_multiplier=3", "framegen_flow_scale=0.5",
            "lsfg_enabled=0", "lsfg_multiplier=4",
        ))
        assertTrue(c.enabled)
        assertEquals(3, c.multiplier)
        assertEquals(0.5f, c.flowScale, 0.0001f)
    }

    @Test fun read_legacyEnabledFallback() {
        // 新键缺失 + 旧 lsfg_enabled=1 → 视为开启（兼容读取，不写回）。
        val c = FrameGenSettings.read(ini("lsfg_enabled=1", "lsfg_multiplier=4", "lsfg_flow_scale=0.25"))
        assertTrue(c.enabled)
        assertEquals(4, c.multiplier)
        assertEquals(0.25f, c.flowScale, 0.0001f)
    }

    @Test fun read_legacyMultiplierOutOfRangeIgnored() {
        val c = FrameGenSettings.read(ini("lsfg_enabled=1", "lsfg_multiplier=7"))
        assertEquals(FrameGenSettings.DEFAULT_MULTIPLIER, c.multiplier) // 7 越界 → 默认
    }

    @Test fun read_illegalValuesFallBack() {
        val c = FrameGenSettings.read(ini(
            "framegen_enabled=maybe", "framegen_multiplier=abc", "framegen_flow_scale=9",
        ))
        assertFalse(c.enabled) // 非法开关值 → 默认关
        assertEquals(FrameGenSettings.DEFAULT_MULTIPLIER, c.multiplier)
        // flow scale 越界与倍率同语义：钳制到边界（spec「倍率取值越界」引申），非默认值。
        assertEquals(1.0f, c.flowScale, 0.0001f)
    }

    @Test fun read_drawerSummaryState() {
        // 抽屉「Lossless Scaling」行摘要读取语义（change fix-framegen-state-reporting）：
        // 开启 → 倍率 + flow；关闭（即使倍率非 0）→ 关。
        val on = FrameGenSettings.read(ini("framegen_enabled=1", "framegen_multiplier=2"))
        assertTrue(on.enabled)
        assertEquals(2, on.multiplier)
        assertEquals(FrameGenSettings.DEFAULT_FLOW_SCALE, on.flowScale, 0.0001f)

        val off = FrameGenSettings.read(ini("framegen_enabled=0", "framegen_multiplier=3"))
        assertFalse(off.enabled)
        assertEquals(3, off.multiplier) // 关闭态倍率保留，但 enabled=false 是摘要判据
    }

    // ---- readStats（HUD 遥测，change fix-framegen-state-reporting）----

    @Test fun readStats_rejectsStaleOrClearedTelemetry() {
        // 跨会话残留/已清零的遥测（multiplier<2）必须返回 null，HUD 回退单一帧率。
        assertNull(FrameGenSettings.readStats(ini("source_fps=59.0", "presented_fps=118.0", "multiplier=0")))
        assertNull(FrameGenSettings.readStats(ini("source_fps=59.0", "presented_fps=118.0", "multiplier=1")))
        assertNull(FrameGenSettings.readStats(ini("multiplier=2"))) // 缺源/上屏帧率
        assertNull(FrameGenSettings.readStats(tmp.newFile("missing_" + (seq++) + ".ini"))) // 文件缺失
    }

    @Test fun readStats_returnsLiveTelemetry() {
        val s = FrameGenSettings.readStats(ini("source_fps=59.5", "presented_fps=119.0", "multiplier=2"))
        assertNotNull(s)
        assertEquals(59.5f, s!!.sourceFps, 0.01f)
        assertEquals(119.0f, s.presentedFps, 0.01f)
        assertEquals(2, s.multiplier)
    }

    // ---- save（整行保留语义，与 Gow3Settings.update 一致）----

    @Test fun save_preservesOtherLinesAndComments() {
        val f = ini("# patch list", "patches_CUSA01715_01_02=", "framegen_enabled=0", "pad.cross=97")
        assertTrue(FrameGenSettings.save(f, FrameGenSettings.Config(true, 4, 0.5f)))
        val text = f.readText()
        assertTrue(text.contains("# patch list"))
        assertTrue(text.contains("patches_CUSA01715_01_02="))
        assertTrue(text.contains("pad.cross=97"))
        val c = FrameGenSettings.read(f)
        assertTrue(c.enabled)
        assertEquals(4, c.multiplier)
        assertEquals(0.5f, c.flowScale, 0.0001f)
    }

    @Test fun save_clampsInputs() {
        val f = ini("framegen_enabled=0")
        assertTrue(FrameGenSettings.save(f, FrameGenSettings.Config(true, 9, 42f)))
        val c = FrameGenSettings.read(f)
        assertEquals(4, c.multiplier)
        assertEquals(1.0f, c.flowScale, 0.0001f)
    }

    // ---- vsync 联动表（design D6）----

    @Test fun ensureVsync_linksOnlyWhenEnabledAndOff() {
        val f1 = ini("framegen_enabled=1", "vsync=0")
        assertTrue(FrameGenSettings.ensureVsyncForFrameGen(f1))
        assertEquals("1", FrameGenSettings.read(f1).let { Gow3Settings.read(f1, "vsync") })

        val f2 = ini("framegen_enabled=1", "vsync=1")
        assertFalse(FrameGenSettings.ensureVsyncForFrameGen(f2)) // 已是 1：无写入

        val f3 = ini("framegen_enabled=0", "vsync=0")
        assertFalse(FrameGenSettings.ensureVsyncForFrameGen(f3)) // 关闭：不动 vsync
        assertEquals("0", Gow3Settings.read(f3, "vsync"))

        val f4 = ini("framegen_enabled=1")
        assertTrue(FrameGenSettings.ensureVsyncForFrameGen(f4)) // vsync 键缺失（默认即 1）→ 写显式 1
        assertEquals("1", Gow3Settings.read(f4, "vsync"))
    }
}
