package com.hyperss.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.hyperss.app.util.Notifications
import com.hyperss.app.util.Settings
import java.util.concurrent.Executor

class CaptureAccessibilityService : AccessibilityService(), ScreenCaptureProvider {

    companion object {
        @Volatile var instance: CaptureAccessibilityService? = null
            private set

        /** 最近一次 TYPE_VIEW_SCROLLED 事件时间（System.currentTimeMillis），
         *  供手动滚动模式检测「用户已滑动且已停止」。 */
        @Volatile var lastScrollEventAt: Long = 0L

        private const val MAX_SCROLL_DISTANCE_PX = 1200
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastForegroundPackage: String? = null
    private var windowManager: WindowManager? = null
    private var arrowOverlay: ArrowOverlayView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Settings.seenAccessibilityOnce = true
        SessionLifecycleObserver.onAccessibilityReady()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // 仅记录时间戳，不读取事件内容
                lastScrollEventAt = System.currentTimeMillis()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg != lastForegroundPackage) {
                    lastForegroundPackage = pkg
                    SessionLifecycleObserver.onForegroundChanged(pkg)
                }
            }
        }
    }

    override fun onInterrupt() {
        SessionLifecycleObserver.onAccessibilityInterrupted()
        Notifications.accessibilityInterrupted(this)
        CaptureAccessibilityService.instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        removeArrowOverlay()
        SessionLifecycleObserver.onAccessibilityDestroyed()
        if (instance === this) instance = null
    }

    // ---------- 箭头覆盖层 ----------
    // MIUI 等定制系统会抑制应用级 NOT_TOUCHABLE 悬浮窗（防屏幕滤镜机制）的渲染，
    // 但不限制无障碍覆盖层（TYPE_ACCESSIBILITY_OVERLAY）；箭头由本服务绘制，
    // 窗口只覆盖点集包围盒且保持不可触摸，不拦截底层手势。

    /** 覆盖层上绘制的连线段（屏幕坐标）。 */
    private class ArrowOverlayView(context: Context) : View(context) {
        var segments: List<Pair<Pair<Float, Float>, Pair<Float, Float>>> = emptyList()
        var originX = 0f
        var originY = 0f

        private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFCC00.toInt()
            style = android.graphics.Paint.Style.FILL
        }
        private val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFCC00.toInt()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f * context.resources.displayMetrics.density
            strokeCap = android.graphics.Paint.Cap.ROUND
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val densityPx = resources.displayMetrics.density
            val head = 16f * densityPx
            segments.forEach { (a, b) ->
                // 屏幕坐标 → 窗口坐标
                val ax = a.first - originX
                val ay = a.second - originY
                val bx = b.first - originX
                val by = b.second - originY
                val dx = bx - ax
                val dy = by - ay
                val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (len < 1f) return@forEach
                val nx = dx / len
                val ny = dy / len
                // 起止端点始终吸附在图标圆心（任何情况下）；
                // 线段穿过圆心的部分由后绘制的圆点自然覆盖
                val tailX = bx - nx * head
                val tailY = by - ny * head
                canvas.drawLine(ax, ay, tailX, tailY, linePaint)
                val px = -ny
                val py = nx
                val path = Path().apply {
                    moveTo(bx, by)
                    lineTo(tailX + px * head * 0.55f, tailY + py * head * 0.55f)
                    lineTo(tailX - px * head * 0.55f, tailY - py * head * 0.55f)
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }
        }
    }

    /** 由悬浮工具条驱动：按点集更新（或移除）箭头覆盖层。points 为屏幕坐标。 */
    fun updateArrowOverlay(points: List<Pair<Float, Float>>) {
        mainHandler.post { updateArrowOverlayInternal(points) }
    }

    fun setArrowOverlayVisible(visible: Boolean) {
        mainHandler.post {
            arrowOverlay?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    fun removeArrowOverlay() {
        mainHandler.post { removeArrowOverlayInternal() }
    }

    private fun updateArrowOverlayInternal(points: List<Pair<Float, Float>>) {
        if (points.size < 2) {
            removeArrowOverlayInternal()
            return
        }
        val wm = windowManager ?: getSystemService(WindowManager::class.java).also { windowManager = it }
        val density = resources.displayMetrics.density
        val pad = 24f * density
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val minX = (points.minOf { it.first } - pad).coerceIn(0f, w)
        val minY = (points.minOf { it.second } - pad).coerceIn(0f, h)
        val maxX = (points.maxOf { it.first } + pad).coerceIn(minX + 1f, w)
        val maxY = (points.maxOf { it.second } + pad).coerceIn(minY + 1f, h)

        var view = arrowOverlay
        if (view == null) {
            view = ArrowOverlayView(this).apply { setWillNotDraw(false) }
            val lp = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = minX.toInt()
                y = minY.toInt()
            }
            runCatching { wm.addView(view, lp) }
            arrowOverlay = view
        }
        view.segments = points.zipWithNext()
        view.originX = minX
        view.originY = minY
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = minX.toInt()
        lp.y = minY.toInt()
        lp.width = (maxX - minX).toInt().coerceAtLeast(1)
        lp.height = (maxY - minY).toInt().coerceAtLeast(1)
        runCatching { wm.updateViewLayout(view, lp) }
        view.postInvalidate()
    }

    private fun removeArrowOverlayInternal() {
        val view = arrowOverlay ?: return
        runCatching { windowManager?.removeView(view) }
        arrowOverlay = null
    }

    override fun takeScreenshot(onResult: (Bitmap?) -> Unit) {
        // 优先使用 MediaProjection（在 MIUI 上 AccessibilityService.takeScreenshot 静默失败）
        android.util.Log.d("HyperSS", "takeScreenshot: trying MediaProjection first")
        MediaProjectionCaptureService.captureFrameAsync { bmp ->
            if (bmp != null) {
                android.util.Log.d("HyperSS", "takeScreenshot: MediaProjection OK, size=${bmp.width}x${bmp.height}")
                onResult(bmp)
            } else {
                android.util.Log.d("HyperSS", "takeScreenshot: MediaProjection unavailable, fallback to AccessibilityService")
                takeScreenshotViaAccessibility(onResult)
            }
        }
    }

    private fun takeScreenshotViaAccessibility(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val executor = Executor { mainHandler.post(it) }
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val hardware = screenshot.hardwareBuffer
                            mainHandler.post {
                                val bmp = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                                hardware.close()
                                onResult(bmp)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            android.util.Log.d("HyperSS", "takeScreenshot onFailure: errorCode=$errorCode")
                            mainHandler.post { onResult(null) }
                        }
                    },
                )
            } catch (e: SecurityException) {
                mainHandler.post { onResult(null) }
            } catch (e: Throwable) {
                mainHandler.post { onResult(null) }
            }
        } else {
            onResult(null)
        }
    }

    override fun dispatchScroll(distancePx: Int, durationMs: Long): Boolean {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val dist = distancePx.coerceIn(1, MAX_SCROLL_DISTANCE_PX)
        return dispatchSwipe(
            w / 2f,
            h * 0.65f,
            w / 2f,
            (h * 0.65f - dist).coerceAtLeast(0f),
            durationMs,
        )
    }

    override fun dispatchSwipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ): Boolean {
        val dragMs = durationMs.coerceAtLeast(450)
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val holdMs = 140L
        val stroke1 = GestureDescription.StrokeDescription(path, 30L, dragMs, true)
        val holdPath = Path().apply {
            moveTo(toX, toY)
            lineTo(toX, toY)
        }
        val stroke2 = stroke1.continueStroke(holdPath, 30L + dragMs, holdMs, false)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }, null)
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return success
    }

    override fun screenWidthPx(): Int = resources.displayMetrics.widthPixels

    override fun screenHeightPx(): Int = resources.displayMetrics.heightPixels

    fun captureNow(onResult: (Bitmap?) -> Unit) = takeScreenshot(onResult)
}
