package com.gow3.android.data.driver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用 JDK 自带的 `com.sun.net.httpserver` 起本地 HTTP 服务做替身（不引新依赖），
 * 覆盖进度回调、取消清理与「直连失败经镜像前缀重试」的网络策略。
 */
class DriverDownloaderTest {

    /** 96KB 的假 zip 内容：跨多个 8KB 块，足以观察进度与中途取消。 */
    private val payload = ByteArray(96 * 1024) { index -> (index % 251).toByte() }

    private val servers = mutableListOf<HttpServer>()
    private val tempDir = java.nio.file.Files.createTempDirectory("gow3-downloader-test").toFile()

    init {
        tempDir.deleteOnExit()
    }

    @After
    fun tearDown() {
        servers.forEach { it.stop(0) }
        servers.clear()
        tempDir.listFiles()?.forEach { it.delete() }
    }

    /** 起一个本地 HTTP 服务，返回 `http://127.0.0.1:<port>` 基址。 */
    private fun startServer(handler: (HttpExchange) -> Unit): String {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/", handler)
        server.start()
        servers.add(server)
        return "http://${InetAddress.getLoopbackAddress().hostName}:${server.address.port}"
    }

    private fun serveBytes(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "application/zip")
        exchange.sendResponseHeaders(200, payload.size.toLong())
        exchange.responseBody.use { it.write(payload) }
    }

    @Test
    fun `download reports monotonic progress and writes the payload`() {
        val base = startServer(::serveBytes)
        val target = File(tempDir, "driver-download-1.zip")
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = DriverDownloader.download("$base/pack.zip", "", target,
            onProgress = { received, total -> progress.add(received to total) },
        )
        assertTrue(result is DriverDownloadResult.Done)
        assertArrayEquals(payload, target.readBytes())
        assertEquals(payload.size.toLong(), progress.last().first)
        assertTrue(progress.all { it.second == payload.size.toLong() || it.second == 0L })
        assertEquals(0L, progress.first().first)
        assertTrue(progress.zipWithNext().all { (a, b) -> a.first <= b.first })
    }

    @Test
    fun `cancel before transfer leaves no target file`() {
        val base = startServer(::serveBytes)
        val target = File(tempDir, "driver-download-2.zip")
        val result = DriverDownloader.download("$base/pack.zip", "", target, isCancelled = { true })
        assertTrue(result is DriverDownloadResult.Cancelled)
        assertFalse(target.exists())
    }

    @Test
    fun `cancel mid download stops and cleans up the temp file`() {
        val base = startServer(::serveBytes)
        val target = File(tempDir, "driver-download-3.zip")
        val receivedCalls = AtomicInteger(0)
        val result = DriverDownloader.download("$base/pack.zip", "", target,
            onProgress = { _, _ -> receivedCalls.incrementAndGet() },
            isCancelled = { receivedCalls.get() >= 2 },
        )
        assertTrue(result is DriverDownloadResult.Cancelled)
        assertFalse(target.exists())
        assertTrue(receivedCalls.get() in 2..3)
    }

    @Test
    fun `direct failure with an explicit direct config ends without retry`() {
        val failing = startServer { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        val target = File(tempDir, "driver-download-4.zip")
        var requests = 0
        val counting = startServer { exchange ->
            requests++
            serveBytes(exchange)
        }
        // 直连指向 500 服务；空前缀 = 显式直连，失败后不得再发任何请求。
        val result = DriverDownloader.download("$failing/pack.zip", "", target)
        assertTrue(result is DriverDownloadResult.Failed)
        assertFalse((result as DriverDownloadResult.Failed).hadProxyRetry)
        assertFalse(target.exists())
        assertEquals(0, requests)
    }

    @Test
    fun `direct failure falls back to the proxy prefix once`() {
        val failing = startServer { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        // 镜像替身：接受 `<prefix><原始URL>` 形态的请求路径（ghproxy 约定）。
        val mirror = startServer { exchange ->
            val path = exchange.requestURI.path.removePrefix("/")
            if (path.startsWith("mirror/http://")) serveBytes(exchange) else {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
        }
        val target = File(tempDir, "driver-download-5.zip")
        val result = DriverDownloader.download("$failing/pack.zip", "$mirror/mirror/", target)
        assertTrue(result is DriverDownloadResult.Done)
        assertArrayEquals(payload, target.readBytes())
    }

    @Test
    fun `failure on both direct and proxy retry reports a single failure`() {
        val failing = startServer { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        val target = File(tempDir, "driver-download-6.zip")
        val result = DriverDownloader.download("$failing/pack.zip", "$failing/mirror/", target)
        assertTrue(result is DriverDownloadResult.Failed)
        assertTrue((result as DriverDownloadResult.Failed).hadProxyRetry)
        assertFalse(target.exists())
    }
}
