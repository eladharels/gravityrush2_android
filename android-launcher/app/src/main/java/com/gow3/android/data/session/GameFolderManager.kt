package com.gow3.android.data.session

import com.gow3.android.core.settings.GamePrefs

import com.gow3.android.MainActivity

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.gow3.android.R
import java.io.File

/** 负责首次运行时的游戏目录校验、存储权限和目录选择器状态。 */
internal class GameFolderManager(private val activity: Activity) {
    companion object {
        // 整个 launcher 的 Activity Result request code 必须全局唯一：MainActivity.onActivityResult
        // 依次询问各消费者，先返回 true 的会消费掉结果，因此撞码会让后到的消费者永远收不到回调。
        // 已知占用方：1001 游戏目录、1002 存储权限、1003 Lossless.dll 导入（LosslessDllManager）、
        // 1004/1005 存档导出/导入（SaveManager）、1006 日志导出（LogManager）。新增请取 1008 起的空位。
        const val REQUEST_GAME_FOLDER = 1001
        const val REQUEST_UPDATE_FOLDER = 1007
        const val REQUEST_STORAGE = 1002
        private val SERIALS = arrayOf("CUSA01740", "CUSA00900", "CUSA00207", "CUSA00208", "CUSA00299",
            "CUSA01363", "CUSA03179", "CUSA03014", "CUSA03023")
    }

    private enum class Kind { GAME, UPDATE }

    /**
     * 发起存储权限请求时正在配置的目录类型，由 [onPermissionResult] 消费一次后清空。
     *
     * 权限回调只带回 request code，而游戏目录和升级补丁目录共用 [REQUEST_STORAGE]，
     * 因此必须在这里记住发起方，否则授权后无法判断该回到哪个目录流程。
     */
    private var pendingFolderKind: Kind? = null

    /** prefs 键名与默认值统一在 GamePrefs（core/settings），本类不再裸访问 SharedPreferences。 */
    private val gamePrefs = GamePrefs(activity)

