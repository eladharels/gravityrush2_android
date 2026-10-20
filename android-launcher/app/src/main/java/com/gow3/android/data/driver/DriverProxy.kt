package com.gow3.android.data.driver

import com.gow3.android.core.settings.GamePrefs

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * 驱动下载网络加速（design D5/D6）：ghproxy 风格镜像前缀。
 *
 * 三级生效前缀解析（与参考工具 DriverScope 同序）：显式直连哨兵 → 已存储选择 →
 * 语言默认（系统语言 `zh` 开头用 [DEFAULT_MIRROR]，否则直连）。语言默认不落盘，
 * 保持未选择时跟随系统；玩家显式选择（含直连）才持久化。
 *
 * 使用策略（[DriverReleaseClient] / [DriverDownloader] 共享）：直连优先，失败且生效
 * 前缀非空时经前缀重试一次；显式直连时前缀为空、失败即结束，MUST NOT 代理请求。
 */
internal object DriverProxy {

    /** 「显式直连」的存储哨兵：与「未选择」区分，直连选择同样要持久化。 */
    const val NONE = "__none__"

    /** 中文环境（未选择时）的默认镜像。 */
    const val DEFAULT_MIRROR = "https://gh-proxy.com/"

    /** 内置镜像表：实现时做过连通性抽查，可用性会漂移，测速给玩家事实依据。 */
    val BUILT_IN: List<String> = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghproxy.homeboyc.cn/",
        "https://github.akams.cn/",
    )

    /** ghproxy 约定：直接把原始 URL 拼在前缀之后；空前缀即原始 URL。前缀补全结尾 `/`。 */
    fun apply(url: String, prefix: String): String =
        if (prefix.isEmpty()) url else normalize(prefix) + url

    /**
     * 纯解析：[stored] 为 prefs 中的原始值（null/空白 = 未选择，[NONE] = 显式直连，
     * 其他 = 前缀）；[languageTag] 为系统首选语言。返回生效前缀（空串 = 直连）。
     */
    fun effectivePrefix(stored: String?, languageTag: String?): String = when {
        stored == null || stored.isBlank() -> languageDefault(languageTag)
        stored == NONE -> ""
        else -> normalize(stored)
    }

    /** 语言默认不落盘：中文环境默认镜像，非中文直连。 */
    fun languageDefault(languageTag: String?): String =
        if (languageTag?.startsWith("zh", ignoreCase = true) == true) DEFAULT_MIRROR else ""

    /** 自定义前缀补全结尾的 `/`，保证拼接形态与内置镜像一致。 */
    fun normalize(prefix: String): String =
        if (prefix.endsWith('/')) prefix else "$prefix/"

    fun stored(context: Context): String? = GamePrefs(context).driverProxyRaw()

    /** 显式选择才落盘：传 [NONE] 或具体前缀；用 [clear] 恢复「未选择」。 */
    fun save(context: Context, raw: String) {
        GamePrefs(context).setDriverProxyRaw(raw)
    }

    fun clear(context: Context) {
        GamePrefs(context).clearDriverProxy()
    }

    /**
     * 对一个镜像前缀做一次轻量 GET 测速，返回 RTT 毫秒；失败返回 null。
     * 结果只用于镜像选择对话框的展示，不自动选优。
     */
    fun speedTest(prefix: String, testUrl: String = SPEED_TEST_URL): Long? {
        val connection = try {
            (URL(apply(testUrl, prefix)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "gow3-android-launcher")
            }
        } catch (_: Exception) {
            return null
        }
        val started = System.currentTimeMillis()
        return try {
            val status = connection.responseCode
            if (status !in 200..299) return null
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8 * 1024)
                while (stream.read(buffer) != -1) {
                    // 读一小段即可证明可达，不必拉完整文件。
                }
            }
            System.currentTimeMillis() - started
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private const val SPEED_TEST_URL = "https://raw.githubusercontent.com/torvalds/linux/master/README"
}
