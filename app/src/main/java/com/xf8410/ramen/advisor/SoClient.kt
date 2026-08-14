package com.xf8410.ramen.advisor

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * hlpatch SO 端点客户端
 *
 * 通过 HTTP 连接本地 hlpatch 插件（127.0.0.1:18765），
 * 读取游戏运行时状态。
 *
 * 上游参考：URA-Plugins OnsenScenarioAnalyzer 通过 MITM 代理获取协议数据，
 * 本项目改为通过 hlpatch IL2CPP 内存读取端点获取。
 */
class SoClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 18765,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /** 拉取 /summary */
    fun fetchSummary(): SummaryResponse? {
        return try {
            val req = Request.Builder()
                .url("http://$host:$port/summary")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                gson.fromJson(body, SummaryResponse::class.java)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 检查 SO 是否在线 */
    fun health(): Boolean {
        return try {
            val req = Request.Builder()
                .url("http://$host:$port/health")
                .get()
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 手动触发 sniff + md5 hook 安装
     *
     * 必须在游戏进入主界面后调用，提前调用会导致游戏崩溃。
     * 原因：IL2CPP 类加载未完成时 hook Cryptographer/HttpHelper 会崩溃。
     *
     * 最小安全序列（3步）：
     * 1. /api/md5log/install          — 装 Cryptographer.MakeMd5 hook
     * 2. /api/sniff/toggle?enabled=1  — 开启 sniff，同时自动装 CompressRequest/DecompressResponse/Post hook
     * 3. /api/md5log/clear            — 清空旧的 MD5 记录
     *
     * /api/sniff/diag 是只读检查，不改变状态，不装 hook。
     * /api/sniff/toggle 内部已经调了 install_api_sniff_hooks()，不需要重复 install。
     *
     * 返回最终 hook 状态
     */
    fun installHooks(): List<Pair<String, Boolean>> {
        val results = mutableListOf<Pair<String, Boolean>>()
        val steps = listOf(
            // 步骤1: 装 MakeMd5 hook（抓发包明文）
            "/api/md5log/install" to "md5_install",
            // 步骤2: 开启 sniff（内部自动装 Compress/Decompress/Post hook）
            "/api/sniff/toggle?enabled=1" to "sniff_toggle_on",
            // 步骤3: 清空旧记录
            "/api/md5log/clear" to "md5_clear",
        )
        for ((path, label) in steps) {
            val ok = try {
                val req = Request.Builder()
                    .url("http://$host:$port$path")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    resp.isSuccessful
                }
            } catch (e: Exception) {
                false
            }
            results.add(label to ok)
            // 每步之间间隔 500ms，避免过快触发
            Thread.sleep(500)
        }
        return results
    }

    /**
     * 检查 sniff hook 是否已安装且生效
     */
    fun checkHookStatus(): HookStatus? {
        return try {
            val req = Request.Builder()
                .url("http://$host:$port/api/sniff/diag")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                parseHookStatus(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseHookStatus(json: String): HookStatus {
        val makemd5Hooked = json.contains("\"makemd5_hooked\":true")
        val compressHooked = json.contains("\"compress_hooked\":true")
        val decompressHooked = json.contains("\"decompress_hooked\":true")
        val postHooked = json.contains("\"post_hooked\":true")
        val sniffEnabled = json.contains("\"sniff_enabled\":true")
        return HookStatus(
            makemd5Hooked = makemd5Hooked,
            compressHooked = compressHooked,
            decompressHooked = decompressHooked,
            postHooked = postHooked,
            sniffEnabled = sniffEnabled,
            ready = makemd5Hooked && compressHooked && decompressHooked && postHooked,
        )
    }
}

data class HookStatus(
    val makemd5Hooked: Boolean,
    val compressHooked: Boolean,
    val decompressHooked: Boolean,
    val postHooked: Boolean,
    val sniffEnabled: Boolean,
    val ready: Boolean,
)
