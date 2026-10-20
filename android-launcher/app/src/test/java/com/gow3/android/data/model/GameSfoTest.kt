package com.gow3.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * `param.sfo` 读取器：用于从游戏自身推导存档标题目录名。
 * 头部 20 字节（magic + version + keyTable + valueTable + count），
 * 条目 16 字节（keyOffset u16 + fmt u16 + len u32 + max u32 + dataOffset u32），
 * 之后依次是键表与值表 —— 与 `scripts/prepare.py` 的 `sfo()` 一致。
 */
class GameSfoTest {

    private fun tempFile(bytes: ByteArray): File {
        val file = File.createTempFile("paramsfo-test", ".sfo")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    /** 构造一个合法的 SFO；所有值按 UTF-8 字符串（fmt 0x0204）写。 */
    private fun sfoBytes(vararg pairs: Pair<String, String>): ByteArray {
        val count = pairs.size
        val keyTable = ByteArrayOutputStream()
        val valueTable = ByteArrayOutputStream()
        val keyOffsets = IntArray(count)
        val valueOffsets = IntArray(count)
        val lengths = IntArray(count)
        pairs.forEachIndexed { i, (key, value) ->
            keyOffsets[i] = keyTable.size()
            keyTable.write(key.toByteArray(Charsets.UTF_8))
            keyTable.write(0)
            val bytes = value.toByteArray(Charsets.UTF_8)
            valueOffsets[i] = valueTable.size()
            lengths[i] = bytes.size + 1 // 含结尾 NUL，与真实 param.sfo 一致
            valueTable.write(bytes)
            valueTable.write(0)
        }
        val keyTableOffset = 20 + count * 16
        val valueTableOffset = keyTableOffset + keyTable.size()

        val out = ByteArrayOutputStream()
        fun le16(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        }
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        out.write(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()))
        le32(0x00000101)
        le32(keyTableOffset)
        le32(valueTableOffset)
        le32(count)
        for (i in 0 until count) {
            le16(keyOffsets[i]); le16(0x0204); le32(lengths[i]); le32(lengths[i]); le32(valueOffsets[i])
        }
        out.write(keyTable.toByteArray())
        out.write(valueTable.toByteArray())
        return out.toByteArray()
    }

    @Test
    fun `read returns the string entries of a param sfo`() {
        val values = GameSfo.read(
            tempFile(
                sfoBytes(
                    "TITLE_ID" to "CUSA01740",
                    "TITLE" to "God of War™ III Remastered",
                    "APP_VER" to "01.00",
                ),
            ),
        )
        assertEquals("CUSA01740", values?.get("TITLE_ID"))
        assertEquals("God of War™ III Remastered", values?.get("TITLE"))
        assertEquals("01.00", values?.get("APP_VER"))
    }

    @Test
    fun `saveDirName prefers INSTALL_DIR_SAVEDATA over TITLE_ID`() {
        val file = tempFile(sfoBytes("TITLE_ID" to "CUSA01740", "INSTALL_DIR_SAVEDATA" to "GOW3SAVE"))
        assertEquals("GOW3SAVE", GameSfo.saveDirName(file))
    }

    @Test
    fun `saveDirName falls back to TITLE_ID`() {
        // 本机真实情况：INSTALL_DIR_SAVEDATA 缺席，只有 TITLE_ID。
        val file = tempFile(sfoBytes("TITLE_ID" to "CUSA01740", "APP_VER" to "01.00"))
        assertEquals("CUSA01740", GameSfo.saveDirName(file))
    }

    @Test
    fun `saveDirName returns null when neither key is present`() {
        val file = tempFile(sfoBytes("APP_VER" to "01.00", "CATEGORY" to "gd"))
        assertNull(GameSfo.saveDirName(file))
    }

    @Test
    fun `read returns null for a file that is not an sfo`() {
        assertNull(GameSfo.read(tempFile("not an sfo at all, just text".toByteArray())))
    }

    @Test
    fun `read returns null for an empty file`() {
        assertNull(GameSfo.read(tempFile(ByteArray(0))))
    }

    @Test
    fun `read returns null for a truncated header`() {
        assertNull(GameSfo.read(tempFile(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte()))))
    }

    @Test
    fun `read returns null for a missing file`() {
        val missing = File.createTempFile("paramsfo-missing", ".sfo").apply { delete(); deleteOnExit() }
        assertNull(GameSfo.read(missing))
    }

    @Test
    fun `read returns null when the entry count exceeds the file`() {
        val good = sfoBytes("TITLE_ID" to "CUSA01740")
        // 把 count 字段改成一个超过文件实际长度的大值
        val broken = good.copyOf()
        broken[16] = 0xFF.toByte(); broken[17] = 0xFF.toByte()
        assertNull(GameSfo.read(tempFile(broken)))
    }
}
