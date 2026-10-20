package com.gow3.android.data.driver

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverSourceCatalogTest {

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `parse reads a well formed catalog`() {
        val json = """
            {"sources":[
              {"id":"banners-turnip","name":"Banners Turnip","description":"daily builds",
               "apiUrl":"https://api.github.com/repos/The412Banner/Banners-Turnip/releases",
               "repoUrl":"https://github.com/The412Banner/Banners-Turnip","gpuVendor":"qualcomm"},
              {"id":"diskdvd-turnip-a8xx","name":"Turnip A8XX","description":"Adreno 8xx variant",
               "apiUrl":"https://api.github.com/repos/DiskDVD/TurniptoolsA8XX/releases",
               "repoUrl":"https://github.com/DiskDVD/TurniptoolsA8XX","gpuVendor":"qualcomm"}
            ]}
        """.trimIndent()
        val catalog = DriverSourceCatalog.parse(json)
        assertNotNull(catalog)
        assertEquals(2, catalog!!.sources.size)
        val first = catalog.sources.first()
        assertEquals("banners-turnip", first.id)
        assertEquals("Banners Turnip", first.name)
        assertEquals("daily builds", first.description)
        assertEquals("https://github.com/The412Banner/Banners-Turnip", first.repoUrl)
        assertEquals("qualcomm", first.gpuVendor)
    }

    @Test
    fun `entries missing required fields are skipped, display fields tolerate blanks`() {
        val json = """
            {"sources":[
              {"id":"no-api","name":"broken"},
              {"apiUrl":"https://api.example.com/releases"},
              {"id":"minimal","apiUrl":"https://api.example.com/minimal/releases"}
            ]}
        """.trimIndent()
        val catalog = DriverSourceCatalog.parse(json)
        assertNotNull(catalog)
        val only = catalog!!.sources.single()
        assertEquals("minimal", only.id)
        assertEquals("minimal", only.name)
        assertEquals("", only.description)
        assertEquals("", only.repoUrl)
        assertEquals("", only.gpuVendor)
    }

    @Test
    fun `broken json and missing sources array degrade to null`() {
        assertNull(DriverSourceCatalog.parse("not json at all"))
        assertNull(DriverSourceCatalog.parse("""{"releases":[]}"""))
        assertNull(DriverSourceCatalog.parse("""{"sources": "nope"}"""))
    }

    @Test
    fun `load closes the stream and propagates read failures as null`() {
        val catalog = DriverSourceCatalog.load { key ->
            assertEquals(DriverSourceCatalog.ASSET_NAME, key)
            stream("""{"sources":[{"id":"s","apiUrl":"https://api.example.com/s/releases"}]}""")
        }
        assertEquals(1, catalog!!.sources.size)

        assertNull(DriverSourceCatalog.load { throw java.io.IOException("missing asset") })
        assertNull(DriverSourceCatalog.load { stream("garbage") })
    }

    @Test
    fun `bundled asset parses and only lists turnip sources`() {
        // 与 APK 内打包的 assets 保持一致：真实文件必须可解析且只收 Turnip/Adreno。
        val asset = java.io.File("src/main/assets/${DriverSourceCatalog.ASSET_NAME}")
            .takeIf { it.isFile }
            ?: return
        val catalog = DriverSourceCatalog.parse(asset.readText())
        assertNotNull(catalog)
        assertTrue(catalog!!.sources.size >= 4)
        assertTrue(catalog.sources.all { it.apiUrl.startsWith("https://api.github.com/repos/") })
        assertTrue(catalog.sources.all { it.gpuVendor == "qualcomm" })
    }
}
