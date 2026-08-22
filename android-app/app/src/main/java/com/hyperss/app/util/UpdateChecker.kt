package com.hyperss.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 更新检查器。
 *
 * 唯一的网络用途：访问 GitHub API 读取最新 Release 元信息（不带任何本机数据），
 * 截图与项目数据仍然只存本机、绝不上传。
 */
object UpdateChecker {

    const val RELEASES_PAGE = "https://github.com/SN00701/HyperSS/releases"
    private const val LATEST_API = "https://api.github.com/repos/SN00701/HyperSS/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 检查结果。 */
    sealed interface Result {
        /** 有新版本：[version] 已去掉前缀 v，如 "0.0.11beta"。 */
        data class UpdateAvailable(val version: String, val notes: String) : Result

        /** 已是最新版本。 */
        data object UpToDate : Result

        /** 检查失败（网络不可达 / 限流 / 解析异常）。 */
        data class Failed(val reason: String) : Result
    }

    /** 启动自动检查的结果（一次性），供 UI 收集后弹窗提醒。 */
    private val _startupResult = MutableStateFlow<Result?>(null)
    val startupResult: StateFlow<Result?> = _startupResult.asStateFlow()

    fun publishStartupResult(result: Result?) {
        _startupResult.value = result
    }

    /** 拉取最新 Release 并与当前版本比较。 */
    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(LATEST_API).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) {
                    return@withContext Result.Failed("HTTP ${conn.responseCode}")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val release = JSONObject(body)
                val tag = release.optString("tag_name", "")
                val remote = parseVersion(tag)
                    ?: return@withContext Result.Failed("bad tag: $tag")
                if (compareVersions(remote, parseVersion(currentVersion) ?: currentVersion) > 0) {
                    Result.UpdateAvailable(remote, release.optString("body", "").trim())
                } else {
                    Result.UpToDate
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /** 解析 "v0.0.10beta" / "0.10.3-beta" 等形式；无法解析返回 null。输出去掉前导 v。 */
    fun parseVersion(raw: String): String? {
        val s = raw.trim().removePrefix("v").removePrefix("V")
        val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(.*)$").find(s) ?: return null
        val (maj, min, pat) = m.destructured
        val suffix = m.groupValues[4].replace(Regex("[-_.]"), "")
        return "$maj.$min.$pat$suffix"
    }

    /** 仅按 major.minor.patch 数值比较；相等视为不更新（含 beta → 正式同号场景）。 */
    fun compareVersions(a: String, b: String): Int {
        val pa = versionNumbers(a) ?: return 0
        val pb = versionNumbers(b) ?: return 0
        for (i in 0..2) {
            val cmp = pa[i].compareTo(pb[i])
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun versionNumbers(v: String): List<Int>? =
        Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(v)?.groupValues?.drop(1)?.map { it.toInt() }
}
