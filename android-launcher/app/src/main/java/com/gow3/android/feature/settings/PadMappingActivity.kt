package com.gow3.android.feature.settings

import com.gow3.android.R
import com.gow3.android.core.settings.GamePrefs
import com.gow3.android.core.settings.LanguageManager
import com.gow3.android.game.PadMapping

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 物理手柄按键映射页（add-pad-button-mapping task 2.2）：View 层全屏 Activity。
 *
 * 不用 Compose：`SOURCE_JOYSTICK` 的 `MotionEvent` 进不了 Compose 事件流，捕获依赖
 * 本类重写的 [dispatchKeyEvent] / [dispatchGenericMotionEvent]（与 GameActivity 同一形态）。
 *
 * 交互（spec：实时捕获绑定）：16 行 = 16 个 PS 按钮，点行进入捕获态——下一颗手柄按键
 * （scanCode 优先，回落 keyCode）或越阈的扳机/HAT 半轴即成为该按钮的绑定；捕获态吞掉
 * 一切按键（含 BACK 与手柄系统键，防误退出），底部提示条可点击取消，10s 无输入自动取消。
 * 「恢复默认」清空映射键（spec：默认映射与恢复默认）；BACK / 完成即退出，改动即时持久化。
 */
class PadMappingActivity : Activity() {

    private lateinit var prefs: GamePrefs
    /** 页面内存中的工作副本；每次绑定后整体持久化（setPadMapping 整表替换）。 */
    private var mapping: Map<String, String> = emptyMap()
    /** 当前捕获中的 PS 按钮名；null = 非捕获态。 */
    private var capturing: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val captureTimeout = Runnable { cancelCapture() }

    private lateinit var rowsContainer: LinearLayout
    private lateinit var hintBar: TextView
    private val rowBackgrounds = HashMap<String, View>()
    private val rowBoundLabels = HashMap<String, TextView>()

