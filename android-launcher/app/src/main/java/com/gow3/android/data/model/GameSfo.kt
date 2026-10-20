package com.gow3.android.data.model

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 最小 `param.sfo` 读取器（纯 JVM，不依赖 Android），镜像 `scripts/prepare.py` 的 `sfo()`
 * 与 `src/probe.c` 的 `sfo_value()`：用于从游戏自身推导存档标题目录名。
 *
 * 只读关键字段，解析失败一律返回 null，不抛异常 —— 调用方据此退到形状检查。
 */
internal object GameSfo {

    /** 一个 param.sfo 只有几 KiB；超过这个大小说明该文件不是 SFO。 */
    private const val LIMIT = 1 shl 20
    /** 文件头的 `\0PSF` 按小端读成 uint32：字节 00 50 53 46 → 0x46535000。 */
    private const val MAGIC = 0x46535000
    private const val HEADER_SIZE = 20
    private const val ENTRY_SIZE = 16
    private const val FORMAT_UINT32 = 0x0404

    /** 解析出的键值；文件缺失、不可读、为空或不是 SFO 时返回 null。 */
    fun read(file: File): Map<String, String>? {
        val data = try {
            readLimited(file) ?: return null
        } catch (_: Exception) {
            return null
        }
        return parse(data)
    }

    /**
     * 最多读 [LIMIT] 字节。不用 `InputStream.readNBytes` —— 它在 Android 上要 API 33，
     * 而本移植的 minSdk 是 28。
     */
    private fun readLimited(file: File): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        file.inputStream().use { input ->
            while (out.size() < LIMIT) {
                val n = input.read(buffer, 0, minOf(buffer.size, LIMIT - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun parse(data: ByteArray): Map<String, String>? {
        if (data.size < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt(0) != MAGIC) return null
        val keyTable = buf.getInt(8)
        val valueTable = buf.getInt(12)
        val count = buf.getInt(16)
        if (count < 0 || HEADER_SIZE + count.toLong() * ENTRY_SIZE > data.size) return null

        val result = HashMap<String, String>()
        for (i in 0 until count) {
            val entry = HEADER_SIZE + i * ENTRY_SIZE
            val keyOffset = buf.getShort(entry).toInt() and 0xFFFF
            val format = buf.getShort(entry + 2).toInt() and 0xFFFF
            val length = buf.getInt(entry + 4)
            val valueOffset = buf.getInt(entry + 12)
            val keyStart = keyTable + keyOffset
            if (keyStart < 0 || keyStart >= data.size) return null
            val key = readString(data, keyStart, data.size - keyStart) ?: continue
            result[key] = readValue(buf, data, format, valueTable + valueOffset, length) ?: continue
        }
        return result
    }

    private fun readValue(buf: ByteBuffer, data: ByteArray, format: Int, start: Int, length: Int): String? {
        if (start < 0 || length < 0 || start > data.size - length) return null
        if (format == FORMAT_UINT32) {
            if (length < 4) return null
            return buf.getInt(start).toLong().and(0xFFFFFFFFL).toString()
        }
        return readString(data, start, length)
    }

    /** NUL 结尾的 UTF-8 字符串，最多读 [limit] 字节。 */
    private fun readString(data: ByteArray, start: Int, limit: Int): String? {
        if (start < 0 || start >= data.size) return null
        var end = start
        val stop = minOf(data.size, start + limit)
        while (end < stop && data[end].toInt() != 0) end++
        return String(data, start, end - start, StandardCharsets.UTF_8)
    }

    /**
     * 存档标题目录名，规则与运行时 `src/probe.c:623-628` 逐字对齐：
     * 优先 `INSTALL_DIR_SAVEDATA`，否则 `TITLE_ID`；都取不到返回 null。
     */
    fun saveDirName(paramSfo: File): String? {
        val values = read(paramSfo) ?: return null
        val install = values["INSTALL_DIR_SAVEDATA"]?.trim()
        if (!install.isNullOrEmpty()) return install
        val title = values["TITLE_ID"]?.trim()
        if (!title.isNullOrEmpty()) return title
        return null
    }
}
