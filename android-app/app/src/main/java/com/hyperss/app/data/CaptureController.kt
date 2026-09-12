package com.hyperss.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import uniffi.hyperss_core.CaptureMode
import uniffi.hyperss_core.CoreException
import uniffi.hyperss_core.ImageInfo
import uniffi.hyperss_core.SessionProgress
import uniffi.hyperss_core.SessionRunner
import java.util.concurrent.Executors

/**
 * 会话编排控制器：持有会话执行器，串行接收帧并拼接。
 *
 * [appendDetected] / [appendFixed] 为**同步阻塞**调用，须在后台线程执行
 * （悬浮工具条服务自带捕获循环线程）；`start / pause / resume / finish /
 * discard / saveAsIs / manualSaveFrame` 通过单一后台线程串行执行，
 * 可安全地从 UI 主线程调用。状态统一通过 [state] 下发。
 */
object CaptureController {

    sealed interface State {
        data object Idle : State
        data object Preparing : State
        data class Active(val progress: SessionProgress) : State
        data class Paused(val progress: SessionProgress, val reason: String) : State
        data class Finished(val image: ImageInfo?) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hyperss-capture").apply { isDaemon = true }
    }
    private val repo = RustRepository()

    @Volatile private var runner: SessionRunner? = null
    @Volatile var sessionId: Long = -1L
        private set
    @Volatile var projectId: Long = -1L
        private set
    @Volatile var mode: CaptureMode = CaptureMode.AUTO_SCROLL
        private set

    @Volatile var minOverlap: UInt = 80u
        private set
    @Volatile var maxOverlap: UInt = 2000u
        private set
    @Volatile var fixedOffsetPx: UInt = 0u

    /** 标定得到的滚动距离（像素）；>0 时覆盖 stepDp 换算值。 */
    @Volatile var calibratedOffsetPx: UInt = 0u
        private set

    /** 用户主动暂停，防止生命周期监听误触发重复暂停。 */
    @Volatile var userPaused = false

    /** 自动滚动模式的目标应用包名（用于中断检测）。 */
    @Volatile var targetPackage: String? = null

    val isActive: Boolean get() = _state.value is State.Active || _state.value is State.Paused

    /** 本地化的「未知错误」文案（核心上下文未初始化时回退常量）。 */
    private fun unknownError(): String = runCatching {
        RustBridge.appContext.getString(com.hyperss.app.R.string.error_unknown)
    }.getOrDefault("未知错误")

    /** 设置标定步长（像素），后续 start() 会优先使用该值。 */
    fun setCalibratedOffset(offsetPx: UInt) {
        if (offsetPx > 0u) calibratedOffsetPx = offsetPx
    }

    // ---------- 会话生命周期（后台线程执行） ----------

    fun start(projectId: Long, mode: CaptureMode, screenHeightPx: Int, stepDp: Int, densityDpi: Float) {
        worker.execute {
            if (isActive) return@execute
            this.projectId = projectId
            this.mode = mode
            _state.value = State.Preparing
            try {
                val session = runBlocking { repo.createSession(projectId, mode) }
                sessionId = session.id
                runner = repo.startRunner(sessionId)
                when (mode) {
                    CaptureMode.AUTO_SCROLL, CaptureMode.MANUAL, CaptureMode.CALIBRATED_DISTANCE -> {
                        minOverlap = (screenHeightPx * 0.02f).toInt().coerceAtLeast(1).toUInt()
                        maxOverlap = (screenHeightPx * 0.98f).toInt()
                            .coerceAtLeast(minOverlap.toInt() + 1).toUInt()
                    }
                    CaptureMode.FIXED_STEP -> {
                        fixedOffsetPx = if (calibratedOffsetPx > 0u) calibratedOffsetPx
                        else (stepDp * densityDpi / 160f).toInt().coerceAtLeast(1).toUInt()
                    }
                }
                userPaused = false
                _state.value = State.Active(runner!!.progress())
            } catch (e: CoreException) {
                _state.value = State.Failed(repo.describe(e))
            } catch (e: Throwable) {
                _state.value = State.Failed(e.message ?: unknownError())
            }
        }
    }

    fun pause(reason: String) {
        val r = runner ?: return
        worker.execute {
            try {
                r.pause(reason)
                _state.value = State.Paused(r.progress(), reason)
            } catch (e: CoreException) {
                _state.value = State.Failed(repo.describe(e))
            }
        }
    }

