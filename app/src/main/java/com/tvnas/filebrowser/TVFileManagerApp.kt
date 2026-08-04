package com.tvnas.filebrowser

import android.app.Application
import com.tencent.bugly.crashreport.CrashReport

/**
 * 应用入口 — 初始化全局组件
 */
class TVFileManagerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化 Bugly 错误监控
        // 第三个参数 isDebug：release 构建应设为 false，debug 构建可设为 true
        CrashReport.initCrashReport(this, "b152ad7f9e", BuildConfig.DEBUG)
    }
}
