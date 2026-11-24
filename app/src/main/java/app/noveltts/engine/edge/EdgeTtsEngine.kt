package app.noveltts.engine.edge

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import app.noveltts.data.settings.SettingsStore
import app.noveltts.engine.Category
import app.noveltts.engine.DiagnosticsLog
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.engine.api.SynthesisError
import app.noveltts.engine.api.SynthesisHandle
import app.noveltts.engine.api.SynthesisListener
import app.noveltts.engine.api.SynthesisRequest
import app.noveltts.engine.api.remainingTimeoutMillis
import app.noveltts.engine.api.synthesisTimeoutMillis
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Microsoft Edge Read Aloud 非官方在线引擎。 */
class EdgeTtsEngine(
    context: Context,
    private val settings: SettingsStore,
) {
    private val cacheDir = File(context.applicationContext.cacheDir, "edge-tts").apply { mkdirs() }
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "multitts-edge").apply { isDaemon = true }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val closed = AtomicBoolean(false)

    fun speak(
        descriptor: VoiceDescriptor,
        request: SynthesisRequest,
        listener: SynthesisListener,
    ): SynthesisHandle {
        val cancelled = AtomicBoolean(false)
        val socket = AtomicReference<WebSocket?>(null)
        val handle = object : SynthesisHandle {
            override fun cancel() {
                cancelled.set(true)
                runCatching { socket.getAndSet(null)?.cancel() }
            }
        }
        if (request.text.isBlank()) {
            listener.onError(SynthesisError.BAD_REQUEST, "待合成文本为空")
            return handle
        }
        val timedRequest = if (request.deadlineNanos != null) request else request.copy(
            deadlineNanos = System.nanoTime() + synthesisTimeoutMillis(request.text.length,
                (request.options?.timeoutSec ?: settings.requestTimeoutSec) * 1000L) * 1_000_000L)
        executor.execute {
            try {
                val mp3 = synthesizeToMp3(descriptor, timedRequest, cancelled, socket)
                if (cancelled.get() || closed.get()) return@execute
                if (mp3.isEmpty()) {
                    listener.onError(SynthesisError.NO_AUDIO, "在线服务正常结束但没有音频")
                    return@execute
                }
                val temp = File(cacheDir, "edge-${UUID.randomUUID()}.mp3")
                val protection = app.noveltts.data.cache.ActiveAudioFiles.protect(temp)
                try {
                    temp.writeBytes(mp3)
                    request.timing.mark("decode-start")
                    val pcm = Mp3PcmDecoder.decodeToPcm16(temp, request.volume, timedRequest.deadlineNanos) { cancelled.get() || closed.get() }
                    request.timing.mark("decode-finished")
                    if (cancelled.get() || closed.get()) return@execute
                    if (pcm.isEmpty()) {
                        listener.onError(SynthesisError.NO_AUDIO, "在线音频解码后没有 PCM")
                        return@execute
                    }
                    listener.onStart(24_000, AudioFormat.ENCODING_PCM_16BIT, 1)
                    var offset = 0
                    while (offset < pcm.size) {
                        if (cancelled.get() || closed.get()) return@execute
                        val length = minOf(CHUNK_BYTES, pcm.size - offset)
                        listener.onAudio(pcm, offset, length)
                        offset += length
                    }
                    if (!cancelled.get()) listener.onComplete()
                } finally {
                    temp.delete()
                    protection.close()
                }
            } catch (error: OutOfMemoryError) {
                runCatching { socket.getAndSet(null)?.cancel() }
                if (!cancelled.get()) listener.onError(SynthesisError.RESOURCE_LIMIT, "在线音频内存不足")
            } catch (error: app.noveltts.engine.api.ResourceLimitException) {
                socket.getAndSet(null)?.cancel()
                if (!cancelled.get()) listener.onError(SynthesisError.RESOURCE_LIMIT, error.message)
            } catch (error: EdgeHttpException) {
                DiagnosticsLog.warn(Category.ENGINE, "Edge 合成失败（HTTP ${error.code}）", error.userMessage().take(200))
                if (!cancelled.get()) {
                    listener.onError(error.code, error.userMessage())
                }
            } catch (error: Exception) {
                DiagnosticsLog.error(Category.ENGINE, "Edge 合成异常", error.message?.take(200))
                if (!cancelled.get()) {
                    Log.w(TAG, "edge synthesis failed", error)
                    listener.onError(SynthesisError.NETWORK_FAILED, error.message ?: "在线语音合成失败")
                }
            }
        }
        return handle
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun synthesizeToMp3(
        descriptor: VoiceDescriptor,
        request: SynthesisRequest,
        cancelled: AtomicBoolean,
        socket: AtomicReference<WebSocket?>,
    ): ByteArray {
        var lastError: EdgeHttpException? = null
        repeat(2) { attempt ->
            if (cancelled.get()) throw IOException("已取消")
            if (request.remainingTimeoutMillis() == 0L) throw IOException("在线语音服务响应超时")
            try {
                return collectAudio(descriptor, request, cancelled, socket)
            } catch (error: EdgeHttpException) {
                lastError = error
                if (error.code != 403 || attempt == 1) throw error
                if (!EdgeGec.adjustClockFromServerDate(error.serverDate)) throw error
                DiagnosticsLog.warn(Category.ENGINE, "Edge 握手 403，已按服务端时间校准时钟后重试")
                Log.i(TAG, "edge handshake 403, retrying after clock adjustment")
            }
        }
        throw lastError ?: IOException("在线语音合成失败")
    }

    private fun collectAudio(
        descriptor: VoiceDescriptor,
        request: SynthesisRequest,
        cancelled: AtomicBoolean,
        socketRef: AtomicReference<WebSocket?>,
    ): ByteArray {
        val done = CountDownLatch(1)
        val audio = ByteArrayOutputStream()
        val failure = AtomicReference<Throwable?>(null)
        val audioFrames = AtomicInteger(0)
        val ssml = buildSsml(descriptor, request)
        val config = speechConfigMessage()
        val requestMessage = ssmlMessage(ssml)
        val requestId = UUID.randomUUID().toString().replace("-", "")

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                request.timing.mark("connected")
                socketRef.set(webSocket)
                if (cancelled.get() || closed.get()) { webSocket.cancel(); done.countDown(); return }
                webSocket.send(config)
                webSocket.send(requestMessage)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    done.countDown()
                    webSocket.close(1000, "turn end")
                } else if (text.contains("Path:turn.start") || text.contains("Path:response")) {
                    // protocol keep-alives
                } else if (text.contains("Path:audio.metadata")) {
                    // Metadata is intentionally ignored; PCM is decoded from the audio frames.
                } else if (text.contains("\"error\"")) {
                    failure.compareAndSet(null, IOException(text.take(500)))
                    webSocket.close(1000, "error")
                    done.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                if (bytes.size.toLong() > app.noveltts.engine.api.AudioBudget.COMPRESSED + 65_537L) {
                    failure.compareAndSet(null, app.noveltts.engine.api.ResourceLimitException("在线音频帧超过资源上限"))
                    webSocket.cancel(); done.countDown(); return
                }
                val parsed = EdgeFrames.parse(bytes.toByteArray()) ?: return
                if (!parsed.header.contains("Path:audio")) return
                if (parsed.header.contains("Content-Type") && !parsed.header.contains("audio/mpeg")) return
                if (parsed.payload.isEmpty()) return
                request.timing.mark("first-compressed-frame")
                if (failure.get() != null || cancelled.get()) return
                try {
                    app.noveltts.engine.api.AudioBudget.check(audio.size().toLong(), parsed.payload.size.toLong(), app.noveltts.engine.api.AudioBudget.COMPRESSED, "压缩音频")
                    audio.write(parsed.payload)
                } catch (error: app.noveltts.engine.api.ResourceLimitException) {
                    failure.compareAndSet(null, error)
                    webSocket.cancel()
                    done.countDown()
                    return
                }
                audioFrames.incrementAndGet()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response != null && !response.isSuccessful) {
                    failure.compareAndSet(
                        null,
                        EdgeHttpException(response.code, response.header("Date"), t.message),
                    )
                } else {
                    failure.compareAndSet(null, t)
                }
                done.countDown()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (done.count > 0) failure.compareAndSet(null, IOException("在线服务在合成完成前关闭连接"))
                webSocket.close(code, null)
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (done.count > 0) {
                    failure.compareAndSet(null, IOException("在线服务提前关闭连接：$reason"))
                }
                done.countDown()
            }
        }

        val ws = client.newWebSocket(
            Request.Builder()
                .url(buildUrl(requestId, request.options?.edgeEndpoint))
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", ORIGIN)
                .header("User-Agent", EdgeGec.USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "MUID=${EdgeGec.muid()}")
                .build(),
            listener,
        )
        socketRef.set(ws)
        if (cancelled.get() || closed.get()) { ws.cancel(); done.countDown() }
        try {
            val timeoutMillis = request.remainingTimeoutMillis() ?: error("Missing Edge request deadline")
            if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                ws.cancel()
                throw IOException("在线语音服务响应超时")
            }
        } finally {
            socketRef.compareAndSet(ws, null)
        }
        if (cancelled.get()) throw IOException("已取消")
        failure.get()?.let { throw it }
        request.timing.mark("compressed-received")
        val bytes = audio.toByteArray()
        return bytes
    }

    private fun buildUrl(connectionId: String, endpoint: String? = null): String {
        val configured = (endpoint ?: settings.edgeEndpoint).trim().ifBlank { SettingsStore.DEFAULT_EDGE_ENDPOINT }
        require(java.net.URI(configured).let { it.scheme == "wss" && !it.host.isNullOrBlank() && it.userInfo == null }) { "Edge 端点必须使用有效的 WSS 地址" }
        val separator = if ('?' in configured) '&' else '?'
        val auth = if (
            configured.contains("Ocp-Apim-Subscription-Key", ignoreCase = true) ||
            configured.contains("TrustedClientToken", ignoreCase = true)
        ) {
            configured
        } else {
            "$configured${separator}TrustedClientToken=${EdgeGec.TRUSTED_CLIENT_TOKEN}"
        }
        val joiner = if ('?' in auth) '&' else '?'
        return "$auth${joiner}Sec-MS-GEC=${EdgeGec.token()}" +
            "&Sec-MS-GEC-Version=${EdgeGec.SEC_MS_GEC_VERSION}" +
            "&ConnectionId=$connectionId"
    }

    private fun speechConfigMessage(): String =
        "X-Timestamp:${edgeDate()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"

    private fun ssmlMessage(ssml: String): String =
        "X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${edgeDate()}Z\r\n" +
            "Path:ssml\r\n\r\n$ssml"

    private fun buildSsml(descriptor: VoiceDescriptor, request: SynthesisRequest): String {
        val ratePercent = ((request.rate.coerceIn(0.25f, 4f) - 1f) * 100).toInt()
        val pitchHz = ((request.pitch.coerceIn(0.25f, 2f) - 1f) * 50).toInt()
        val rate = signedPercent(ratePercent)
        val pitch = signed(pitchHz) + "Hz"
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
            "xml:lang='${escapeXml(descriptor.localeTag)}'>" +
            "<voice name='${escapeXml(descriptor.voiceCode)}'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='+0%'>" +
            escapeXml(request.text) +
            "</prosody></voice></speak>"
    }

    private fun signedPercent(value: Int): String = signed(value) + "%"

    private fun signed(value: Int): String = if (value >= 0) "+$value" else value.toString()

    private fun edgeDate(): String = DATE_FORMAT.format(Instant.now())

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }

    private class EdgeHttpException(
        val code: Int,
        val serverDate: String?,
        private val detailMessage: String?,
    ) : IOException(detailMessage) {
        fun userMessage(): String = when (code) {
            401, 403 -> "在线语音服务拒绝了请求（HTTP $code），请检查网络、系统时间或端点"
            429 -> "在线语音请求过于频繁（HTTP 429），请稍后重试"
            else -> "在线语音服务返回 HTTP $code${detailMessage?.let { "：$it" }.orEmpty()}"
        }
    }

    private companion object {
        const val TAG = "EdgeTtsEngine"

        /** Edge 朗读接口只接受来自该浏览器扩展的 Origin，缺了会 403。 */
        const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        const val CHUNK_BYTES = 8 * 1024
        const val RECEIVE_TIMEOUT_SECONDS = 60L
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US,
        ).withZone(ZoneOffset.UTC)
    }
}
