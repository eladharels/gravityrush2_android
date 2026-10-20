package com.gow3.android.feature.firstrun

import com.gow3.android.core.settings.GamePrefs
import com.gow3.android.data.model.GameIdentity
import com.gow3.android.data.model.GameIdentityState
import com.gow3.android.ui.components.LauncherColors

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gow3.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首启游戏选择页（spec：android-kotlin-launcher-first-run-game-select）：一次性硬门控，
 * 只有「两个大目录入口 + 确认按钮」三个要素。选择流程复用 GameFolderManager 的对话框
 * 与系统目录选择器（经 Activity 注入的回调），持久化到既有 prefs 键；入口以即时校验
 * 反馈展示所选目录的有效性与游戏信息摘要。确认按钮在游戏身份解析就绪后才可用；
 * 返回键不拦截（默认退出应用，下次启动重新进入本页，即 spec 的不可绕过门控）。
 *
 * 本页不提供跳过路径：Home 上不存在「从未选过目录」的正常状态，目录后续修改收敛到
 * Settings 存储分类，本页确认后不再出现。
 */
@Composable
internal fun FirstRunScreen(
    onChooseGame: () -> Unit,
    onChooseUpdate: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    var gameDir by remember { mutableStateOf(currentGameDir(context)) }
    var updateDir by remember { mutableStateOf(currentUpdateDir(context)) }
    var identity by remember { mutableStateOf<GameIdentityState>(GameIdentityState.Loading) }
    var revision by remember { mutableIntStateOf(0) }
    val prefs = remember(context) { GamePrefs(context) }

    // prefs 只用于键变化监听：目录选择对话框写回 game_dir/update_dir 后这里即时刷新。
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == GamePrefs.KEY_GAME_DIR || key == GamePrefs.KEY_UPDATE_DIR) {
                gameDir = currentGameDir(context)
                updateDir = currentUpdateDir(context)
                revision++
            }
        }
        prefs.raw.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.raw.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(revision) {
        identity = GameIdentityState.Loading
        identity = withContext(Dispatchers.IO) {
            GameIdentity.load(gameDir, updateDir, context.assets::open)
        }
    }

    // 确认可用条件 = 游戏身份解析就绪（spec：无效目录/不受支持版本保持置灰）。
    val ready = identity is GameIdentityState.Ready

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.bg_wallpaper),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(LauncherColors.backgroundTop.copy(alpha = 0.55f), LauncherColors.backgroundBottom.copy(alpha = 0.85f)), radius = 950f)),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp)
                .widthIn(max = 520.dp)
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.first_run_title), color = LauncherColors.textPrimary, fontFamily = FontFamily.Serif, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(stringResource(R.string.first_run_subtitle), color = LauncherColors.textMuted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            EntryCard(
                title = stringResource(R.string.first_run_game_folder),
                tag = stringResource(R.string.first_run_required),
                tagHighlighted = true,
                path = gameDir,
                desc = null,
                identity = identity,
                onClick = onChooseGame,
            )
            EntryCard(
                title = stringResource(R.string.first_run_update_folder),
                tag = stringResource(R.string.first_run_optional),
                tagHighlighted = false,
                path = updateDir,
                desc = stringResource(R.string.first_run_update_desc),
                identity = null,
                onClick = onChooseUpdate,
            )
            Spacer(Modifier.height(12.dp))
            ConfirmButton(enabled = ready, onConfirm = onConfirm)
            if (!ready) {
                // 置灰原因：未选目录给锁定提示（玩家就在选择页上，不应让他去设置）；
                // 已选但解析失败（版本不受支持等）才给具体原因。
                val hint = when (val state = identity) {
                    is GameIdentityState.Failed ->
                        if (state.problem == com.gow3.android.data.CatalogProblem.NO_GAME) stringResource(R.string.first_run_confirm_locked)
                        else stringResource(GameIdentity.problemMessageRes(state.problem))
                    else -> stringResource(R.string.first_run_confirm_locked)
                }
                Text(hint, color = LauncherColors.textMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

/**
 * 大目录入口卡：标题 + 必选/可选标签 + 当前路径或未选择态 + 即时校验反馈
 * （游戏身份徽章或失败原因）。点击走既有目录选择流程。
 */
@Composable
private fun EntryCard(
    title: String,
    tag: String,
    tagHighlighted: Boolean,
    path: String?,
    desc: String?,
    identity: GameIdentityState?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LauncherColors.surface)
            .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = LauncherColors.textPrimary, fontFamily = FontFamily.Serif, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                tag,
                color = if (tagHighlighted) LauncherColors.gold else LauncherColors.textMuted,
                fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(LauncherColors.navSurface)
                    .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = if (tagHighlighted) 0.5f else 0.2f)), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Text(
            path ?: stringResource(R.string.first_run_tap_to_select),
            color = if (path != null) LauncherColors.textPrimary else LauncherColors.textMuted,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
        if (desc != null) {
            Text(desc, color = LauncherColors.textMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        // 即时校验反馈（仅游戏目录卡）：就绪展示身份徽章，失败展示可理解原因。
        when (identity) {
            is GameIdentityState.Ready -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedbackBadge("TITLE " + identity.catalog.titleId)
                    FeedbackBadge("VER " + identity.catalog.appVersion)
                }
            }
            is GameIdentityState.Failed -> if (path != null) {
                Text(
                    stringResource(GameIdentity.problemMessageRes(identity.problem)),
                    color = LauncherColors.textMuted, fontSize = 11.sp, lineHeight = 16.sp,
                )
            }
            GameIdentityState.Loading -> if (path != null) {
                Text(stringResource(R.string.mods_loading), color = LauncherColors.textMuted, fontSize = 11.sp)
            }
            null -> {}
        }
    }
}

@Composable
private fun FeedbackBadge(text: String) {
    Text(
        text,
        color = LauncherColors.gold,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(LauncherColors.navSurface)
            .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.45f)), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** 确认按钮：就绪时红色渐变可点，锁定时暗色置灰（spec 的两种状态）。 */
@Composable
private fun ConfirmButton(enabled: Boolean, onConfirm: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (enabled) Brush.linearGradient(listOf(Color(0xFFB8172B), Color(0xFF8C0D1A), Color(0xFFB8172B)))
                else Brush.linearGradient(listOf(LauncherColors.navSurface, LauncherColors.navSurface))
            )
            .border(
                BorderStroke(1.dp, LauncherColors.gold.copy(alpha = if (enabled) 1f else 0.25f)),
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onConfirm)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.first_run_confirm),
            color = if (enabled) LauncherColors.textPrimary else LauncherColors.textMuted,
            fontFamily = FontFamily.Serif, fontSize = 20.sp,
            fontWeight = FontWeight.Black, letterSpacing = 1.2.sp,
        )
    }
}

private fun currentGameDir(context: Context): String? = GamePrefs(context).gameDir()

private fun currentUpdateDir(context: Context): String? = GamePrefs(context).updateDir()