    fun hasGameFolder(): Boolean {
        if (activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return false
        val path = gamePrefs.gameDir() ?: return false
        return isGame(File(path))
    }

    fun updateFolder(): String? = gamePrefs.updateDir()

    fun hasUpdateFolder(): Boolean {
        val path = updateFolder() ?: return false
        val gamePath: String? = gamePrefs.gameDir()
        if (gamePath == null) return false
        return isGame(File(path)) && !sameAs(File(path), File(gamePath))
    }

    /**
     * gravityrush2_android: sets the game folder without the system folder picker, which can show
     * an empty storage root (Galaxy S24 Ultra with a work profile, 2026-10-27). [requested] is the
     * `game_dir` extra of an adb start (`am start -n ... --es game_dir <path>`); without it, and
     * while no folder is set, the usual places are searched: <storage>/games/<any>/ and
     * <storage>/games/<any>/Game/ holding Gravity Rush 2. Returns the folder set, or null.
     */
    fun adoptFolder(requested: String?): String? {
        if (activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return null
        val dir = if (!requested.isNullOrBlank()) File(requested).takeIf { isGame(it) }
        else if (gamePrefs.gameDir() != null) null
        else File(Environment.getExternalStorageDirectory(), "games").listFiles().orEmpty().sortedBy { it.name }
            .flatMap { listOf(it, File(it, "Game")) }
            .firstOrNull { com.gow3.android.data.GravityRush2.isGame(it) }
        dir ?: return null
        gamePrefs.setGameDir(dir.path)
        return dir.path
    }

    fun ensureGameFolder() {
        if (hasGameFolder()) return
        chooseGameFolder()
    }

    /** 重新打开目录选择器，让玩家可以将游戏指向另一个目录。 */
    fun chooseGameFolder() = chooseFolder(Kind.GAME)

    /** 选择（或更换）游戏升级补丁目录；可选，选中后运行时叠加到主游戏上。 */
    fun chooseUpdateFolder() = chooseFolder(Kind.UPDATE)

    /** 清空已保存的补丁目录（改回只使用主游戏）。 */
    fun clearUpdateFolder() {
        gamePrefs.clearUpdateDir()
    }

    private fun chooseFolder(kind: Kind) {
        if (activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingFolderKind = kind
            activity.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
        } else {
            showFolderDialog(kind)
        }
    }

    fun onPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQUEST_STORAGE) return
        // 授权后回到发起这次请求的目录流程，而不是一律回到游戏目录。
        val requested = pendingFolderKind
        pendingFolderKind = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showFolderDialog(requested ?: Kind.GAME)
        } else {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.storage_permission_title))
                .setMessage(activity.getString(R.string.storage_permission_message))
                .setPositiveButton(activity.getString(R.string.dialog_ok), null)
                .show()
        }
    }

    fun onFolderResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val kind = when (requestCode) {
            REQUEST_GAME_FOLDER -> Kind.GAME
            REQUEST_UPDATE_FOLDER -> Kind.UPDATE
            else -> return
        }
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            if (kind == Kind.GAME) ensureGameFolder()
            return
        }
        var dir = folderOf(data.data!!)
        if (dir != null && !isGame(dir)) {
            for (serial in SERIALS) {
                val nested = File(dir, serial)
                if (isGame(nested)) {
                    dir = nested
                    break
                }
            }
        }
        val problem = validate(kind, dir)
        if (problem != null) {
            AlertDialog.Builder(activity)
                .setTitle(
                    if (kind == Kind.UPDATE) activity.getString(R.string.not_a_game_update_folder)
                    else activity.getString(R.string.not_a_game_folder)
                )
                .setMessage(problem)
                .setPositiveButton(activity.getString(R.string.choose_again)) { _, _ -> showFolderDialog(kind) }
                .show()
            return
        }
        val path = dir!!.path
        if (kind == Kind.GAME) gamePrefs.setGameDir(path) else gamePrefs.setUpdateDir(path)
    }

    /** 目录是否可用；null 表示可用，否则返回给玩家看的原因。 */
    private fun validate(kind: Kind, dir: File?): String? {
        val folder = dir ?: return activity.getString(R.string.no_eboot)
        if (!isGame(folder)) {
            return activity.getString(R.string.no_eboot_path_fmt, folder.path)
        }
        if (kind == Kind.UPDATE) {
            val gamePath = gamePrefs.gameDir() ?: ""
            if (gamePath.isNotEmpty() && sameAs(folder, File(gamePath))) {
                return activity.getString(R.string.update_same_as_game)
            }
        }
        return null
    }

    private fun sameAs(a: File, b: File): Boolean =
        try { a.canonicalFile == b.canonicalFile } catch (_: Exception) { a.absolutePath == b.absolutePath }

    private fun showFolderDialog(kind: Kind) {
        val current = if (kind == Kind.UPDATE) gamePrefs.updateDir() else gamePrefs.gameDir()
        val isUpdate = kind == Kind.UPDATE
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(24))
            background = roundedBackground(0xff171411.toInt(), 0xffc6ad76.toInt(), 20)
        }
        val eyebrow = TextView(activity).apply {
            text = if (isUpdate) activity.getString(R.string.folder_eyebrow_update)
            else activity.getString(R.string.folder_eyebrow_game)
            textSize = 11f
            letterSpacing = 0.18f
            setTextColor(0xffc6ad76.toInt())
        }
        val title = TextView(activity).apply {
            text = if (isUpdate) activity.getString(R.string.folder_title_update)
            else activity.getString(R.string.folder_title_game)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xffe8dfc9.toInt())
            setPadding(0, dp(7), 0, 0)
        }
        val message = TextView(activity).apply {
            text = if (isUpdate)
                activity.getString(R.string.folder_message_update) +
                    (if (current != null) activity.getString(R.string.folder_current_selection_fmt, current) else "")
            else
                activity.getString(R.string.folder_message_game) +
                    (if (current != null) activity.getString(R.string.folder_current_selection_fmt, current) else "")
            textSize = 14f
            setTextColor(0xff8e9097.toInt())
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(12), 0, dp(20))
        }
        val choose = TextView(activity).apply {
            text = if (isUpdate) activity.getString(R.string.folder_choose_update)
            else activity.getString(R.string.folder_choose_game)
            gravity = Gravity.CENTER
            textSize = 13f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xffe8dfc9.toInt())
            background = roundedBackground(0xffb8172b.toInt(), 0xffc6ad76.toInt(), 12)
            setPadding(0, dp(15), 0, dp(15))
            isFocusable = true
        }
        panel.addView(eyebrow, LinearLayout.LayoutParams(-1, -2))
        panel.addView(title, LinearLayout.LayoutParams(-1, -2))
        panel.addView(message, LinearLayout.LayoutParams(-1, -2))
        panel.addView(choose, LinearLayout.LayoutParams(-1, dp(52)))

        // 首次设置不可被取消；当从 Home 页重新选择时已存在有效目录，
        // 因此允许玩家在不做任何修改的情况下退出。
        val dialog = AlertDialog.Builder(activity).setView(panel).setCancelable(current != null || isUpdate).create()
        choose.setOnClickListener {
            dialog.dismiss()
            try {
                activity.startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                    if (isUpdate) REQUEST_UPDATE_FOLDER else REQUEST_GAME_FOLDER,
                )
            } catch (ex: Exception) {
                AlertDialog.Builder(activity)
                    .setMessage(activity.getString(R.string.no_folder_picker_fmt, ex.toString()))
                    .setPositiveButton(activity.getString(R.string.dialog_ok), null)
                    .show()
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(dp(350), LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun isGame(dir: File?): Boolean = dir != null && File(dir, "eboot.bin").isFile

    private fun folderOf(tree: Uri): File? {
        return try {
            val id = android.provider.DocumentsContract.getTreeDocumentId(tree)
            val colon = id.indexOf(':')
            val volume = if (colon < 0) id else id.substring(0, colon)
            val path = if (colon < 0) "" else id.substring(colon + 1)
            val base = if ("primary".equals(volume, ignoreCase = true)) Environment.getExternalStorageDirectory()
            else File("/storage/$volume")
            if (path.isEmpty()) base else File(base, path)
        } catch (_: Exception) {
            null
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun roundedBackground(fill: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setCornerRadius(dp(radius).toFloat())
            setStroke(dp(1), stroke)
        }
}
