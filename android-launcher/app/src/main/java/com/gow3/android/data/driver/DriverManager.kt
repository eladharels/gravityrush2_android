package com.gow3.android.data.driver

import com.gow3.android.core.settings.Gow3Settings
import com.gow3.android.data.Gow3Data


import java.io.File
import org.json.JSONObject

/**
 * 一个已下载的驱动包条目。数据每次从磁盘重新读取（design D1：磁盘 + gow3.ini 是唯一
 * 事实来源，不建数据库），因此下载 / 删除 / 安装之后重扫即可刷新 UI。
 *
 * [id] 即 `files/drivers/<id>/` 目录名，也是 `gow3.ini` 中 `gpu_driver=` 的自定义取值；
 * 该取值必须满足 `tools/android/device/run-bionic.sh` 的解析契约（不以 `.` 开头、不含 `/`）。
 */
internal data class DriverEntry(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val library: String,
    val valid: Boolean,
)

/** 一次删除的结果；[DriverDeleteResult.Rejected] 表示请求本身不合法，未做任何删除。 */
internal sealed class DriverDeleteResult {
    /** [wasSelected] 为 true 表示被删的是当前选中驱动，ini 已同步回退为系统驱动。 */
    data class Done(val wasSelected: Boolean) : DriverDeleteResult()

    data class Rejected(val reason: DriverDeleteReason) : DriverDeleteResult()
}

internal enum class DriverDeleteReason {
    /** id 形态不满足运行链契约（空、含 `/`、以 `.` 开头）。 */
    INVALID_ID,

    /** 对应目录不存在。 */
    NOT_FOUND,

    /** 目录删除失败（IO 错误）。 */
    IO,
}

/**
 * 驱动库的扫描与选择通道，纯 JVM 可测（文件树由调用方注入）。
 *
 * 布局与消费契约对齐 `tools/android/device/install_bionic_driver.sh` / `run-bionic.sh`：
 * `files/drivers/<id>/` 内含驱动库 `.so` 与 `driver.lib`（单行文本，写库文件名）；
 * 可选 `meta.json`（adrenotools 包自带）提供展示名 / 版本 / 作者，缺失时以目录 id
 * 与库名展示（兼容 adb 脚本安装的旧包）。本类只写 `gow3.ini` 的 `gpu_driver=` 键，
 * 运行链如何 dlopen 由 `run-bionic.sh` + `vk_driver_loader` 决定，本类不感知。
 */
internal object DriverManager {

    /** gow3.ini 的驱动键。 */
    const val INI_KEY = "gpu_driver"

    /** 选中「系统驱动」时写入的兜底值（与键缺失、`system` 在 run-bionic.sh 中同分支）。 */
    const val SYSTEM_VALUE = "auto"

    private const val LIB_POINTER = "driver.lib"
    private const val META = "meta.json"

    /** 驱动包根目录：与应用 filesDir 同级（即 `files/drivers/`），不在 gow3data 内。 */
    fun driversDir(files: File): File = File(files, "drivers")

    /**
     * 扫描已下载的驱动包。跳过 `.` 开头目录（安装临时目录）；无效包仍返回（`valid=false`），
     * 供设置页标示不可选并可删除；首页选择列表只取 valid 项。
     */
    fun scan(files: File): List<DriverEntry> {
        val root = driversDir(files)
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: return emptyList()
        return dirs.map { dir -> entry(dir) }.sortedBy { it.name.lowercase() }
    }

    /**
     * 归一化 `gow3.ini` 的当前选中项：返回 null 表示系统驱动（`''|auto|system|缺失`），
     * 否则为驱动包 id。指向失效包的 id 原样返回，由 UI 层结合 scan 结果回退展示。
     */
    fun selectedId(ini: Map<String, String>): String? {
        val raw = ini[INI_KEY]?.trim().orEmpty()
        return when {
            raw.isEmpty() || raw == "auto" || raw == "system" -> null
            else -> raw
        }
    }

    /** 把选择持久化到 gow3.ini；[id] 为 null 表示选中系统驱动（写 `auto`）。 */
    fun select(iniFile: File, id: String?): Boolean =
        Gow3Settings.update(iniFile, mapOf(INI_KEY to (id ?: SYSTEM_VALUE)))

