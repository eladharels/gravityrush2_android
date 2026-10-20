package com.gow3.android.data.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringWriter
import java.util.zip.ZipInputStream

class LogArchiveTest {

    private fun tempDir(): File {
        val dir = java.nio.file.Files.createTempDirectory("logarchive-test").toFile()
        dir.deleteOnExit()
        return dir
    }

    private fun makeLogs(root: File): File {
        val logs = File(root, "logs").apply { mkdirs() }
        File(logs, "android-game.log").writeText("Texture integrity: title=CUSA01623\n")
        val nested = File(logs, "turnip").apply { mkdirs() }
        File(nested, "driver.log").writeText("turnip main\n")
        return logs
    }

    private fun zipEntries(zip: File): Map<String, String> {
        val entries = HashMap<String, String>()
        ZipInputStream(zip.inputStream()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (entry.isDirectory) continue
                entries[entry.name] = stream.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }

    private fun listFiles(dir: File): Map<String, String> {
        val root = dir.parentFile ?: dir
        return dir.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    @Test
    fun `hasLogs is true for a non-empty logs tree`() {
        assertTrue(LogArchive.hasLogs(makeLogs(tempDir())))
    }

    @Test
    fun `hasLogs is false for missing and empty directories`() {
        val dir = tempDir()
        assertFalse(LogArchive.hasLogs(File(dir, "missing")))
        File(dir, "logs").mkdirs()
        assertFalse(LogArchive.hasLogs(File(dir, "logs")))
    }

    @Test
    fun `pack round-trips the logs tree contents`() {
        val dir = tempDir()
        val logs = makeLogs(dir)
        val zip = File(dir, "export.zip")
        LogArchive.pack(logs, zip)
        assertTrue(zip.isFile)
        assertEquals(listFiles(logs), zipEntries(zip))
    }

    @Test
    fun `pack keeps the logs prefix on every entry`() {
        val dir = tempDir()
        val zip = File(dir, "export.zip")
        LogArchive.pack(makeLogs(dir), zip)
        val entries = zipEntries(zip).keys
        assertTrue(entries.contains("logs/android-game.log"))
        assertTrue(entries.contains("logs/turnip/driver.log"))
        assertTrue(entries.all { it.startsWith("logs/") })
    }

    @Test
    fun `pack does not modify the source log files`() {
        val dir = tempDir()
        val logs = makeLogs(dir)
        val before = listFiles(logs)
        LogArchive.pack(logs, File(dir, "export.zip"))
        assertEquals(before, listFiles(logs))
    }

    @Test
    fun `pack writes extraEntries as zip entries alongside the logs tree`() {
        val dir = tempDir()
        val logs = makeLogs(dir)
        val zip = File(dir, "export.zip")
        LogArchive.pack(logs, zip, mapOf("device-info.txt" to "model=TestModel\nram_mb=8192\n"))
        val entries = zipEntries(zip)
        assertTrue(entries.containsKey("device-info.txt"))
        assertEquals("model=TestModel\nram_mb=8192\n", entries["device-info.txt"])
        assertTrue(entries.containsKey("logs/android-game.log"))
        assertTrue(entries.all { it.key.startsWith("logs/") || it.key == "device-info.txt" })
    }

    @Test
    fun `writeZip writes the ordered entries`() {
        val dir = tempDir()
        val zip = File(dir, "export.zip")
        LogArchive.writeZip(linkedMapOf(
            "report.txt" to "App: test\n".toByteArray(),
            "logs/android-game.log" to "boot ok\n".toByteArray(),
        ), zip)
        assertEquals(mapOf(
            "report.txt" to "App: test\n",
            "logs/android-game.log" to "boot ok\n",
        ), zipEntries(zip))
    }

    @Test
    fun `tailStripped keeps whole lines and replaces secret lines`() {
        val dir = tempDir()
        val log = File(dir, "android-game.log")
        log.writeText("0123456789abcdef\nSECRET token=xyz\nhello\n")
        // 超出 cap 时只保留整行尾段并标注截断。
        val cut = StringWriter()
        assertEquals(0, LogArchive.tailStripped(log, 20, cut))
        val cutText = cut.toString()
        assertTrue(cutText.startsWith("[Cut: only the last 0 MB"))
        assertTrue(cutText.contains("hello\n"))
        assertFalse(cutText.contains("0123456789abcdef"))
        assertFalse(cutText.contains("SECRET token=xyz"))
        // cap 足够大时不做截断，仅把敏感行替换为占位。
        val kept = StringWriter()
        assertEquals(1, LogArchive.tailStripped(log, 4096, kept))
        val keptText = kept.toString()
        assertTrue(keptText.contains("[line left out: looks like a secret]\n"))
        assertTrue(keptText.contains("0123456789abcdef\n"))
        assertTrue(keptText.contains("hello\n"))
        assertFalse(keptText.contains("token=xyz"))
    }

    @Test
    fun `stripSecrets replaces matching lines with a placeholder`() {
        val (out, removed) = LogArchive.stripSecrets("line one\npassword = hunter2\nline three\n")
        assertEquals(1, removed)
        assertEquals("line one\n[line left out: looks like a secret]\nline three\n", out)
    }

    @Test
    fun `settingsStripped removes secret-looking keys`() {
        val dir = tempDir()
        val ini = File(dir, "gow3.ini")
        ini.writeText("fps_limit=60\napi_token=abc123\nupscaler=none\n")
        val (out, skipped) = LogArchive.settingsStripped(ini)
        assertEquals(1, skipped)
        assertTrue(out.contains("fps_limit=60"))
        assertTrue(out.contains("upscaler=none"))
        assertFalse(out.contains("api_token"))
    }

    @Test
    fun `stamp uses the yyyyMMdd-HHmmss layout`() {
        assertTrue(LogArchive.stamp().matches(Regex("""\d{8}-\d{6}""")))
    }
}