package com.gow3.android.data.session

import com.gow3.android.core.settings.GamePrefs
import com.gow3.android.data.Gow3Data


import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.gow3.android.R
import java.io.File

/** 按运行时解析顺序，记录有效 Lossless.dll 被发现的位置。 */
internal enum class LosslessDllSource { GAME_FOLDER, APP_PRIVATE }

/** launcher 当前已安装的内容，每次调用 `resolve()` 时都会从磁盘重新读取。 */
internal sealed class LosslessDllState {
    object NotInstalled : LosslessDllState()
    data class Installed(val file: File, val sizeBytes: Long, val source: LosslessDllSource) : LosslessDllState()
}

/** 一次 `IMPORT .DLL` 尝试的结果。Cancelled 表示用户在文件选择器中退出了。 */
internal sealed class ImportResult {
    data class Success(val state: LosslessDllState.Installed) : ImportResult()
    object Cancelled : ImportResult()
    data class Failed(val message: String) : ImportResult()
}

/** 一次 `REMOVE` 的结果。游戏目录中的副本不会被改动，只会被上报。 */
internal sealed class RemoveResult {
    data class Done(val state: LosslessDllState, val gameFolderRemains: Boolean) : RemoveResult()
    data class Failed(val message: String) : RemoveResult()
}

/**
 * 负责玩家的 `Lossless.dll`：运行时在哪里查找它、导入玩家自己选择的文件，
 * 并告知 UI 当前生效的是哪份副本。
 *
 * 查找顺序是与游戏运行时共享的单一事实来源：游戏目录旁的同名副本优先，
 * `filesDir/gow3data/Lossless.dll` 是 Steam 下载和手动导入都会写入的回退位置。
 * 不查询第三个位置，也不会写入或删除应用私有目录之外的任何内容——对本类而言，
 * 玩家的游戏目录是只读的。
 */
internal class LosslessDllManager(private val activity: Activity) {

    companion object {
        // 区别于 GameFolderManager 的 1001（游戏目录）、1002（存储权限）、1007（升级补丁目录），
        // 以及 SaveManager 的 1004/1005 与 LogManager 的 1006：撞码会让先返回 true 的消费者
        // 吃掉别的流程的结果（历史上 1003 就与升级补丁目录撞过）。
        const val REQUEST_LOSSESS_DLL = 1003
        const val FILE_NAME = "Lossless.dll"
    }

    /** 由 [launchPicker] 设置，由 [onPickerResult] 消费；取消或第二次启动会清空它。 */
    private var pending: ((ImportResult) -> Unit)? = null

    /** Steam 下载和手动导入都会落地的位置：运行时的回退位置。 */
    fun appCopy(): File = Gow3Data.lossless(activity)

    /** 游戏目录旁的同名副本；未选择游戏目录或它没有父目录时返回 null。 */
    fun besideGameFolder(): File? {
        val dir = GamePrefs(activity).gameDir() ?: return null
        return try {
            File(dir).parentFile?.let { File(it, FILE_NAME) }
        } catch (_: Exception) {
            null
        }
    }

    fun resolve(): LosslessDllState {
        besideGameFolder()?.let { if (it.isFile) return installed(it, LosslessDllSource.GAME_FOLDER) }
        val own = appCopy()
        if (own.isFile) return installed(own, LosslessDllSource.APP_PRIVATE)
        return LosslessDllState.NotInstalled
    }

