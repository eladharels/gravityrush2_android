package com.gow3.android.core.settings

import java.io.File

/**
 * 帧生成设置（change add-native-frame-generation）：`gow3.ini` 的 `framegen_*` 键
 * 读写与启动期 vsync 联动。ini 解析/写回复用 [Gow3Settings]（被改键替换所在行、
 * 缺失键追加、其余行原样保留——启动器补丁选择/手柄配置等不受影响）。
 *
 * 键：`framegen_enabled`（0/1）、`framegen_multiplier`（2–4）、
 * `framegen_flow_scale`（0.25–1.00）。兼容读取旧 `lsfg_enabled`（=1 且无新键时
 * 视为开启）。旧 `lsfg_multiplier/lsfg_flow_scale/lsfg_performance` 仅作初次
 * 取值迁移（读），不再写回。
 */
object FrameGenSettings {
    const val KEY_ENABLED = "framegen_enabled"
    const val KEY_MULTIPLIER = "framegen_multiplier"
    const val KEY_FLOW_SCALE = "framegen_flow_scale"
    const val LEGACY_ENABLED = "lsfg_enabled"
    const val LEGACY_MULTIPLIER = "lsfg_multiplier"
    const val LEGACY_FLOW_SCALE = "lsfg_flow_scale"
    const val KEY_VSYNC = "vsync"
    const val STATE_FILE_NAME = "framegen.state"
    const val STATS_FILE_NAME = "framegen.stats"

    /** HUD 遥测快照（`framegen.stats`，游戏侧 presenter 每秒原子写）。 */
    // @JvmField：Java 侧（PerfHudOverlay）直接字段访问。
    data class Stats(@JvmField val sourceFps: Float,
                     @JvmField val presentedFps: Float,
                     @JvmField val multiplier: Int)

    /** 读取遥测；文件缺失/格式不符/倍率<2（未生效）返回 null。 */
    @JvmStatic
    fun readStats(file: File): Stats? {
        val ini = try { Gow3Settings.read(file) } catch (_: Exception) { return null }
        val mult = ini["multiplier"]?.toIntOrNull() ?: return null
        if (mult < 2) return null
        val src = ini["source_fps"]?.toFloatOrNull() ?: return null
        val pres = ini["presented_fps"]?.toFloatOrNull() ?: return null
        if (src <= 0f || pres <= 0f) return null
        return Stats(src, pres, mult)
    }

    const val DEFAULT_MULTIPLIER = 2
    const val DEFAULT_FLOW_SCALE = 0.80f

    // @JvmField：Java 侧（GameActivity）直接字段访问。
    data class Config(@JvmField val enabled: Boolean,
                      @JvmField val multiplier: Int,
                      @JvmField val flowScale: Float)

    @JvmStatic
    fun clampMultiplier(v: Int): Int = v.coerceIn(2, 4)
    @JvmStatic
    fun clampFlowScale(v: Float): Float = v.coerceIn(0.25f, 1.00f)

    /** 读取当前配置；键缺失/非法回退默认（enabled 默认关，见 spec「默认关闭」）。 */
    @JvmStatic
    fun read(file: File): Config {
        val ini = Gow3Settings.read(file)
        val legacyEnabled = ini[LEGACY_ENABLED] == "1"
        val enabled = when (ini[KEY_ENABLED]) {
            "1" -> true
            "0" -> false
            else -> legacyEnabled // 新键缺失：旧键兼容
        }
        val multiplier = ini[KEY_MULTIPLIER]?.toIntOrNull()
            ?: ini[LEGACY_MULTIPLIER]?.toIntOrNull()?.takeIf { it in 2..4 }
            ?: DEFAULT_MULTIPLIER
        val flow = ini[KEY_FLOW_SCALE]?.toFloatOrNull()
            ?: ini[LEGACY_FLOW_SCALE]?.toFloatOrNull()?.takeIf { it in 0.25f..1.0f }
            ?: DEFAULT_FLOW_SCALE
        return Config(enabled, clampMultiplier(multiplier), clampFlowScale(flow))
    }

    /** 保存（Gow3Settings.update 整行保留语义）。返回 false = 写失败（原文件不变）。 */
    @JvmStatic
    fun save(file: File, config: Config): Boolean = Gow3Settings.update(
        file,
        mapOf(
            KEY_ENABLED to if (config.enabled) "1" else "0",
            KEY_MULTIPLIER to clampMultiplier(config.multiplier).toString(),
            KEY_FLOW_SCALE to clampFlowScale(config.flowScale).toString(),
        ),
    )

    /**
     * 运行期状态文件（change add-native-frame-generation，design D4）：`framegen.state`
     * （BB lsfg.state 同格式），游戏内 watcher（~250ms）热应用。原子写（tmp+rename）。
     * `performance`/`fp16` 沿用 BB 默认（轻模型 + FP16 优先）。
     */
    @JvmStatic
    fun writeState(file: File, config: Config): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            tmp.writeText(
                buildString {
                    appendLine("multiplier=${if (config.enabled) clampMultiplier(config.multiplier) else 0}")
                    appendLine("flow=${clampFlowScale(config.flowScale)}")
                    appendLine("performance=1")
                    appendLine("fp16=1")
                },
            )
            // POSIX rename 原子替换既有文件，无窗口期；失败清理 .tmp 残留。
            if (tmp.renameTo(file)) true else { tmp.delete(); false }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    /**
     * 启动期 FIFO 前提联动（design D6）：帧生成开启且 `vsync≠1` 时写 `vsync=1`。
     * 返回 true = 发生了联动写入（调用方据此提示）。帧生成关闭时不改任何键。
     */
    @JvmStatic
    fun ensureVsyncForFrameGen(file: File): Boolean {
        val ini = Gow3Settings.read(file)
        if (ini[KEY_ENABLED] != "1") return false
        if (ini[KEY_VSYNC] == "1") return false
        return Gow3Settings.update(file, mapOf(KEY_VSYNC to "1"))
    }
}
