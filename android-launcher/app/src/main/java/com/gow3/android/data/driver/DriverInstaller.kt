package com.gow3.android.data.driver

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** 一次安装的结果；失败时既有安装与 `gow3.ini` 保持原样（spec：MUST NOT 产生半安装状态）。 */
internal sealed class DriverInstallResult {
    data class Success(val id: String, val name: String, val version: String, val library: String) : DriverInstallResult()

    /** [detail] 是可定位的失败原因（写入日志 / 展示给玩家），不含玩家私人路径。 */
    data class Failed(val detail: DriverInstallFailure) : DriverInstallResult()
}

internal enum class DriverInstallFailure {
    /** zip 打不开或内容不是合法归档。 */
    BAD_ZIP,

    /** 归档包含路径穿越（绝对路径 / `..`）条目。 */
    UNSAFE_ENTRY,

    /** 包内缺少 `meta.json` 或它声明了空名称。 */
    MISSING_META,

    /** 无法确定驱动库（meta.libraryName 缺失或文件不在，且包内不是唯一 `.so`）。 */
    NO_LIBRARY,

    /** `driver.lib` 所指库文件在包内不存在。 */
    LIBRARY_MISSING,

    /** 名称清洗后无法得到安全目录 id。 */
    BAD_NAME,

    /** 解压或目录替换的文件系统操作失败。 */
    IO,
}

/**
 * 把下载好的 adrenotools 风格驱动 zip 安装到 `files/drivers/<id>/`（design D3）。
 *
 * 安装语义对齐 `tools/android/device/install_bionic_driver.sh`：库由包内 `meta.json` 的
 * `libraryName` 指定（缺失或文件不存在时回退包根唯一的 `.so`），安装器写出 `driver.lib`
 * 指针文件；与脚本不同，本类保留整个包内容（含 `meta.json`），供扫描展示元数据。
 *
 * 流程：解压到 `files/drivers/.tmp-<ts>/` → 校验 → 原子 rename 到最终目录 → 清理。
 * 任何一步失败都删除临时目录，不动既有安装；同 id 覆盖安装时旧目录先改名、新目录就位
 * 后才删除，替换失败即恢复旧目录。
 */
internal object DriverInstaller {

    private const val META = "meta.json"
    private const val LIB_POINTER = "driver.lib"

    /**
     * 安装 [zipFile] 到 [files] 的 `drivers/` 目录。[zipStem] 是 zip 文件名主干（无扩展名），
     * 用作包内名称不可用时的 id 回退。必须在 IO 线程调用。
     */
    fun install(files: File, zipFile: File, zipStem: String): DriverInstallResult {
        if (!zipFile.isFile) return DriverInstallResult.Failed(DriverInstallFailure.BAD_ZIP)
        val root = DriverManager.driversDir(files)
        if (!root.isDirectory && !root.mkdirs()) {
            return DriverInstallResult.Failed(DriverInstallFailure.IO)
        }
        val staging = File(root, ".tmp-${System.currentTimeMillis()}")
        val extracted = extract(zipFile, staging)
        if (extracted != null) return finishWith(staging, zipFile, extracted, zipStem)
        staging.deleteRecursively()
        return DriverInstallResult.Failed(extractionFailure ?: DriverInstallFailure.BAD_ZIP)
    }

    /** 解压期间的失败原因；install 里据此给 [DriverInstallResult.Failed] 定类。 */
    private var extractionFailure: DriverInstallFailure? = null

    private fun finishWith(
        staging: File,
        zipFile: File,
        meta: ParsedMeta,
        zipStem: String,
    ): DriverInstallResult {
        val id = DriverManager.safeId(meta.name, zipStem)
        if (id == null) {
            cleanup(staging, zipFile)
            return DriverInstallResult.Failed(DriverInstallFailure.BAD_NAME)
        }
        val library = resolveLibrary(staging, meta)
            ?: run {
                cleanup(staging, zipFile)
                return DriverInstallResult.Failed(DriverInstallFailure.NO_LIBRARY)
            }
        if (!File(staging, library).isFile) {
            cleanup(staging, zipFile)
            return DriverInstallResult.Failed(DriverInstallFailure.LIBRARY_MISSING)
        }
        // 写出运行链读取的指针文件（内容 = 库名，单行）。
        if (!writePointer(staging, library)) {
            cleanup(staging, zipFile)
            return DriverInstallResult.Failed(DriverInstallFailure.IO)
        }

        val root = staging.parentFile ?: staging
        val final = File(root, id)
        // 覆盖安装：旧目录先改名暂存，新目录就位后再删；失败即回滚旧目录。
        var displaced: File? = null
        if (final.exists()) {
            displaced = File(root, ".tmp-old-${System.currentTimeMillis()}")
            if (!final.renameTo(displaced)) {
                cleanup(staging, zipFile)
                return DriverInstallResult.Failed(DriverInstallFailure.IO)
            }
        }
        if (!staging.renameTo(final)) {
            displaced?.renameTo(final)
            cleanup(staging, zipFile)
            return DriverInstallResult.Failed(DriverInstallFailure.IO)
        }
        displaced?.deleteRecursively()
        zipFile.delete()
        return DriverInstallResult.Success(id, meta.name, meta.version.orEmpty(), library)
    }

