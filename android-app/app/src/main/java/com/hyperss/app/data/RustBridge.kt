package com.hyperss.app.data

import android.content.Context
import uniffi.hyperss_core.ConfigStore
import uniffi.hyperss_core.SessionManager
import uniffi.hyperss_core.Storage
import uniffi.hyperss_core.calibrateOffset
import uniffi.hyperss_core.coreVersion
import java.io.File

/**
 * 到 Rust 核心库的唯一入口。
 *
 * 所有对 Rust 的调用一律经由 [RustBridge] 与 [RustRepository] 收敛，
 * UI / Service 层不得直接 import 生成的绑定类（见开发文档第 12 章）。
 */
object RustBridge {

    lateinit var appContext: Context
        private set

    private val storage: Storage by lazy {
        Storage.open(storageRoot(appContext))
    }

    private val sessionManager: SessionManager by lazy { SessionManager(storage) }

    private val configStore: ConfigStore by lazy { ConfigStore.open(storageRoot(appContext)) }

    /** 数据根目录：应用私有外部目录下的 HyperSS/（卸载自动清理，无需存储权限）。 */
    fun storageRoot(context: Context): String {
        val dir = File(context.getExternalFilesDir(null), "HyperSS")
        dir.mkdirs()
        return dir.absolutePath
    }

    fun storage(): Storage = storage
    fun sessionManager(): SessionManager = sessionManager
    fun configStore(): ConfigStore = configStore
    fun version(): String = com.hyperss.app.BuildConfig.VERSION_NAME

    /** 双点取距标定：求起点/终点两帧间的滚动像素距离（步长）。 */
    fun calibrateOffset(
        startPng: ByteArray,
        endPng: ByteArray,
        minOverlap: UInt,
        maxOverlap: UInt,
    ): UInt = calibrateOffset(startPng, endPng, minOverlap, maxOverlap)

    fun init(context: Context) {
        appContext = context.applicationContext
        // 触发一次初始化（含 native 库加载），提前暴露加载失败问题。
        kotlin.runCatching { storage.rootDir() }
    }
}