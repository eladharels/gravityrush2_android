package com.gow3.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object LauncherColors {
    val backgroundTop = Color(0xFF29120F)
    val backgroundBottom = Color(0xFF0D0A07)
    val surface = Color(0xFF12100D)
    val navSurface = Color(0xFF171411)
    val textPrimary = Color(0xFFE8DFC9)
    val textMuted = Color(0xFF8E9097)
    val gold = Color(0xFFC6AD76)
    val crimson = Color(0xFFB8172B)
}

@Composable
internal fun ActionButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = if (primary) LauncherColors.textPrimary else LauncherColors.textMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) LauncherColors.crimson else LauncherColors.navSurface)
            .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.45f)), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 8.dp),
    )
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%d KB".format(bytes / 1024L)
    else -> "$bytes B"
}

@Composable
internal fun ScreenColumn(
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 36.dp),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun EmptyState(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(LauncherColors.surface)
            .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.35f)), RoundedCornerShape(14.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("◇", color = LauncherColors.gold, fontSize = 30.sp)
        Spacer(Modifier.height(16.dp))
        Text(title, color = LauncherColors.textPrimary, fontFamily = FontFamily.Serif, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(8.dp))
        Text(description, color = LauncherColors.textMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

/** 分区小标题：金色衬线小字（Home 各配置区与 Settings 存储分类的统一头部样式）。 */
@Composable
internal fun SectionLabel(label: String) {
    Text(label, color = LauncherColors.gold, fontFamily = FontFamily.Serif, fontSize = 11.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
}
