package com.gow3.android.data.driver

import com.gow3.android.core.settings.Gow3Settings

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DriverManagerTest {

    private lateinit var files: File
    private lateinit var iniFile: File

    @Before
    fun setUp() {
        files = java.nio.file.Files.createTempDirectory("gow3-driver-test").toFile()
        iniFile = File(files, "gow3data/gow3.ini")
        assertTrue(iniFile.parentFile.mkdirs() || iniFile.parentFile.isDirectory)
        assertTrue(Gow3Settings.update(iniFile, mapOf("fps_limit" to "60")))
        files.deleteOnExit()
    }

    @After
    fun tearDown() {
        files.deleteRecursively()
    }

    /** 按安装脚本产出的目录形态构造一个驱动包：库名由 driver.lib 指向。 */
    private fun installDir(id: String, library: String = "libvulkan_adreno.so", withMeta: Boolean = true): File {
        val dir = File(DriverManager.driversDir(files), id)
        assertTrue(dir.mkdirs())
        File(dir, library).writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "driver.lib").writeText("$library\n")
        if (withMeta) {
            File(dir, "meta.json").writeText(
                """{"name":"Turnip Test","libraryName":"$library","driverVersion":"25.1.0","author":"someone"}""",
            )
        }
        return dir
    }

    private fun ini(): Map<String, String> = Gow3Settings.read(iniFile)

    @Test
    fun `scan lists valid package with metadata and normalizes selection`() {
        installDir("turnip-25.1.0")
        assertTrue(DriverManager.select(iniFile, "turnip-25.1.0"))

        val entries = DriverManager.scan(files)
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("turnip-25.1.0", entry.id)
        assertEquals("Turnip Test", entry.name)
        assertEquals("25.1.0", entry.version)
        assertEquals("someone", entry.author)
        assertEquals("libvulkan_adreno.so", entry.library)
        assertTrue(entry.valid)

        assertEquals("turnip-25.1.0", DriverManager.selectedId(ini()))
    }

    @Test
    fun `scan skips hidden tmp dirs and tolerates missing meta json`() {
        installDir("legacy-pack", withMeta = false)
        File(DriverManager.driversDir(files), ".tmp-123").mkdirs()
        File(DriverManager.driversDir(files), "loose.txt").writeText("not a dir")

        val entries = DriverManager.scan(files)
        assertEquals(1, entries.size)
        assertEquals("legacy-pack", entries.single().name)
        assertEquals("libvulkan_adreno.so", entries.single().library)
        assertTrue(entries.single().valid)
    }

    @Test
    fun `packages with missing pointer or missing library are invalid but still listed`() {
        val noPointer = File(DriverManager.driversDir(files), "no-pointer")
        assertTrue(noPointer.mkdirs())
        File(noPointer, "libx.so").writeBytes(byteArrayOf(1))

        val dangling = File(DriverManager.driversDir(files), "dangling")
        assertTrue(dangling.mkdirs())
        File(dangling, "driver.lib").writeText("libgone.so\n")

        val entries = DriverManager.scan(files).associateBy { it.id }
        assertFalse(entries.getValue("no-pointer").valid)
        assertFalse(entries.getValue("dangling").valid)
    }

    @Test
    fun `selection normalization maps system shapes to null`() {
        assertNull(DriverManager.selectedId(emptyMap()))
        for (value in listOf("", "auto", "system", " auto ")) {
            assertTrue(Gow3Settings.update(iniFile, mapOf(DriverManager.INI_KEY to value)))
            assertNull(DriverManager.selectedId(ini()))
        }
        assertTrue(Gow3Settings.update(iniFile, mapOf(DriverManager.INI_KEY to "turnip")))
        assertEquals("turnip", DriverManager.selectedId(ini()))
    }

    @Test
    fun `selecting system driver writes auto and keeps other ini keys`() {
        installDir("turnip")
        assertTrue(DriverManager.select(iniFile, "turnip"))
        assertEquals("turnip", ini()[DriverManager.INI_KEY])

        assertTrue(DriverManager.select(iniFile, null))
        assertEquals("auto", ini()[DriverManager.INI_KEY])
        assertEquals("60", ini()["fps_limit"])
    }

    @Test
    fun `deleting an unselected driver keeps the ini key untouched`() {
        installDir("a")
        installDir("b")
        assertTrue(DriverManager.select(iniFile, "b"))

        val result = DriverManager.delete(files, iniFile, "a")
        assertTrue(result is DriverDeleteResult.Done)
        assertFalse((result as DriverDeleteResult.Done).wasSelected)
        assertFalse(File(DriverManager.driversDir(files), "a").exists())
        assertEquals("b", ini()[DriverManager.INI_KEY])
    }

    @Test
    fun `deleting the selected driver falls back to the system driver`() {
        installDir("a")
        assertTrue(DriverManager.select(iniFile, "a"))

        val result = DriverManager.delete(files, iniFile, "a")
        assertTrue(result is DriverDeleteResult.Done)
        assertTrue((result as DriverDeleteResult.Done).wasSelected)
        assertFalse(File(DriverManager.driversDir(files), "a").exists())
        assertEquals(DriverManager.SYSTEM_VALUE, ini()[DriverManager.INI_KEY])
    }

    @Test
    fun `deleting rejects ids outside the drivers directory`() {
        for (bad in listOf("..", "../..", "sub/dir", ".hidden", "")) {
            val result = DriverManager.delete(files, iniFile, bad)
            assertTrue("rejected: $bad", result is DriverDeleteResult.Rejected)
            assertEquals(DriverDeleteReason.INVALID_ID, (result as DriverDeleteResult.Rejected).reason)
        }
    }

    @Test
    fun `deleting a missing driver reports not found without touching the ini`() {
        assertTrue(DriverManager.select(iniFile, "ghost"))
        val result = DriverManager.delete(files, iniFile, "ghost")
        assertTrue(result is DriverDeleteResult.Rejected)
        assertEquals(DriverDeleteReason.NOT_FOUND, (result as DriverDeleteResult.Rejected).reason)
        assertEquals("ghost", ini()[DriverManager.INI_KEY])
    }

    @Test
    fun `safe id keeps runtime contract shapes`() {
        assertEquals("Turnip-25.1.0", DriverManager.safeId("Turnip 25.1.0", "fallback"))
        assertEquals("a-b-c-d-e_f-c.d", DriverManager.safeId("a/b:c*d?e_f-c.d", "fallback"))
        // 路径穿越形态被清洗成无害 id（不含点前缀与分隔符），而不是原样落盘。
        assertEquals("etc", DriverManager.safeId("../../../etc", "…"))
        // 名称不可用但 zip 主干可用时回退；两者皆空才拒绝。
        assertEquals("pack.zip", DriverManager.safeId("///", "pack.zip"))
        assertNull(DriverManager.safeId("///", "…"))
        // 结果不得以点开头。
        assertEquals("x", DriverManager.safeId("..x", "fallback"))
    }

    @Test
    fun `scan reflects freshly installed directory on the next read`() {
        // 2.6 的契约：无缓存单例，安装完成后调用方重扫即可见。
        assertTrue(DriverManager.scan(files).isEmpty())
        installDir("fresh")
        val ids = DriverManager.scan(files).map { it.id }
        assertEquals(listOf("fresh"), ids)
    }
}