    fun resume() {
        val r = runner ?: return
        worker.execute {
            try {
                userPaused = false
                _state.value = State.Active(r.progress())
            } catch (e: CoreException) {
                _state.value = State.Failed(repo.describe(e))
            }
        }
    }

    fun finish() = worker.execute { finishOnWorker() }

    fun saveAsIs() = worker.execute {
        val r = runner ?: return@execute
        try {
            val info = r.saveAsIs()
            val saved = persistFinal(info)
            runner = null
            calibratedOffsetPx = 0u
            _state.value = State.Finished(saved)
        } catch (e: CoreException) {
            _state.value = State.Failed(repo.describe(e))
        }
    }

    fun discard() = worker.execute {
        val r = runner ?: return@execute
        try {
            r.discard()
            runner = null
            calibratedOffsetPx = 0u
            _state.value = State.Idle
        } catch (e: CoreException) {
            _state.value = State.Failed(repo.describe(e))
        }
    }

    fun reset() = worker.execute { _state.value = State.Idle }

    // ---------- 帧处理（同步阻塞，须在后台线程调用） ----------

    /** 自动/手动滚动模式：按重叠检测拼接。返回 true 表示应继续循环。 */
    fun appendDetected(png: ByteArray): Boolean {
        val r = runner ?: return false
        try {
            val progress = r.appendFrameDetected(png, minOverlap, maxOverlap)
            // 用户已暂停时不回写 Active（否则会覆盖 Paused，导致“暂停不生效”）
            if (_state.value !is State.Paused) {
                _state.value = State.Active(progress)
            }
            if (progress.isContentEndDetected || progress.isSafetyLimitReached) {
                finishOnWorker()
                return false
            }
            return true
        } catch (e: CoreException.StitchLowConfidence) {
            pause("jump")
            return false
        } catch (e: CoreException) {
            _state.value = State.Failed(repo.describe(e))
            return false
        } catch (e: Throwable) {
            _state.value = State.Failed(e.message ?: unknownError())
            return false
        }
    }

    /** 固定步长 / 双点取距模式：按已知偏移拼接。 */
    fun appendFixed(png: ByteArray): Boolean {
        val r = runner ?: return false
        try {
            val progress = r.appendFrameFixed(png, fixedOffsetPx)
            if (_state.value !is State.Paused) {
                _state.value = State.Active(progress)
            }
            if (progress.isSafetyLimitReached) {
                finishOnWorker()
                return false
            }
            return true
        } catch (e: CoreException) {
            _state.value = State.Failed(repo.describe(e))
            return false
        } catch (e: Throwable) {
            _state.value = State.Failed(e.message ?: unknownError())
            return false
        }
    }

    /** 手动滚动模式：当前帧作为独立图片直接落库（失败保留独立帧语义）。 */
    fun saveManualFrame(png: ByteArray) = worker.execute {
        try {
            runBlocking { repo.saveImage(projectId, mode, null, sessionId, png) }
            runner?.let { r ->
                val p = r.progress()
                if (_state.value !is State.Paused) {
                    _state.value = State.Active(p)
                }
            }
        } catch (e: CoreException) {
            _state.value = State.Failed(repo.describe(e))
        }
    }

    fun finishManual() = worker.execute {
        try {
            runner?.let {
                it.complete()
                runner = null
            }
            calibratedOffsetPx = 0u
            _state.value = State.Finished(null)
        } catch (e: CoreException) {
            _state.value = State.Failed(repo.describe(e))
        }
    }

    // ---------- 内部 ----------

    private fun finishOnWorker() {
        val r = runner ?: return
        try {
            val info = r.complete()
            val saved = persistFinal(info)
            runner = null
            calibratedOffsetPx = 0u
            _state.value = State.Finished(saved)
        } catch (e: CoreException) {
            _state.value = State.Failed(repo.describe(e))
        }
    }

    /** 把完成的草稿落为项目图片（complete() 已返回 ImageInfo，直接持久化）。 */
    private fun persistFinal(info: ImageInfo?): ImageInfo? {
        if (info == null) return null
        return try {
            val bytes = java.io.File(info.filePath).takeIf { it.exists() }?.readBytes()
            if (bytes == null) info else runBlocking { repo.saveImage(projectId, mode, null, sessionId, bytes) }
        } catch (e: Throwable) {
            info
        }
    }
}