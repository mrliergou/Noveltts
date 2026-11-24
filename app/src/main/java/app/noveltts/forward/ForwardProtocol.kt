package app.noveltts.forward

import app.noveltts.data.JsonInput
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/**
 * HTTP 层错误：直接决定响应的状态码与 JSON 错误码。
 *
 * 只用于「可以明确告诉调用方」的失败；引擎崩溃一类的内部异常由调用方包成 500。
 */
internal class ForwardHttpException(
    val status: Int,
    val code: String,
    message: String,
    /** 405 时可选返回的 Allow 头。 */
    val allow: String? = null,
) : Exception(message)

/** 请求行解析结果；[target] 保留原始查询串，交给 [ForwardProtocol.splitTarget]。 */
internal data class HttpRequestLine(val method: String, val target: String)

/** [HttpRequestLine.target] 拆出来的路径与查询串（query 不含 '?'，没有查询串时为 null）。 */
internal data class HttpTarget(val path: String, val query: String?)

/** /tts 的归一化入参；[format] 目前只可能是 "wav"。 */
internal data class TtsParams(
    val text: String,
    val voice: String?,
    val rate: Float,
    val pitch: Float,
    val volume: Float,
    val format: String = FORMAT_WAV,
) {
    companion object {
        const val FORMAT_WAV = "wav"
    }
}

/**
 * HTTP 解析与 /tts 入参校验的纯逻辑部分。
 *
 * 这里刻意不碰 Android API、也不碰网络：只对字符串和 [InputStream] 操作，
 * 因此可以在 JVM 单测里直接覆盖（见 app/src/test/java/app/noveltts/forward/ForwardServerTest.kt）。
 */
internal object ForwardProtocol {

    /** 单行请求行/请求头的字节上限，防止畸形请求把内存吃满。 */
    const val MAX_LINE_BYTES = 8 * 1024

    /** 请求头整体上限。 */
    const val MAX_HEADER_BYTES = 32 * 1024

    /** 请求头条数上限。 */
    const val MAX_HEADER_COUNT = 64

    /** POST 请求体上限。 */
    const val MAX_BODY_BYTES = 4 * 1024 * 1024

    /** 单次合成文本上限（字）；超过请调用方分段，否则容易先撞上合成超时。 */
    const val MAX_TEXT_CHARS = 5_000

    const val MIN_RATE = 0.25f
    const val MAX_RATE = 4f
    const val MIN_VOLUME = 0f
    const val MAX_VOLUME = 1f

