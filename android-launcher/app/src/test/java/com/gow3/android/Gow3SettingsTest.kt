package com.gow3.android

import com.gow3.android.core.settings.Gow3Settings

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

class Gow3SettingsTest {

    private fun tempDir(): File {
        val dir = java.nio.file.Files.createTempDirectory("gow3ini-test").toFile()
        dir.deleteOnExit()
        return dir
    }

    @Test
    fun `read parses keys and ignores comments and blanks`() {
        val file = File(tempDir(), "gow3.ini")
        file.writeText(
            "# gow3 settings\n\n" +
                "upscaler=fsr3\n" +
                "fps_limit = 60\n" +
                "# a comment line\n" +
                "skip_intro=0\n",
        )
        val values = Gow3Settings.read(file)
        assertEquals(3, values.size)
        assertEquals("fsr3", values["upscaler"])
        assertEquals("60", values["fps_limit"])
        assertEquals("0", values["skip_intro"])
    }

    @Test
    fun `read returns empty map for missing file`() {
        val values = Gow3Settings.read(File(tempDir(), "does-not-exist.ini"))
        assertTrue(values.isEmpty())
    }

    @Test
    fun `write replaces existing key in place and keeps other lines`() {
        val dir = tempDir()
        val file = File(dir, "gow3.ini")
        file.writeText("# header\nupscaler=fsr3\nfps_limit=60\n\nskip_intro=0\n")
        val ok = Gow3Settings.update(file, mapOf("fps_limit" to "0"))
        assertTrue(ok)
        val content = file.readText()
        assertEquals("# header\nupscaler=fsr3\nfps_limit=0\n\nskip_intro=0\n", content)
    }

    @Test
    fun `write appends missing keys at the end`() {
        val dir = tempDir()
        val file = File(dir, "gow3.ini")
        file.writeText("upscaler=fsr3\n")
        val ok = Gow3Settings.update(file, mapOf("debug_camera" to "1", "skip_intro" to "1"))
        assertTrue(ok)
        assertEquals("upscaler=fsr3\ndebug_camera=1\nskip_intro=1\n", file.readText())
    }

    @Test
    fun `write preserves comments and blank lines`() {
        val dir = tempDir()
        val file = File(dir, "gow3.ini")
        file.writeText("# keep me\nupscaler=fsr3\n\n# and me\nfps_limit=60\n")
        val ok = Gow3Settings.update(file, mapOf("upscaler" to "off"))
        assertTrue(ok)
        assertEquals("# keep me\nupscaler=off\n\n# and me\nfps_limit=60\n", file.readText())
    }

    @Test
    fun `write leaves original untouched when temp write fails`() {
        val dir = tempDir()
        val file = File(dir, "gow3.ini")
        file.writeText("upscaler=fsr3\nfps_limit=60\n")
        // 让临时路径不可写：用目录占据它，使临时写入失败。
        val tmp = File(dir, "gow3.ini.tmp")
        assertTrue(tmp.mkdir())
        val ok = Gow3Settings.update(file, mapOf("fps_limit" to "0"))
        assertFalse(ok)
        assertEquals("upscaler=fsr3\nfps_limit=60\n", file.readText())
    }

    @Test
    fun `write creates missing parent directory`() {
        val dir = tempDir()
        val nested = File(dir, "gow3port")
        val file = File(nested, "gow3.ini")
        val ok = Gow3Settings.update(file, mapOf("upscaler" to "fsr3"))
        assertTrue(ok)
        assertEquals("upscaler=fsr3\n", file.readText())
    }
}
