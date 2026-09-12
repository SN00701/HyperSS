package com.hyperss.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.hyperss.app.data.CaptureController
import com.hyperss.app.data.RustBridge
import com.hyperss.app.util.Notifications

/**
 * 任务生命周期 / 中断监听（开发文档第 10 章场景③④⑤）。
 *
 * 监听信号：熄屏/亮屏（广播）、前台包名变化（由无障碍服务上报）。
 * 统一注册与反注册，避免服务生命周期结束后残留监听。
 */
object SessionLifecycleObserver {

    @Volatile private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onInterrupt("screen_off")
                Intent.ACTION_USER_PRESENT -> onUserPresent()
            }
        }
    }

    fun register(context: Context) {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(receiver, filter)
    }

    fun unregister(context: Context) {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** 由无障碍服务上报前台包名变化。 */
    fun onForegroundChanged(packageName: String) {
        val controller = CaptureController
        val target = controller.targetPackage
        val appPkg = RustBridge.appContext.packageName
        val isSystem = packageName == "com.android.systemui" ||
            packageName.startsWith("com.android.phone") ||
            packageName == appPkg
        if (controller.isActive && !controller.userPaused && target != null && target != packageName && !isSystem) {
            onInterrupt("left_app")
        }
    }

    fun onAccessibilityInterrupted() {
        onInterrupt("a11y")
    }

    fun onAccessibilityReady() {
        // 无障碍服务就绪后自动恢复被系统中断暂停的自动任务。
        val controller = CaptureController
        if (controller.isActive && !controller.userPaused) {
            controller.resume()
        }
    }

    fun onAccessibilityDestroyed() = Unit

    private fun onInterrupt(reason: String) {
        val controller = CaptureController
        if (controller.isActive && !controller.userPaused) {
            controller.pause(reason)
            Notifications.postInterrupted(RustBridge.appContext)
        }
    }

    private fun onUserPresent() {
        // 亮屏后若会话处于暂停状态且非用户主动暂停，可在此提示继续。
        // 简化实现：交由悬浮工具条外观变化提示用户。
    }
}