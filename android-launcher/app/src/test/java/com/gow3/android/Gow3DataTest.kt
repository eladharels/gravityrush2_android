package com.gow3.android

import com.gow3.android.data.Gow3Data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gow3DataTest {
    private fun tempDir(): File = Files.createTempDirectory("gow3data-test").toFile()

    @Test
    fun `prepare migrates old user config dll and logs`() {
        val files = tempDir()
        val old = File(files, "gow3port").apply { mkdirs() }
        File(old, "user/savedata/1/CUSA01623/AUTOSAVE").mkdirs()
        File(old, "user/savedata/1/CUSA01623/AUTOSAVE/data.bin").writeText("save")
        File(old, "gow3.ini").writeText("fps_limit=60\n")
        File(old, "Lossless.dll").writeBytes(byteArrayOf(1, 2, 3))
        File(old, "logs").mkdirs()
        File(old, "logs/android-game.log").writeText("log")
        val defaults = File(files, "default.ini").apply { writeText("fps_limit=30\n") }

        Gow3Data.prepare(files, defaults)

        assertEquals("save", File(files, "gow3data/user/savedata/1/CUSA01623/AUTOSAVE/data.bin").readText())
        // upscaler 键缺失被一次性迁移追加（超分默认关闭）
        assertEquals("fps_limit=60\nupscaler=off\n", File(files, "gow3data/gow3.ini").readText())
        assertTrue(File(files, "gow3data/Lossless.dll").isFile)
        assertEquals("log", File(files, "gow3data/logs/android-game.log").readText())
        assertTrue(File(files, "gow3data/.data-schema").isFile)
    }

    @Test
    fun `prepare does not overwrite existing user data`() {
        val files = tempDir()
        File(files, "gow3port").mkdirs()
        File(files, "gow3port/gow3.ini").writeText("old=1\n")
        File(files, "gow3data").mkdirs()
        File(files, "gow3data/gow3.ini").writeText("new=1\n")
        val defaults = File(files, "default.ini").apply { writeText("default=1\n") }

        Gow3Data.prepare(files, defaults)

        assertEquals("new=1\nupscaler=off\n", File(files, "gow3data/gow3.ini").readText())
    }

    @Test
    fun `prepare creates default config when no legacy config exists`() {
        val files = tempDir()
        val defaults = File(files, "default.ini").apply { writeText("fps_limit=30\n") }

        Gow3Data.prepare(files, defaults)

        assertEquals("fps_limit=30\nupscaler=off\n", Gow3Data.config(files).readText())
        assertTrue(Gow3Data.user(files).isDirectory)
        assertTrue(Gow3Data.logs(files).isDirectory)
    }

    // ---- 引擎 FPS 显示退役：prepare 把残留的 show_fps=1 归零（change design D7）----

    @Test
    fun `prepare rewrites legacy show_fps=1 to 0 keeping other lines`() {
        val files = tempDir()
        // 模拟旧版默认模板落盘的 gow3.ini（show_fps=1 + 其它键 + 注释）
        val legacy = File(files, "gow3port").apply { mkdirs() }
        File(legacy, "gow3.ini").writeText(
            "# base config\nfps_limit=60\nshow_fps=1 ; old default\nupscaler=fsr3\n"
        )

        Gow3Data.prepare(files, null)

        val text = Gow3Data.config(files).readText()
        assertTrue(text.contains("show_fps=0"))
        assertFalse(text.contains("show_fps=1"))
        assertTrue("其它键保留", text.contains("fps_limit=60\n"))
        assertTrue("注释保留", text.contains("# base config\n") && text.contains("old default"))
    }

    @Test
    fun `prepare keeps config untouched when show_fps already off or absent`() {
        val files = tempDir()
        val ini = File(files, "gow3port").apply { mkdirs() }.resolve("gow3.ini")
        // 已为 0：show_fps 不再触碰；upscaler 键缺失被迁移追加
        ini.writeText("fps_limit=60\nshow_fps=0\n")
        Gow3Data.prepare(files, null)
        assertEquals("fps_limit=60\nshow_fps=0\nupscaler=off\n",
            File(files, "gow3data/gow3.ini").readText())

        val files2 = tempDir()
        val ini2 = File(files2, "gow3port").apply { mkdirs() }.resolve("gow3.ini")
        ini2.writeText("fps_limit=30\n")
        Gow3Data.prepare(files2, null)
        assertEquals("fps_limit=30\nupscaler=off\n", File(files2, "gow3data/gow3.ini").readText())
    }

    @Test
    fun `prepare does not confuse show_fps with keys sharing the prefix`() {
        val files = tempDir()
        val legacy = File(files, "gow3port").apply { mkdirs() }
        File(legacy, "gow3.ini").writeText("show_fps_note=keep\nshow_fps=1\n")

        Gow3Data.prepare(files, null)

        val text = Gow3Data.config(files).readText()
        assertTrue("前缀相似键不受影响", text.contains("show_fps_note=keep\n"))
        assertTrue(text.contains("show_fps=0\n"))
    }

    // ---- 一次性迁移：超分默认关闭，upscaler 一律改写为 off（change replace-android-fsr3-with-gsr2）----

    @Test
    fun `prepare rewrites upscaler fsr3 to off once keeping other lines`() {
        val files = tempDir()
        val legacy = File(files, "gow3port").apply { mkdirs() }
        File(legacy, "gow3.ini").writeText(
            "# header\nupscaler=fsr3 ; my choice\nfps_limit=60\n"
        )

        Gow3Data.prepare(files, null)

        val text = Gow3Data.config(files).readText()
        assertEquals("# header\nupscaler=off ; my choice\nfps_limit=60\n", text)
        assertTrue(File(files, "gow3data/upscaler_default_off").isFile)
    }

    @Test
    fun `upscaler migration never rewrites after the marker`() {
        val files = tempDir()
        val legacy = File(files, "gow3port").apply { mkdirs() }
        File(legacy, "gow3.ini").writeText("upscaler=fsr3\n")
        Gow3Data.prepare(files, null)
        // 玩家手动改回 fsr3 后再次 prepare：不再被覆盖
        Gow3Data.config(files).writeText("upscaler=fsr3\n")
        Gow3Data.prepare(files, null)
        assertEquals("upscaler=fsr3\n", Gow3Data.config(files).readText())
    }

    @Test
    fun `upscaler migration is idempotent when already off`() {
        val files = tempDir()
        val legacy = File(files, "gow3port").apply { mkdirs() }
        File(legacy, "gow3.ini").writeText("upscaler=off\nfps_limit=60\n")
        Gow3Data.prepare(files, null)
        assertEquals("upscaler=off\nfps_limit=60\n", Gow3Data.config(files).readText())
        assertTrue(File(files, "gow3data/upscaler_default_off").isFile)
    }
}
