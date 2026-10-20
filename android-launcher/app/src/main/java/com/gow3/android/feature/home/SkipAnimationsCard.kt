package com.gow3.android.feature.home

import com.gow3.android.data.PatchCatalog
import com.gow3.android.feature.settings.ParamInfoDialog
import com.gow3.android.ui.components.LauncherColors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gow3.android.R
import com.gow3.android.ui.components.SectionLabel

/**
 * Home 页 GPU 驱动选择下方的「跳过动画」快捷开关：一行开关同时控制补丁目录里的
 * Skip Intro 与 Skip Any Video With X Button，写入与补丁页同一份
 * `patches_<titleId>_<version>` 选择。目录未就绪或补丁组为空（版本不提供）时整行
 * 置灰。选中态采用「全部选中」语义：玩家在补丁页单独关掉其一，开关回落为关，
 * 重新打开则两个补丁一起启用。
 */
@Composable
internal fun SkipAnimationsCard(
    catalog: PatchCatalog?,
    ini: Map<String, String>,
    onApply: (Map<String, String>) -> Unit,
) {
    var showInfo by remember { mutableStateOf(false) }
    val skipPatches = catalog?.skipVideoPatches.orEmpty()
    val selected = skipPatches.ifEmpty { null }?.let { catalog!!.selected(ini) }
    val checked = selected != null && selected.containsAll(skipPatches.map { it.name })
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
            SectionLabel(stringResource(R.string.param_skip_animations))
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
        Switch(
            checked = checked,
            onCheckedChange = { enabled -> onApply(catalog!!.toggleAll(ini, skipPatches, enabled)) },
            enabled = skipPatches.isNotEmpty(),
            colors = SwitchDefaults.colors(checkedTrackColor = LauncherColors.crimson, checkedThumbColor = LauncherColors.gold),
        )
    }
    if (showInfo) {
        ParamInfoDialog(
            title = stringResource(R.string.param_skip_animations),
            message = stringResource(R.string.param_desc_skip_animations),
            onDismiss = { showInfo = false },
        )
    }
}
