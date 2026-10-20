package com.gow3.android.ui

import com.gow3.android.feature.about.AboutScreen
import com.gow3.android.feature.home.HomeScreen
import com.gow3.android.feature.mods.ModsScreen
import com.gow3.android.feature.settings.SettingsScreen

import com.gow3.android.data.model.LaunchCategory
import com.gow3.android.data.session.LogManager
import com.gow3.android.data.session.LosslessDllManager
import com.gow3.android.data.session.SaveManager
import com.gow3.android.ui.components.LauncherColors

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gow3.android.R

private enum class LauncherTab { HOME, SETTINGS, MODS, ABOUT }

/**
 * 宽屏断点（design 决策 1）：内容宽度不小于该值时导航从底部药丸切换为左侧竖栏，
 * Home 同步切换为双栏构图。用 `BoxWithConstraints` 而非 WindowSizeClass，避免新增依赖。
 */
private val WIDE_BREAKPOINT = 600.dp

@Composable
internal fun LauncherScreen(
    onStartGame: () -> Unit,
    losslessDlls: LosslessDllManager,
    onGetFromSteam: ((Boolean, String) -> Unit) -> Unit,
    onChooseGameFolder: () -> Unit,
    onChooseUpdateFolder: () -> Unit,
    saves: SaveManager,
    logs: LogManager,
    onChangeLanguage: (String) -> Unit,
) {
    // tab 与 Settings 初始分类用 rememberSaveable：旋转/分屏重建 Activity 后状态不丢
    //（design 决策 3——横竖屏自适应落地后这是从隐性变显性的既有缺陷）。
    var selectedTab by rememberSaveable { mutableIntStateOf(LauncherTab.HOME.ordinal) }
    var settingsCategory by rememberSaveable { mutableStateOf(LaunchCategory.DEFAULT.name) }
    val tab = LauncherTab.entries[selectedTab]

    // Home 异常态 / 驱动面板的深链：切到 Settings tab 并选中指定分类。
    fun openSettings(category: LaunchCategory) {
        settingsCategory = category.name
        selectedTab = LauncherTab.SETTINGS.ordinal
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_BREAKPOINT
        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                SideRail(tab) { selectedTab = it.ordinal }
                // 竖栏与内容之间的金色细分隔线。
                Spacer(
                    Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(LauncherColors.gold.copy(alpha = 0.2f)),
                )
                Box(modifier = Modifier.weight(1f)) {
                    ScreenBackground()
                    TabContent(
                        tab = tab,
                        wide = true,
                        settingsCategory = settingsCategory,
                        onStartGame = onStartGame,
                        losslessDlls = losslessDlls,
                        onGetFromSteam = onGetFromSteam,
                        onChooseGameFolder = onChooseGameFolder,
                        onChooseUpdateFolder = onChooseUpdateFolder,
                        saves = saves,
                        logs = logs,
                        onChangeLanguage = onChangeLanguage,
                        onOpenStorageCategory = { openSettings(LaunchCategory.GENERAL) },
                        onManageDrivers = { openSettings(LaunchCategory.DRIVER) },
                    )
                }
            }
        } else {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = { BottomNavigation(tab) { selectedTab = it.ordinal } },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    ScreenBackground()
                    TabContent(
                        tab = tab,
                        wide = false,
                        settingsCategory = settingsCategory,
                        onStartGame = onStartGame,
                        losslessDlls = losslessDlls,
                        onGetFromSteam = onGetFromSteam,
                        onChooseGameFolder = onChooseGameFolder,
                        onChooseUpdateFolder = onChooseUpdateFolder,
                        saves = saves,
                        logs = logs,
                        onChangeLanguage = onChangeLanguage,
                        onOpenStorageCategory = { openSettings(LaunchCategory.GENERAL) },
                        onManageDrivers = { openSettings(LaunchCategory.DRIVER) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenBackground() {
    GameArtBackground(modifier = Modifier.fillMaxSize())
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(LauncherColors.backgroundTop.copy(alpha = 0.55f), LauncherColors.backgroundBottom.copy(alpha = 0.85f)), radius = 950f)),
    )
}

