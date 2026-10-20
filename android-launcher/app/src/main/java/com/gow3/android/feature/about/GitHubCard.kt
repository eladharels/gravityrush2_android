package com.gow3.android.feature.about

import com.gow3.android.ui.components.ActionButton

import com.gow3.android.data.model.ProjectRepo
import com.gow3.android.ui.components.LauncherColors

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.gow3.android.R

/** 求 Star 与问题反馈，跳转到作者 GitHub 仓库 / Issues 页面。 */
@Composable
internal fun GitHubCard() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LauncherColors.surface)
            .border(
                BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.5f)),
                RoundedCornerShape(14.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.github_title),
            color = LauncherColors.gold,
            fontFamily = FontFamily.Serif,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GitHubRow(
                label = stringResource(R.string.github_star_label),
                description = stringResource(R.string.github_star_description),
                action = stringResource(R.string.github_open),
            ) {
                openExternalUrl(context, ProjectRepo.URL)
            }
            GitHubRow(
                label = stringResource(R.string.github_issue_label),
                description = stringResource(R.string.github_issue_description),
                action = stringResource(R.string.github_open),
            ) {
                openExternalUrl(context, ProjectRepo.ISSUES_URL)
            }
        }
    }
}

@Composable
private fun GitHubRow(
    label: String,
    description: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                label,
                color = LauncherColors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                description,
                color = LauncherColors.textMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        ActionButton(action, false, onClick = onAction)
    }
}

/**
 * 在能够处理 [url] 的应用中打开它；当没有任何应用可以打开时（例如未安装浏览器）
 * 用 `contact_no_app_fmt` 提示并把地址展示给玩家，返回 false。
 * About 页的仓库 / Issues 入口与检测更新的下载入口共用这一处降级。
 */
internal fun openExternalUrl(context: android.content.Context, url: String): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    } catch (error: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.contact_no_app_fmt, url), Toast.LENGTH_SHORT)
            .show()
        false
    }
