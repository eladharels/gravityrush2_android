package com.gow3.android.game;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 物理手柄按键到 PS 按钮的用户映射（change add-pad-button-mapping）。
 *
 *  纯 Java、零 Android 依赖：源 key 编码、默认表与查表规则都在本类，
 *  宿主单测直接覆盖（PadMappingTest），PadBridge 只做事件转发。
 *
 *  源 key 编码（design D1）：
 *    "scan:<n>"          Linux 手柄规范 scanCode（304..318，跨厂商稳定）
 *    "key:<keyCode>"     Android keyCode（蓝牙手柄与无线接收器常见路径）
 *    "axis:<id>:<sign>"  模拟轴半轴（'+'/'-'，sign 取按下方向；仅扳机与 HAT 参与捕获）
 *  目标值域 = [PS_BUTTONS] 的 16 个按钮名，与 pad 状态文件词表一致。
 *
 *  默认表逐条对应历史硬编码的 PadBridge.name() 行为（含 314=Select→touchpad、
 *  315=Start→options 的既有约定）：无映射或映射条目非法时，翻译结果与映射功能
 *  引入前完全一致（spec：默认映射与恢复默认）。 */
public final class PadMapping {
    private PadMapping() {}

    /** 可绑定的 16 个 PS 按钮，顺序即设置页展示顺序。 */
    public static final String[] PS_BUTTONS = {
        "cross", "circle", "square", "triangle",
        "l1", "r1", "l2", "r2", "l3", "r3",
        "options", "touchpad", "up", "down", "left", "right",
    };

    // 捕获可用的模拟轴 id（数值对应 android.view.MotionEvent 的 AXIS_* 常量；
    // 纯类内不引用 Android 类型，摇杆 X/Y/Z/RZ 不参与捕获，见 design D6）。
    public static final int AXIS_HAT_X = 15, AXIS_HAT_Y = 16;
    public static final int AXIS_LTRIGGER = 17, AXIS_RTRIGGER = 18;
    public static final int AXIS_GAS = 22, AXIS_BRAKE = 23;
    /** 参与捕获的轴全集；遍历顺序即运行期查表顺序。 */
    public static final int[] CAPTURABLE_AXES = {
        AXIS_HAT_X, AXIS_HAT_Y, AXIS_LTRIGGER, AXIS_RTRIGGER, AXIS_GAS, AXIS_BRAKE,
    };

    /** Linux 手柄规范 scanCode → 默认 PS 按钮（与历史 name() 一致；306/309/316 无默认）。 */
    private static final Map<Integer, String> SCAN_DEFAULTS = buildScanDefaults();
    /** Android keyCode（KeyEvent 的 BUTTON_x 与 DPAD_x 系列）到默认 PS 按钮。 */
    private static final Map<Integer, String> KEY_DEFAULTS = buildKeyDefaults();

    private static Map<Integer, String> buildScanDefaults() {
        // 数值即 KeyEvent 扫描码：304=A/南, 305=B/东, 307=X/北, 308=Y/西, 310/311=L1/R1,
        // 312/313=L2/R2, 314=Select, 315=Start, 317/318=L3/R3（306/309/316 为保留键）。
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(304, "cross"); m.put(305, "circle"); m.put(307, "square"); m.put(308, "triangle");
        m.put(310, "l1"); m.put(311, "r1"); m.put(312, "l2"); m.put(313, "r2");
        m.put(314, "touchpad"); m.put(315, "options"); m.put(317, "l3"); m.put(318, "r3");
        return Collections.unmodifiableMap(m);
    }

    private static Map<Integer, String> buildKeyDefaults() {
        // 数值即 android.view.KeyEvent：BUTTON_A=96, B=97, X=99, Y=100, L1=102, R1=103,
        // L2=104, R2=105, THUMBL=106, THUMBR=107, START=108, SELECT=109,
        // DPAD_UP=19, DOWN=20, LEFT=21, RIGHT=22。
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(96, "cross"); m.put(97, "circle"); m.put(99, "square"); m.put(100, "triangle");
        m.put(102, "l1"); m.put(103, "r1"); m.put(104, "l2"); m.put(105, "r2");
        m.put(106, "l3"); m.put(107, "r3"); m.put(108, "options"); m.put(109, "touchpad");
        m.put(19, "up"); m.put(20, "down"); m.put(21, "left"); m.put(22, "right");
        return Collections.unmodifiableMap(m);
    }

