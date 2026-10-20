package com.gow3.android.data.model

import com.gow3.android.core.settings.Gow3Settings
import com.gow3.android.data.PatchCatalog

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MobilePresetsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun catalog(): PatchCatalog = File(requireNotNull(System.getProperty("gow3.patchCatalogDir")),
        "God_of_War_III_Remastered_CUSA01740.xml").inputStream().use {
        PatchCatalog.parse(it, "CUSA01740", "01.00")
    }

    @Test fun `1740 offers all five imported mobile tiers`() {
        assertEquals(listOf("480p", "540p", "720p", "900p", "1080p"), MobilePresets.available(catalog()).map { it.resolution })
        assertTrue(MobilePresets.available(null).isEmpty())
        val c = catalog()
        val limited = c.copy(patches = c.patches.filter { it.resolution != "900p" })
        assertFalse(MobilePresets.available(limited).any { it.resolution == "900p" })
        assertTrue(MobilePresets.available(c.copy(patches = c.patches.filter { it.name != "Disable SSAO" })).isEmpty())
    }

    @Test fun `profiles persist resolution and patches without touching fps or personal settings`() {
        val c = catalog()
        val file = temp.newFile("gow3.ini")
        assertTrue(Gow3Settings.update(file, mapOf(
            "vibration" to "25", "lsfg_enabled" to "1", "pad.cross" to "a",
            "patches_CUSA01715_01_02" to "Skip Intro", "restart_unconfirmed" to "1",
            "render_resolution" to "480p", "engine_fps" to "120", "fps_limit" to "30",
            "display_stretch" to "1",
            c.selectionKey to "Bug Fix - Startup Black Screen;Resolution Patch - 480p",
        )))
        for (preset in MobilePresets.available(c)) {
            assertTrue(Gow3Settings.update(file, MobilePresets.updates(preset, c, Gow3Settings.read(file))))
            val ini = Gow3Settings.read(file)
            assertEquals(preset, MobilePresets.matching(c, ini))
            assertEquals("25", ini["vibration"])
            assertEquals("1", ini["lsfg_enabled"])
            assertEquals("a", ini["pad.cross"])
            assertEquals("Skip Intro", ini["patches_CUSA01715_01_02"])
            assertEquals("0", ini["restart_unconfirmed"])
            // fps 与图形项是玩家独立选择：预设不写它们，玩家既有值保持。
            assertEquals("120", ini["engine_fps"])
            assertEquals("30", ini["fps_limit"])
            // display_stretch 是显示端个人偏好：预设不重置它（用户开的宽屏保持开启）。
            assertEquals("1", ini["display_stretch"])
            // 预设只强制分辨率补丁与六项特效补丁，不再连带开启视频跳过补丁。
            assertEquals(setOf("Resolution Patch - ${preset.resolution}", "Disable SSAO", "Disable Motion Blur",
                "Disable Depth of Field", "Disable Bloom", "Disable Soft Particles", "Disable Dynamic Shadows",
                "Bug Fix - Startup Black Screen"), c.selected(ini))
            // 预设管理的唯一启动参数是 render_resolution；其余参数不由预设写入。
            assertEquals(preset.resolution, ini["render_resolution"])
            for (param in LaunchParams.forCatalog(c).filter { it.key != "vibration" && it.key != "display_stretch" }) {
                if (param.key == "render_resolution") {
                    assertEquals(preset.settings[param.key], LaunchParams.selectedOption(ini, param)?.value)
                } else {
                    assertEquals(null, preset.settings[param.key]) // 非预设管理参数不在预设写集内
                }
            }
        }
    }

    @Test fun `video and fix patch switches stay independent of the preset match`() {
        val c = catalog()
        val preset = MobilePresets.available(c).first { it.resolution == "720p" }
        var ini = MobilePresets.updates(preset, c, emptyMap())
        assertEquals(preset, MobilePresets.matching(c, ini))
        val xVideo = c.patches.first { it.name == "Skip Any Video With X Button" }
        val blackScreen = c.patches.first { it.name == "Bug Fix - Startup Black Screen" }
        // 补丁页单独开启视频跳过/修复补丁：保存正常，预设匹配不受影响。
        ini += c.toggle(ini, xVideo, true)
        ini += c.toggle(ini, blackScreen, true)
        assertTrue(xVideo.name in c.selected(ini))
        assertTrue(blackScreen.name in c.selected(ini))
        assertEquals(preset, MobilePresets.matching(c, ini))
        // 再单独关闭它们：预设匹配同样不受影响。
        ini += c.toggle(ini, xVideo, false)
        ini += c.toggle(ini, blackScreen, false)
        assertEquals(preset, MobilePresets.matching(c, ini))
    }

    @Test fun `preset keeps an already enabled Skip Intro`() {
        val c = catalog()
        val preset = MobilePresets.available(c).first { it.resolution == "720p" }
        val skipIntro = c.patches.first { it.name == "Skip Intro" }
        val enabled = c.toggle(emptyMap(), skipIntro, true)
        val ini = MobilePresets.updates(preset, c, enabled)
        // 已开启的 Skip Intro 不被预设关闭，预设仍正常匹配。
        assertTrue(skipIntro.name in c.selected(ini))
        assertEquals(preset, MobilePresets.matching(c, ini))
    }

    @Test fun `single settings and patch switches remain editable and show custom`() {
        val c = catalog()
        val preset = MobilePresets.available(c).first { it.resolution == "720p" }
        val ini = MobilePresets.updates(preset, c, emptyMap())
        assertEquals(setOf("Resolution Patch - 720p", "Disable SSAO", "Disable Motion Blur",
            "Disable Depth of Field", "Disable Bloom", "Disable Soft Particles", "Disable Dynamic Shadows"),
            c.selected(ini))
        // fps 与图形参数独立于预设：改它们不破坏预设匹配（不再显示自定义）。
        assertEquals(preset, MobilePresets.matching(c, ini + ("fps_limit" to "30")))
        assertEquals(preset, MobilePresets.matching(c, ini + ("engine_fps" to "120")))
        assertEquals(preset, MobilePresets.matching(c, ini + mapOf("sharpen" to "1", "sharpness" to "0.75")))
        // 改分辨率档位：预设匹配失效。
        assertNull(MobilePresets.matching(c, ini + ("render_resolution" to "1440p")))
        // 关闭预设中的某项特效补丁：预设匹配失效（显示自定义）。
        val bloom = c.patches.first { it.name == "Disable Bloom" }
        val changed = ini + c.toggle(ini, bloom, false)
        assertFalse(bloom.name in c.selected(changed))
        assertNull(MobilePresets.matching(c, changed))
        assertEquals(preset, MobilePresets.matching(c, changed + MobilePresets.updates(preset, c, changed)))
    }

    @Test fun `fps and graphics edits keep the preset match`() {
        val c = catalog()
        val preset = MobilePresets.available(c).first { it.resolution == "720p" }
        val ini = MobilePresets.updates(preset, c, emptyMap())
        assertEquals(preset, MobilePresets.matching(c, ini))
        // 应用预设后单独改帧率上限 / 引擎帧率 / 图形项：预设保持高亮。
        assertEquals(preset, MobilePresets.matching(c, ini + ("fps_limit" to "30")))
        assertEquals(preset, MobilePresets.matching(c, ini + ("engine_fps" to "120")))
        assertEquals(preset, MobilePresets.matching(c, ini + mapOf("sharpen" to "1", "sharpness" to "0.75")))
        // 改分辨率档位（非预设档位）或关闭特效补丁：预设失效（自定义）。
        assertNull(MobilePresets.matching(c, ini + ("render_resolution" to "1440p")))
        val bloom = c.patches.first { it.name == "Disable Bloom" }
        assertNull(MobilePresets.matching(c, ini + c.toggle(ini, bloom, false)))
    }

    @Test fun `unsupported profile cannot partially apply`() {
        val c = catalog().let { c -> c.copy(patches = c.patches.filter { it.resolution != "540p" }) }
        val light = MobilePresets.all.first { it.resolution == "540p" }
        assertThrows(IllegalArgumentException::class.java) {
            MobilePresets.updates(light, c, emptyMap())
        }
    }
}
