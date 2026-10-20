package com.gow3.android.feature.settings

import com.gow3.android.ui.components.SectionLabel

import com.gow3.android.ui.components.LauncherColors

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gow3.android.R

/**
 * Settings「操作」分类的按键映射功能卡（slot `pad_mapping`，add-pad-button-mapping）。
 * 卡片只有入口行：16 个 PS 按钮的绑定列表在 [PadMappingActivity]（View 层——
 * `SOURCE_JOYSTICK` 的 MotionEvent 进不了 Compose 事件流，见其类注释）。
 */
@Composable
internal fun PadMappingCard() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(R.string.pad_mapping_title))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(LauncherColors.navSurface)
                .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.35f)), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.pad_mapping_desc),
                color = LauncherColors.textPrimary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.pad_mapping_custom),
                color = LauncherColors.gold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(LauncherColors.crimson)
                    .clickable {
                        context.startActivity(Intent(context, PadMappingActivity::class.java))
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
