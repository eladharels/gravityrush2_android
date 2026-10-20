package com.gow3.android.data.driver

import com.gow3.android.core.settings.Gow3Settings


import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DriverInstallerTest {

    private lateinit var files: File
    private lateinit var cache: File

    @Before
    fun setUp() {
        files = java.nio.file.Files.createTempDirectory("gow3-installer-files").toFile()
        cache = java.nio.file.Files.createTempDirectory("gow3-installer-cache").toFile()
        files.deleteOnExit()
        cache.deleteOnExit()
    }

    @After
    fun tearDown() {
        files.deleteRecursively()
        cache.deleteRecursively()
    }

    /** 在临时 zip 内写一个条目。 */
    private fun zipEntry(zip: ZipOutputStream, name: String, content: ByteArray = ByteArray(16) { (it % 7).toByte() }) {
        zip.putNextEntry(ZipEntry(name))
        if (!name.endsWith('/')) zip.write(content)
        zip.closeEntry()
    }

    private fun newZip(build: (ZipOutputStream) -> Unit): File {
        val zip = File(cache, "pack.zip")
        ZipOutputStream(FileOutputStream(zip)).use(build)
        return zip
    }

    private fun goodZip(
        metaName: String = "Turnip Test",
        library: String = "libvulkan_adreno.so",
        metaLibrary: String? = library,
    ): File = newZip { zip ->
        zipEntry(zip, "libturnip_common.so")
        zipEntry(zip, library)
        zipEntry(
            zip, "meta.json",
            buildString {
                append('{')
                append("\"name\":\"$metaName\"")
                if (metaLibrary != null) append(",\"libraryName\":\"$metaLibrary\"")
                append(",\"driverVersion\":\"25.1.0\",\"author\":\"someone\"")
                append('}')
            }.toByteArray(),
        )
    }

    @Test
    fun `installing a well formed pack writes pointer and is immediately scannable`() {
        val result = DriverInstaller.install(files, goodZip(), "pack.zip")
        assertTrue("$result", result is DriverInstallResult.Success)
        val success = result as DriverInstallResult.Success
        assertEquals("Turnip-Test", success.id)
        assertEquals("Turnip Test", success.name)
        assertEquals("25.1.0", success.version)
        assertEquals("libvulkan_adreno.so", success.library)

        val dir = File(DriverManager.driversDir(files), "Turnip-Test")
        assertEquals("libvulkan_adreno.so\n", File(dir, "driver.lib").readText())
        assertTrue(File(dir, "meta.json").isFile)
        assertTrue(File(dir, "libvulkan_adreno.so").isFile)
        assertTrue(File(dir, "libturnip_common.so").isFile)

        // 2.6：安装完成后下一次扫描立即可见。
        val scanned = DriverManager.scan(files).single()
        assertEquals("Turnip-Test", scanned.id)
        assertEquals("Turnip Test", scanned.name)
        assertTrue(scanned.valid)
        // 安装成功后下载 zip 被清理。
        assertFalse(File(cache, "pack.zip").exists())
    }

    @Test
    fun `missing meta json is rejected with no leftover directory`() {
        val zip = newZip { zipEntry(it, "libvulkan_adreno.so") }
        val result = DriverInstaller.install(files, zip, "pack.zip")
        assertTrue(result is DriverInstallResult.Failed)
        assertEquals(DriverInstallFailure.MISSING_META, (result as DriverInstallResult.Failed).detail)
        // 不产生新目录（唯一的残留可能是被清理掉的临时目录）。
        assertTrue(DriverManager.scan(files).isEmpty())
        assertFalse(File(DriverManager.driversDir(files), ".tmp-x").exists())
        assertTrue(DriverManager.driversDir(files).listFiles().isNullOrEmpty())
    }

    @Test
    fun `library without driver declaration is accepted only when the pack has a single so`() {
        val multi = newZip { zip ->
            zipEntry(zip, "a.so")
            zipEntry(zip, "b.so")
            zipEntry(zip, "meta.json", """{"name":"multi"}""".toByteArray())
        }
        val multiResult = DriverInstaller.install(files, multi, "multi.zip")
        assertEquals(DriverInstallFailure.NO_LIBRARY, (multiResult as DriverInstallResult.Failed).detail)

        val single = newZip { zip ->
            zipEntry(zip, "only.so")
            zipEntry(zip, "meta.json", """{"name":"single"}""".toByteArray())
        }
        val singleResult = DriverInstaller.install(files, single, "single.zip")
        assertTrue(singleResult is DriverInstallResult.Success)
        assertEquals("only.so", (singleResult as DriverInstallResult.Success).library)
        assertEquals("only.so\n", File(DriverManager.driversDir(files), "single/driver.lib").readText())
    }

    @Test
    fun `meta library name pointing to a missing file falls back to the single so`() {
        val zip = newZip { zip ->
            zipEntry(zip, "libvulkan_adreno.so")
            zipEntry(zip, "meta.json", """{"name":"fallback","libraryName":"libgone.so"}""".toByteArray())
        }
        val result = DriverInstaller.install(files, zip, "fallback.zip")
        assertTrue(result is DriverInstallResult.Success)
        assertEquals("libvulkan_adreno.so", (result as DriverInstallResult.Success).library)
    }

    @Test
    fun `library named by meta but absent from the pack is a hard failure`() {
        val zip = newZip { zip ->
            zipEntry(zip, "unrelated.so")
            zipEntry(zip, "another.so")
            zipEntry(zip, "meta.json", """{"name":"broken","libraryName":"libgone.so"}""".toByteArray())
        }
        val result = DriverInstaller.install(files, zip, "broken.zip")
        // 两个 .so 且声明的库不在包内：不做猜测，明确失败。
        assertEquals(DriverInstallFailure.NO_LIBRARY, (result as DriverInstallResult.Failed).detail)
        assertTrue(DriverManager.scan(files).isEmpty())
    }

    @Test
    fun `zip slip entries are rejected and nothing is extracted`() {
        val zip = newZip { writer ->
            zipEntry(writer, "../evil.so")
            zipEntry(writer, "meta.json", """{"name":"slip"}""".toByteArray())
        }
        val result = DriverInstaller.install(files, zip, "slip.zip")
        assertEquals(DriverInstallFailure.UNSAFE_ENTRY, (result as DriverInstallResult.Failed).detail)
        assertFalse(File(files, "evil.so").exists())
        assertTrue(DriverManager.driversDir(files).listFiles().isNullOrEmpty())

        val absolute = newZip { zip ->
            zipEntry(zip, "/abs.so")
            zipEntry(zip, "meta.json", """{"name":"abs"}""".toByteArray())
        }
        val absResult = DriverInstaller.install(files, absolute, "abs.zip")
        assertEquals(DriverInstallFailure.UNSAFE_ENTRY, (absResult as DriverInstallResult.Failed).detail)
    }

    @Test
    fun `corrupted zip is rejected`() {
        val zip = File(cache, "broken.zip")
        zip.writeText("this is not a zip archive")
        val result = DriverInstaller.install(files, zip, "broken.zip")
        assertEquals(DriverInstallFailure.BAD_ZIP, (result as DriverInstallResult.Failed).detail)
        assertTrue(DriverManager.driversDir(files).listFiles().isNullOrEmpty())
    }

    @Test
    fun `reinstalling the same id overwrites in place and keeps the selection`() {
        val iniFile = File(files, "gow3data/gow3.ini")
        assertTrue(iniFile.parentFile.mkdirs() || iniFile.parentFile.isDirectory)

        val first = DriverInstaller.install(files, goodZip(metaName = "Same Pack", library = "libv1.so", metaLibrary = "libv1.so"), "v1.zip")
        assertTrue(first is DriverInstallResult.Success)
        assertTrue(DriverManager.select(iniFile, (first as DriverInstallResult.Success).id))

        val second = DriverInstaller.install(
            files,
            goodZip(metaName = "Same Pack", library = "libv2.so", metaLibrary = "libv2.so"),
            "v2.zip",
        )
        assertTrue(second is DriverInstallResult.Success)
        // 同 id：目录仍是一份，库被新包替换，选中保持不变。
        val dirs = DriverManager.driversDir(files).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
        assertEquals(listOf("Same-Pack"), dirs.map { it.name })
        assertEquals("libv2.so\n", File(dirs.single(), "driver.lib").readText())
        assertFalse(File(dirs.single(), "libv1.so").exists())
        assertTrue(File(dirs.single(), "libv2.so").exists())
        assertEquals("Same-Pack", com.gow3.android.core.settings.Gow3Settings.read(iniFile)["gpu_driver"])
        assertEquals(listOf("Same-Pack"), DriverManager.scan(files).map { it.id })
    }

    @Test
    fun `temp files never leak into the scan`() {
        DriverInstaller.install(files, goodZip(), "pack.zip")
        val all = DriverManager.driversDir(files).listFiles().orEmpty()
        assertTrue(all.isNotEmpty())
        // 安装完成后的 drivers/ 下不残留 .tmp- 目录。
        assertTrue(all.none { it.name.startsWith(".tmp") })
    }
}
