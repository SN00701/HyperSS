package com.hyperss.app

import android.app.Application
import android.content.Context
import com.hyperss.app.data.RustBridge
import com.hyperss.app.util.LocaleHelper
import com.hyperss.app.util.Notifications
import com.hyperss.app.util.Settings
import com.hyperss.app.util.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        // 启动时后台静默检查更新：仅在有新版本且未被用户跳过时提示，失败不打扰。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = UpdateChecker.check(BuildConfig.VERSION_NAME)
            if (result is UpdateChecker.Result.UpdateAvailable &&
                result.version != Settings.updateSkipVersion
            ) {
                UpdateChecker.publishStartupResult(result)
            }
        }
    }
}