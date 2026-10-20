package com.gow3.android.feature.settings

import com.gow3.android.ui.components.ScreenColumn

import com.gow3.android.core.settings.GamePrefs
import com.gow3.android.core.settings.Gow3Settings
import com.gow3.android.data.CatalogProblem
import com.gow3.android.data.Gow3Data
import com.gow3.android.data.model.GameIdentity
import com.gow3.android.data.model.GameIdentityState
import com.gow3.android.data.model.LaunchCategory
import com.gow3.android.data.model.LaunchParams
import com.gow3.android.ui.components.LauncherColors


import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gow3.android.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings 配置页：顶部分类栏（图形/性能/操作/音频/驱动/存储）按 `LaunchCategory`
 * 顺序全量展示，选中分类下按 [settingsEntries] 就地渲染参数行与功能卡 slot，写入与
 * Home 相同的 `filesDir/gow3data/gow3.ini`。音频为预留分类，显示「暂无配置」占位；
 * 存储分类（change redesign-android-launcher-home）就地承载游戏目录与升级补丁目录行，
 * 是首启之后目录修改的常驻位置。[initialCategory] 供 Home 异常态/驱动面板深链定位。
 */
@Composable
internal fun SettingsScreen(
    initialCategory: LaunchCategory,
    losslessDlls: com.gow3.android.data.session.LosslessDllManager,
    onChooseGameFolder: () -> Unit,
    onChooseUpdateFolder: () -> Unit,
    onChangeLanguage: (String) -> Unit,
) {
    val context = LocalContext.current
    // 组合作用域预解析文案（回调/Toast 内不得调用 stringResource）。
    val saveFailedMsg = stringResource(R.string.settings_save_failed)
    val importDoneMsg = stringResource(R.string.frame_gen_import_done)
    // 玩家自备 Lossless.dll 状态（常规分类的导入行；change add-native-frame-generation）。
    var dllState by remember { mutableStateOf(losslessDlls.resolve()) }
    var iniValues by remember { mutableStateOf(loadIni(context)) }
    var gameDir by remember { mutableStateOf(currentGameDir(context)) }
    var updateDir by remember { mutableStateOf(currentUpdateDir(context)) }
    // 游戏身份状态：catalog 或 CatalogProblem，参数过滤与存储行有效性共用同一份解析。
    var identity by remember { mutableStateOf<GameIdentityState>(GameIdentityState.Loading) }
    var catalogRevision by remember { mutableIntStateOf(0) }
    var selected by remember(initialCategory) { mutableStateOf(initialCategory) }
    // prefs 只用于键变化监听；读写一律走 GamePrefs（core/settings 单一真源）。
    val prefs = remember(context) { GamePrefs(context) }

    // 玩家可能在其他 tab 或游戏运行期间改目录，回到前台时重新读取目录与 ini。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, prefs) {
        fun refreshSettings() {
            gameDir = currentGameDir(context)
            updateDir = currentUpdateDir(context)
            iniValues = loadIni(context)
            dllState = losslessDlls.resolve()
            identity = GameIdentityState.Loading
            catalogRevision++
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "game_dir" || key == "update_dir") refreshSettings()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshSettings()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        prefs.raw.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            prefs.raw.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    LaunchedEffect(gameDir, updateDir, catalogRevision) {
        identity = GameIdentityState.Loading
        identity = withContext(Dispatchers.IO) {
            GameIdentity.load(gameDir, updateDir, context.assets::open)
        }
    }

    val catalog = (identity as? GameIdentityState.Ready)?.catalog

    fun saveUpdates(updates: Map<String, String>) {
        if (Gow3Settings.update(gow3IniFile(context), updates)) {
            iniValues = loadIni(context)
        } else {
            Toast.makeText(context, saveFailedMsg, Toast.LENGTH_SHORT).show()
        }
    }

    // 与 Home 预设保存同一契约：补丁类设置（分辨率/引擎帧率）连同 restart_unconfirmed
    // 一起写入；其余键单键写回。
    fun save(key: String, value: String) {
        val param = LaunchParams.forCatalog(catalog).firstOrNull { it.key == key } ?: return
        if (!param.enabled || param.options.none { it.value == value }) return
        saveUpdates(if (LaunchParams.isPatchSetting(key)) mapOf(key to value, "restart_unconfirmed" to "0")
                    else mapOf(key to value))
    }

    ScreenColumn {
        Text(stringResource(R.string.settings_title), color = LauncherColors.textPrimary, fontFamily = FontFamily.Serif,
            fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.settings_subtitle), color = LauncherColors.textMuted, fontSize = 13.sp, lineHeight = 19.sp)
        CategoryTabs(selected) { selected = it }
        val entries = settingsEntries(selected, LaunchParams.forCatalog(catalog))
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.settings_no_settings),
                color = LauncherColors.textMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(LauncherColors.surface)
                    .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                entries.forEach { entry ->
                    when (entry) {
                        is SettingsEntry.Param -> ParamRow(entry.param, iniValues, ::save)
                        is SettingsEntry.Card -> {
                            // 功能卡 slot：内容由对应功能的 change 注册（design D2）。
                            if (entry.slotId == "driver_manager") {
                                DriverManagerCard(
                                    ini = iniValues,
                                    hasGameDir = gameDir != null,
                                    onIniChanged = { iniValues = loadIni(context) },
                                )
                            }
                            // 存储行（redesign-android-launcher-home）：目录修改的常驻位置。
                            if (entry.slotId == "storage") {
                                StorageSection(gameDir, updateDir, identity, onChooseGameFolder, onChooseUpdateFolder)
                            }
                            // 语言入口：点击切换后由 MainActivity 持久化并重建 Activity。
                            if (entry.slotId == "language") {
                                LanguageCard(onChangeLanguage)
                            }
                            // 按键映射（add-pad-button-mapping）：入口卡，列表在 PadMappingActivity。
                            if (entry.slotId == "pad_mapping") {
                                PadMappingCard()
                            }
                        }
                    }
                }
                if (selected == LaunchCategory.GENERAL) {
                    LosslessImportRow(
                        installed = dllState is com.gow3.android.data.session.LosslessDllState.Installed,
                        onImport = {
                            losslessDlls.launchPicker { result ->
                                dllState = losslessDlls.resolve()
                                when (result) {
                                    is com.gow3.android.data.session.ImportResult.Failed ->
                                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                    is com.gow3.android.data.session.ImportResult.Success ->
                                        Toast.makeText(context, importDoneMsg, Toast.LENGTH_SHORT).show()
                                    com.gow3.android.data.session.ImportResult.Cancelled -> {}
                                }
                            }
                        },
                    )
                }
            }
        }
        Text(stringResource(R.string.launch_parameters_footer), color = LauncherColors.textMuted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun CategoryTabs(selected: LaunchCategory, onSelect: (LaunchCategory) -> Unit) {
    // 组合作用域预解析文案（回调/Toast 内不得调用 stringResource）。
    val saveFailedMsg = stringResource(R.string.settings_save_failed)
    val importDoneMsg = stringResource(R.string.frame_gen_import_done)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LaunchParams.categories().forEach { category ->
            val active = category == selected
            Text(
                stringResource(category.titleRes),
                color = if (active) LauncherColors.textPrimary else LauncherColors.textMuted,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (active) LauncherColors.crimson else LauncherColors.navSurface)
                    .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = if (active) 0.6f else 0.35f)), RoundedCornerShape(20.dp))
                    .clickable { onSelect(category) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** launcher 的 gow3.ini 路径，即游戏、run-bionic.sh 和 GameActivity 读取的同一个文件。 */
private fun gow3IniFile(context: Context): File = Gow3Data.config(context)

private fun loadIni(context: Context): Map<String, String> = Gow3Settings.read(gow3IniFile(context))

private fun currentGameDir(context: Context): String? =
    GamePrefs(context).gameDir()

private fun currentUpdateDir(context: Context): String? =
    GamePrefs(context).updateDir()

/**
 * 存储行（spec：android-kotlin-launcher-first-run-game-select「Directory modification
 * after first run lives in Settings」）：就地承载游戏目录与升级补丁目录两行，展示当前
 * 路径（未设置态）与有效性状态，点击走既有目录选择流程（含权限前置）。
 */
@Composable
private fun StorageSection(
    gameDir: String?,
    updateDir: String?,
    identity: GameIdentityState,
    onChooseGame: () -> Unit,
    onChooseUpdate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        com.gow3.android.ui.components.SectionLabel(stringResource(R.string.storage_section_title))
        // 游戏行有效性：升级补丁目录损坏不影响游戏目录本身。
        val gameProblem = (identity as? GameIdentityState.Failed)?.problem?.takeIf { it != CatalogProblem.INVALID_UPDATE }
        StorageRow(
            label = stringResource(R.string.game_folder_label),
            path = gameDir,
            pathPlaceholder = stringResource(R.string.game_folder_not_selected),
            status = when {
                identity is GameIdentityState.Loading -> stringResource(R.string.mods_loading)
                gameProblem != null -> stringResource(GameIdentity.problemMessageRes(gameProblem))
                else -> stringResource(R.string.storage_valid)
            },
            statusValid = identity is GameIdentityState.Ready || gameProblem == null && identity !is GameIdentityState.Loading,
            desc = stringResource(R.string.storage_game_desc),
            onClick = onChooseGame,
        )
        val updateProblem = (identity as? GameIdentityState.Failed)?.problem?.takeIf { it == CatalogProblem.INVALID_UPDATE }
        StorageRow(
            label = stringResource(R.string.update_folder_label),
            path = updateDir,
            pathPlaceholder = stringResource(R.string.update_folder_not_set),
            status = when {
                updateDir == null -> stringResource(R.string.storage_update_desc)
                updateProblem != null -> stringResource(GameIdentity.problemMessageRes(updateProblem))
                identity is GameIdentityState.Ready -> stringResource(R.string.storage_valid)
                else -> stringResource(R.string.mods_loading)
            },
            statusValid = updateProblem == null && identity is GameIdentityState.Ready,
            desc = null,
            onClick = onChooseUpdate,
        )
    }
}