@Composable
private fun TabContent(
    tab: LauncherTab,
    wide: Boolean,
    settingsCategory: String,
    onStartGame: () -> Unit,
    losslessDlls: LosslessDllManager,
    onGetFromSteam: ((Boolean, String) -> Unit) -> Unit,
    onChooseGameFolder: () -> Unit,
    onChooseUpdateFolder: () -> Unit,
    saves: SaveManager,
    logs: LogManager,
    onChangeLanguage: (String) -> Unit,
    onOpenStorageCategory: () -> Unit,
    onManageDrivers: () -> Unit,
) {
    when (tab) {
        LauncherTab.HOME -> HomeScreen(
            onStartGame = onStartGame,
            losslessDlls = losslessDlls,
            onGetFromSteam = onGetFromSteam,
            onOpenStorageCategory = onOpenStorageCategory,
            onManageDrivers = onManageDrivers,
            saves = saves,
            wide = wide,
        )
        LauncherTab.SETTINGS -> SettingsScreen(
            initialCategory = LaunchCategory.fromSaved(settingsCategory),
            losslessDlls = losslessDlls,
            onChooseGameFolder = onChooseGameFolder,
            onChooseUpdateFolder = onChooseUpdateFolder,
            onChangeLanguage = onChangeLanguage,
        )
        LauncherTab.MODS -> ModsScreen()
        LauncherTab.ABOUT -> AboutScreen(saves, logs)
    }
}

/** 窄屏底部药丸导航：四个固定 tab。 */
@Composable
private fun BottomNavigation(selectedTab: LauncherTab, onTabSelected: (LauncherTab) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(LauncherColors.backgroundBottom),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .background(LauncherColors.backgroundBottom)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(LauncherColors.navSurface)
                .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.35f)), RoundedCornerShape(28.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavItem(LauncherTab.HOME, selectedTab == LauncherTab.HOME, stringResource(R.string.nav_home), "⌂", onTabSelected)
            NavItem(LauncherTab.SETTINGS, selectedTab == LauncherTab.SETTINGS, stringResource(R.string.nav_settings), "⚙", onTabSelected)
            NavItem(LauncherTab.MODS, selectedTab == LauncherTab.MODS, stringResource(R.string.nav_mods), "☰", onTabSelected)
            NavItem(LauncherTab.ABOUT, selectedTab == LauncherTab.ABOUT, stringResource(R.string.nav_about), "ⓘ", onTabSelected)
        }
    }

}

/**
 * 宽屏左侧竖栏（design 决策 2）：自绘而非 material3 NavigationRail——底部药丸本就是
 * 自绘组件，自绘竖栏保证两种导航形态视觉同源，也不依赖 BOM 中 NavigationRail 的默认形态。
 */
@Composable
private fun SideRail(selectedTab: LauncherTab, onTabSelected: (LauncherTab) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(84.dp)
            .background(LauncherColors.backgroundBottom)
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RailItem(LauncherTab.HOME, selectedTab == LauncherTab.HOME, stringResource(R.string.nav_home), "⌂", onTabSelected)
        Spacer(Modifier.height(10.dp))
        RailItem(LauncherTab.SETTINGS, selectedTab == LauncherTab.SETTINGS, stringResource(R.string.nav_settings), "⚙", onTabSelected)
        Spacer(Modifier.height(10.dp))
        RailItem(LauncherTab.MODS, selectedTab == LauncherTab.MODS, stringResource(R.string.nav_mods), "☰", onTabSelected)
        Spacer(Modifier.height(10.dp))
        RailItem(LauncherTab.ABOUT, selectedTab == LauncherTab.ABOUT, stringResource(R.string.nav_about), "ⓘ", onTabSelected)
    }
}

@Composable
private fun RailItem(
    tab: LauncherTab,
    selected: Boolean,
    label: String,
    icon: String,
    onTabSelected: (LauncherTab) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) LauncherColors.crimson else Color.Transparent)
            .border(
                BorderStroke(1.dp, LauncherColors.gold.copy(alpha = if (selected) 0.6f else 0f)),
                RoundedCornerShape(18.dp),
            )
            .clickable { onTabSelected(tab) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(icon, color = if (selected) LauncherColors.textPrimary else LauncherColors.textMuted, fontSize = 17.sp, lineHeight = 17.sp)
        Text(label, color = if (selected) LauncherColors.textPrimary else LauncherColors.textMuted, fontSize = 9.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.3.sp, maxLines = 1)
    }
}

@Composable
private fun RowScope.NavItem(
    tab: LauncherTab,
    selected: Boolean,
    label: String,
    icon: String,
    onTabSelected: (LauncherTab) -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (selected) LauncherColors.crimson else Color.Transparent)
            .clickable { onTabSelected(tab) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(icon, color = if (selected) LauncherColors.textPrimary else LauncherColors.textMuted, fontSize = 17.sp, lineHeight = 17.sp)
        Text(label, color = if (selected) LauncherColors.textPrimary else LauncherColors.textMuted, fontSize = 9.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.3.sp, maxLines = 1)
    }
}
