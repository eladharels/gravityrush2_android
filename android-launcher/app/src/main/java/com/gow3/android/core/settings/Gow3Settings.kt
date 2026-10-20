package com.gow3.android.core.settings



import java.io.File

/**
 * gow3.ini 的唯一读写口（launcher 与 game 两侧共享的契约层；Java 侧经 @JvmStatic 调用）。
 *
 * 语义继承自原 `Gow3Ini`（与 game 侧 `SettingsDialog.load()/save()` 一致）：
 * `key=value` 行、`#` 注释。[update] 整文件重写：被修改的键原位替换所在行，
 * 缺失的键追加到末尾，注释、空行与未修改的键原样保留；写入先写临时文件再原子
 * 重命名，因此失败绝不会破坏原文件。
 *
 * 消费方：游戏（Gow3Settings::Load）、run-bionic.sh、游戏内设置对话框、launcher
 * 配置页/Home 页/Mods 页、DriverManager 的 `gpu_driver=` 键。这是 launcher 唯一的
 * 启动参数持久化通道。对象为 public：game 侧 Java 代码要调用，Kotlin `internal`
 * 成员在字节码里会被改名，Java 无法引用。
 */
object Gow3Settings {

    /** 将 key=value 行解析为映射；`#` 注释和空行被忽略。文件缺失/不可读 -> 返回空映射。 */
    @JvmStatic
    fun read(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val values = LinkedHashMap<String, String>()
        try {
            file.forEachLine { line ->
                val eq = line.indexOf('=')
                if (eq > 0 && !line.startsWith("#")) {
                    values[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
                }
            }
        } catch (_: Exception) {
            return emptyMap()
        }
        return values
    }

    /** 单键读取；键缺失时返回 null。 */
    @JvmStatic
    fun read(file: File, key: String): String? = read(file)[key]

    /**
     * 应用 `updates` 后整体重写 `file`（被修改的键替换其所在行，缺失的键追加到
     * 末尾；其余行原样保留）。成功返回 true；任何失败都会保持原文件不变并返回 false。
     */
    @JvmStatic
    fun update(file: File, updates: Map<String, String>): Boolean {
        val lines = try {
            file.readLines(Charsets.UTF_8)
        } catch (_: Exception) {
            emptyList()
        }
        val pending = LinkedHashMap(updates)
        val out = mutableListOf<String>()
        for (line in lines) {
            val eq = line.indexOf('=')
            val key = if (eq > 0 && !line.startsWith("#")) line.substring(0, eq).trim() else null
            if (key != null && pending.containsKey(key)) {
                out.add("$key=${pending.remove(key)}")
            } else {
                out.add(line)
            }
        }
        for ((k, v) in pending) out.add("$k=$v")
        val temporary = File(file.path + ".tmp")
        return try {
            temporary.parentFile?.mkdirs()
            temporary.writeText(out.joinToString("\n") + "\n", Charsets.UTF_8)
            if (temporary.renameTo(file)) {
                true
            } else {
                temporary.delete()
                false
            }
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }
}
