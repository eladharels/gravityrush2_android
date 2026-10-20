package com.gow3.android.data.model

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import com.gow3.android.BuildConfig
import java.io.File
import java.util.Locale

/**
 * 采集日志导出时所在设备与系统的上下文（机型、SoC、Android 版本、内核、内存、ABI、
 * 显示能力与 GPU 型号），渲染为 `Key: value` 行，
 * 作为导出 zip 的 `report.txt` 设备清单。
 *
 * 隐私边界：只包含设备/系统/应用版本字段；绝不包含序列号、IMEI、账号、位置或任何
 * 可识别到具体用户的标识。任何字段读取失败都降级为占位值，绝不抛出使导出失败。
 */
internal object DeviceInfo {

    /** 渲染 `Key: value` 的设备/系统/GPU 清单行（不含导出条目说明，由调用方追加）。 */
    fun report(activity: Activity): String {
        val s = StringBuilder()
        line(s, "App", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        line(s, "Device", deviceLine())
        line(s, "Android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
        line(s, "ABI", Build.SUPPORTED_ABIS.joinToString(", "))
        line(s, "RAM", ram(activity))
        line(s, "Display", displayLine(activity))
        line(s, "GPU", gpuModel())
        line(s, "GPU chip", gpuChipLine())
        line(s, "Kernel", System.getProperty("os.version") ?: "unknown")
        return s.toString()
    }

    private fun line(s: StringBuilder, key: String, value: String) {
        s.append(key).append(": ").append(value).append('\n')
    }

    /** 厂商+机型，API 31+ 且 SoC 可用时附带 SoC 厂商与型号。 */
    private fun deviceLine(): String {
        val s = StringBuilder(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        if (Build.VERSION.SDK_INT >= 31 && !Build.SOC_MODEL.isNullOrEmpty() && Build.SOC_MODEL != "unknown") {
            s.append(", SoC ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL)
        }
        return s.toString()
    }

    /** 总内存（GB），失败时降级为 unknown。 */
    private fun ram(activity: Activity): String = try {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val memory = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memory)
            if (memory.totalMem > 0) {
                String.format(Locale.US, "%.1f GB total", memory.totalMem / 1073741824.0)
            } else "unknown"
        } else "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    /** 屏幕物理分辨率、当前刷新率与支持的全部刷新率；失败时降级为 unknown。 */
    private fun displayLine(activity: Activity): String = try {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
        if (display == null) {
            "unknown"
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val rates = try {
                display.supportedModes
                    .map { Math.round(it.refreshRate).toInt() }
                    .distinct().sorted().joinToString("/")
            } catch (_: Exception) {
                ""
            }
            val suffix = if (rates.isNotEmpty()) ", supported $rates Hz" else ""
            "${metrics.widthPixels}x${metrics.heightPixels} at " +
                String.format(Locale.US, "%.1f", display.refreshRate) + " Hz$suffix"
        }
    } catch (_: Exception) {
        "unknown"
    }

    /**
     * GPU 型号尽力而为读取：优先读只读 sysfs 节点 `/sys/class/kgsl/kgsl-3d0/gpu_model`
     * 的首行（Adreno 设备常见），失败回退到设备节点（`/dev/kgsl-3d0`→Adreno、
     * `/dev/mali0`→Mali），再失败回退 `Build.HARDWARE`，最后写 unknown。
     */
    private fun gpuModel(): String = try {
        File("/sys/class/kgsl/kgsl-3d0/gpu_model")
            .takeIf { it.isFile }
            ?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: gpuByDeviceNode()
    } catch (_: Exception) {
        gpuByDeviceNode()
    }

    /** GPU 芯片来源：设备节点存在性 + 硬件标识；无法判定时写 unknown。 */
    private fun gpuChipLine(): String = try {
        when {
            File("/dev/kgsl-3d0").exists() -> "kgsl-3d0 (Adreno)"
            File("/dev/mali0").exists() -> "mali0 (Mali)"
            else -> Build.HARDWARE.ifBlank { "unknown" }
        }
    } catch (_: Exception) {
        "unknown"
    }

    private fun gpuByDeviceNode(): String = try {
        when {
            File("/dev/kgsl-3d0").exists() -> "Adreno"
            File("/dev/mali0").exists() -> "Mali"
            else -> Build.HARDWARE.ifBlank { "unknown" }
        }
    } catch (_: Exception) {
        "unknown"
    }
}
