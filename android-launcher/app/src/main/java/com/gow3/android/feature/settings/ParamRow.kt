package com.gow3.android.feature.settings

import com.gow3.android.data.model.LaunchParam
import com.gow3.android.data.model.LaunchParams
import com.gow3.android.ui.components.LauncherColors

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gow3.android.R

/**
 * 单行参数编辑控件，Settings 配置页按分类复用：多值参数用下拉、布尔参数用开关。
 * 编辑即时回调 [save]，由调用方持久化到 gow3.ini。
 */
@Composable
internal fun ParamRow(param: LaunchParam, values: Map<String, String>, save: (String, String) -> Unit) {
    val context = LocalContext.current
    var showInfo by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(param.labelRes), color = LauncherColors.textMuted, fontSize = 13.sp)
            if (param.descriptionRes != null) {
                Text(
                    "ⓘ",
                    color = LauncherColors.gold,
                    fontSize = 13.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showInfo = true }
                        .padding(2.dp),
                )
            }
        }
        if (param.options.size == 2 && param.options[0].value == "0" && param.options[1].value == "1") {
            Switch(
                checked = LaunchParams.valueOf(values, param) == "1",
                onCheckedChange = { save(param.key, if (it) "1" else "0") },
                enabled = param.enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = LauncherColors.crimson, checkedThumbColor = LauncherColors.gold),
            )
        } else {
            // 生效档位：fps_limit 受当前 engine_fps 约束（上限不超过引擎帧率，0=不限除外）。
            val options = LaunchParams.effectiveOptions(param, values)
            var expanded by remember(options, param.enabled) { mutableStateOf(false) }
            Box {
                Text(
                    selectedLabel(context, values, param) + "  ▾",
                    color = if (param.enabled) LauncherColors.textPrimary else LauncherColors.textMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(LauncherColors.navSurface)
                        .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.35f)), RoundedCornerShape(8.dp))
                        .clickable(enabled = param.enabled) { expanded = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.labelRes)) },
                            onClick = {
                                expanded = false
                                save(param.key, option.value)
                            },
                        )
                    }
                }
            }
        }
    }
    if (showInfo && param.descriptionRes != null) {
        ParamInfoDialog(
            title = stringResource(param.labelRes),
            message = stringResource(param.descriptionRes),
            onDismiss = { showInfo = false },
        )
    }
}

/** 当前值对应的本地化展示名；未知值回退到原始值。 */
private fun selectedLabel(context: Context, values: Map<String, String>, param: LaunchParam): String {
    val option = LaunchParams.selectedOption(values, param)
    if (option == null && param.enabled && LaunchParams.isPatchSetting(param.key)) {
        return context.getString(R.string.home_patch_unavailable_value, LaunchParams.valueOf(values, param))
    }
    return option?.let { context.getString(it.labelRes) } ?: LaunchParams.valueOf(values, param)
}

@Composable
internal fun ParamInfoDialog(title: String, message: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(LauncherColors.surface)
                .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = LauncherColors.gold, fontFamily = FontFamily.Serif, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Text(message, color = LauncherColors.textMuted, fontSize = 13.sp, lineHeight = 19.sp)
            Text(
                stringResource(R.string.dialog_ok),
                color = LauncherColors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(8.dp))
                    .background(LauncherColors.navSurface)
                    .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.45f)), RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
