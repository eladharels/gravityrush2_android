package com.gow3.android.feature.about

import com.gow3.android.ui.components.ActionButton

import com.gow3.android.ui.components.LauncherColors

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

private const val AUTHOR = "zfitness"
private const val QQ_GROUP = "1105902080"
private const val TELEGRAM_URL = "https://t.me/gow3_android"
private const val TELEGRAM_HANDLE = "@gow3_android"
private const val QQ_GROUP_SCHEME =
    "mqqapi://card/show_pslcard?src_type=internal&version=1&card_type=group&source=qrcode&uin=$QQ_GROUP"

/** 致谢移植作者，并引导玩家前往反馈群。 */
@Composable
internal fun ContactCard() {
    val context = LocalContext.current
    // 组合作用域预解析（局部函数/回调内不得调用 stringResource）。
    val copiedFmt = stringResource(R.string.copied_fmt)
    val authorLabel = stringResource(R.string.contact_author_label)
    val qqLabel = stringResource(R.string.contact_qq_label)
    val telegramLabel = stringResource(R.string.contact_telegram_label)
    val openLabel = stringResource(R.string.contact_open)
    val qqNotInstalledMsg = stringResource(R.string.contact_qq_not_installed)
    val noAppFmt = stringResource(R.string.contact_no_app_fmt)

    fun copy(label: String, value: String) {
        copyToClipboard(context, label, value)
        Toast.makeText(context, copiedFmt.format(value), Toast.LENGTH_SHORT).show()
    }

    /** 用 QQ 打开群卡片；未安装 QQ 返回 false。 */
    fun openQqGroup(context: Context, group: String): Boolean =
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, QQ_GROUP_SCHEME.toUri()))
            true
        } catch (error: ActivityNotFoundException) {
            false
        }

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
            stringResource(R.string.contact_title),
            color = LauncherColors.gold,
            fontFamily = FontFamily.Serif,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ContactRow(authorLabel, AUTHOR)
            ContactRow(qqLabel, QQ_GROUP, openLabel) {
                if (!openQqGroup(context, QQ_GROUP)) {
                    copyToClipboard(context, qqLabel, QQ_GROUP)
                    Toast.makeText(
                        context,
                        qqNotInstalledMsg,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            ContactRow(telegramLabel, TELEGRAM_HANDLE, openLabel) {
                if (!openUrl(context, TELEGRAM_URL)) {
                    Toast.makeText(context, noAppFmt.format(TELEGRAM_URL), Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
        Text(
            stringResource(R.string.contact_description),
            color = LauncherColors.textMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun ContactRow(
    label: String,
    value: String,
    action: String? = null,
    onAction: () -> Unit = {},
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
                color = LauncherColors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp
            )
            Text(
                value,
                color = LauncherColors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (action != null) {
            ActionButton(action, false, onClick = onAction)
        }
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

/** 在能够处理 [url] 的应用中打开它；当没有任何应用可以打开时（例如未安装浏览器）返回 false。 */
private fun openUrl(context: Context, url: String): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    } catch (error: ActivityNotFoundException) {
        false
    }