    /**
     * 打开系统文件选择器。[onResult] 一定会执行：用户取消、没有安装选择器、
     * 文件被拒绝和复制失败都会以 [ImportResult] 返回，绝不会抛异常。
     * 结果通过 activity 的 legacy 回调经由 [onPickerResult] 到达。
     */
    fun launchPicker(onResult: (ImportResult) -> Unit) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        pending = onResult
        try {
            activity.startActivityForResult(intent, REQUEST_LOSSESS_DLL)
        } catch (ex: Exception) {
            pending = null
            onResult(ImportResult.Failed(activity.getString(R.string.no_file_picker_fmt, ex.message ?: activity.getString(R.string.unknown_error))))
        }
    }

    /** @return 当请求属于我们且已被消费时返回 true。 */
    fun onPickerResult(requestCode: Int, resultCode: Int, intent: Intent?): Boolean {
        if (requestCode != REQUEST_LOSSESS_DLL) return false
        val callback = pending
        pending = null
        if (callback == null) return true
        val uri = intent?.data
        if (resultCode != Activity.RESULT_OK || uri == null) callback(ImportResult.Cancelled) else callback(importFrom(uri))
        return true
    }

    /**
     * 将玩家提供的 DLL 复制到 [appCopy]。校验仅限文件名、非空和可读性：
     * launcher 不附带任何可比对的副本，且由 lsfg-vk——而非我们——决定加载哪个
     * Lossless 构建，因此在这里解析 PE 头只会带来误拒合法文件的风险。
     */
    fun importFrom(uri: Uri): ImportResult {
        val name = displayName(uri)
        if (name == null) return ImportResult.Failed(activity.getString(R.string.lossless_no_readable_name))
        if (!name.equals(FILE_NAME, ignoreCase = true)) {
            return ImportResult.Failed(activity.getString(R.string.lossless_wrong_file_fmt, FILE_NAME, name))
        }

        val size = sizeOf(uri)
        if (size != null && size <= 0L) return ImportResult.Failed(activity.getString(R.string.lossless_empty_fmt, FILE_NAME))

        val target = copyInto(uri)
            ?: return ImportResult.Failed(activity.getString(R.string.lossless_write_failed_fmt, FILE_NAME))
        return ImportResult.Success(installed(target, LosslessDllSource.APP_PRIVATE))
    }

    /**
     * 只删除应用私有副本。当游戏目录副本才是生效副本时，需要玩家自行删除，
     * 因此通过 [RemoveResult.Done.gameFolderRemains] 告知调用方，并且结果状态
     * 仍将其报告为已安装。
     */
    fun remove(): RemoveResult {
        val own = appCopy()
        if (own.exists() && !own.delete()) return RemoveResult.Failed(activity.getString(R.string.lossless_remove_failed_fmt, FILE_NAME))
        val state = resolve()
        return RemoveResult.Done(state, state is LosslessDllState.Installed && state.source == LosslessDllSource.GAME_FOLDER)
    }

    /**
     * 先写入同一目录下的临时文件，再重命名覆盖目标，因此复制中途失败绝不会让
     * 运行时读到被截断的 Lossless.dll。rename(2) 会原子地替换目标文件。
     */
    private fun copyInto(source: Uri): File? {
        val target = appCopy()
        val parent = target.parentFile ?: return null
        if (!parent.isDirectory && !parent.mkdirs()) return null
        val temporary = File(parent, ".$FILE_NAME.tmp")
        temporary.delete()
        try {
            val input = activity.contentResolver.openInputStream(source) ?: return null
            input.use { stream -> temporary.outputStream().use { stream.copyTo(it) } }
        } catch (_: Exception) {
            temporary.delete()
            return null
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            return null
        }
        return target
    }

    private fun installed(file: File, source: LosslessDllSource): LosslessDllState.Installed =
        LosslessDllState.Installed(file, if (file.isFile) file.length() else 0L, source)

    /** Provider 的显示名称，回退到 URI 的最后一段。 */
    private fun displayName(uri: Uri): String? {
        try {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(column)
                    if (!name.isNullOrEmpty()) return name
                }
            }
        } catch (_: Exception) {
            // 回退到 URI 段：部分 provider 拒绝该查询。
        }
        val segment = uri.lastPathSegment ?: return null
        return segment.substringAfterLast('/').takeIf { it.isNotEmpty() }
    }

    /** 当 provider 未上报大小时返回 null；下面的可读性检查仍然适用。 */
    private fun sizeOf(uri: Uri): Long? =
        try {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else null
            }
        } catch (_: Exception) {
            null
        }
}
