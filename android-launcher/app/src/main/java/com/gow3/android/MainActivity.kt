package com.gow3.android

import com.gow3.android.feature.firstrun.FirstRunScreen
import com.gow3.android.ui.LauncherScreen
import com.gow3.android.ui.theme.Gow3LauncherTheme

import com.gow3.android.core.settings.GamePrefs
import com.gow3.android.core.settings.LanguageManager
import com.gow3.android.data.session.GameFolderManager
import com.gow3.android.data.session.LogManager
import com.gow3.android.data.session.LosslessDllManager
import com.gow3.android.data.session.SaveManager
import com.gow3.android.game.GameActivity
import com.gow3.android.game.SteamDllDialog
import com.gow3.android.ui.components.LauncherColors

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private lateinit var gameFolderManager: GameFolderManager
    private lateinit var losslessDllManager: LosslessDllManager
    private lateinit var saveManager: SaveManager
    private lateinit var logManager: LogManager

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(applyLanguage(newBase))
    }

    /** 按 LanguageManager 的语言覆盖（或系统语言）为 MainActivity 注入 Locale 配置。 */
    private fun applyLanguage(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(LanguageManager.resolve(base))
        return base.createConfigurationContext(config)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = LauncherColors.backgroundTop.toArgbCompat()
        window.navigationBarColor = LauncherColors.backgroundBottom.toArgbCompat()
        window.decorView.systemUiVisibility = 0

        gameFolderManager = GameFolderManager(this)
        gameFolderManager.adoptFolder(intent?.getStringExtra("game_dir"))
        losslessDllManager = LosslessDllManager(this)
        saveManager = SaveManager(this)
        logManager = LogManager(this)
        setContent {
            Gow3LauncherTheme(dynamicColor = false) {
                // 首启硬门控（spec：android-kotlin-launcher-first-run-game-select）：
                // `game_dir` 从未持久化时强制进入选择页，确认后不再出现；已持久化目录
                //（无论当前是否仍有效）的启动不被拦回。语言切换 recreate 后按 prefs 重算。
                var showFirstRun by remember { mutableStateOf(GamePrefs(this).gameDir() == null) }
                if (showFirstRun) {
                    FirstRunScreen(
                        onChooseGame = { gameFolderManager.chooseGameFolder() },
                        onChooseUpdate = { gameFolderManager.chooseUpdateFolder() },
                        onConfirm = { showFirstRun = false },
                    )
                } else {
                    LauncherScreen(
                        onStartGame = {
                            if (gameFolderManager.hasGameFolder()) {
                                // action=launch：GameActivity 的单次启动踢动标记。没有它，
                                // surfaceCreated 只记录 holder、等一个永不到来的用户动作，
                                // 启动流程（runtime 解包 → 游戏进程）不会开始。
                                startActivity(
                                    Intent(this, GameActivity::class.java)
                                        .putExtra("action", "launch"),
                                )
                            }
                        },
                        losslessDlls = losslessDllManager,
                        onGetFromSteam = { onDownloaded ->
                            SteamDllDialog(this, losslessDllManager.appCopy()) { ok, msg ->
                                onDownloaded(ok, msg)
                            }.show()
                        },
                        // 目录选择回调现在服务于 Settings 存储分类（目录修改的常驻位置）；
                        // 首启选择页直接使用同一对回调。
                        onChooseGameFolder = { gameFolderManager.chooseGameFolder() },
                        onChooseUpdateFolder = { gameFolderManager.chooseUpdateFolder() },
                        saves = saveManager,
                        logs = logManager,
                        onChangeLanguage = { value ->
                            LanguageManager.set(this, value)
                            recreate()
                        },
                    )
                }
            }
        }
        // 旧的首启对话框引导（ensureGameFolder）已由 Compose 首启选择页取代：不再在
        // onCreate 里弹目录对话框，否则会叠在硬门控页面之上。
    }

    @Deprecated("Legacy Activity Result API is required by this target SDK flow")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        gameFolderManager.onPermissionResult(requestCode, grantResults)
    }

    @Deprecated("Legacy Activity Result API is required by this target SDK flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (saveManager.onActivityResult(requestCode, resultCode, data)) return
        if (logManager.onActivityResult(requestCode, resultCode, data)) return
        if (losslessDllManager.onPickerResult(requestCode, resultCode, data)) return
        gameFolderManager.onFolderResult(requestCode, resultCode, data)
    }
}

private fun androidx.compose.ui.graphics.Color.toArgbCompat(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
