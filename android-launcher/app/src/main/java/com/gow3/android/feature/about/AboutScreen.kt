package com.gow3.android.feature.about

import com.gow3.android.ui.components.ScreenColumn

import com.gow3.android.data.UpdateChecker
import com.gow3.android.data.archive.LogArchive
import com.gow3.android.data.archive.SaveArchive
import com.gow3.android.data.session.LogManager
import com.gow3.android.data.session.LogResult
import com.gow3.android.data.session.SaveManager
import com.gow3.android.data.session.SaveResult
import com.gow3.android.ui.components.LauncherColors

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gow3.android.BuildConfig
import com.gow3.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AboutScreen(
    saves: SaveManager,
    logs: LogManager,
) {
    val context = LocalContext.current
    var hasSaves by remember { mutableStateOf(SaveArchive.hasSaves(saves.savedataDir())) }
    var hasLogs by remember { mutableStateOf(LogArchive.hasLogs(logs.logsDir())) }
    var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        hasSaves = SaveArchive.hasSaves(saves.savedataDir())
        hasLogs = LogArchive.hasLogs(logs.logsDir())
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // 只在玩家点击入口时发起检查；网络请求在 Dispatchers.IO 上跑，不阻塞界面，
    // scope 随 composition 销毁而取消，玩家离开本页后不会继续持有请求。
    fun checkForUpdates() {
        if (updateState == UpdateCheckState.Checking) return
        updateState = UpdateCheckState.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(BuildConfig.VERSION_NAME) }
            updateState = UpdateCheckState.Done(result)
        }
    }

    // 玩家可能在应用退到后台期间备份/恢复存档、安装运行时或运行游戏，
    // 因此每次 About 回到前台时都会从磁盘重新读取 savedata/日志状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ScreenColumn {
        Text(stringResource(R.string.about_title), color = LauncherColors.textPrimary, fontFamily = FontFamily.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.about_version_fmt, BuildConfig.VERSION_NAME), color = LauncherColors.gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.about_description), color = LauncherColors.textMuted, fontSize = 13.sp, lineHeight = 19.sp)
        UpdateCheckCard(state = updateState, onCheck = ::checkForUpdates)
        Spacer(Modifier.height(8.dp))
        ContactCard()
        GitHubCard()
        SaveCard(
            hasSaves = hasSaves,
            onExport = {
                saves.launchExport { result ->
                    when (result) {
                        is SaveResult.Success -> toast(result.message)
                        SaveResult.Cancelled -> Unit
                        is SaveResult.Failed -> toast(result.message)
                    }
                }
            },
            onImport = {
                saves.launchImport { result ->
                    when (result) {
                        is SaveResult.Success -> {
                            refresh()
                            toast(result.message)
                        }
                        SaveResult.Cancelled -> Unit
                        is SaveResult.Failed -> toast(result.message)
                    }
                }
            },
        )
        LogCard(
            hasLogs = hasLogs,
            onExport = {
                logs.launchExport { result ->
                    when (result) {
                        is LogResult.Success -> {
                            refresh()
                            toast(result.message)
                        }
                        LogResult.Cancelled -> Unit
                        is LogResult.Failed -> toast(result.message)
                    }
                }
            },
        )
        // 第三方许可入口暂隐藏：许可页尚未实现（行内 clickable 为空占位），
        // assets 里的 THIRD_PARTY_LICENSES.txt 照常打包。做许可页时恢复本段。
        // Row(
        //     modifier = Modifier
        //         .fillMaxWidth()
        //         .clip(RoundedCornerShape(12.dp))
        //         .background(LauncherColors.surface)
        //         .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
        //         .clickable { }
        //         .padding(horizontal = 16.dp, vertical = 16.dp),
        //     verticalAlignment = Alignment.CenterVertically,
        //     horizontalArrangement = Arrangement.SpaceBetween,
        // ) {
        //     Text(stringResource(R.string.about_third_party_licenses), color = LauncherColors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        //     Text("›", color = LauncherColors.gold, fontSize = 24.sp)
        // }
        // 语言入口已移至 Settings → 常规（LanguageCard）。
    }
}
