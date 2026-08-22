package com.hyperss.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.hyperss.app.util.Notifications

/**
 * MediaProjection 截屏通道（前台服务 + 持续通知，MediaProjection 强制要求）。
 *
 * 所有 API 级别均作为**优先通道**使用：MIUI 等定制系统上
 * `AccessibilityService.takeScreenshot()` 可能静默失败，故先走本通道，
 * 无障碍截屏作为回退。屏幕持续镜像到 VirtualDisplay，按帧抓取。
 */
class MediaProjectionCaptureService : Service() {

    companion object {
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val GRAB_RETRY_LIMIT = 25 // 40ms × 25 = 最多等待 1s

        @Volatile private var projection: MediaProjection? = null
        @Volatile private var imageReader: ImageReader? = null
        @Volatile private var virtualDisplay: VirtualDisplay? = null
        @Volatile private var captureThread: HandlerThread? = null
        @Volatile private var captureHandler: Handler? = null
        private val mainHandler = Handler(android.os.Looper.getMainLooper())

        /** 待回调槽：captureFrameAsync（任意线程）写入、grabFrame（捕获线程）取出。 */
        private val pendingLock = Any()
        private var pending: ((Bitmap?) -> Unit)? = null

        /** 由 Activity 拿到 MediaProjection 授权结果后启动。 */
        fun start(context: Context, resultCode: Int, resultData: Intent) {
            Notifications.ensureChannel(context)
            val intent = Intent(context, MediaProjectionCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 异步抓取一帧。 */
        fun captureFrameAsync(onResult: (Bitmap?) -> Unit) {
            val reader = imageReader
            val handler = captureHandler
            if (reader == null || handler == null) {
                mainHandler.post { onResult(null) }
                return
            }
            synchronized(pendingLock) {
                // 上一帧回调尚未消费时不再排队，直接失败，避免回调被覆盖后丢失
                val prev = pending
                pending = onResult
                if (prev != null) {
                    pending = null
                    mainHandler.post { prev.invoke(null) }
                    mainHandler.post { onResult(null) }
                    return
                }
            }
            handler.post { grabFrame(reader, 0) }
        }

        private fun grabFrame(reader: ImageReader, retry: Int) {
            val image: Image? = try {
                reader.acquireLatestImage()
            } catch (e: Throwable) {
                null
            }
            if (image != null) {
                val bitmap = image.toBitmap()
                image.close()
                val cb = synchronized(pendingLock) {
                    val c = pending
                    pending = null
                    c
                }
                mainHandler.post { cb?.invoke(bitmap) }
            } else if (retry < GRAB_RETRY_LIMIT) {
                captureHandler?.postDelayed({ grabFrame(reader, retry + 1) }, 40L)
            } else {
                // 超过重试上限：按失败回调，避免无限轮询卡死上游
                val cb = synchronized(pendingLock) {
                    val c = pending
                    pending = null
                    c
                }
                mainHandler.post { cb?.invoke(null) }
            }
        }

        private fun Image.toBitmap(): Bitmap? {
            return try {
                val plane = planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val width = width
                val height = height
                if (pixelStride != 4) return null
                val buffer = plane.buffer
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                if (rowStride == width * 4) {
                    bitmap.copyPixelsFromBuffer(buffer)
                } else {
                    // rowStride 带行尾对齐填充时逐行拷贝，直接整块复制会像素错位
                    val rowBytes = width * 4
                    val packed = java.nio.ByteBuffer.allocate(rowBytes * height)
                    for (row in 0 until height) {
                        buffer.position(row * rowStride)
                        buffer.limit(row * rowStride + rowBytes)
                        packed.put(buffer)
                    }
                    packed.rewind()
                    buffer.clear()
                    bitmap.copyPixelsFromBuffer(packed)
                }
                bitmap
            } catch (e: Throwable) {
                null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        setupProjection(code, data)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = Notifications.captureNotification(this)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.NOTIF_CAPTURE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(Notifications.NOTIF_CAPTURE, notification)
        }
    }

    private fun setupProjection(resultCode: Int, resultData: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(resultCode, resultData) ?: run {
            stopSelf()
            return
        }
        projection = proj

        val wm = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        val thread = HandlerThread("hyperss-media-proj").apply { start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)

        val reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = proj.createVirtualDisplay(
            "hyperss-capture",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            virtualDisplay?.release()
            imageReader?.close()
            projection?.stop()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        imageReader = null
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}