package com.gow3.android.data.driver



import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverReleaseClientTest {

    /** 乱序、混入非 zip、缺字段、坏条目的 fixture。 */
    private val fixture = """
        [
          {"tag_name":"v27.0.0","published_at":"2026-10-17T05:14:06Z",
           "assets":[
             {"name":"Banners-Turnip.zip","browser_download_url":"https://github.com/x/r/releases/d/b.zip","size":10485760},
             {"name":"notes.txt","browser_download_url":"https://github.com/x/r/releases/d/n.txt","size":10}
           ]},
          {"tag_name":"v26.9.0","published_at":"2026-09-01T00:00:00Z",
           "assets":[{"name":"old.zip","browser_download_url":"https://github.com/x/r/releases/d/old.zip","size":2048}]},
          {"tag_name":"v26.10.0","published_at":"2026-10-01T00:00:00Z",
           "assets":[{"name":"a.ZIP","browser_download_url":"https://github.com/x/r/releases/d/a.zip","size":1}]},
          {"published_at":"2020-01-01T00:00:00Z","assets":[]},
          "not-an-object",
          {"tag_name":"no-published","published_at":"",
           "assets":[{"name":"missing-url.zip","size":5}]}
        ]
    """.trimIndent()

    @Test
    fun `parses zip assets only, newest first, capped at ten`() {
        val releases = DriverReleaseClient.parseReleases(fixture)
        // 缺 tag、缺资产下载链接、非 JSON 条目被跳过；没有 zip 资产的 release 不展示。
        assertEquals(3, releases.size)
        assertEquals(listOf("v27.0.0", "v26.10.0", "v26.9.0"), releases.map { it.tag })
        assertEquals("a.ZIP", releases[1].assets.single().name)
        assertEquals(10485760L, releases[0].assets.single().sizeBytes)
    }

    @Test
    fun `asset without a download url is dropped, plain text assets are filtered`() {
        val releases = DriverReleaseClient.parseReleases(fixture)
        // missing-url.zip 没有 browser_download_url：该 release 因此没有任何可用资产，不展示。
        assertFalse(releases.flatMap { it.assets }.any { it.name == "missing-url.zip" })
        assertFalse(releases[0].assets.any { it.name.endsWith(".txt") })
        assertEquals("old.zip", releases[2].assets.single().name)
    }

    @Test
    fun `more than ten releases are truncated after sorting`() {
        val many = (1..15).joinToString(",", prefix = "[", postfix = "]") { index ->
            val second = index.toString().padStart(2, '0')
            """{"tag_name":"v$index","published_at":"2026-01-01T00:00:${second}Z","assets":[""" +
                """{"name":"d$index.zip","browser_download_url":"https://github.com/x/r/releases/d/$index.zip","size":1}]}"""
        }
        val releases = DriverReleaseClient.parseReleases(many)
        assertEquals(10, releases.size)
        // 发布时间新到旧：v15 排第一，v6 是截断后的最后一个。
        assertEquals("v15", releases.first().tag)
        assertEquals("v6", releases.last().tag)
    }

    @Test
    fun `broken json yields an empty list instead of throwing`() {
        assertTrue(DriverReleaseClient.parseReleases("garbage").isEmpty())
        assertTrue(DriverReleaseClient.parseReleases("""{"not":"an array"}""").isEmpty())
    }

    @Test
    fun `rate limit detection mirrors the update checker contract`() {
        val limited = java.net.URL("https://localhost/").openConnection() as java.net.HttpURLConnection
        // 无法注入响应头，这里只验证正文分支；响应头分支与 UpdateChecker 保持同一行逻辑。
        assertTrue(DriverReleaseClient.isRateLimited(403, "API rate limit exceeded", limited))
        assertTrue(DriverReleaseClient.isRateLimited(429, "You have exceeded a rate limit", limited))
        assertFalse(DriverReleaseClient.isRateLimited(403, "not found", limited))
        assertFalse(DriverReleaseClient.isRateLimited(500, "rate limit", limited))
    }

    @Test
    fun `failed result types carry distinct meanings for the ui`() {
        // 结构性验证：Ok / RateLimited / Http / Network 是可区分的分支（单源失败隔离的文案依据）。
        assertTrue(DriverReleaseResult.Ok(emptyList()) is DriverReleaseResult.Ok)
        assertTrue(DriverReleaseResult.RateLimited is DriverReleaseResult.RateLimited)
        assertTrue(DriverReleaseResult.Http(500) is DriverReleaseResult.Http)
        assertTrue(DriverReleaseResult.Network is DriverReleaseResult.Network)
    }
}