    /** Read one bounded CRLF/LF line; EOF is valid only before any bytes are read. */
    fun readLine(input: InputStream, limit: Int): String? {
        val buffer = ByteArrayOutputStream(64)
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (buffer.size() == 0) return null
                throw ForwardHttpException(400, "line_truncated", "请求行或请求头不完整")
            }
            if (value == '\n'.code) break
            if (value == '\r'.code) {
                if (input.read() != '\n'.code) throw ForwardHttpException(400, "invalid_line", "请求行换行非法")
                break
            }
            if (buffer.size() >= limit) {
                throw ForwardHttpException(400, "request_too_long", "请求行或请求头过长")
            }
            buffer.write(value)
        }
        return String(buffer.toByteArray(), Charsets.UTF_8)
    }

    /** 解析 "GET /tts?text=hi HTTP/1.1"；方法统一转大写。 */
    fun parseRequestLine(line: String): HttpRequestLine? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size != 3 || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) return null
        val method = parts[0]
        val target = parts[1]
        if (method.isEmpty() || target.isEmpty()) return null
        return HttpRequestLine(method.uppercase(Locale.ROOT), target)
    }

    /** 拆 path / query，兼容代理由的绝对形式 http://host/path?query，并去掉路径尾部多余的 '/'。 */
    fun splitTarget(target: String): HttpTarget {
        var raw = target.trim()
        val schemeIndex = raw.indexOf("://")
        if (schemeIndex > 0) {
            val afterScheme = raw.substring(schemeIndex + 3)
            val slash = afterScheme.indexOf('/')
            raw = if (slash >= 0) afterScheme.substring(slash) else "/"
        }
        val question = raw.indexOf('?')
        val path = if (question >= 0) raw.substring(0, question) else raw
        val query = if (question >= 0) raw.substring(question + 1) else null
        return HttpTarget(normalizePath(path), query)
    }

    /** 空路径补成 "/"，去掉尾部多余 '/'（根路径保持 "/"）。 */
    fun normalizePath(path: String): String {
        if (path.isEmpty()) return "/"
        var result = if (path.startsWith("/")) path else "/$path"
        while (result.length > 1 && result.endsWith('/')) {
            result = result.dropLast(1)
        }
        return result
    }

    /** 解析 query 串为 map；同名参数取第一个，值做 URL 解码。 */
    fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val equals = pair.indexOf('=')
            val rawKey = if (equals >= 0) pair.substring(0, equals) else pair
            val rawValue = if (equals >= 0) pair.substring(equals + 1) else ""
            val key = decode(rawKey)
            if (key.isEmpty()) continue
            if (!result.containsKey(key)) result[key] = decode(rawValue)
        }
        return result
    }

    /** URL 解码；%XX 畸形时原样返回，避免因为一个坏参数整条请求 400。 */
    fun decode(value: String): String = try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        value
    } catch (_: java.io.UnsupportedEncodingException) {
        value
    }

    /** Headers and chunk trailers share byte/count limits and require a terminating blank line. */
    fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        var consumed = 0
        var count = 0
        while (true) {
            val line = readLine(input, MAX_LINE_BYTES)
                ?: throw ForwardHttpException(400, "headers_truncated", "请求头不完整")
            if (line.isEmpty()) break
            consumed += line.toByteArray(Charsets.UTF_8).size + 2
            if (consumed > MAX_HEADER_BYTES) throw ForwardHttpException(400, "headers_too_large", "请求头过大")
            if (++count > MAX_HEADER_COUNT) throw ForwardHttpException(400, "too_many_headers", "请求头过多")
            val colon = line.indexOf(':')
            if (colon <= 0) throw ForwardHttpException(400, "invalid_header", "请求头格式无效")
            val name = line.substring(0, colon)
            if (name.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in "!#$%&'*+-.^_`|~" }) {
                throw ForwardHttpException(400, "invalid_header", "请求头名称无效")
            }
            val key = name.lowercase(Locale.ROOT)
            if (key in headers && key in setOf("content-length", "transfer-encoding")) {
                throw ForwardHttpException(400, "ambiguous_framing", "请求包含重复的长度或编码头")
            }
            val value = line.substring(colon + 1).trim()
            val existing = headers[key]
            headers[key] = if (existing == null) value else "$existing, $value"
        }
        return headers
    }

    /** Accept exactly one framing mechanism; unsupported transfer codings are rejected. */
    fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val transferEncoding = headers["transfer-encoding"]
        val rawLength = headers["content-length"]
        if (transferEncoding != null) {
            if (rawLength != null) throw ForwardHttpException(400, "ambiguous_framing", "不能同时提供长度与传输编码")
            if (!transferEncoding.trim().equals("chunked", ignoreCase = true)) {
                throw ForwardHttpException(400, "unsupported_encoding", "仅支持 chunked 传输编码")
            }
            return readChunkedBody(input)
        }
        if (rawLength == null) throw ForwardHttpException(411, "length_required", "缺少 Content-Length 或 chunked 传输编码")
        val text = rawLength.trim()
        val length = text.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toLongOrNull()
            ?: throw ForwardHttpException(400, "invalid_length", "Content-Length 非法")
        if (length > MAX_BODY_BYTES) throw ForwardHttpException(413, "body_too_large", "请求体超过 ${MAX_BODY_BYTES / 1024} KB")
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val count = input.read(body, read, body.size - read)
            if (count <= 0) throw ForwardHttpException(400, "body_truncated", "请求体不完整")
            read += count
        }
        return body
    }

    /** Chunk data and trailers are bounded independently; EOF never substitutes for completion. */
    fun readChunkedBody(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(1024)
        while (true) {
            val line = readLine(input, MAX_LINE_BYTES)
                ?: throw ForwardHttpException(400, "body_truncated", "chunked 请求体不完整")
            val sizeText = line.substringBefore(';').trim()
            val size = sizeText.takeIf { it.isNotEmpty() && it.all { char -> char in "0123456789abcdefABCDEF" } }
                ?.toLongOrNull(16) ?: throw ForwardHttpException(400, "invalid_chunk", "chunk 长度非法")
            if (size == 0L) {
                val trailers = readHeaders(input)
                if (trailers.keys.any { it in setOf("content-length", "transfer-encoding") }) {
                    throw ForwardHttpException(400, "invalid_trailer", "trailer 不能改变请求体长度")
                }
                return output.toByteArray()
            }
            if (size > MAX_BODY_BYTES.toLong() - output.size()) {
                throw ForwardHttpException(413, "body_too_large", "请求体超过 ${MAX_BODY_BYTES / 1024} KB")
            }
            val chunk = ByteArray(size.toInt())
            var read = 0
            while (read < chunk.size) {
                val count = input.read(chunk, read, chunk.size - read)
                if (count <= 0) throw ForwardHttpException(400, "body_truncated", "chunked 请求体不完整")
                read += count
            }
            output.write(chunk)
            val eol = input.read()
            if (eol != '\n'.code && (eol != '\r'.code || input.read() != '\n'.code)) {
                throw ForwardHttpException(400, "invalid_chunk", "chunk 缺少换行")
            }
        }
    }

    /** 依次尝试 X-Token、Authorization: Bearer、?token=。 */
    fun requestToken(headers: Map<String, String>, query: Map<String, String>): String? {
        headers["x-token"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val authorization = headers["authorization"]?.trim().orEmpty()
        if (authorization.length > 6 &&
            authorization.startsWith("Bearer", ignoreCase = true) &&
            authorization[6].isWhitespace()
        ) {
            val value = authorization.substring(7).trim()
            if (value.isNotEmpty()) return value
        }
        return query["token"]?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 常量时间比较；配置的 token 为空时表示不校验。 */
    fun isAuthorized(expectedToken: String, providedToken: String?): Boolean {
        if (expectedToken.isBlank()) return true
        if (providedToken.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            expectedToken.toByteArray(Charsets.UTF_8),
            providedToken.toByteArray(Charsets.UTF_8),
        )
    }

    /** POST /tts 的 JSON 入参。 */
    fun parseTtsJson(body: String): TtsParams {
        if (body.isBlank()) throw ForwardHttpException(400, "invalid_json", "请求体为空")
        val json = try {
            JsonInput.parseObject(body)
        } catch (_: JSONException) {
            throw ForwardHttpException(400, "invalid_json", "请求体不是合法 JSON")
        } catch (_: IllegalArgumentException) {
            throw ForwardHttpException(400, "invalid_json", "请求体不是合法 JSON 或嵌套过深")
        }
        val text = if (json.has("text") && !json.isNull("text")) json.optString("text") else ""
        val voice = optText(json, "voice")
        val rate = optNumber(json, "rate", 1f)
        val pitch = optNumber(json, "pitch", 1f)
        val volume = optNumber(json, "volume", 1f)
        val format = optText(json, "format")
        return normalizeParams(text, voice, rate, pitch, volume, format)
    }

    /** GET /tts?... 的 query 入参。 */
    fun parseTtsQuery(query: Map<String, String>): TtsParams {
        val text = query["text"].orEmpty()
        val voice = query["voice"]?.trim()?.takeIf { it.isNotEmpty() }
        val rate = queryNumber("rate", query["rate"], 1f)
        val pitch = queryNumber("pitch", query["pitch"], 1f)
        val volume = queryNumber("volume", query["volume"], 1f)
        return normalizeParams(text, voice, rate, pitch, volume, query["format"])
    }

    /** 文本/音色/参数/format 的统一校验。format 目前只支持 wav，mp3 明确拒绝而不是假装支持。 */
    fun normalizeParams(
        text: String,
        voice: String?,
        rate: Float,
        pitch: Float,
        volume: Float,
        format: String?,
    ): TtsParams {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) throw ForwardHttpException(400, "text_required", "text 不能为空")
        if (cleaned.length > MAX_TEXT_CHARS) {
            throw ForwardHttpException(400, "text_too_long", "单次最多 ${MAX_TEXT_CHARS} 字，请分段请求")
        }
        val normalizedFormat = format?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: TtsParams.FORMAT_WAV
        if (normalizedFormat != TtsParams.FORMAT_WAV) {
            throw ForwardHttpException(400, "unsupported_format", "暂不支持 format=$normalizedFormat，只支持 wav（本机没有 MP3 编码器）")
        }
        requireRange("rate", rate, MIN_RATE, MAX_RATE)
        requireRange("pitch", pitch, 0.25f, 2f)
        requireRange("volume", volume, MIN_VOLUME, MAX_VOLUME)
        return TtsParams(
            text = cleaned,
            voice = voice?.trim()?.takeIf { it.isNotEmpty() },
            rate = rate,
            pitch = pitch,
            volume = volume,
            format = normalizedFormat,
        )
    }

    private fun requireRange(name: String, value: Float, min: Float, max: Float) {
        if (!value.isFinite() || value < min || value > max) {
            throw ForwardHttpException(400, "invalid_param", "$name 需要在 $min..$max 之间")
        }
    }

    private fun optText(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.optString(key).trim()
        return value.takeIf { it.isNotEmpty() }
    }

    private fun optNumber(json: JSONObject, key: String, fallback: Float): Float {
        if (!json.has(key) || json.isNull(key)) return fallback
        val value = try {
            json.optDouble(key, Double.NaN)
        } catch (_: JSONException) {
            Double.NaN
        }
        if (value.isNaN() || value.isInfinite()) {
            throw ForwardHttpException(400, "invalid_param", "$key 必须是数字")
        }
        return value.toFloat()
    }

    private fun queryNumber(name: String, raw: String?, fallback: Float): Float {
        if (raw.isNullOrBlank()) return fallback
        return raw.trim().toFloatOrNull()
            ?: throw ForwardHttpException(400, "invalid_param", "$name 必须是数字")
    }
}