    // ---- 源 key 编码 ----

    public static String scanSource(int scanCode) { return "scan:" + scanCode; }
    public static String keySource(int keyCode) { return "key:" + keyCode; }
    /** 模拟轴半轴源 key：sign 取该次采样的方向（负值 '-'、非负 '+'）。 */
    public static String axisSource(int axis, float value) {
        return "axis:" + axis + ":" + (value < 0 ? '-' : '+');
    }

    /** KeyEvent 源解析（design D1 优先级）：scanCode 落在 304..318 时优先 scan 路径
     *  （用户映射、再默认表），两表都无该 scanCode 才回落 keyCode 路径；历史行为里
     *  306/309/316 落到 keyCode switch，此规则与之一致。 */
    public static String resolveButton(Map<String, String> mapping, int scanCode, int keyCode) {
        if (scanCode >= 304 && scanCode <= 318) {
            String mapped = lookup(mapping, scanSource(scanCode));
            if (mapped != null) return mapped;
            String def = SCAN_DEFAULTS.get(scanCode);
            if (def != null) return def;
        }
        String mapped = lookup(mapping, keySource(keyCode));
        if (mapped != null) return mapped;
        return KEY_DEFAULTS.get(keyCode);
    }

    /** 模拟轴查表：命中映射且 |value| 越过阈值时返回目标按钮，否则 null。
     *  未映射的轴返回 null——模拟量到 l2/r2 的默认传递在 PadBridge 原样保留。 */
    public static String resolveAxis(Map<String, String> mapping, int axis, float value) {
        if (Math.abs(value) < 0.5f) return null;
        for (int capturable : CAPTURABLE_AXES) {
            if (capturable == axis) return lookup(mapping, axisSource(axis, value));
        }
        return null;
    }

    /** 查映射表：目标值必须是已知 PS 按钮名，非法条目一律视为无映射（spec：损坏降级）。 */
    public static String lookup(Map<String, String> mapping, String source) {
        if (mapping == null || source == null) return null;
        String target = mapping.get(source);
        return isValidTarget(target) ? target : null;
    }

    /** 目标是否为可绑定的 PS 按钮名。 */
    public static boolean isValidTarget(String name) {
        if (name == null) return false;
        for (String known : PS_BUTTONS) if (known.equals(name)) return true;
        return false;
    }

    /** 源 key 是否为本类可识别的编码（UI 侧过滤用；不校验目标）。 */
    public static boolean isKnownSource(String source) {
        if (source == null || source.length() < 6) return false;
        if (source.startsWith("scan:") || source.startsWith("key:")) {
            return parseTrailingInt(source.substring(source.indexOf(':') + 1)) != Integer.MIN_VALUE;
        }
        if (source.startsWith("axis:")) {
            int colon = source.lastIndexOf(':');
            if (colon < 5) return false;
            char sign = source.charAt(source.length() - 1);
            if (sign != '-' && sign != '+') return false;
            int axis = parseTrailingInt(source.substring(5, colon));
            if (axis == Integer.MIN_VALUE) return false;
            for (int capturable : CAPTURABLE_AXES) if (capturable == axis) return true;
            return false;
        }
        return false;
    }

    /** 某 PS 按钮的默认源 key（无默认返回 null）；设置页展示默认绑定用。 */
    public static String defaultSourceFor(String psButton) {
        if (!isValidTarget(psButton)) return null;
        for (Map.Entry<Integer, String> e : SCAN_DEFAULTS.entrySet())
            if (e.getValue().equals(psButton)) return scanSource(e.getKey());
        for (Map.Entry<Integer, String> e : KEY_DEFAULTS.entrySet())
            if (e.getValue().equals(psButton)) return keySource(e.getKey());
        return null;
    }

    private static int parseTrailingInt(String text) {
        try { return Integer.parseInt(text); }
        catch (NumberFormatException ex) { return Integer.MIN_VALUE; }
    }
}
