package com.gow3.android.feature.settings

import com.gow3.android.ui.components.formatBytes

import com.gow3.android.core.settings.GamePrefs
import com.gow3.android.data.driver.DriverAsset
import com.gow3.android.data.driver.DriverDownloadResult
import com.gow3.android.data.driver.DriverDownloader
import com.gow3.android.data.driver.DriverInstallResult
import com.gow3.android.data.driver.DriverInstaller
import com.gow3.android.data.driver.DriverProxy
import com.gow3.android.data.driver.DriverRelease
import com.gow3.android.data.driver.DriverReleaseClient
import com.gow3.android.data.driver.DriverReleaseResult
import com.gow3.android.data.driver.DriverSource
import com.gow3.android.data.driver.DriverSourceCatalog
import com.gow3.android.ui.components.LauncherColors

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.window.Dialog
import com.gow3.android.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「已下载」标记的持久化：记录成功安装过的资产下载 URL（spec：资产列表的已下载标记）。 */
private object DownloadedMarks {
    fun read(context: android.content.Context): Set<String> =
        GamePrefs(context).downloadedDriverUrls()

    fun mark(context: android.content.Context, url: String) {
        GamePrefs(context).markDriverUrlDownloaded(url)
    }
}

/** 单个源的拉取状态：懒加载、单源失败隔离（spec：单个源失败只影响该源）。 */
private sealed class SourceState {
    data object Loading : SourceState()
    data class Ok(val releases: List<DriverRelease>) : SourceState()
    data class Failed(val reason: FailureReason, val status: Int = 0) : SourceState()
}

private enum class FailureReason { NETWORK, RATE_LIMITED, HTTP }

/** 进行中的下载：单任务（spec：同一时刻只允许一个下载任务）。 */
private data class ActiveDownload(val asset: DriverAsset, val received: Long, val total: Long)

/**
 * 驱动下载对话框（spec：内置驱动源目录、按源拉取 release 驱动资产列表、下载并安装、
 * 下载进度与取消）。源目录来自 assets，离线即可渲染；展开某个源时才经
 * [DriverReleaseClient] 拉取（直连优先、失败经生效镜像前缀重试一次）。
 *
 * 下载 → [DriverInstaller] 安装的整条链都在 IO 线程；取消经协程取消传导给
 * [DriverDownloader] 的轮询点，不产生任何安装残留。安装成功后回调 [onInstalled]
 * 让驱动管理卡片重扫。
 */
@Composable
internal fun DriverDownloadDialog(onDismiss: () -> Unit, onInstalled: () -> Unit) {
    val context = LocalContext.current
    // 组合作用域预解析（回调内不得调用 stringResource）。
    val doneFmt = stringResource(R.string.driver_dl_done_fmt)
    val scope = rememberCoroutineScope()

    var catalog by remember { mutableStateOf<DriverSourceCatalog?>(null) }
    var catalogFailed by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val sourceStates = remember { mutableStateMapOf<String, SourceState>() }
    var download by remember { mutableStateOf<ActiveDownload?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var downloadedUrls by remember { mutableStateOf(DownloadedMarks.read(context)) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { DriverSourceCatalog.load(context.assets::open) }.getOrNull()
        }
        if (loaded == null) catalogFailed = true else catalog = loaded
    }

    fun failureReason(result: DriverReleaseResult): FailureReason = when (result) {
        is DriverReleaseResult.Ok -> FailureReason.NETWORK
        DriverReleaseResult.RateLimited -> FailureReason.RATE_LIMITED
        is DriverReleaseResult.Http -> FailureReason.HTTP
        DriverReleaseResult.Network -> FailureReason.NETWORK
    }

    fun fetch(source: DriverSource) {
        if (sourceStates[source.id] is SourceState.Loading) return
        sourceStates[source.id] = SourceState.Loading
        scope.launch {
            val prefix = DriverProxy.effectivePrefix(
                DriverProxy.stored(context),
                context.resources.configuration.locales[0].language,
            )
            val state = withContext(Dispatchers.IO) { DriverReleaseClient.listReleases(source.apiUrl, prefix) }
            sourceStates[source.id] = when (state) {
                is DriverReleaseResult.Ok -> SourceState.Ok(state.releases)
                DriverReleaseResult.RateLimited -> SourceState.Failed(FailureReason.RATE_LIMITED)
                is DriverReleaseResult.Http -> SourceState.Failed(FailureReason.HTTP, state.status)
                DriverReleaseResult.Network -> SourceState.Failed(FailureReason.NETWORK)
            }
        }
    }

    fun startDownload(asset: DriverAsset) {
        if (download != null) return
        download = ActiveDownload(asset, 0L, 0L)
        job = scope.launch {
            val prefix = DriverProxy.effectivePrefix(
                DriverProxy.stored(context),
                context.resources.configuration.locales[0].language,
            )
            val zip = File(context.cacheDir, "driver-download-${System.currentTimeMillis()}.zip")
            val result = withContext(Dispatchers.IO) {
                DriverDownloader.download(
                    url = asset.downloadUrl,
                    proxyPrefix = prefix,
                    target = zip,
                    onProgress = { received, total ->
                        download = download?.copy(received = received, total = total)
                    },
                    isCancelled = { !isActive },
                )
            }
            when (result) {
                is DriverDownloadResult.Done -> {
                    val install = withContext(Dispatchers.IO) {
                        DriverInstaller.install(context.filesDir, result.file, asset.name.removeSuffix(".zip"))
                    }
                    when (install) {
                        is DriverInstallResult.Success -> {
                            DownloadedMarks.mark(context, asset.downloadUrl)
                            downloadedUrls = DownloadedMarks.read(context)
                            Toast.makeText(
                                context,
                                doneFmt.format(install.name),
                                Toast.LENGTH_SHORT,
                            ).show()
                            onInstalled()
                        }
                        is DriverInstallResult.Failed ->
                            Toast.makeText(context, R.string.driver_dl_failed_install, Toast.LENGTH_LONG).show()
                    }
                }
                DriverDownloadResult.Cancelled -> Unit
                is DriverDownloadResult.Failed ->
                    Toast.makeText(context, R.string.driver_dl_failed_download, Toast.LENGTH_LONG).show()
            }
            download = null
            job = null
        }
    }

    Dialog(onDismissRequest = {
        // 有任务进行中时不允许直接关闭：先取消，保证临时文件被清理（spec）。
        if (download == null) onDismiss()
    }) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(LauncherColors.surface)
                .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.driver_dl_title),
                    color = LauncherColors.gold,
                    fontFamily = FontFamily.Serif,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                )
                if (download == null) {
                    Text(
                        stringResource(R.string.driver_dl_close),
                        color = LauncherColors.textMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            when {
                catalogFailed -> Text(
                    stringResource(R.string.driver_dl_sources_unavailable),
                    color = LauncherColors.textMuted, fontSize = 12.sp, lineHeight = 18.sp,
                )
                catalog == null -> Text(
                    stringResource(R.string.driver_dl_loading),
                    color = LauncherColors.textMuted, fontSize = 12.sp,
                )
                else -> Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    catalog!!.sources.forEach { source ->
                        DriverSourceSection(
                            source = source,
                            state = sourceStates[source.id],
                            expanded = expanded[source.id] == true,
                            downloadedUrls = downloadedUrls,
                            downloadActive = download != null,
                            onToggle = {
                                val next = !(expanded[source.id] == true)
                                expanded[source.id] = next
                                if (next && sourceStates[source.id] == null) fetch(source)
                            },
                            onRetry = { fetch(source) },
                            onDownload = ::startDownload,
                        )
                    }
                }
            }

            download?.let { active ->
                DownloadProgressRow(active) {
                    job?.cancel()
                    job = null
                    download = null
                }
            }
        }
    }
}

