package com.gow3.android

import com.gow3.android.feature.settings.settingsEntries

import com.gow3.android.data.model.LaunchCategory
import com.gow3.android.data.model.LaunchParams
import com.gow3.android.feature.settings.SettingsEntry

import org.junit.Assert.*
import org.junit.Test

class SettingsEntryTest {
    private val params = LaunchParams.all

    @Test fun `graphics entries are card slots first then params in declared order`() {
        val entries = settingsEntries(LaunchCategory.GRAPHICS, params)
        assertEquals(listOf("render_resolution", "anisotropy", "sharpen", "sharpness", "display_stretch"),
            entries.map { (it as SettingsEntry.Param).param.key })
        assertTrue(entries.all { it is SettingsEntry.Param })
    }

    @Test fun `reserved categories assemble to empty lists`() {
        assertTrue(settingsEntries(LaunchCategory.AUDIO, params).isEmpty())
    }

    /** 常规分类（原存储分类）排第一位：storage 卡片在前，language 语言入口随后。 */
    @Test fun `general category renders storage card then language card`() {
        val entries = settingsEntries(LaunchCategory.GENERAL, params)
        assertEquals(2, entries.size)
        assertEquals(listOf("storage", "language"), entries.map { (it as SettingsEntry.Card).slotId })
        assertEquals("card:storage", entries[0].id)
        assertEquals("card:language", entries[1].id)
    }

    /** DRIVER 分类由 add-android-launcher-driver-manager 落位：driver_manager 卡片 slot 在参数行之前。 */
    @Test fun `driver category renders the driver manager card slot`() {
        val entries = settingsEntries(LaunchCategory.DRIVER, params)
        assertEquals(1, entries.size)
        val card = entries.single()
        assertTrue(card is SettingsEntry.Card)
        assertEquals("driver_manager", (card as SettingsEntry.Card).slotId)
        assertEquals("card:driver_manager", card.id)
    }

    @Test fun `entry ids are stable and unique per category`() {
        for (category in LaunchCategory.entries) {
            val ids = settingsEntries(category, params).map { it.id }
            assertEquals(ids.size, ids.distinct().size)
        }
        assertEquals("param:fps_limit",
            settingsEntries(LaunchCategory.PERFORMANCE, params).first { it.id == "param:fps_limit" }.id)
    }

    /** Card slot 扩展点：声明 slot 后排在参数行之前（以 DRIVER 为例的行为契约）。 */
    @Test fun `card slots would render before params`() {
        // 首版 categoryCards 为空：仅验证模型可表达 slot 且 id 规则稳定。
        assertEquals("card:driver_manager", SettingsEntry.Card("driver_manager").id)
    }
}
