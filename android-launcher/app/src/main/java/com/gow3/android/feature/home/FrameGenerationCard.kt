package com.gow3.android.feature.home

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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gow3.android.R

/**
 * FRAME GENERATION 卡片（change add-native-frame-generation）：一个开关。
 * 未导入 Lossless.dll 时开关禁用，并提示到 设置 → 常规 导入；倍率等调参在
 * 游戏内抽屉（Lossless Scaling），供给入口在设置页常规分类。
 */
@Composable
internal fun FrameGenerationCard(
    enabled: Boolean,
    dllInstalled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LauncherColors.surface)
            .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.frame_gen_title), color = LauncherColors.gold, fontFamily = FontFamily.Serif, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (enabled) stringResource(R.string.frame_gen_toggle_on) else stringResource(R.string.frame_gen_toggle_off),
                color = LauncherColors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            // 样式与「跳过动画」开关一致（红轨金钮，Home 视觉契约）。
            Switch(
                checked = enabled && dllInstalled,
                onCheckedChange = { onChange(it) },
                enabled = dllInstalled,
                colors = SwitchDefaults.colors(checkedTrackColor = LauncherColors.crimson, checkedThumbColor = LauncherColors.gold),
            )
        }
        if (!dllInstalled) {
            Text(
                stringResource(R.string.frame_gen_import_hint),
                color = LauncherColors.gold.copy(alpha = 0.85f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}
