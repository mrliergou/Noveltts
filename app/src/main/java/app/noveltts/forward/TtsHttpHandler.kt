package app.noveltts.forward

import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.engine.Category
import app.noveltts.engine.DiagnosticsLog
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject

/** 合成出来的单声道 16-bit PCM。 */
internal data class ForwardAudio(val sampleRateHz: Int, val pcm: ByteArray)

/**
 * HTTP 层唯一的引擎依赖。
 *
 * 真正实现见 [EngineForwardBackend]（在 ForwardServer.kt）；单测里换成假实现即可，
 * 不依赖 Android 运行时。
 */
internal interface ForwardSynthesisBackend {
    /** 当前可用音色列表。 */
    fun voices(): List<VoiceDescriptor>

    /**
     * 合成一段文本。
     *
     * @param voiceId 已确认可用的音色；null 表示使用默认旁白音色
     * @throws ForwardHttpException 音色不存在、引擎失败等可返回给调用方的错误
     */
    fun synthesize(voiceId: String?, text: String, rate: Float, pitch: Float, volume: Float): ForwardAudio
    fun synthesize(voiceId: String?, text: String, rate: Float, pitch: Float, volume: Float,
        context: ForwardRequestContext): ForwardAudio {
        context.ensureActive()
        return synthesize(voiceId, text, rate, pitch, volume)
    }
}

/**
 * 极简 HTTP/1.1 请求处理：一条连接一次请求，处理完即关闭（Connection: close）。
 *
 * 路由：
 * - GET  /health  健康检查（不需要 token）
 * - GET  /voices  音色列表
 * - GET|POST /tts 合成，返回 audio/wav
 */
internal class TtsHttpHandler(
    private val backend: ForwardSynthesisBackend,
    private val config: () -> ForwardConfig,
) {

    fun handle(input: InputStream, output: OutputStream, context: ForwardRequestContext = ForwardRequestContext()) {
        val token = config().token
        try {
            context.ensureActive()
            val requestLine = ForwardProtocol.readLine(input, ForwardProtocol.MAX_LINE_BYTES) ?: return
            val request = ForwardProtocol.parseRequestLine(requestLine)
                ?: throw ForwardHttpException(400, "bad_request", "无法解析请求行")
            val headers = ForwardProtocol.readHeaders(input)
            val target = ForwardProtocol.splitTarget(request.target)
            val query = ForwardProtocol.parseQuery(target.query)
            when (target.path) {
                "/health" -> {
                    requireMethod(request.method, "GET")
                    handleHealth(output)
                }

                "/voices" -> {
                    requireAuthorized(token, headers, query)
                    requireMethod(request.method, "GET")
                    handleVoices(output)
                }

                "/tts" -> {
                    requireAuthorized(token, headers, query)
                    handleTts(output, request.method, input, headers, query, context)
                }
                "/legado" -> {
                    requireAuthorized(token, headers, query)
                    requireMethod(request.method, "GET")
                    writeResponse(output, 200, "application/json; charset=utf-8",
                        LegadoIntegration.configJson(config()).toByteArray(Charsets.UTF_8),
                        listOf("Cache-Control" to "no-store"))
                }

                else -> throw ForwardHttpException(404, "not_found", "未知路径：${target.path}")
            }
        } catch (error: ForwardHttpException) {
            writeJsonError(output, error.status, error.code, error.message.orEmpty(), error.allow)
        } catch (error: Exception) {
            DiagnosticsLog.warn(Category.ENGINE, "HTTP 转发请求处理失败", error.message?.take(200))
            writeJsonError(output, 500, "internal_error", "服务内部错误")
        }
    }

    private fun handleHealth(output: OutputStream) {
        val count = backend.voices().count { it.usable }
        writeJson(output, 200, JSONObject().put("status", "ok").put("voices", count))
    }

    private fun handleVoices(output: OutputStream) {
        val array = JSONArray()
        for (voice in backend.voices()) {
            array.put(
                JSONObject()
                    .put("systemName", voice.systemName)
                    .put("displayName", voice.displayName)
                    .put("engineCode", voice.engineCode)
                    .put("isOnline", voice.isOnline)
                    .put("usable", voice.usable)
                    .put("locale", voice.localeTag),
            )
        }
        writeJson(output, 200, JSONObject().put("voices", array))
    }

    private fun handleTts(
        output: OutputStream,
        method: String,
        input: InputStream,
        headers: Map<String, String>,
        query: Map<String, String>,
        context: ForwardRequestContext,
    ) {
        val params = when (method) {
            "POST" -> {
                val body = ForwardProtocol.readBody(input, headers)
                ForwardProtocol.parseTtsJson(String(body, Charsets.UTF_8))
            }

            "GET" -> ForwardProtocol.parseTtsQuery(query)
            else -> throw ForwardHttpException(405, "method_not_allowed", "/tts 只支持 GET 与 POST", allow = "GET, POST")
        }
        context.beginSynthesis()
        val audio = backend.synthesize(params.voice, params.text, params.rate, params.pitch, params.volume, context)
        context.ensureActive()
        val wav = ByteArray(WavPcm.totalBytes(audio.pcm.size))
        System.arraycopy(WavPcm.header(audio.pcm.size, audio.sampleRateHz), 0, wav, 0, WavPcm.HEADER_BYTES)
        System.arraycopy(audio.pcm, 0, wav, WavPcm.HEADER_BYTES, audio.pcm.size)
        writeResponse(
            output = output,
            status = 200,
            contentType = "audio/wav",
            body = wav,
            extraHeaders = listOf(
                "Cache-Control" to "no-store",
                "Content-Disposition" to "inline; filename=\"tts.wav\"",
            ),
        )
    }

    private fun requireAuthorized(expected: String, headers: Map<String, String>, query: Map<String, String>) {
        val provided = ForwardProtocol.requestToken(headers, query)
        if (!ForwardProtocol.isAuthorized(expected, provided)) {
            throw ForwardHttpException(401, "unauthorized", "访问令牌无效")
        }
    }

    private fun requireMethod(actual: String, expected: String) {
        if (actual != expected) {
            throw ForwardHttpException(405, "method_not_allowed", "只支持 $expected", allow = expected)
        }
    }

    private fun writeJson(output: OutputStream, status: Int, payload: JSONObject) {
        writeResponse(
            output = output,
            status = status,
            contentType = "application/json; charset=utf-8",
            body = payload.toString().toByteArray(Charsets.UTF_8),
            extraHeaders = listOf("Cache-Control" to "no-store"),
        )
    }

    private fun writeJsonError(output: OutputStream, status: Int, code: String, message: String, allow: String? = null) {
        val payload = JSONObject().put("error", code).put("message", message.take(300))
        runCatching {
            writeResponse(
                output = output,
                status = status,
                contentType = "application/json; charset=utf-8",
                body = payload.toString().toByteArray(Charsets.UTF_8),
                extraHeaders = listOfNotNull(
                    "Cache-Control" to "no-store",
                    allow?.let { "Allow" to it },
                ),
            )
        }
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ) {
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            for ((name, value) in extraHeaders) {
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        499 -> "Client Closed Request"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Error"
    }
}
