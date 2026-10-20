package com.gow3.android.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** PadMapping 纯逻辑的宿主单测（task 1.2）：源 key 编码、默认表回落、映射覆盖、
 *  scanCode 优先级、模拟轴阈值与非法目标过滤。无 Android 依赖。 */
public class PadMappingTest {

    @Test public void defaultsMatchHistoricalHardcodedName() {
        Map<String, String> none = new HashMap<>();
        // Linux 手柄规范 scanCode 路径（与历史 name() 逐条一致）
        assertEquals("cross", PadMapping.resolveButton(none, 304, 0));
        assertEquals("circle", PadMapping.resolveButton(none, 305, 0));
        assertEquals("square", PadMapping.resolveButton(none, 307, 0));
        assertEquals("triangle", PadMapping.resolveButton(none, 308, 0));
        assertEquals("touchpad", PadMapping.resolveButton(none, 314, 0));
        assertEquals("options", PadMapping.resolveButton(none, 315, 0));
        // keyCode 路径（蓝牙手柄常见）
        assertEquals("square", PadMapping.resolveButton(none, 0, 99));   // KEYCODE_BUTTON_X
        assertEquals("triangle", PadMapping.resolveButton(none, 0, 100)); // KEYCODE_BUTTON_Y
        assertEquals("up", PadMapping.resolveButton(none, 0, 19));       // KEYCODE_DPAD_UP
        // 历史行为:306/309/316 无 scan 默认,回落 keyCode switch
        assertEquals("square", PadMapping.resolveButton(none, 306, 99));
        assertNull(PadMapping.resolveButton(none, 306, 0));
    }

    @Test public void userMappingOverridesDefaults() {
        Map<String, String> map = new HashMap<>();
        map.put("scan:307", "triangle");
        map.put("key:99", "circle");
        assertEquals("triangle", PadMapping.resolveButton(map, 307, 0));
        assertEquals("circle", PadMapping.resolveButton(map, 0, 99));
        assertEquals("cross", PadMapping.resolveButton(map, 304, 0)); // 未映射仍走默认
    }

    @Test public void scanPriorityFallsBackToKeyWhenNoDefault() {
        Map<String, String> map = new HashMap<>();
        // scan:306 命中映射 → 覆盖同事件的 keyCode 默认
        map.put("scan:306", "cross");
        assertEquals("cross", PadMapping.resolveButton(map, 306, 99));
        // scan:306 未映射 → 回落 keyCode 默认
        assertEquals("square", PadMapping.resolveButton(new HashMap<>(), 306, 99));
    }

    @Test public void axisResolutionRespectsThresholdAndCapturableSet() {
        Map<String, String> map = new HashMap<>();
        map.put("axis:23:+", "circle");   // BRAKE 正向 → circle
        map.put("axis:15:-", "left");     // HAT X 负向 → left
        assertEquals("circle", PadMapping.resolveAxis(map, 23, 0.8f));
        assertNull("低于阈值不按下", PadMapping.resolveAxis(map, 23, 0.3f));
        assertEquals("left", PadMapping.resolveAxis(map, 15, -1f));
        assertNull("摇杆轴不参与映射", PadMapping.resolveAxis(map, 0, 1f));
        assertNull("未映射轴返回 null(模拟量默认传递)", PadMapping.resolveAxis(new HashMap<>(), 17, 0.9f));
    }

    @Test public void invalidTargetsAreTreatedAsUnmapped() {
        Map<String, String> map = new HashMap<>();
        map.put("scan:304", "start_button"); // 未知目标
        map.put("key:99", null);
        assertNull(PadMapping.lookup(map, "scan:304"));
        assertNull(PadMapping.lookup(map, "key:99"));
        assertEquals("cross", PadMapping.resolveButton(map, 304, 0)); // 回落默认
        assertTrue(PadMapping.isValidTarget("cross"));
        assertFalse(PadMapping.isValidTarget("select"));
    }

    @Test public void sourceKeyEncodingAndKnownSourceCheck() {
        assertEquals("scan:304", PadMapping.scanSource(304));
        assertEquals("key:99", PadMapping.keySource(99));
        assertEquals("axis:23:+", PadMapping.axisSource(23, 0.5f));
        assertEquals("axis:15:-", PadMapping.axisSource(15, -1f));
        assertTrue(PadMapping.isKnownSource("scan:304"));
        assertTrue(PadMapping.isKnownSource("axis:16:-"));
        assertFalse("摇杆半轴不是可捕获源", PadMapping.isKnownSource("axis:0:+"));
        assertFalse(PadMapping.isKnownSource("scan:abc"));
        assertFalse(PadMapping.isKnownSource("motor:1"));
    }

    @Test public void defaultSourceExistsForEveryBindableButton() {
        for (String button : PadMapping.PS_BUTTONS) {
            String source = PadMapping.defaultSourceFor(button);
            assertTrue("按钮 " + button + " 必须有默认绑定", source != null);
            assertTrue(PadMapping.isKnownSource(source));
        }
        assertNull(PadMapping.defaultSourceFor("select"));
    }
}
