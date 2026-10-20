package com.gow3.android.feature.about

import com.gow3.android.ui.components.ActionButton

import com.gow3.android.data.NotComparableReason
import com.gow3.android.data.UpdateCheckResult
import com.gow3.android.ui.components.LauncherColors

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gow3.android.R

/** 入口的三种状态：未触发、正在检查、已有结果。 */
internal sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Done(val result: UpdateCheckResult) : UpdateCheckState
}

/**
 * About 页版本号下方的「检测更新」入口。
 *
 * 只在玩家点击 [onCheck] 时才发起检查；检查在后台线程进行，本组件只负责渲染状态。
 * 发现新版本时只提供跳转浏览器下载页的入口——不下载、不安装 APK。
 */
@Composable
internal fun UpdateCheckCard(
    state: UpdateCheckState,
    onCheck: () -> Unit,
) {
    val context = LocalContext.current
    val result = (state as? UpdateCheckState.Done)?.result

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LauncherColors.surface)
            .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.update_check_title),
                color = LauncherColors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            // 检查进行中时把入口换成不可点击的进度指示，避免重复触发请求。
            if (state == UpdateCheckState.Checking) {
                CircularProgressIndicator(
                    color = LauncherColors.gold,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            } else {
                ActionButton(stringResource(R.string.update_check_action), primary = false) { onCheck() }
            }
        }
        if (state == UpdateCheckState.Checking) {
            Text(
                stringResource(R.string.update_check_checking),
                color = LauncherColors.textMuted,
                fontSize = 12.sp,
            )
        }
        when (result) {
            is UpdateCheckResult.UpToDate -> ResultText(
                stringResource(R.string.update_check_up_to_date_fmt, result.version),
            )

            is UpdateCheckResult.Newer -> {
                ResultText(stringResource(R.string.update_check_newer_fmt, result.version), LauncherColors.gold)
                // 优先指向该 release 的 APK 资产，缺失时退回 release 页面。
                val download = result.release.apkUrl ?: result.release.pageUrl
                if (download.isNotBlank()) {
                    // 只把地址交给系统浏览器；打不开时 openExternalUrl 会提示并展示地址。
                    ActionButton(stringResource(R.string.update_check_download), primary = true) {
                        openExternalUrl(context, download)
                    }
                }
            }

            is UpdateCheckResult.NotComparable -> ResultText(
                stringResource(
                    when (result.reason) {
                        NotComparableReason.INSTALLED_VERSION ->
                            R.string.update_check_not_comparable_installed_fmt

                        NotComparableReason.LATEST_TAG ->
                            R.string.update_check_not_comparable_latest_fmt
                    },
                    result.currentVersion ?: result.latestTag.orEmpty(),
                ),
            )

            is UpdateCheckResult.Failed -> ResultText(stringResource(result.messageRes))
            null -> Unit
        }
    }
}

@Composable
private fun ResultText(text: String, color: Color = LauncherColors.textMuted) {
    Text(text, color = color, fontSize = 12.sp, lineHeight = 18.sp)
}
