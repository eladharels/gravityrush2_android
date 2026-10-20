package com.gow3.android.data.driver



import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** 一个 release 里的 zip 驱动资产。 */
internal data class DriverAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/** 一个 release：`tag_name` 即展示版本，附带 zip 资产与发布时间。 */
internal data class DriverRelease(
    val tag: String,
    val publishedAt: String,
    val assets: List<DriverAsset>,
)

/** 一次源 release 列表拉取的结果；失败被折叠成结果而不是异常（与 [UpdateChecker] 同风格）。 */
internal sealed class DriverReleaseResult {
    data class Ok(val releases: List<DriverRelease>) : DriverReleaseResult()

    /** GitHub 未认证限额耗尽，单独成类以便 UI 给出专门文案。 */
    object RateLimited : DriverReleaseResult()

    data class Http(val status: Int) : DriverReleaseResult()

    object Network : DriverReleaseResult()
}

/**
 * 拉取驱动源的 GitHub releases 列表（只读 GET，不下载文件）。
 *
 * 网络策略与 design D5 一致：直连优先，失败且存在镜像前缀时经 `<prefix><apiUrl>` 重试一次；
 * 显式直连（前缀为空）时失败即结束。解析（[parseReleases]）与传输分离，解析可单测。
 */
internal object DriverReleaseClient {

    /** 只展示最近 N 个 release，避免长仓库把列表拖得不可读。 */
    private const val MAX_RELEASES = 10
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    fun listReleases(apiUrl: String, proxyPrefix: String): DriverReleaseResult {
        val direct = fetch(apiUrl)
        if (direct is DriverReleaseResult.Ok) return direct
        if (proxyPrefix.isEmpty()) return direct
        // 镜像前缀对 api.github.com 未必可用，只回退一次，失败即结束（spec：不自动换镜像）。
        return fetch(DriverProxy.apply(apiUrl, proxyPrefix))
    }

    private fun fetch(url: String): DriverReleaseResult {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                // GitHub 拒绝没有 User-Agent 的请求，同时它也是未认证限额的依据。
                setRequestProperty("User-Agent", "gow3-android-launcher")
                setRequestProperty("Accept-Encoding", "identity")
            }
        } catch (_: Exception) {
            return DriverReleaseResult.Network
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                status in 200..299 ->
                    parseReleases(body).let { DriverReleaseResult.Ok(it) }
                isRateLimited(status, body, connection) -> DriverReleaseResult.RateLimited
                else -> DriverReleaseResult.Http(status)
            }
        } catch (_: SocketTimeoutException) {
            DriverReleaseResult.Network
        } catch (_: Exception) {
            DriverReleaseResult.Network
        } finally {
            connection.disconnect()
        }
    }

    /** 与 `UpdateChecker` 相同的限额识别：403/429 且响应表明是 GitHub 速率限额。 */
    internal fun isRateLimited(status: Int, body: String, connection: HttpURLConnection): Boolean {
        if (status != 403 && status != 429) return false
        return connection.getHeaderField("x-ratelimit-remaining") == "0" ||
            body.contains("rate limit", ignoreCase = true)
    }

    /**
     * 解析 releases JSON：取前 [MAX_RELEASES] 个 release，每个只保留 `.zip` 资产，
     * 按 `published_at` 新到旧排序。结构异常的 release / 资产被跳过而不是整体失败。
     */
    internal fun parseReleases(json: String): List<DriverRelease> {
        val array = try {
            JSONArray(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val releases = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val tag = item.optString("tag_name").takeIf { it.isNotBlank() } ?: continue
                val assets = readAssets(item.optJSONArray("assets"))
                add(DriverRelease(tag, item.optString("published_at").orEmpty(), assets))
            }
        }
        // GitHub 本身新到旧返回，这里显式排序以便对 fixture 或乱序响应保持稳定；
        // 没有 zip 资产的 release 对下载入口没有意义，直接不展示。
        return releases.filter { it.assets.isNotEmpty() }
            .sortedByDescending { it.publishedAt }
            .take(MAX_RELEASES)
    }

    private fun readAssets(array: JSONArray?): List<DriverAsset> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val asset = array.optJSONObject(index) ?: continue
                val name = asset.optString("name").orEmpty()
                if (!name.endsWith(".zip", ignoreCase = true)) continue
                val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
                add(DriverAsset(name, url, asset.optLong("size", 0L)))
            }
        }
    }
}