@Composable
private fun DriverSourceSection(
    source: DriverSource,
    state: SourceState?,
    expanded: Boolean,
    downloadedUrls: Set<String>,
    downloadActive: Boolean,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onDownload: (DriverAsset) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(LauncherColors.navSurface)
            .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.3f)), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(source.name, color = LauncherColors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    source.description,
                    color = LauncherColors.textMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
            Text(if (expanded) "▾" else "▸", color = LauncherColors.gold, fontSize = 14.sp)
        }

        if (!expanded) return
        when (state) {
            null, is SourceState.Loading -> Text(
                stringResource(R.string.driver_dl_loading),
                color = LauncherColors.textMuted, fontSize = 11.sp,
            )
            is SourceState.Failed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when (state.reason) {
                        FailureReason.RATE_LIMITED -> stringResource(R.string.driver_dl_failed_rate_limit)
                        FailureReason.HTTP -> stringResource(R.string.driver_dl_failed_http_fmt, state.status)
                        FailureReason.NETWORK -> stringResource(R.string.driver_dl_failed_network)
                    },
                    color = LauncherColors.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.driver_dl_retry),
                    color = LauncherColors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            is SourceState.Ok -> {
                if (state.releases.isEmpty()) {
                    Text(
                        stringResource(R.string.driver_dl_empty),
                        color = LauncherColors.textMuted, fontSize = 11.sp,
                    )
                }
                state.releases.forEach { release ->
                    release.assets.forEach { asset ->
                        DriverAssetRow(
                            release = release,
                            asset = asset,
                            downloaded = asset.downloadUrl in downloadedUrls,
                            downloadEnabled = !downloadActive,
                            onDownload = { onDownload(asset) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DriverAssetRow(
    release: DriverRelease,
    asset: DriverAsset,
    downloaded: Boolean,
    downloadEnabled: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LauncherColors.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(asset.name, color = LauncherColors.textPrimary, fontSize = 11.sp, lineHeight = 15.sp)
            Text(
                "${release.tag}  ·  ${if (asset.sizeBytes > 0) formatBytes(asset.sizeBytes) else stringResource(R.string.driver_dl_unknown_size)}" +
                    if (downloaded) "  ·  ${stringResource(R.string.driver_dl_downloaded)}" else "",
                color = if (downloaded) LauncherColors.gold else LauncherColors.textMuted,
                fontSize = 10.sp,
            )
        }
        Text(
            stringResource(R.string.driver_dl_download),
            color = if (downloadEnabled) LauncherColors.textPrimary else LauncherColors.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(LauncherColors.navSurface)
                .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.35f)), RoundedCornerShape(6.dp))
                .clickable(enabled = downloadEnabled, onClick = onDownload)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DownloadProgressRow(active: ActiveDownload, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(
                R.string.driver_dl_downloading_fmt,
                active.asset.name,
                formatBytes(active.received),
                if (active.total > 0) formatBytes(active.total) else stringResource(R.string.driver_dl_unknown_size),
            ),
            color = LauncherColors.textPrimary,
            fontSize = 11.sp,
        )
        Box {
            if (active.total > 0) {
                LinearProgressIndicator(
                    progress = { (active.received.toDouble() / active.total).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = LauncherColors.crimson,
                    trackColor = LauncherColors.navSurface,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = LauncherColors.crimson,
                    trackColor = LauncherColors.navSurface,
                )
            }
        }
        Text(
            stringResource(R.string.driver_dl_cancel),
            color = LauncherColors.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onCancel)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
