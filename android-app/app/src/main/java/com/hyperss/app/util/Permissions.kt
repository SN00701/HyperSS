package com.hyperss.app.util

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hyperss.app.R

object Permissions {

    const val NOTIFICATION_PERMISSION_REQUEST = 4101
    const val MEDIA_PROJECTION_REQUEST = 4102

    fun hasOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun requestOverlayIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasAccessibility(context: Context): Boolean {
        // 最可靠：运行时检查服务实例是否存活。
        if (com.hyperss.app.service.CaptureAccessibilityService.instance != null) return true

        // 首选运行时查询：仅枚举本应用自己的服务，无需额外权限。
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (enabled.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }) {
            return true
        }

        // 兜底：解析系统设置字符串（兼容部分定制 ROM）。
        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val myName = context.packageName + "/" +
            "com.hyperss.app.service.CaptureAccessibilityService"
        // 检查完全匹配或前缀匹配（部分ROM可能附加额外信息）
        return services.split(':').any {
            it.equals(myName, ignoreCase = true) ||
                it.startsWith(context.packageName + "/", ignoreCase = true) &&
                it.contains("CaptureAccessibilityService")
        }
    }

    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}

object Notifications {

    const val CHANNEL_FOREGROUND = "hyperss_foreground"
    const val NOTIF_CAPTURE = 1
    const val NOTIF_INTERRUPTED = 2

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_FOREGROUND,
                context.getString(R.string.notification_channel_foreground),
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    fun captureNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_foreground_title))
            .setContentText(context.getString(R.string.notification_foreground_text))
            .setOngoing(true)
            .setSilent(true)
            .build()

    fun interruptedNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_interrupted_title))
            .setContentText(context.getString(R.string.notification_interrupted_text))
            .setAutoCancel(true)
            .build()

    fun postInterrupted(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_INTERRUPTED, interruptedNotification(context))
    }

    /** 无障碍服务被系统杀死时的兜底提醒。 */
    fun accessibilityInterrupted(context: Context) = postInterrupted(context)

    /** AccessibilityService 类型不匹配的守卫（供调用方确认类型）。 */
    fun isAccessibilityService(service: Any): Boolean = service is AccessibilityService
}