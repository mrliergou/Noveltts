package app.noveltts.data.voicepack

import app.noveltts.data.JsonInput
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在线语音包目录与下载。
 *
 * 目录是一个 JSON 文档，两种写法都支持：
 * ```json
 * { "packs": [ { "id": "...", "name": "...", "url": "https://.../pack.zip" } ] }
 * ```
 * 或者直接是数组 `[ { ... } ]`。除 `id`/`url` 外的字段都可省略。
 */
object VoicePackCatalog {
    private const val TAG = "VoicePackCatalog"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    fun fetch(url: String): List<CatalogEntry> {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("目录请求失败：HTTP $code")
            PackInput.checkLength(connection.contentLengthLong, PackInput.MAX_CATALOG_BYTES)
            val body = connection.inputStream.use {
                PackInput.readBytes(it, PackInput.MAX_CATALOG_BYTES).toString(Charsets.UTF_8)
            }
            return parse(body)
        } finally {
            connection.disconnect()
        }
    }

    fun parse(body: String): List<CatalogEntry> {
        JsonInput.checkDepth(body)
        val trimmed = body.removePrefix("\uFEFF").trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed).optJSONArray("packs") ?: JSONArray()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                CatalogEntry.fromJson(item)?.let { add(it) }
            }
        }
    }

    /** 下载到 [dest]，进度回调 (已下载字节, 总字节或 -1)。 */
    fun download(entry: CatalogEntry, dest: File, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val connection = open(entry.url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("下载失败：HTTP $code")
            val total = connection.contentLengthLong.takeIf { it > 0 }
                ?: entry.sizeBytes.takeIf { it > 0 }
                ?: -1L
            PackInput.checkLength(total, PackInput.MAX_ARCHIVE_BYTES)
            val received = connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    PackInput.copy(input, output, PackInput.MAX_ARCHIVE_BYTES) { onProgress(it, total) }
                }
            }
            Log.i(TAG, "downloaded ${entry.id} (${received}B)")
        } catch (error: Exception) {
            dest.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val address = URL(url)
        if (address.protocol !in setOf("http", "https")) throw IOException("仅支持 HTTP / HTTPS 下载地址")
        val connection = address.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "NovelTTS/1.0")
        connection.setRequestProperty("Accept", "application/json, application/zip, */*")
        return connection
    }
}
