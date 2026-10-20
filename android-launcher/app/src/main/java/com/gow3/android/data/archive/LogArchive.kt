package com.gow3.android.data.archive

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 纯日志归档逻辑：既保留将 `logs/` 目录树打包的通用工具，也提供维护者诊断 zip 所需的
 * 纯文本处理——整行尾段截断、疑似密钥行/键脱敏。保持不依赖 Android，以便宿主端 JVM
 * 测试直接验证它，与 [SaveArchive] 思路一致。导出是只读的：日志目录树绝不会被修改、
 * 截断或删除，只对打包时已存在于磁盘上的文件做快照。
 */
internal object LogArchive {

    private const val ROOT = "logs"

    /** 疑似携带凭据的行：命中即整体替换（参考 Bloodborne-Android 的 LogReport）。 */
    val secretLine = Regex("(?i)(token|passw(or)?d|cookie|secret|steam_?guard|refresh_?key)\\s*[=:]")
    /** 疑似敏感的设置键名：命中即剔除该键值对（参考 Bloodborne-Android 的 LogReport）。 */
    val secretKey = Regex("(?i)token|password|key|cookie|steam")

    /** 游戏日志尾段上限。 */
    const val LOG_CAP = 8L shl 20

    /** logcat 尾段上限。 */
    const val LOGCAT_CAP = 4L shl 20

    /** 当目录中至少含一个日志文件时为 true。 */
    fun hasLogs(logs: File): Boolean =
        logs.isDirectory && logs.walkTopDown().any { it.isFile }

    /** `yyyyMMdd-HHmmss`，与存档导出使用相同的时间戳格式。 */
    fun stamp(now: Date = Date()): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)

    /**
     * 通用工具：将整个 [logs] 目录树写入 [target] 的 zip，条目保留 `logs/` 前缀；
     * [extraEntries] 中的键值对作为额外 zip 条目直接写入，不落盘、不修改 [logs] 目录。
     * 默认为空，保持既有调用与测试不变。
     */
    fun pack(logs: File, target: File, extraEntries: Map<String, String> = emptyMap()) {
        val root = logs.parentFile ?: throw IllegalStateException("logs has no parent directory")
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            logs.walkTopDown().forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry("$relative/"))
                    zip.closeEntry()
                } else {
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            extraEntries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    /** 把 [entries]（有序，键为条目名、值为字节内容）写入 [target] 的 zip。 */
    fun writeZip(entries: Map<String, ByteArray>, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }

    /**
     * 把 [source] 的末 [cap] 字节（整行）经敏感行脱敏写入 [writer]；被截掉的起始部分以
     * `[Cut: ...]` 说明标注，被截的半行丢弃。返回被脱敏（替换为占位）的行数。
     */
    fun tailStripped(source: File, cap: Long, writer: Writer): Int {
        var removed = 0
        source.inputStream().buffered().use { raw ->
            val size = source.length()
            if (size > cap) {
                var skip = size - cap
                while (skip > 0) {
                    val n = raw.skip(skip)
                    if (n <= 0) break
                    skip -= n
                }
                var c = raw.read()
                while (c >= 0 && c != '\n'.code) c = raw.read()
                writer.write("[Cut: only the last ${cap shr 20} MB of this ${size shr 20} MB log is kept]\n")
            }
            val reader = BufferedReader(InputStreamReader(raw, Charsets.UTF_8))
            for (line in reader.lineSequence()) {
                if (secretLine.containsMatchIn(line)) {
                    writer.write("[line left out: looks like a secret]\n")
                    removed++
                } else {
                    writer.write(line)
                    writer.write('\n'.code)
                }
            }
        }
        return removed
    }

    /** 对 [text] 逐行做敏感行脱敏，命中行以占位替换；返回（处理后的文本, 脱敏行数）。 */
    fun stripSecrets(text: String): Pair<String, Int> {
        var removed = 0
        val out = StringBuilder()
        val parts = text.split('\n')
        for ((index, line) in parts.withIndex()) {
            // 输入以换行结尾时，split 会产生末尾空段：跳过，避免输出多出一个空行。
            if (index == parts.lastIndex && line.isEmpty()) continue
            if (secretLine.containsMatchIn(line)) {
                out.append("[line left out: looks like a secret]\n")
                removed++
            } else {
                out.append(line).append('\n')
            }
        }
        return out.toString() to removed
    }

    /** 读取 ini 设置，剔除键名疑似敏感的键值对；返回（保留内容, 剔除的行数）。 */
    fun settingsStripped(ini: File): Pair<String, Int> {
        var skipped = 0
        val out = StringBuilder()
        ini.readLines(Charsets.UTF_8).forEach { line ->
            val eq = line.indexOf('=')
            if (eq >= 0 && secretKey.containsMatchIn(line.substring(0, eq))) {
                skipped++
            } else {
                out.append(line).append('\n')
            }
        }
        return out.toString() to skipped
    }
}
