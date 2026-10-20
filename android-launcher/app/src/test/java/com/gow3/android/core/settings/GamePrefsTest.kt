package com.gow3.android.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GamePrefs 性能 HUD 键的宿主单测：用内存版 SharedPreferences 替身覆盖
 * 默认值、正常存取与成对写入（task 2.1；本工程无 Robolectric，不引入新依赖）。
 */
class GamePrefsTest {

    /** 最小内存版 SharedPreferences：只实现 GamePrefs 用到的读写路径。 */
    private class FakePrefs : android.content.SharedPreferences {
        val map = HashMap<String, Any>()
        private val editor = object : android.content.SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { map[key ?: return this] = value ?: ""; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor { map[key ?: return this] = values ?: mutableSetOf<String>(); return this }
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { map[key ?: return this] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { map[key ?: return this] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { map[key ?: return this] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { map[key ?: return this] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { map.remove(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { map.clear(); return this }
            override fun commit() = true
            override fun apply() {}
        }

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?) = map[key] as String? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
            @Suppress("UNCHECKED_CAST") (map[key] as MutableSet<String>?) ?: defValues
        override fun getInt(key: String?, defValue: Int) = map[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long) = map[key] as Long? ?: defValue
        override fun getFloat(key: String?, defValue: Float) = map[key] as Float? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = map[key] as Boolean? ?: defValue
        override fun contains(key: String?) = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = editor
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private fun prefs() = GamePrefs(FakePrefs())

    @Test fun `perf hud defaults to disabled and unpositioned`() {
        val p = prefs()
        assertFalse(p.perfHudEnabled())          // spec「默认关闭」
        assertNull(p.perfHudX())                 // 未拖动过：null → PerfHudOverlay 回退默认右上角
        assertNull(p.perfHudY())
    }

    @Test fun `perf hud enabled roundtrip`() {
        val p = prefs()
        p.setPerfHudEnabled(true)
        assertTrue(p.perfHudEnabled())
        p.setPerfHudEnabled(false)
        assertFalse(p.perfHudEnabled())
    }

    @Test fun `perf hud position persists as pair`() {
        val p = prefs()
        p.setPerfHudPosition(12.5f, 40f)
        assertEquals(12.5f, p.perfHudX()!!)
        assertEquals(40f, p.perfHudY()!!)
        // x/y 成对写入（GamePrefs.setPerfHudPosition 一次 edit）
        val store = FakePrefs()
        GamePrefs(store).setPerfHudPosition(1f, 2f)
        assertTrue(store.contains(GamePrefs.KEY_PERF_HUD_X) && store.contains(GamePrefs.KEY_PERF_HUD_Y))
    }

    // ---- 物理手柄按键映射（add-pad-button-mapping task 1.1） ----

    @Test fun `pad mapping defaults to empty and roundtrips`() {
        val p = prefs()
        assertTrue("未设置时为空表 = 全默认", p.padMapping().isEmpty())
        val mapping = mapOf("scan:304" to "triangle", "key:99" to "circle", "axis:23:+" to "l2")
        p.setPadMapping(mapping)
        assertEquals(mapping, p.padMapping())
        // 持久化确实落盘（新 FakePrefs 重读）
        val store = FakePrefs()
        GamePrefs(store).setPadMapping(mapping)
        assertEquals(mapping, GamePrefs(store).padMapping())
    }

    @Test fun `pad mapping empty map clears the key`() {
        val p = prefs()
        p.setPadMapping(mapOf("scan:304" to "triangle"))
        p.setPadMapping(emptyMap())
        assertFalse("空表应清键回落全默认", p.raw.contains(GamePrefs.KEY_PAD_MAPPING))
        assertTrue(p.padMapping().isEmpty())
    }

    @Test fun `pad mapping corrupt json falls back to empty`() {
        val store = FakePrefs()
        store.edit().putString(GamePrefs.KEY_PAD_MAPPING, "{\"version\":1,\"map\":{broken").commit()
        assertTrue(GamePrefs(store).padMapping().isEmpty())
        store.edit().putString(GamePrefs.KEY_PAD_MAPPING, "not json at all").commit()
        assertTrue(GamePrefs(store).padMapping().isEmpty())
        store.edit().putString(GamePrefs.KEY_PAD_MAPPING, "{\"version\":1,\"map\":{\"scan:304\":42}}").commit()
        assertTrue("非字符串值拒绝", GamePrefs(store).padMapping().isEmpty())
    }
}
