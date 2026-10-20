package com.gow3.android

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * 仪器化测试，将在 Android 设备上执行。
 *
 * 参见 [testing documentation](http://d.android.com/tools/testing)。
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // 被测应用的上下文。debug 变体包名带 .debug 后缀（applicationIdSuffix），
        // 断言用变体自己的 BuildConfig.APPLICATION_ID，两种变体都成立。
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(com.gow3.android.BuildConfig.APPLICATION_ID, appContext.packageName)
        assertTrue(appContext.packageName.startsWith("com.gow3.android.bionic"))
    }
}