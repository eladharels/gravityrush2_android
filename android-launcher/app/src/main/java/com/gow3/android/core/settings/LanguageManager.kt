package com.gow3.android.core.settings

import com.gow3.android.MainActivity

import android.content.Context
import java.util.Locale

/**
 * 启动器语言设置：跟随系统 / 简体中文 / English。
 *
 * 持久化统一走 [GamePrefs]（`gow3` key `language`；键名与默认值在 GamePrefs 一处），
 * 得到覆盖值（`system`/`zh`/`en`）后再解析出应注入 Activity 的 `Locale`。
 * `system` 表示跟随设备系统语言，其余为固定覆盖。`MainActivity.attachBaseContext`
 * 与 Settings 常规分类的语言入口都依赖它。
 */
internal object LanguageManager {
    const val PREF_NAME = GamePrefs.NAME
    const val KEY_LANGUAGE = GamePrefs.KEY_LANGUAGE
    const val FOLLOW_SYSTEM = GamePrefs.LANGUAGE_FOLLOW_SYSTEM
    const val ZH = "zh"
    const val EN = "en"

    /** 读取持久化的语言覆盖值；未设置时返回 [FOLLOW_SYSTEM]。 */
    fun read(context: Context): String = GamePrefs(context).language()

    /** 持久化语言覆盖值。 */
    fun set(context: Context, value: String) {
        GamePrefs(context).setLanguage(value)
    }

    /** 解析当前生效的 Locale：`system` 跟随设备系统语言，其余为固定覆盖。 */
    fun resolve(context: Context): Locale = when (read(context)) {
        ZH -> Locale("zh", "CN")
        EN -> Locale.ENGLISH
        else -> Locale.getDefault()
    }
}
