package com.hyperss.app.util

import android.content.Context
import android.content.SharedPreferences
import com.hyperss.app.data.RustBridge

/** 轻量偏好存储，统一收敛于 data 层之外的工具。 */
object Settings {

    const val PREFS = "hyperss_prefs"

    /** 直接基于给定 context 读取语言偏好（attachBaseContext 早期阶段专用，此时 RustBridge 尚未初始化）。 */
    fun readLanguageFrom(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("ui_language", "system") ?: "system"

    private fun prefs(): SharedPreferences =
        RustBridge.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否曾经引导过用户开启无障碍（用于设置页提示状态）。 */
    var seenAccessibilityOnce: Boolean
        get() = prefs().getBoolean("seen_accessibility_once", false)
        set(value) = prefs().edit().putBoolean("seen_accessibility_once", value).apply()

    /** 最近一次选中的录制目标项目 id（用于恢复会话）。 */
    var lastProjectId: Long
        get() = prefs().getLong("last_project_id", -1L)
        set(value) = prefs().edit().putLong("last_project_id", value).apply()

    /** 手动滚动模式下是否已设置过提示。 */
    var manualModeHinted: Boolean
        get() = prefs().getBoolean("manual_mode_hinted", false)
        set(value) = prefs().edit().putBoolean("manual_mode_hinted", value).apply()

    /** UI 主题偏好：system / light / dark（commit 同步落盘，确保重启即生效）。 */
    var theme: String
        get() = prefs().getString("ui_theme", "system") ?: "system"
        set(value) { prefs().edit().putString("ui_theme", value).commit() }

    /** UI 语言偏好：system / zh / en（commit 同步落盘）。 */
    var language: String
        get() = prefs().getString("ui_language", "system") ?: "system"
        set(value) { prefs().edit().putString("ui_language", value).commit() }

    // ---------- 按项目的截图偏好 ----------

    private fun modeKey(projectId: Long) = "project_${projectId}_mode"
    private fun stepKey(projectId: Long) = "project_${projectId}_stepdp"
    private fun loopCountKey(projectId: Long) = "project_${projectId}_loopcount"

    /** 项目默认截图模式（AUTO_SCROLL / MANUAL / FIXED_STEP / CALIBRATED_DISTANCE）。 */
    fun getProjectMode(projectId: Long): String =
        prefs().getString(modeKey(projectId), "AUTO_SCROLL") ?: "AUTO_SCROLL"

    fun setProjectMode(projectId: Long, mode: String) {
        prefs().edit().putString(modeKey(projectId), mode).apply()
    }

    /** 项目默认步长（dp），仅固定步长模式使用。 */
    fun getProjectStepDp(projectId: Long): Int =
        prefs().getInt(stepKey(projectId), 200)

    fun setProjectStepDp(projectId: Long, stepDp: Int) {
        prefs().edit().putInt(stepKey(projectId), stepDp).apply()
    }

    /** 项目循环次数：0 表示不限次数（滑动步长变化时自动停止）。 */
    fun getProjectLoopCount(projectId: Long): Int =
        prefs().getInt(loopCountKey(projectId), 0)

    fun setProjectLoopCount(projectId: Long, count: Int) {
        prefs().edit().putInt(loopCountKey(projectId), count.coerceIn(0, 999)).apply()
    }

    // ---------- 项目截图张数上限（长截图 / 手动滚动共用） ----------

    /** 长截图与手动滚动的单次拼接张数上限，默认 10，范围 3~20。
     *  优先级高于循环次数：达到上限立即收尾保存。 */
    fun getProjectMaxFrames(projectId: Long): Int =
        prefs().getInt("project_max_frames_$projectId", 10).coerceIn(3, 20)

    fun setProjectMaxFrames(projectId: Long, value: Int) {
        prefs().edit().putInt("project_max_frames_$projectId", value.coerceIn(3, 20)).apply()
    }

    // ---------- 点位持久化（CALIBRATED_DISTANCE 模式） ----------

    private fun pointsKey(projectId: Long) = "project_${projectId}_points"

    /** 保存点位列表，格式 "x1,y1;x2,y2;..." */
    fun setProjectPoints(projectId: Long, points: List<Pair<Float, Float>>) {
        val raw = points.joinToString(";") { "${it.first},${it.second}" }
        prefs().edit().putString(pointsKey(projectId), raw).apply()
    }

    /** 读取点位列表，解析失败返回空列表。 */
    fun getProjectPoints(projectId: Long): List<Pair<Float, Float>> {
        val raw = prefs().getString(pointsKey(projectId), null) ?: return emptyList()
        return try {
            raw.split(";").filter { it.isNotBlank() }.map {
                val (x, y) = it.split(",")
                x.toFloat() to y.toFloat()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ---------- 结束判定：重复率（%） ----------

    /**
     * 结束判定重复率阈值（%）。连续两帧相同像素占比达到该值时判定到底/回弹并停止。
     * 默认 90：正常滚动（哪怕实际滚动距离只有半屏）相同占比远低于该值，
     * 只有界面真正不再变化才会触发结束。
     */
    var duplicateRate: Int
        get() = prefs().getInt("capture_duplicate_rate", 90)
        set(value) = prefs().edit().putInt("capture_duplicate_rate", value.coerceIn(1, 100)).apply()
}