@Composable
private fun StorageRow(
    label: String,
    path: String?,
    pathPlaceholder: String,
    status: String,
    statusValid: Boolean,
    desc: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = LauncherColors.textMuted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (path != null && statusValid) {
                    Text(
                        stringResource(R.string.storage_valid),
                        color = LauncherColors.gold, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(LauncherColors.navSurface)
                            .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.45f)), RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
                Text(stringResource(R.string.change_button), color = LauncherColors.gold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
            }
        }
        Text(
            path ?: pathPlaceholder,
            color = if (path != null) LauncherColors.textPrimary else LauncherColors.textMuted,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, softWrap = false,
        )
        if (desc != null && path == null) {
            Text(desc, color = LauncherColors.textMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        if (path != null && !statusValid) {
            Text(status, color = LauncherColors.textMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}


/**
 * 常规分类的 Lossless.dll 导入行（change add-native-frame-generation）：状态 + IMPORT
 * 按钮。帧生成的供给入口只在设置页（Home 卡片是开关，游戏内抽屉是调参）。
 */
@Composable
private fun LosslessImportRow(installed: Boolean, onImport: () -> Unit) {
    val context = LocalContext.current
    // 组合作用域预解析文案（回调/Toast 内不得调用 stringResource）。
    val saveFailedMsg = stringResource(R.string.settings_save_failed)
    val importDoneMsg = stringResource(R.string.frame_gen_import_done)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.gow3.android.ui.components.SectionLabel(stringResource(R.string.lossless_row_title))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(LauncherColors.navSurface)
                .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.35f)), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                if (installed) stringResource(R.string.lossless_row_installed)
                else stringResource(R.string.lossless_row_missing),
                color = if (installed) LauncherColors.textPrimary else LauncherColors.gold.copy(alpha = 0.85f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.lossless_row_import),
                color = LauncherColors.gold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(LauncherColors.crimson)
                    .clickable { onImport() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
