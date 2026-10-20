package com.gow3.android.feature.settings

import com.gow3.android.core.settings.LanguageManager
import com.gow3.android.ui.components.LauncherColors

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gow3.android.R

/**
 * 设置页常规分类的语言入口（slot `language`）：左标签 / 右当前值 + ▾ / 点击展开下拉
 * （ParamRow 同款交互）。选项为 跟随系统 / 简体中文 / English（端元名不译）；
 * 选择后由调用方持久化并重建 Activity，本组件只读 [LanguageManager] 展示当前值。
 */
@Composable
internal fun LanguageCard(onChangeLanguage: (String) -> Unit) {
    val context = LocalContext.current
    val current = LanguageManager.read(context)
    val options = listOf(
        R.string.language_follow_system to LanguageManager.FOLLOW_SYSTEM,
        R.string.language_zh to LanguageManager.ZH,
        R.string.language_en to LanguageManager.EN,
    )
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.second == current }
        ?.let { stringResource(it.first) }
        ?: stringResource(R.string.language_follow_system)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.language_setting), color = LauncherColors.textMuted, fontSize = 13.sp)
        Box {
            Text(
                selectedLabel + "  ▾",
                color = LauncherColors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(LauncherColors.navSurface)
                    .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.35f)), RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (labelRes, value) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes)) },
                        onClick = {
                            expanded = false
                            onChangeLanguage(value)
                        },
                    )
                }
            }
        }
    }
}
