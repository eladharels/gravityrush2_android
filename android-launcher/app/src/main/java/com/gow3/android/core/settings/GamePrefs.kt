package com.gow3.android.core.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * `SharedPreferences("gow3")` 的唯一读写口（launcher 与 game 两侧共享的契约层）。
 *
 * 键名、默认值与读写全部集中在本类：其他代码不得再直接调用
 * `getSharedPreferences("gow3")`。历史上这些键散落在两个包 20+ 处裸访问，
 * 收敛后改键名/改默认值只需看这一处。
 *
 * 类本身是 public 而非 `internal`：`ro/cobrabm`（迁移后 `game/`）侧的 Java 代码
 * 也要调用，而 Kotlin `internal` 成员在字节码里会被改名，Java 无法引用。
 *
 * 键名与默认值是持久化契约：历史 APK 升级安装后必须读到原值，MUST NOT 改名或
 * 改默认值。新增键一律加在本类并在这里登记。
 */
class GamePrefs internal constructor(private val prefs: SharedPreferences) {

    /** 应用入口使用的构造：经 Gow3App 容器注入 Context。internal 主构造同时允许
     *  同模块宿主单测直接注入 SharedPreferences 替身（无 Robolectric 的替代方案）。 */
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    )

    /**
     * 平台监听器的注册通道（`game_dir`/`update_dir` 变化驱动 UI 重扫）。
     * 仅供注册/注销监听使用，读写仍应走本类方法。
     */
    val raw: SharedPreferences get() = prefs

    // ---- 游戏与升级补丁目录 ----

    fun gameDir(): String? = prefs.getString(KEY_GAME_DIR, null)

    fun setGameDir(path: String) {
        prefs.edit().putString(KEY_GAME_DIR, path).apply()
    }

    fun updateDir(): String? = prefs.getString(KEY_UPDATE_DIR, null)

    fun setUpdateDir(path: String) {
        prefs.edit().putString(KEY_UPDATE_DIR, path).apply()
    }

    fun clearUpdateDir() {
        prefs.edit().remove(KEY_UPDATE_DIR).apply()
    }

    // ---- 触屏手柄与物理手柄 ----

    fun controllerOverlayEnabled(): Boolean = prefs.getBoolean(KEY_CONTROLLER_OVERLAY, false)

    fun setControllerOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTROLLER_OVERLAY, enabled).apply()
    }

    // 「交换方块/三角」已删除（add-pad-button-mapping）:特例开关被完整按键映射取代。
    // 历史安装残留的 swap_square_triangle 键值被有意忽略（不迁移、不清理），无副作用。

    // ---- 物理手柄按键映射（android-physical-pad-mapping） ----

    /**
     * 玩家自定义的「物理源 → PS 按钮」映射；空表 = 全默认（spec：默认映射与恢复默认）。
     * JSON 编解码（容错解析，损坏一律回落空表）与值域校验分布在本类与
     * `game/PadMapping`：本类只管prefs 读写与 JSON 结构，目标按钮名合法性由
     * PadMapping.lookup 在翻译时兜底。
     */
    fun padMapping(): Map<String, String> =
        prefs.getString(KEY_PAD_MAPPING, null)?.let(::parsePadMappingJson) ?: emptyMap()

    /** 整体替换映射并持久化；空表时清键（恢复「全默认」的存储形态）。 */
    fun setPadMapping(mapping: Map<String, String>) {
        val json = padMappingJson(mapping)
        val editor = prefs.edit()
        if (json == null) editor.remove(KEY_PAD_MAPPING) else editor.putString(KEY_PAD_MAPPING, json)
        editor.apply()
    }

    fun clearPadMapping() {
        prefs.edit().remove(KEY_PAD_MAPPING).apply()
    }

    /** 序列化为 `{"version":1,"map":{...}}`；空表返回 null（调用方清键回落全默认）。 */
    private fun padMappingJson(mapping: Map<String, String>): String? {
        if (mapping.isEmpty()) return null
        val body = mapping.entries.joinToString(",") { entry ->
            "${padMappingQuote(entry.key)}:${padMappingQuote(entry.value)}"
        }
        return "{\"version\":1,\"map\":{$body}}"
    }

    private fun padMappingQuote(text: String): String = buildString(text.length + 2) {
        append('"')
        for (ch in text) when {
            ch == '\\' || ch == '"' -> append('\\').append(ch)
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' -> append("\\u").append(String.format("%04x", ch.code))
            else -> append(ch)
        }
        append('"')
    }

    /**
     * 平面映射的容错 JSON 解析：只接受本类自产的结构（顶层对象、可含 "version" 数字与
     * "map" 字符串对象）；截断、未知顶层键的非字符串值或任何结构异常一律返回空表
     * （spec：映射数据损坏时回落默认映射，不得抛出）。
     */
    private fun parsePadMappingJson(text: String): Map<String, String> {
        var i = skipPadMappingWs(text, 0)
        if (i >= text.length || text[i] != '{') return emptyMap()
        i = skipPadMappingWs(text, i + 1)
        var result: Map<String, String> = emptyMap()
        while (i < text.length) {
            if (text[i] == '}') return result
            val key = parsePadMappingString(text, i) ?: return emptyMap()
            i = skipPadMappingWs(text, key.second)
            if (i >= text.length || text[i] != ':') return emptyMap()
            i = skipPadMappingWs(text, i + 1)
            if (key.first == "map") {
                if (i >= text.length || text[i] != '{') return emptyMap()
                i = skipPadMappingWs(text, i + 1)
                val entries = LinkedHashMap<String, String>()
                while (true) {
                    if (i >= text.length) return emptyMap()
                    if (text[i] == '}') { i++; break }
                    val entry = parsePadMappingString(text, i) ?: return emptyMap()
                    i = skipPadMappingWs(text, entry.second)
                    if (i >= text.length || text[i] != ':') return emptyMap()
                    i = skipPadMappingWs(text, i + 1)
                    val value = parsePadMappingString(text, i) ?: return emptyMap()
                    i = skipPadMappingWs(text, value.second)
                    entries[entry.first] = value.first
                    if (i < text.length && text[i] == ',') i = skipPadMappingWs(text, i + 1)
                }
                result = entries
            } else {
                // 顶层只可能再出现 "version" 数字；其余未知键要求字符串值并忽略。
                if (i < text.length && (text[i] == '-' || text[i].isDigit())) {
                    while (i < text.length && (text[i].isDigit() || text[i] in "+-.eE")) i++
                } else {
                    val ignored = parsePadMappingString(text, i) ?: return emptyMap()
                    i = ignored.second
                }
            }
            i = skipPadMappingWs(text, i)
            if (i < text.length && text[i] == ',') i = skipPadMappingWs(text, i + 1)
        }
        return emptyMap()
    }

    /** 从 [start] 解析一个带转义的 JSON 字符串；返回内容与结束后的下标，失败返回 null。 */
    private fun parsePadMappingString(text: String, start: Int): Pair<String, Int>? {
        var i = skipPadMappingWs(text, start)
        if (i >= text.length || text[i] != '"') return null
        i++
        val content = StringBuilder()
        while (i < text.length) {
            when (val ch = text[i]) {
                '"' -> return content.toString() to (i + 1)
                '\\' -> {
                    if (i + 1 >= text.length) return null
                    when (val esc = text[i + 1]) {
                        '"' -> content.append('"')
                        '\\' -> content.append('\\')
                        '/' -> content.append('/')
                        'n' -> content.append('\n')
                        'r' -> content.append('\r')
                        't' -> content.append('\t')
                        'u' -> {
                            if (i + 6 > text.length) return null
                            val hex = text.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                            content.append(hex.toChar())
                            i += 4
                        }
                        else -> return null
                    }
                    i += 2
                }
                else -> { content.append(ch); i++ }
            }
        }
        return null
    }

    private fun skipPadMappingWs(text: String, index: Int): Int {
        var i = index
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** 触屏按键布局的 X 坐标；未拖动过时返回布局默认值。 */
    fun overlayX(name: String, default: Float): Float =
        prefs.getFloat(OVERLAY_KEY_PREFIX + "x_" + name, default)

    /** 触屏按键布局的 Y 坐标；未拖动过时返回布局默认值。 */
    fun overlayY(name: String, default: Float): Float =
        prefs.getFloat(OVERLAY_KEY_PREFIX + "y_" + name, default)

    /** 拖动结束时持久化一个按键的位置（x/y 成对写入）。 */
    fun setOverlayPosition(name: String, x: Float, y: Float) {
        prefs.edit()
            .putFloat(OVERLAY_KEY_PREFIX + "x_" + name, x)
            .putFloat(OVERLAY_KEY_PREFIX + "y_" + name, y)
            .apply()
    }

    /** 触屏按键的尺寸因子（1.0 = 默认大小）；本特性之前保存的布局没有该键，回落 1.0。 */
    fun overlaySize(name: String, default: Float = 1f): Float =
        prefs.getFloat(OVERLAY_KEY_PREFIX + "s_" + name, default)

    /** 捏合缩放结束时持久化一个按键的尺寸因子（钳制 0.6..2.0，与编辑器一致）。 */
    fun setOverlaySize(name: String, value: Float) {
        val clamped = value.coerceIn(0.6f, 2f)
        prefs.edit().putFloat(OVERLAY_KEY_PREFIX + "s_" + name, clamped).apply()
    }

    /** 重置触屏布局：删除全部 `overlay_*` 键，按键回到代码内默认位置。 */
    fun clearOverlayLayout() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(OVERLAY_KEY_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    // ---- 性能 HUD（android-perf-hud-overlay） ----

    /** 性能 HUD 开关；默认开启（用户未操作过开关时直接可见帧率/GPU/CPU），与 spec「默认开启」一致。
     *  显式关闭过的玩家偏好已持久化，不受默认值影响。 */
    fun perfHudEnabled(): Boolean = prefs.getBoolean(KEY_PERF_HUD_ENABLED, true)

    fun setPerfHudEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERF_HUD_ENABLED, enabled).apply()
    }

    /**
     * HUD 左上角 X 坐标；从未拖动过时返回 null（由 PerfHudOverlay 按默认右上角回退，
     * NaN/越界的兜底校验在 PerfHudMetrics.resolvePosition，两处共同构成 spec 的
     * 「异常位置数据不致丢失 HUD」契约）。
     */
    fun perfHudX(): Float? =
        if (prefs.contains(KEY_PERF_HUD_X)) prefs.getFloat(KEY_PERF_HUD_X, 0f) else null

    /** HUD 左上角 Y 坐标；语义同 [perfHudX]。 */
    fun perfHudY(): Float? =
        if (prefs.contains(KEY_PERF_HUD_Y)) prefs.getFloat(KEY_PERF_HUD_Y, 0f) else null

    /** 拖动松手时持久化 HUD 位置（x/y 成对写入）。 */
    fun setPerfHudPosition(x: Float, y: Float) {
        prefs.edit()
            .putFloat(KEY_PERF_HUD_X, x)
            .putFloat(KEY_PERF_HUD_Y, y)
            .apply()
    }

    // ---- 界面语言 ----

    /** 语言覆盖值；未设置时为 [LANGUAGE_FOLLOW_SYSTEM]。 */
    fun language(): String = prefs.getString(KEY_LANGUAGE, LANGUAGE_FOLLOW_SYSTEM) ?: LANGUAGE_FOLLOW_SYSTEM

    fun setLanguage(value: String) {
        prefs.edit().putString(KEY_LANGUAGE, value).apply()
    }

    // ---- 驱动下载镜像代理 ----

    /** 玩家存储的原始镜像前缀选择；null/空白 = 未选择（跟随语言默认）。 */
    fun driverProxyRaw(): String? = prefs.getString(KEY_DRIVER_PROXY_PREFIX, null)

    fun setDriverProxyRaw(value: String) {
        prefs.edit().putString(KEY_DRIVER_PROXY_PREFIX, value).apply()
    }

    fun clearDriverProxy() {
        prefs.edit().remove(KEY_DRIVER_PROXY_PREFIX).apply()
    }

    // ---- 驱动包「已下载」标记 ----

    fun downloadedDriverUrls(): Set<String> =
        prefs.getStringSet(KEY_DRIVER_DOWNLOADED_URLS, emptySet()).orEmpty()

    fun markDriverUrlDownloaded(url: String) {
        prefs.edit().putStringSet(KEY_DRIVER_DOWNLOADED_URLS, downloadedDriverUrls() + url).apply()
    }

    companion object {
        /** SharedPreferences 文件名，与历史版本一致；改名会让玩家丢掉全部设置。 */
        const val NAME = "gow3"

        const val KEY_GAME_DIR = "game_dir"
        const val KEY_UPDATE_DIR = "update_dir"
        const val KEY_CONTROLLER_OVERLAY = "controller_overlay"
        // KEY_SWAP_SQUARE_TRIANGLE("swap_square_triangle")已随按键映射功能删除,历史值残留无害。
        const val KEY_PAD_MAPPING = "pad_mapping"
        const val KEY_PERF_HUD_ENABLED = "perf_hud_enabled"
        const val KEY_PERF_HUD_X = "perf_hud_x"
        const val KEY_PERF_HUD_Y = "perf_hud_y"
        const val KEY_LANGUAGE = "language"
        const val KEY_DRIVER_PROXY_PREFIX = "driver_proxy_prefix"
        const val KEY_DRIVER_DOWNLOADED_URLS = "driver_downloaded_urls"

        /** 触屏布局键前缀：`overlay_x_<按键>` / `overlay_y_<按键>`。 */
        const val OVERLAY_KEY_PREFIX = "overlay_"

        /** 「跟随系统」的语言覆盖值（与 LanguageManager.FOLLOW_SYSTEM 同值）。 */
        const val LANGUAGE_FOLLOW_SYSTEM = "system"
    }
}
