package com.gow3.android.feature.mods

import com.gow3.android.ui.components.ActionButton
import com.gow3.android.ui.components.EmptyState
import com.gow3.android.ui.components.ScreenColumn

import com.gow3.android.core.settings.GamePrefs
import com.gow3.android.core.settings.Gow3Settings
import com.gow3.android.data.CatalogProblem
import com.gow3.android.data.GamePatch
import com.gow3.android.data.Gow3Data
import com.gow3.android.data.PatchCatalog
import com.gow3.android.data.PatchCatalogException
import com.gow3.android.data.PatchGroup
import com.gow3.android.ui.components.LauncherColors

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gow3.android.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ModsScreen() {
    val context = LocalContext.current
    val prefs = remember(context) { GamePrefs(context) }
    var revision by remember { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf<PatchCatalog?>(null) }
    var ini by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<CatalogProblem?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun load(): PatchCatalog = PatchCatalog.load(
        prefs.gameDir()?.let(::File),
        prefs.updateDir()?.takeIf { it.isNotBlank() }?.let(::File),
        context.assets::open,
    )

    DisposableEffect(prefs, lifecycle) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "game_dir" || key == "update_dir") revision++
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        prefs.raw.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            prefs.raw.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(revision) {
        loading = true
        catalog = null
        problem = null
        notice = null
        val result = withContext(Dispatchers.IO) { runCatching { load() to Gow3Settings.read(Gow3Data.config(context)) } }
        result.onSuccess { (loaded, values) -> catalog = loaded; ini = values }
            .onFailure { problem = (it as? PatchCatalogException)?.problem ?: CatalogProblem.READ_FAILED }
        loading = false
    }

    fun toggle(current: PatchCatalog, patch: GamePatch, enabled: Boolean) {
        saving = true
        notice = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 写入前重新读配置和身份，避免覆盖首页刚保存的值或写到另一游戏。
                    check(load().selectionKey == current.selectionKey)
                    val file = Gow3Data.config(context)
                    check(Gow3Settings.update(file, current.toggle(Gow3Settings.read(file), patch, enabled)))
                    Gow3Settings.read(file)
                }
            }
            result.onSuccess { values ->
                if (catalog?.selectionKey == current.selectionKey) ini = values
                notice = R.string.mods_saved
            }.onFailure { notice = R.string.settings_save_failed }
            saving = false
        }
    }

    ScreenColumn {
        Text(stringResource(R.string.mods_title), color = LauncherColors.textPrimary, fontFamily = FontFamily.Serif,
            fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.mods_description), color = LauncherColors.textMuted, fontSize = 13.sp, lineHeight = 19.sp)
        notice?.let { Text(stringResource(it), color = LauncherColors.gold, fontSize = 12.sp) }
        if (loading) {
            CircularProgressIndicator(color = LauncherColors.gold)
            Text(stringResource(R.string.mods_loading), color = LauncherColors.textMuted)
        } else if (problem != null) {
            val message = when (problem) {
                CatalogProblem.NO_GAME -> R.string.mods_no_game
                CatalogProblem.INVALID_GAME -> R.string.mods_invalid_game
                CatalogProblem.INVALID_UPDATE -> R.string.mods_invalid_update
                CatalogProblem.UNSUPPORTED -> R.string.mods_unsupported
                CatalogProblem.VERSION -> R.string.mods_version
                else -> R.string.mods_read_failed
            }
            EmptyState(stringResource(R.string.mods_unavailable), stringResource(message))
            ActionButton(stringResource(R.string.mods_retry), primary = false) { revision++ }
        } else {
            catalog?.let { current ->
                val selected = current.selected(ini)
                Surface(color = LauncherColors.navSurface, shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.35f))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${current.titleId} · ${current.appVersion}", color = LauncherColors.gold, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.mods_count, current.patches.size, selected.size), color = LauncherColors.textPrimary, fontSize = 13.sp)
                        Text(stringResource(R.string.mods_next_launch), color = LauncherColors.textMuted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
                for (group in PatchGroup.entries) {
                    val entries = current.patches.filter { it.group == group }
                    if (entries.isNotEmpty()) {
                        val label = when (group) {
                            PatchGroup.RESOLUTION -> R.string.mods_group_resolution
                            PatchGroup.FRAME_RATE -> R.string.mods_group_fps
                            PatchGroup.VIDEO -> R.string.mods_group_video
                            PatchGroup.FIX -> R.string.mods_group_fixes
                            PatchGroup.EFFECT -> R.string.mods_group_effects
                        }
                        Text(stringResource(label), color = LauncherColors.gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        if (group == PatchGroup.RESOLUTION || group == PatchGroup.FRAME_RATE) {
                            Text(stringResource(R.string.mods_exclusive), color = LauncherColors.textMuted, fontSize = 12.sp)
                        }
                        entries.forEach { patch ->
                            PatchCard(patch, patch.name in selected, !saving) { enabled -> toggle(current, patch, enabled) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchCard(patch: GamePatch, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    var details by remember(patch.name) { mutableStateOf(false) }
    Surface(color = LauncherColors.surface, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LauncherColors.gold.copy(alpha = if (checked) 0.65f else 0.2f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(patch.displayName, color = LauncherColors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    val status = when (patch.validation) {
                        "android-videoout-verified" -> R.string.mods_verified_device
                        "offline-matched" -> R.string.mods_verified_offline
                        "pending-title-sample" -> R.string.mods_pending_sample
                        else -> R.string.mods_legacy
                    }
                    Text(stringResource(status), color = LauncherColors.textMuted, fontSize = 11.sp)
                }
                Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = patch.displayName },
                    colors = SwitchDefaults.colors(checkedTrackColor = LauncherColors.crimson, checkedThumbColor = LauncherColors.gold))
            }
//            TextButton(onClick = { details = !details }) {
//                Text(stringResource(if (details) R.string.mods_hide_details else R.string.mods_details), color = LauncherColors.gold, fontSize = 12.sp)
//            }
//            if (details) {
//                Text(patch.note, color = LauncherColors.textMuted, fontSize = 12.sp, lineHeight = 18.sp)
//                Text(stringResource(R.string.mods_author, patch.author), color = LauncherColors.textMuted, fontSize = 11.sp)
//            }
        }
    }
}
