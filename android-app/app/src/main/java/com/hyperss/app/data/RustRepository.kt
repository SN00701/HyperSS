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

    /** 把 UniFFI 异常转成用户可读信息。 */
    fun describe(e: Throwable): String = when (e) {
        is CoreException.InvalidPrefix -> "前缀不合法：${e.v1}"
        is CoreException.PrefixExists -> "前缀已存在：${e.prefix}"
        is CoreException.ProjectNotFound -> "项目不存在"
        is CoreException.ImageNotFound -> "图片不存在"
        is CoreException.SessionNotFound -> "会话不存在"
        is CoreException.StitchLowConfidence -> "画面变化过大，拼接失败（置信度过低）"
        is CoreException.WidthMismatch -> "截图宽度不一致，无法拼接"
        is CoreException.SequenceExhausted -> "序号已用尽"
        is CoreException.NoDraft -> "没有可恢复的草稿"
        else -> e.message ?: e.javaClass.simpleName
    }
}