package com.gow3.android.core.di

import com.gow3.android.core.settings.GamePrefs
import com.gow3.android.core.settings.Gow3Settings


import android.content.Context

/**
 * 手工依赖注入容器（design D4）：持有 Context 级、可单例化的服务。
 *
 * 范围界定：
 * - 入容器的只有 Context 级服务（如 [GamePrefs]）。
 * - `Gow3Settings`/`DriverManager`/`DriverProxy`/`LanguageManager` 等是 Kotlin
 *   object（进程级单例），容器只做显式暴露，便于 UI 层统一获取入口与将来替换为实例。
 * - `GameFolderManager`/`SaveManager`/`LogManager`/`LosslessDllManager` 依赖 legacy
 *   `onActivityResult`（targetSdk 28 硬约束），是 Activity 绑定对象，MUST NOT 入容器，
 *   仍由各自 Activity 构造（它们位于 `data/session/`，包位置本身就是这个约束的标记）。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** `SharedPreferences("gow3")` 唯一读写口；懒加载，进程内单例。 */
    val gamePrefs: GamePrefs by lazy { GamePrefs(appContext) }

    /** gow3.ini 唯一读写口（object，显式暴露）。 */
    val settings: Gow3Settings get() = Gow3Settings
}