    /** 简体中文注释约定：语言覆盖与 MainActivity.attachBaseContext 同一模式。 */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(LanguageManager.resolve(newBase))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = GamePrefs(this)
        mapping = prefs.padMapping()
        setContentView(buildUi())
    }

    // ---- 界面构建 ----

    private fun dp(n: Int): Int = Math.round(n * resources.displayMetrics.density)

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.background = ColorDrawable(0xF214100E.toInt()) // 深底，与 launcher 暗色一致
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        column.addView(title(R.string.pad_mapping_title))
        column.addView(caption(R.string.pad_mapping_desc))
        rowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 底部留出悬浮提示条的高度,最后一行不被遮挡。
            setPadding(0, dp(10), 0, dp(96))
        }
        PadMapping.PS_BUTTONS.forEach { rowsContainer.addView(buildRow(it)) }
        val scroll = ScrollView(this).apply { addView(rowsContainer) }
        column.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // 底部：恢复默认 / 完成 两个操作 + 捕获提示条。
        column.addView(buildFooter())
        hintBar = TextView(this).apply {
            background = rounded(0x33E8B34C)
            setPadding(dp(14), dp(12), dp(14), dp(12)
            )
            textSize = 13f
            text = getString(R.string.pad_mapping_hint_idle)
            // 提示条即取消出口（spec 风险面:捕获不吞掉自身的取消路径）。
            setOnClickListener { cancelCapture() }
        }
        root.addView(column)
        // 提示条悬浮在底部,不被列表滚动带走(不设 gravity 会落在顶部盖住标题)。
        val hintParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hintParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        hintParams.setMargins(dp(20), 0, dp(20), dp(20))
        root.addView(hintBar, hintParams)
        return root
    }

    private fun title(res: Int) = TextView(this).apply {
        text = getString(res)
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
    }

    private fun caption(res: Int) = TextView(this).apply {
        text = getString(res)
        textSize = 12f
        setPadding(0, dp(4), 0, dp(8))
        setTextColor(0xFFBDB2A8.toInt())
    }

    /** 一行 = PS 按钮名 + 当前生效来源；点击进入捕获态。 */
    private fun buildRow(button: String): View {
        val psLabel = TextView(this).apply {
            text = getString(psButtonLabel(button))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val boundLabel = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.END
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0x14FFFFFF)
            addView(psLabel, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(boundLabel, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener {
                if (capturing == button) cancelCapture() else startCapture(button)
            }
        }
        rowBackgrounds[button] = row
        rowBoundLabels[button] = boundLabel
        refreshRow(button)
        return row
    }

    private fun buildFooter(): View {
        fun button(labelRes: Int, action: () -> Unit) = TextView(this).apply {
            text = getString(labelRes)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFE8B34C.toInt())
            gravity = Gravity.CENTER
            background = rounded(0x33E8B34C)
            setOnClickListener { action() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
            // 底部留出悬浮提示条的高度,按钮不被遮挡(与列表底部留白同值)。
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(8), 0, dp(84))
            addView(button(R.string.pad_mapping_restore) { restoreDefaults() },
                LinearLayout.LayoutParams(dp(120), dp(40)).apply { marginEnd = dp(10) })
            addView(button(R.string.pad_mapping_done) { finish() },
                LinearLayout.LayoutParams(dp(120), dp(40)))
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
    }

    // ---- 行状态刷新 ----

    private fun refreshRow(button: String) {
        val label = rowBoundLabels[button] ?: return
        val custom = mapping.entries.firstOrNull { it.value == button }?.key
        when {
            custom != null -> {
                label.text = describeSource(custom)
                label.setTextColor(0xFFE8B34C.toInt())
            }
            // 默认来源被别的按钮占用 → 诚实显示未绑定(spec:实时捕获绑定的来源重指派)。
            claimedByOther(button) -> {
                label.text = getString(R.string.pad_mapping_unbound)
                label.setTextColor(0xFF8A8078.toInt())
            }
            else -> {
                val def = PadMapping.defaultSourceFor(button)
                label.text = if (def == null) getString(R.string.pad_mapping_unbound)
                    else getString(R.string.pad_mapping_default_fmt, describeSource(def))
                label.setTextColor(0xFFBDB2A8.toInt())
            }
        }
        rowBackgrounds[button]?.setBackgroundColor(
            if (capturing == button) 0x66E8B34C.toInt() else 0x14FFFFFF)
    }

    /** 该按钮的默认物理来源当前是否已被映射指给别的按钮。 */
    private fun claimedByOther(button: String): Boolean {
        val def = PadMapping.defaultSourceFor(button) ?: return false
        val owner = mapping[def]
        return owner != null && owner != button
    }

    private fun refreshAllRows() = PadMapping.PS_BUTTONS.forEach { refreshRow(it) }

    // ---- 捕获态 ----

    private fun startCapture(button: String) {
        handler.removeCallbacks(captureTimeout)
        capturing = button
        hintBar.text = getString(R.string.pad_mapping_hint_capture)
        refreshAllRows()
        // 10s 无输入自动取消(spec/design 风险面:吞键过宽把玩家困在捕获态)。
        handler.postDelayed(captureTimeout, 10_000)
    }

    private fun cancelCapture() {
        handler.removeCallbacks(captureTimeout)
        capturing = null
        hintBar.text = getString(R.string.pad_mapping_hint_idle)
        refreshAllRows()
    }

    /** 捕获完成:源 key 写入工作副本并整体持久化,行立即刷新(spec:改动持久化)。 */
    private fun completeCapture(source: String) {
        val button = capturing ?: return
        handler.removeCallbacks(captureTimeout)
        mapping = HashMap(mapping).apply { put(source, button) }
        prefs.setPadMapping(mapping)
        capturing = null
        hintBar.text = getString(R.string.pad_mapping_hint_idle)
        refreshAllRows()
    }

    private fun restoreDefaults() {
        handler.removeCallbacks(captureTimeout)
        capturing = null
        prefs.clearPadMapping()
        mapping = emptyMap()
        hintBar.text = getString(R.string.pad_mapping_hint_idle)
        refreshAllRows()
        Toast.makeText(this, R.string.pad_mapping_restored, Toast.LENGTH_SHORT).show()
    }

    // ---- 事件捕获(design D2:View 层接手柄事件,Compose 收不到 joystick motion) ----

    /** KeyEvent → 源 key(design D1:scanCode 落在 Linux 手柄规范区间优先,回落 keyCode)。 */
    private fun sourceKeyFor(e: KeyEvent): String? {
        val scan = e.scanCode
        if (scan in 304..318) return PadMapping.scanSource(scan)
        return if (e.keyCode != KeyEvent.KEYCODE_UNKNOWN) PadMapping.keySource(e.keyCode) else null
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (capturing != null) {
            // 吞掉捕获中的一切按键(含 BACK/options 系统键):只有 ACTION_DOWN 首次触发绑定。
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                sourceKeyFor(e)?.let { completeCapture(it) }
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (capturing != null && e.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) {
            for (axis in PadMapping.CAPTURABLE_AXES) {
                val value = e.getAxisValue(axis)
                if (Math.abs(value) >= 0.5f) {
                    completeCapture(PadMapping.axisSource(axis, value))
                    break
                }
            }
            return true // 捕获中吞掉摇杆移动,防止列表/系统误触
        }
        return super.dispatchGenericMotionEvent(e)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(captureTimeout)
    }

    // ---- 展示辅助 ----

    /** 源 key → 人读标签(技术名,专有名词保持英文约定);未知编码原样回显。 */
    private fun describeSource(key: String): String = when {
        key.startsWith("scan:") -> {
            val name = key.substringAfter("scan:").toIntOrNull()?.let { SCAN_LABELS[it] }
            name ?: key
        }
        key.startsWith("key:") -> key.substringAfter("key:").toIntOrNull()
            ?.let { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_").replace('_', ' ') } ?: key
        key.startsWith("axis:") -> {
            val body = key.removePrefix("axis:")
            val axis = body.substringBefore(':').toIntOrNull()
            val sign = body.substringAfterLast(':')
            axis?.let { AXIS_LABELS[it] }?.let { "$it$sign" } ?: key
        }
        else -> key
    }

    private fun psButtonLabel(button: String): Int = when (button) {
        "cross" -> R.string.pad_button_cross
        "circle" -> R.string.pad_button_circle
        "square" -> R.string.pad_button_square
        "triangle" -> R.string.pad_button_triangle
        "l1" -> R.string.pad_button_l1
        "r1" -> R.string.pad_button_r1
        "l2" -> R.string.pad_button_l2
        "r2" -> R.string.pad_button_r2
        "l3" -> R.string.pad_button_l3
        "r3" -> R.string.pad_button_r3
        "options" -> R.string.pad_button_options
        "touchpad" -> R.string.pad_button_touchpad
        "up" -> R.string.pad_button_up
        "down" -> R.string.pad_button_down
        "left" -> R.string.pad_button_left
        else -> R.string.pad_button_right
    }

    companion object {
        /** Linux 手柄规范 scanCode → 位置名(数值与 PadMapping 默认表一致)。 */
        private val SCAN_LABELS = mapOf(
            304 to "Pad A (South)", 305 to "Pad B (East)", 306 to "Pad C",
            307 to "Pad X (North)", 308 to "Pad Y (West)", 309 to "Pad Z",
            310 to "Pad L1", 311 to "Pad R1", 312 to "Pad L2", 313 to "Pad R2",
            314 to "Pad Select", 315 to "Pad Start", 316 to "Pad Mode",
            317 to "Pad L3", 318 to "Pad R3",
        )

        /** 可捕获轴 id → 名称(与 android.view.MotionEvent 的 AXIS_* 常量同值)。 */
        private val AXIS_LABELS = mapOf(
            PadMapping.AXIS_HAT_X to "HAT X", PadMapping.AXIS_HAT_Y to "HAT Y",
            PadMapping.AXIS_LTRIGGER to "LTRIGGER", PadMapping.AXIS_RTRIGGER to "RTRIGGER",
            PadMapping.AXIS_GAS to "GAS", PadMapping.AXIS_BRAKE to "BRAKE",
        )
    }
}