    /**
     * 删除驱动包目录，且仅允许删除 `files/drivers/<id>/` 这一层：id 形态不合法时拒绝
     *（防御 `..`、路径分隔与隐藏目录）；被删的是当前选中驱动时把 `gpu_driver=` 写为
     * 系统兜底值，保证运行链不会指向已消失的目录。
     */
    fun delete(files: File, iniFile: File, id: String): DriverDeleteResult {
        if (!isValidId(id)) return DriverDeleteResult.Rejected(DriverDeleteReason.INVALID_ID)
        val dir = File(driversDir(files), id)
        if (!dir.isDirectory) return DriverDeleteResult.Rejected(DriverDeleteReason.NOT_FOUND)
        val wasSelected = selectedId(Gow3Settings.read(iniFile)) == id
        val deleted = try {
            Gow3Data.deleteTree(dir)
            true
        } catch (_: Exception) {
            false
        }
        if (!deleted) return DriverDeleteResult.Rejected(DriverDeleteReason.IO)
        if (wasSelected) select(iniFile, null)
        return DriverDeleteResult.Done(wasSelected)
    }

    /**
     * 把驱动包声明的名称清洗成满足运行链契约的目录 id：保留 `[A-Za-z0-9._-]`、剥掉
     * 开头的 `.`；清洗后为空时回退到 [fallback] 的清洗结果；两者皆空返回 null
     *（调用方拒绝安装）。`..` 之类形态会被剥点后瓦解或被空回退拦下。
     */
    fun safeId(raw: String?, fallback: String): String? {
        sanitize(raw)?.let { return it }
        return sanitize(fallback)
    }

    private fun sanitize(raw: String?): String? {
        val cleaned = raw.orEmpty()
            .map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '-' }
            .joinToString("")
            .replace("-{2,}".toRegex(), "-")
            .trim('.', '-', ' ')
        return cleaned.takeIf { it.isNotEmpty() }?.take(80)
    }

    private fun isValidId(id: String): Boolean =
        id.isNotEmpty() && !id.startsWith(".") && !id.contains('/') && !id.contains('\\')

    private fun entry(dir: File): DriverEntry {
        val pointer = readLibraryPointer(dir)
        val meta = readMeta(dir)
        val library = pointer ?: meta.libraryName.orEmpty()
        // 有效判据与 run-bionic.sh 的解析分支一致：指针非空、无路径分隔、不以 `.` 开头、
        // 所指库文件存在。
        val valid = pointer != null &&
            pointer.isNotEmpty() &&
            !pointer.contains('/') &&
            !pointer.startsWith(".") &&
            File(dir, pointer).isFile
        return DriverEntry(
            id = dir.name,
            name = meta.name ?: dir.name,
            version = meta.version.orEmpty(),
            author = meta.author.orEmpty(),
            library = library,
            valid = valid,
        )
    }

    /** 读 `driver.lib` 首行；文件缺失或不可读返回 null。 */
    private fun readLibraryPointer(dir: File): String? = try {
        File(dir, LIB_POINTER).readLines().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /**
     * 容错解析包内 `meta.json`：任何失败都降级为空元数据（name=null, libraryName=null,
     * version=null, author=null），绝不抛出——展示信息缺失不应让有效驱动不可选。
     */
    private fun readMeta(dir: File): Meta {
        val text = try {
            File(dir, META).readText()
        } catch (_: Exception) {
            return Meta()
        }
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return Meta()
        }
        val version = json.optString("driverVersion").takeIf { it.isNotBlank() }
            ?: json.optString("packageVersion").takeIf { it.isNotBlank() }
        return Meta(
            name = json.optString("name").takeIf { it.isNotBlank() },
            libraryName = json.optString("libraryName").takeIf { it.isNotBlank() },
            version = version,
            author = json.optString("author").takeIf { it.isNotBlank() },
        )
    }

    private data class Meta(
        val name: String? = null,
        val libraryName: String? = null,
        val version: String? = null,
        val author: String? = null,
    )
}
