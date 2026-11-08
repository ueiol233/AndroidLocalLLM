package com.ueiol.scanaianswer

import android.app.Application
import timber.log.Timber

/**
 * Application 入口。
 * 全局初始化如 Timber、崩溃报告等在此进行。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}