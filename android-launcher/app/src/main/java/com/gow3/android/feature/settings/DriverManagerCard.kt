package com.gow3.android.feature.settings

import com.gow3.android.ui.components.ActionButton

import com.gow3.android.data.Gow3Data
import com.gow3.android.data.driver.DriverDeleteResult
import com.gow3.android.data.driver.DriverEntry
import com.gow3.android.data.driver.DriverInstallFailure
import com.gow3.android.data.driver.DriverInstallResult
import com.gow3.android.data.driver.DriverInstaller
import com.gow3.android.data.driver.DriverManager
import com.gow3.android.ui.components.LauncherColors

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gow3.android.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings DRIVER 分类的驱动管理卡片（slot `driver_manager`，spec：设置页查看选中的驱动、
 * 已下载驱动的列表与删除、导入本地驱动包）。与 Home 的驱动下拉读同一数据源：
 * `gow3.ini` + `files/drivers/` 目录扫描，两处展示保持一致。
 *
 * [ini] 内容变化时重扫磁盘（父级 ON_RESUME / 目录偏好变化都会重载 ini）；删除、导入
 * 与下载完成后经 [onIniChanged] 让父级重读 ini，卡片随之刷新。无游戏目录时删除入口
 * 禁用（spec：驱动选择与删除依赖游戏目录已配置；导入不受限——它只新增驱动包）。
 */
@Composable
internal fun DriverManagerCard(
    ini: Map<String, String>,
    hasGameDir: Boolean,
    onIniChanged: () -> Unit,
) {
    val context = LocalContext.current
    // 组合作用域预解析（回调内不得调用 stringResource）。
    val doneFmt = stringResource(R.string.driver_dl_done_fmt)
    val deletedFmt = stringResource(R.string.driver_manager_deleted_fmt)
    val deletedFallbackFmt = stringResource(R.string.driver_manager_deleted_fallback_fmt)
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<DriverEntry>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<DriverEntry?>(null) }
    var showDownload by remember { mutableStateOf(false) }
    var showProxy by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(ini) {
        entries = withContext(Dispatchers.IO) { DriverManager.scan(context.filesDir) }
    }

    // SAF 导入：选中的 zip 复制到缓存后走与下载安装完全相同的 DriverInstaller 校验安装。
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importing) {
            importing = true
            scope.launch {
                val name = displayName(context, uri)
                val install = withContext(Dispatchers.IO) {
                    val zip = File(context.cacheDir, "driver-import-${System.currentTimeMillis()}.zip")
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            zip.outputStream().use { output -> input.copyTo(output) }
                        } ?: return@withContext DriverInstallResult.Failed(DriverInstallFailure.BAD_ZIP)
                        DriverInstaller.install(
                            context.filesDir, zip,
                            name?.removeSuffix(".zip") ?: "import",
                        )
                    } catch (_: Exception) {
                        zip.delete()
                        DriverInstallResult.Failed(DriverInstallFailure.BAD_ZIP)
                    }
                }
                when (install) {
                    is DriverInstallResult.Success ->
                        Toast.makeText(
                            context,
                            doneFmt.format(install.name),
                            Toast.LENGTH_SHORT,
                        ).show()
                    is DriverInstallResult.Failed ->
                        Toast.makeText(context, R.string.driver_dl_failed_install, Toast.LENGTH_LONG).show()
                }
                importing = false
                onIniChanged()
            }
        }
    }

    val selectedId = DriverManager.selectedId(ini)
    // 失效 / 已删除的选中项按系统驱动回退展示（spec 与 Home 卡片一致）。
    val selectedEntry = entries.firstOrNull { it.id == selectedId && it.valid }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LauncherColors.navSurface),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.driver_manager_title),
            color = LauncherColors.gold,
            fontFamily = FontFamily.Serif,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )

        // 当前选中驱动 + 元数据（meta 缺失时显示目录 id 与库名，兼容 adb 脚本装的旧包）。
        val selectedName = selectedEntry
            ?.let { it.name + if (it.version.isBlank()) "" else "  ·  ${it.version}" }
            ?: stringResource(R.string.driver_manager_system_fmt, stringResource(R.string.driver_manager_stock))
        Text(selectedName, color = LauncherColors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        selectedEntry?.let { entry ->
            if (entry.author.isNotBlank()) {
                Text(
                    stringResource(R.string.driver_manager_author_fmt, entry.author),
                    color = LauncherColors.textMuted, fontSize = 11.sp,
                )
            }
            Text(
                stringResource(R.string.driver_manager_library_fmt, entry.library),
                color = LauncherColors.textMuted, fontSize = 11.sp,
            )
        }

        // 已下载驱动列表：名称与版本、选中标记、删除。
        entries.forEach { entry ->
            DriverRow(
                entry = entry,
                selected = entry.id == selectedEntry?.id,
                deleteEnabled = hasGameDir,
                onDelete = { pendingDelete = entry },
            )
        }

        if (!hasGameDir) {
            Text(
                stringResource(R.string.driver_manager_needs_game_folder),
                color = LauncherColors.textMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                label = stringResource(R.string.driver_manager_download),
                primary = true,
                modifier = Modifier.weight(1f),
            ) { showDownload = true }
            ActionButton(
                label = stringResource(R.string.driver_manager_import),
                primary = false,
                modifier = Modifier.weight(1f),
            ) {
                // zip 的 provider 标注不统一：放宽为任意类型，安装器负责结构校验。
                importPicker.launch(arrayOf("*/*"))
            }
            ActionButton(
                label = stringResource(R.string.driver_manager_network),
                primary = false,
                modifier = Modifier.weight(1f),
            ) { showProxy = true }
        }
        Text(
            stringResource(R.string.driver_manager_footer),
            color = LauncherColors.textMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }

    pendingDelete?.let { entry ->
        ConfirmDeleteDialog(
            entry = entry,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    val iniFile = Gow3Data.config(context)
                    val result = withContext(Dispatchers.IO) {
                        DriverManager.delete(context.filesDir, iniFile, entry.id)
                    }
                    when (result) {
                        is DriverDeleteResult.Done ->
                            Toast.makeText(
                                context,
                                (if (result.wasSelected) deletedFallbackFmt else deletedFmt).format(entry.name),
                                Toast.LENGTH_SHORT,
                            ).show()
                        is DriverDeleteResult.Rejected ->
                            Toast.makeText(context, R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
                    }
                    onIniChanged()
                }
            },
        )
    }

    if (showDownload) {
        DriverDownloadDialog(
            onDismiss = { showDownload = false },
            onInstalled = onIniChanged,
        )
    }
    if (showProxy) {
        DriverProxyDialog(onDismiss = { showProxy = false })
    }
}

