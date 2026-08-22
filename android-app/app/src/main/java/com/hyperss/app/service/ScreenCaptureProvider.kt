package com.hyperss.app.service

import android.graphics.Bitmap

/**
 * 屏幕帧捕获通道的统一抽象。
 *
 * 优先通道：MediaProjection + ImageReader（MIUI 等定制系统上
 * `AccessibilityService.takeScreenshot()` 可能静默失败，故先走 MediaProjection）。
 * 回退通道（API 30+）：`AccessibilityService.takeScreenshot()`。
 */
interface ScreenCaptureProvider {

    /** 异步获取一帧屏幕位图。失败时回调 null。 */
    fun takeScreenshot(onResult: (Bitmap?) -> Unit)

    /**
     * 发送竖向滑动（内容向上滚动的「向下滑」手势）。
     * @return 手势是否成功下发。
     */
    fun dispatchScroll(distancePx: Int, durationMs: Long = 220): Boolean

    /**
     * 发送从 (fromX, fromY) 到 (toX, toY) 的任意方向滑动。
     * 屏幕坐标以像素计。
     * @return 手势是否成功下发。
     */
    fun dispatchSwipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 500,
    ): Boolean

    /** 当前可视区域尺寸。 */
    fun screenWidthPx(): Int

    fun screenHeightPx(): Int

    /** 手势间隔：每次滑动后等待的时长（毫秒），需覆盖回弹动画的复位时间。 */
    fun scrollSettleMs(): Long = 700L
}