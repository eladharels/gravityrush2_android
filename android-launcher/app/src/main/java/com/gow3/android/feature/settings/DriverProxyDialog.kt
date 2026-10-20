package com.gow3.android.feature.settings

import com.gow3.android.ui.components.ActionButton

import com.gow3.android.data.driver.DriverProxy
import com.gow3.android.ui.components.LauncherColors

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gow3.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 驱动下载加速对话框（spec：镜像前缀选择 + 测速）。单选内置镜像 / 显式直连 /
 * 自定义前缀；显式选择才落盘（design D6）。测速对内置镜像逐一发轻量请求展示
 * 延迟或失败，不自动选优。
 */
@Composable
internal fun DriverProxyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val stored = remember { DriverProxy.stored(context) }
    // 选择态：null = 未选择（跟随语言默认）、NONE = 显式直连、镜像 URL = 选中该镜像、
    // CUSTOM = 自定义前缀。
    var selected by remember {
        mutableStateOf(
            when {
                stored == null -> null
                stored == DriverProxy.NONE -> DriverProxy.NONE
                stored in DriverProxy.BUILT_IN -> stored
                else -> CUSTOM
            },
        )
    }
    var customText by remember { mutableStateOf(if (stored != null && stored != DriverProxy.NONE && stored !in DriverProxy.BUILT_IN) stored else "") }
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }

    val language = context.resources.configuration.locales[0].language
    val effective = DriverProxy.effectivePrefix(stored, language)

    fun save() {
        val choice = selected
        when (choice) {
            DriverProxy.NONE -> DriverProxy.save(context, DriverProxy.NONE)
            CUSTOM -> {
                val normalized = DriverProxy.normalize(customText.trim())
                if (normalized.isNotEmpty() && normalized != "/") DriverProxy.save(context, normalized)
            }
            null -> Unit
            else -> DriverProxy.save(context, choice)
        }
        onDismiss()
    }

    fun runSpeedTest() {
        if (testing) return
        testing = true
        scope.launch {
            results = withContext(Dispatchers.IO) {
                DriverProxy.BUILT_IN.associateWith { prefix -> DriverProxy.speedTest(prefix) }
            }
            testing = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(LauncherColors.surface)
                .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.driver_px_title),
                color = LauncherColors.gold,
                fontFamily = FontFamily.Serif,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
            Text(
                stringResource(
                    R.string.driver_px_active_fmt,
                    effective.ifEmpty { stringResource(R.string.driver_px_direct_label) },
                ),
                color = LauncherColors.textMuted,
                fontSize = 11.sp,
            )

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ProxyRow(
                    title = stringResource(R.string.driver_px_direct),
                    detail = null,
                    selected = selected == DriverProxy.NONE,
                ) { selected = DriverProxy.NONE }
                DriverProxy.BUILT_IN.forEach { prefix ->
                    ProxyRow(
                        title = prefix.removePrefix("https://").removeSuffix("/"),
                        detail = speedLabel(prefix, testing, results),
                        selected = selected == prefix,
                    ) { selected = prefix }
                }
                ProxyRow(
                    title = stringResource(R.string.driver_px_custom),
                    detail = null,
                    selected = selected == CUSTOM,
                ) { selected = CUSTOM }
                if (selected == CUSTOM) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        singleLine = true,
                        textStyle = TextStyle(color = LauncherColors.textPrimary, fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LauncherColors.gold,
                            unfocusedBorderColor = LauncherColors.gold.copy(alpha = 0.35f),
                            focusedContainerColor = LauncherColors.navSurface,
                            unfocusedContainerColor = LauncherColors.navSurface,
                            cursorColor = LauncherColors.gold,
                            focusedTextColor = LauncherColors.textPrimary,
                            unfocusedTextColor = LauncherColors.textPrimary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Text(
                stringResource(R.string.driver_px_note),
                color = LauncherColors.textMuted,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                ActionButton(
                    label = if (testing) stringResource(R.string.driver_px_testing) else stringResource(R.string.driver_px_speedtest),
                    primary = false,
                ) { runSpeedTest() }
                ActionButton(stringResource(R.string.driver_px_save), primary = true) { save() }
            }
        }
    }
}

private const val CUSTOM = "__custom__"

/** 测速徽标：未测时 null；结果为延迟毫秒或失败文案。 */
@Composable
private fun speedLabel(prefix: String, testing: Boolean, results: Map<String, Long?>): String? {
    val latency = results[prefix]
    return when {
        latency != null -> stringResource(R.string.driver_px_ms_fmt, latency)
        testing -> stringResource(R.string.driver_px_testing)
        results.containsKey(prefix) -> stringResource(R.string.driver_px_failed)
        else -> null
    }
}

@Composable
private fun ProxyRow(title: String, detail: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) LauncherColors.navSurface else LauncherColors.surface)
            .border(
                BorderStroke(1.dp, LauncherColors.gold.copy(alpha = if (selected) 0.6f else 0.25f)),
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, color = LauncherColors.textPrimary, fontSize = 12.sp)
            if (detail != null) {
                Text(detail, color = LauncherColors.textMuted, fontSize = 10.sp)
            }
        }
        if (selected) Text("✓", color = LauncherColors.gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
