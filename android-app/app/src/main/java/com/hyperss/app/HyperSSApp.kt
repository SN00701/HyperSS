package com.hyperss.app

import android.app.Application
import android.content.Context
import com.hyperss.app.data.RustBridge
import com.hyperss.app.util.LocaleHelper
import com.hyperss.app.util.Notifications

class HyperSSApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        RustBridge.init(this)
        Notifications.ensureChannel(this)
        // 启动时清理过期孤儿草稿（开发文档 10.x）。
        kotlin.runCatching {
            Thread {
                try {
                    RustBridge.sessionManager().recoverOrphanedSessions()
                    RustBridge.sessionManager().cleanupStaleDrafts()
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true }.start()
        }
    }
}