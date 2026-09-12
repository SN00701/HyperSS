package com.hyperss.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.hyperss_core.CaptureMode
import uniffi.hyperss_core.CoreException
import uniffi.hyperss_core.ImageInfo
import uniffi.hyperss_core.ProjectInfo
import uniffi.hyperss_core.SessionInfo
import uniffi.hyperss_core.SessionProgress
import uniffi.hyperss_core.SessionResumeAction
import uniffi.hyperss_core.SessionRunner
import uniffi.hyperss_core.SessionStatus
import uniffi.hyperss_core.StorageStats

/** 对 Rust 核心层的统一调用封装，全部在 IO 线程执行。 */
class RustRepository {

    // ---------- 项目 ----------

    suspend fun listProjects(): List<ProjectInfo> = withContext(Dispatchers.IO) {
        RustBridge.storage().listProjects()
    }

    suspend fun createProject(prefix: String, displayName: String): ProjectInfo =
        withContext(Dispatchers.IO) { RustBridge.storage().createProject(prefix, displayName) }

    suspend fun deleteProject(id: Long) = withContext(Dispatchers.IO) {
        RustBridge.storage().deleteProject(id)
    }

    suspend fun getProject(id: Long): ProjectInfo = withContext(Dispatchers.IO) {
        RustBridge.storage().getProject(id)
    }

    suspend fun renameProject(id: Long, displayName: String): ProjectInfo =
        withContext(Dispatchers.IO) { RustBridge.storage().renameProject(id, displayName) }

    // ---------- 图片 ----------

    suspend fun listImages(projectId: Long): List<ImageInfo> = withContext(Dispatchers.IO) {
        RustBridge.storage().listImages(projectId)
    }

    suspend fun deleteImage(id: Long) = withContext(Dispatchers.IO) {
        RustBridge.storage().deleteImage(id)
    }

    suspend fun deleteImages(ids: List<Long>): Long = withContext(Dispatchers.IO) {
        RustBridge.storage().deleteImages(ids)
    }

    suspend fun renameAlias(id: Long, alias: String?) = withContext(Dispatchers.IO) {
        RustBridge.storage().renameImageAlias(id, alias)
    }

    suspend fun saveImage(
        projectId: Long,
        mode: CaptureMode,
        alias: String?,
        sessionId: Long?,
        png: ByteArray,
    ): ImageInfo = withContext(Dispatchers.IO) {
        RustBridge.storage().saveImage(projectId, mode, alias, sessionId, png)
    }

    suspend fun stats(): StorageStats = withContext(Dispatchers.IO) {
        RustBridge.storage().stats()
    }

    // ---------- 会话 ----------

    suspend fun createSession(projectId: Long, mode: CaptureMode): SessionInfo =
        withContext(Dispatchers.IO) { RustBridge.sessionManager().createSession(projectId, mode) }

    suspend fun recoverableSessions(): List<SessionInfo> = withContext(Dispatchers.IO) {
        RustBridge.sessionManager().listRecoverableSessions()
    }

    suspend fun recoverOrphans(): Long = withContext(Dispatchers.IO) {
        RustBridge.sessionManager().recoverOrphanedSessions()
    }

    suspend fun resumeOrFinalize(sessionId: Long, action: SessionResumeAction): ImageInfo? =
        withContext(Dispatchers.IO) { RustBridge.sessionManager().resumeOrFinalize(sessionId, action) }

    /** 启动一个会话执行器（挂起调用，可长期持有，操作需在后台线程执行）。 */
    fun startRunner(sessionId: Long): SessionRunner = SessionRunner.start(RustBridge.storage(), sessionId)

    /** 便捷进度读取（IO 线程）。 */
    suspend fun runnerProgress(runner: SessionRunner): SessionProgress = withContext(Dispatchers.IO) {
        runner.progress()
    }

    // ---------- 配置 ----------

    fun configGet(key: String, default: String): String =
        RustBridge.configStore().getOr(key, default)

    fun configSet(key: String, value: String) {
        RustBridge.configStore().set(key, value)
    }

    fun configAll(): Map<String, String> =
        RustBridge.configStore().getAll().associate { it.key to it.value }

    // ---------- 其他 ----------

    fun version(): String = RustBridge.version()

    /** 双点取距标定：求起点/终点两帧间的滚动像素距离（步长）。 */
    fun calibrateOffset(
        startPng: ByteArray,
        endPng: ByteArray,
        minOverlap: UInt,
        maxOverlap: UInt,
    ): UInt = RustBridge.calibrateOffset(startPng, endPng, minOverlap, maxOverlap)

    /** 把 UniFFI 异常转成用户可读信息（按当前语言资源取文案，核心未初始化时回退中文）。 */
    fun describe(e: Throwable): String = when (e) {
        is CoreException.InvalidPrefix -> res(com.hyperss.app.R.string.error_invalid_prefix, e.v1)
        is CoreException.PrefixExists -> res(com.hyperss.app.R.string.error_prefix_exists, e.prefix)
        is CoreException.ProjectNotFound -> res(com.hyperss.app.R.string.error_project_not_found)
        is CoreException.ImageNotFound -> res(com.hyperss.app.R.string.error_image_not_found)
        is CoreException.SessionNotFound -> res(com.hyperss.app.R.string.error_session_not_found)
        is CoreException.StitchLowConfidence -> res(com.hyperss.app.R.string.error_stitch_low_confidence)
        is CoreException.WidthMismatch -> res(com.hyperss.app.R.string.error_width_mismatch)
        is CoreException.SequenceExhausted -> res(com.hyperss.app.R.string.error_sequence_exhausted)
        is CoreException.NoDraft -> res(com.hyperss.app.R.string.error_no_draft)
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun res(id: Int, vararg args: Any?): String = runCatching {
        RustBridge.appContext.getString(id, *args)
    }.getOrDefault(id.toString())
}