    private fun cleanup(staging: File, zipFile: File) {
        staging.deleteRecursively()
        zipFile.delete()
    }

    /** 库选择与安装脚本同款：`meta.libraryName` 优先且文件必须在，否则包根唯一 `.so`。 */
    private fun resolveLibrary(staging: File, meta: ParsedMeta): String? {
        meta.libraryName?.let { name ->
            if (!name.contains('/') && !name.startsWith(".") && File(staging, name).isFile) return name
        }
        val candidates = staging.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".so") }
            ?.map { it.name }
            .orEmpty()
        return candidates.singleOrNull()
    }

    private fun writePointer(dir: File, library: String): Boolean = try {
        File(dir, LIB_POINTER).writeText("$library\n")
        true
    } catch (_: Exception) {
        false
    }

    /**
     * zip-slip 安全解压：拒绝绝对路径与 `..` 段，规范路径必须仍落在临时目录内；
     * 解压后定位并解析 `meta.json`（缺失 → [MISSING_META] 由调用方经 null 判定）。
     * 任何失败都返回 null 并记录 [extractionFailure]。
     */
    private fun extract(zipFile: File, staging: File): ParsedMeta? {
        extractionFailure = null
        if (!staging.mkdirs()) {
            extractionFailure = DriverInstallFailure.IO
            return null
        }
        val stagingPath = staging.canonicalPath + File.separator
        var entries = 0
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name.isBlank()) continue
                    entries++
                    val failure = unsafeReason(name, staging, stagingPath)
                    if (failure != null) {
                        extractionFailure = failure
                        return null
                    }
                    val outFile = File(staging, name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                    }
                    zip.closeEntry()
                }
            }
        } catch (_: Exception) {
            extractionFailure = DriverInstallFailure.BAD_ZIP
            return null
        }
        // 一个条目都读不出来：不是合法归档（ZipInputStream 对坏文件可能静默返回空）。
        if (entries == 0) {
            extractionFailure = DriverInstallFailure.BAD_ZIP
            return null
        }
        val metaFile = File(staging, META)
        if (!metaFile.isFile) {
            extractionFailure = DriverInstallFailure.MISSING_META
            return null
        }
        val meta = try {
            JSONObject(metaFile.readText())
        } catch (_: Exception) {
            extractionFailure = DriverInstallFailure.MISSING_META
            return null
        }
        val name = meta.optString("name").takeIf { it.isNotBlank() }
            ?: run {
                extractionFailure = DriverInstallFailure.MISSING_META
                return null
            }
        return ParsedMeta(
            name = name,
            version = meta.optString("driverVersion").takeIf { it.isNotBlank() }
                ?: meta.optString("packageVersion").takeIf { it.isNotBlank() },
            libraryName = meta.optString("libraryName").takeIf { it.isNotBlank() },
        )
    }

    /** 条目路径安全检查：绝对路径、`..` 段、规范化后越界都拒绝。 */
    private fun unsafeReason(name: String, staging: File, stagingPath: String): DriverInstallFailure? {
        if (name.startsWith('/') || name.startsWith('\\')) return DriverInstallFailure.UNSAFE_ENTRY
        val segments = name.replace('\\', '/').split('/')
        if (segments.any { it == ".." }) return DriverInstallFailure.UNSAFE_ENTRY
        val target = File(staging, name).canonicalPath
        if (target != stagingPath.removeSuffix(File.separator) && !target.startsWith(stagingPath)) {
            return DriverInstallFailure.UNSAFE_ENTRY
        }
        return null
    }

    private data class ParsedMeta(
        val name: String,
        val version: String?,
        val libraryName: String?,
    )
}