@Composable
private fun DriverRow(entry: DriverEntry, selected: Boolean, deleteEnabled: Boolean, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LauncherColors.surface)
            .border(
                BorderStroke(1.dp, LauncherColors.gold.copy(alpha = if (selected) 0.6f else 0.25f)),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    entry.name + if (entry.version.isBlank()) "" else "  ·  ${entry.version}",
                    color = LauncherColors.textPrimary,
                    fontSize = 12.sp,
                )
                if (selected) Text("✓", color = LauncherColors.gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                if (entry.valid) stringResource(R.string.driver_manager_library_fmt, entry.library)
                else stringResource(R.string.driver_manager_invalid),
                color = if (entry.valid) LauncherColors.textMuted else LauncherColors.crimson,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
        Text(
            stringResource(R.string.driver_manager_delete),
            color = if (deleteEnabled) LauncherColors.textMuted else LauncherColors.textMuted.copy(alpha = 0.4f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = deleteEnabled, onClick = onDelete)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(entry: DriverEntry, onDismiss: () -> Unit, onConfirm: () -> Unit) {    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(LauncherColors.surface)
                .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.driver_manager_confirm_delete_fmt, entry.name),
                color = LauncherColors.textPrimary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                ActionButton(stringResource(R.string.dialog_cancel), primary = false) { onDismiss() }
                ActionButton(stringResource(R.string.driver_manager_delete), primary = true) { onConfirm() }
            }
        }
    }
}

/** Provider 的显示名（用作 safeId 回退主干）；部分 provider 拒绝查询时返回 null。 */
private fun displayName(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column)?.takeIf { it.isNotBlank() } else null
            }
    } catch (_: Exception) {
        null
    }
}
