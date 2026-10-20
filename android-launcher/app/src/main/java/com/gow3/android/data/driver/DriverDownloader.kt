package com.gow3.android.data.driver

import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** 一次驱动 zip 下载的结局；失败被折叠成结果而不是异常，UI 只需穷举分支。 */
internal sealed class DriverDownloadResult {
    data class Done(val file: File) : DriverDownloadResult()

    /** 玩家取消：临时文件已删除。 */
    data object Cancelled : DriverDownloadResult()

    /** 直连与镜像重试（若有）都失败，临时文件已删除。 */
    data class Failed(val hadProxyRetry: Boolean) : DriverDownloadResult()
}

/**
 * 驱动 zip 的单流下载（design D5：驱动包 < 100MB，不做分片/断点续传）。
 *
 * 网络策略与 release 列表一致：直连优先，失败且 [proxyPrefix] 非空时经
 * `<prefix><url>` 重试一次；显式直连（空前缀）失败即结束。取消以 [isCancelled]
 * 轮询（协程取消时调用方传 `{ !job.isActive }`），取消或失败都删除临时文件，
 * 不产生半下载残留（spec：取消后 MUST NOT 产生任何驱动安装、临时文件被清理）。
 */
internal object DriverDownloader {

    private const val BUFFER_BYTES = 8 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * 下载到 [target]（调用方传入 `cacheDir/driver-download-<ts>.zip` 形态的路径）。
     * [onProgress] 收到 `(已接收字节, 总字节)`；总字节未知时为 0。必须在 IO 线程调用。
     */
    fun download(
        url: String,
        proxyPrefix: String,
        target: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): DriverDownloadResult {
        target.parentFile?.mkdirs()
        when (val direct = transfer(url, target, onProgress, isCancelled)) {
            is Transfer.Completed -> return DriverDownloadResult.Done(direct.file)
            is Transfer.Cancelled -> {
                target.delete()
                return DriverDownloadResult.Cancelled
            }
            is Transfer.Failure -> Unit
        }
        // 直连失败：显式直连（空前缀）即结束；有镜像前缀时经前缀重试一次（spec：不自动换镜像）。
        if (proxyPrefix.isEmpty() || isCancelled()) {
            target.delete()
            return DriverDownloadResult.Failed(hadProxyRetry = false)
        }
        val retried = transfer(DriverProxy.apply(url, proxyPrefix), target, onProgress, isCancelled)
        if (retried is Transfer.Completed) return DriverDownloadResult.Done(retried.file)
        target.delete()
        return if (retried is Transfer.Cancelled) DriverDownloadResult.Cancelled
        else DriverDownloadResult.Failed(hadProxyRetry = true)
    }

    private sealed class Transfer {
        data class Completed(val file: File) : Transfer()
        data object Cancelled : Transfer()
        data object Failure : Transfer()
    }

    private fun transfer(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Transfer {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "gow3-android-launcher")
            }
        } catch (_: Exception) {
            return Transfer.Failure
        }
        try {
            if (isCancelled()) return Transfer.Cancelled
            val status = connection.responseCode
            if (status !in 200..299) return Transfer.Failure
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            try {
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var received = 0L
                        onProgress(0L, total)
                        while (true) {
                            if (isCancelled()) return Transfer.Cancelled
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            received += read
                            onProgress(received, total)
                        }
                        output.flush()
                    }
                }
            } catch (_: SocketTimeoutException) {
                return Transfer.Failure
            } catch (_: Exception) {
                // 写盘/读流中断：若同时请求了取消，按取消归类，让 UI 走清理分支。
                return if (isCancelled()) Transfer.Cancelled else Transfer.Failure
            }
            if (!target.isFile || target.length() == 0L) return Transfer.Failure
            return Transfer.Completed(target)
        } catch (_: Exception) {
            return Transfer.Failure
        } finally {
            connection.disconnect()
        }
    }
}
