package com.gow3.android.feature.settings

import com.gow3.android.data.model.LaunchCategory
import com.gow3.android.data.model.LaunchParam


/**
 * Settings 分类页的一条可渲染条目：参数行或功能卡 slot。
 *
 * 这是配置页的扩展点（change design D2）：后续功能（驱动管理 DriverManagerCard、
 * 手柄映射 PadMappingCard、FEX 配置 FexConfigCard 等）各占一个 [SettingsEntry.Card]
 * slot、各起独立 change，只需在 [categoryCards] 声明 slot 并提供卡片内容，
 * 不需要再改 SettingsScreen 骨架。
 */
internal sealed interface SettingsEntry {
    /** 稳定条目标识，供组合状态与未来测试定位。 */
    val id: String

    /** 一个 gow3.ini 参数的就地编辑行。 */
    data class Param(val param: LaunchParam) : SettingsEntry {
        override val id: String get() = "param:${param.key}"
    }

    /**
     * 功能卡 slot：带稳定 slot 标识（如 `driver_manager`）。首版无激活 slot，
     * 渲染层对未提供内容的 slot 不产生任何可见输出。
     */
    data class Card(val slotId: String) : SettingsEntry {
        override val id: String get() = "card:$slotId"
    }
}

/**
 * 各分类的功能卡 slot 声明，顺序即渲染顺序（渲染在参数行之前）。
 * `driver_manager` 由 add-android-launcher-driver-manager 落位（design D2），
 * 由 [DriverManagerCard] 提供内容；`storage` 由 redesign-android-launcher-home
 * 落位，提供游戏目录与升级补丁目录行；`pad_mapping` 由 add-pad-button-mapping
 * 落位，由 [PadMappingCard] 提供内容。
 */
private val categoryCards: Map<LaunchCategory, List<String>> = mapOf(
    LaunchCategory.GENERAL to listOf("storage", "language"),
    LaunchCategory.CONTROLS to listOf("pad_mapping"),
    LaunchCategory.DRIVER to listOf("driver_manager"),
)

/** 按分类组装条目列表：先声明顺序的功能卡 slot，再按声明顺序排该分类的参数。 */
internal fun settingsEntries(category: LaunchCategory, params: List<LaunchParam>): List<SettingsEntry> =
    categoryCards[category].orEmpty().map { SettingsEntry.Card(it) } +
        params.filter { it.category == category }.map { SettingsEntry.Param(it) }