/** 最小 WAV(RIFF) 写入：只支持 16-bit PCM。 */
internal object WavPcm {
    const val HEADER_BYTES = 44
    const val SAMPLE_RATE_HZ = 24_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    /** 整段 WAV 的字节数（头 + PCM）。 */
    fun totalBytes(pcmBytes: Int): Int = HEADER_BYTES + pcmBytes.coerceAtLeast(0)

    /** 生成 44 字节 WAV 头；参数非法时退回默认值。 */
    fun header(
        pcmBytes: Int,
        sampleRateHz: Int = SAMPLE_RATE_HZ,
        channels: Int = CHANNELS,
        bitsPerSample: Int = BITS_PER_SAMPLE,
    ): ByteArray {
        val data = pcmBytes.coerceAtLeast(0)
        val rate = if (sampleRateHz > 0) sampleRateHz else SAMPLE_RATE_HZ
        val channelCount = if (channels > 0) channels else CHANNELS
        val bits = if (bitsPerSample > 0) bitsPerSample else BITS_PER_SAMPLE
        val blockAlign = channelCount * bits / 8
        val byteRate = rate * blockAlign
        val out = ByteArray(HEADER_BYTES)
        ascii(out, 0, "RIFF")
        intLe(out, 4, 36 + data)
        ascii(out, 8, "WAVE")
        ascii(out, 12, "fmt ")
        intLe(out, 16, 16)
        shortLe(out, 20, 1)
        shortLe(out, 22, channelCount)
        intLe(out, 24, rate)
        intLe(out, 28, byteRate)
        shortLe(out, 32, blockAlign)
        shortLe(out, 34, bits)
        ascii(out, 36, "data")
        intLe(out, 40, data)
        return out
    }

    private fun ascii(target: ByteArray, offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(target, offset)
    }

    private fun intLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun shortLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
