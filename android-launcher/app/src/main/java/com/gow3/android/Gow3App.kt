package com.gow3.android

import com.gow3.android.core.di.AppContainer

import android.app.Application

/**
 * 应用级依赖入口（design D4）：`AndroidManifest.xml` 的 `android:name` 指向本类，
 * 各 Activity 经 `(application as Gow3App).container` 获取 Context 级服务。
 */
class Gow3App : Application() {

    /** 惰性构造的手工 DI 容器；Activity onCreate 时 Application 必然已就绪。 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
