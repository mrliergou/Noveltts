package app.noveltts.forward

import app.noveltts.data.voicepack.VoiceCatalog

import android.content.Context
import app.noveltts.data.cache.AudioCache
import app.noveltts.data.config.RulesRepository
import app.noveltts.data.settings.SettingsStore
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.data.voicepack.VoicePackStore
import app.noveltts.data.voicepack.VoiceRegistry
import app.noveltts.domain.SynthesisPlanBuilder
import app.noveltts.engine.Category
import app.noveltts.engine.DiagnosticsLog
import app.noveltts.engine.EngineManager
import app.noveltts.engine.MultiSegmentSynthesizer
import app.noveltts.engine.RetryPolicy
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Validated as one unit before a service applies and commits it. */
data class ForwardConfig(
    val enabled: Boolean = false,
    val bindAddress: String = SettingsStore.FORWARD_BIND_LOOPBACK,
    val port: Int = SettingsStore.DEFAULT_FORWARD_PORT,
    val token: String = "",
) {
    val host: String get() = if (bindAddress == SettingsStore.FORWARD_BIND_ALL)
        SettingsStore.FORWARD_BIND_ALL else SettingsStore.FORWARD_BIND_LOOPBACK
    val blockedByMissingToken: Boolean get() = enabled && host == SettingsStore.FORWARD_BIND_ALL && token.isBlank()
    fun validationError(): String? = when {
        bindAddress !in setOf(SettingsStore.FORWARD_BIND_LOOPBACK, SettingsStore.FORWARD_BIND_ALL) -> "监听地址无效"
        port !in SettingsStore.FORWARD_PORT_RANGE -> "端口需在 1024–65535 之间"
        token.length > 512 || token.any { it == '\r' || it == '\n' } -> "访问令牌格式无效"
        blockedByMissingToken -> "监听所有网卡时需要访问令牌"
        else -> null
    }
}

/** The foreground service owns this listener; activity destruction does not stop it. */
class ForwardServer(context: Context) {
    private val app = context.applicationContext
    private val settings = SettingsStore(app)
    private val lock = Any()
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var queue: ForwardRequestQueue? = null
    private var requestDeadlines: ScheduledThreadPoolExecutor? = null
    private val clients = ConcurrentHashMap<Socket, ForwardRequestContext>()
    private var engineBackend: EngineForwardBackend? = null
    @Volatile var runningConfig: ForwardConfig? = null
        private set
    @Volatile var lastError: String? = null
        private set
    val isRunning: Boolean get() = socket?.let { it.isBound && !it.isClosed } == true && acceptThread?.isAlive == true
    val activeRequests: Int get() = queue?.activeCount ?: 0
    val queuedRequests: Int get() = queue?.pendingCount ?: 0

    fun start(): Boolean = start(ForwardConfig(settings.forwardEnabled, settings.forwardBind,
        settings.forwardPort, settings.forwardToken))
    fun restart(config: ForwardConfig): Boolean = start(config)
    fun start(config: ForwardConfig): Boolean = synchronized(lock) {
        lastError = config.validationError()
        if (lastError != null) return@synchronized false
        if (!config.enabled) { stopLocked(); return@synchronized false }
        if (isRunning && runningConfig?.host == config.host && runningConfig?.port == config.port) {
            runningConfig = config
            return@synchronized true
        }
        stopLocked()
        var prepared: ServerSocket? = null
        try {
            prepared = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(config.host, config.port)) }
            val pool = ForwardRequestQueue()
            val deadlines = ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "noveltts-http-deadlines").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
            requestDeadlines = deadlines
            val bound = prepared
            val backend = engineBackend ?: EngineForwardBackend(app, settings).also { engineBackend = it }
            val handler = TtsHttpHandler(backend, { runningConfig ?: config })
            socket = bound
            queue = pool
            runningConfig = config
            acceptThread = Thread({ acceptLoop(bound, pool, handler, deadlines) }, "noveltts-http-accept").apply { isDaemon = true; start() }
            DiagnosticsLog.info(Category.ENGINE, "HTTP 转发已启动：${config.host}:${config.port}")
            true
        } catch (error: Exception) {
            prepared?.close()
            lastError = if (error is java.net.BindException) "端口 ${config.port} 已被占用" else "监听启动失败：${error.javaClass.simpleName}"
            stopLocked()
            DiagnosticsLog.error(Category.ENGINE, lastError.orEmpty())
            false
        }
    }
    fun stop() = synchronized(lock) { stopLocked() }
    fun shutdown() {
        stop()
        val backend = synchronized(lock) { engineBackend.also { engineBackend = null } }
        backend?.shutdown()
    }
    private fun stopLocked() {
        socket?.close()
        socket = null
        acceptThread = null
        runningConfig = null
        clients.forEach { (client, context) ->
            context.cancel("HTTP 转发已停止", 503, "service_stopped")
            runCatching { client.close() }
        }
        clients.clear()
        queue?.shutdown()
        queue = null
        requestDeadlines?.shutdownNow()
        requestDeadlines = null
    }
    private fun acceptLoop(bound: ServerSocket, pool: ForwardRequestQueue, handler: TtsHttpHandler,
        deadlines: ScheduledThreadPoolExecutor) {
        var failures = 0
        while (!bound.isClosed) {
            val client = try { bound.accept().also { failures = 0 } } catch (error: IOException) {
                if (bound.isClosed) return
                lastError = "HTTP 接收连接失败：${error.javaClass.simpleName}"
                DiagnosticsLog.warn(Category.ENGINE, lastError.orEmpty())
                if (++failures >= 3) { runCatching { bound.close() }; return }
                try { Thread.sleep(100L * failures) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
                continue
            }
            val timeoutMs = settings.requestTimeoutSec.coerceIn(10, 180) * 1000L
            val context = ForwardRequestContext(timeoutMs)
            clients[client] = context
            // A socket close also interrupts a blocked read/write; SO_TIMEOUT only limits idle reads.
            val deadline = try { deadlines.schedule({
                context.cancel("请求读取、等待或合成超时", 504, "request_timeout")
                runCatching { client.close() }
            }, timeoutMs, TimeUnit.MILLISECONDS) } catch (_: RejectedExecutionException) {
                clients.remove(client)
                context.finish()
                client.close()
                return
            }
            if (!pool.execute { try { serve(client, handler, context) } finally { deadline.cancel(false) } }) {
                runCatching { client.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nRetry-After: 1\r\nConnection: close\r\n\r\n".toByteArray()) }
                clients.remove(client)
                context.finish()
                client.close()
                deadline.cancel(false)
            }
        }
    }
    private fun serve(client: Socket, handler: TtsHttpHandler, context: ForwardRequestContext) {
        val start = System.nanoTime()
        try {
            client.soTimeout = 15_000
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            context.onSynthesisStart = {
                client.soTimeout = 250
                Thread({
                    watchForwardConnection(input, context) { client.isClosed }
                }, "noveltts-http-disconnect").apply { isDaemon = true; start() }
            }
            handler.handle(input, BufferedOutputStream(client.getOutputStream()), context)
        } catch (error: Exception) {
            if (error !is SocketTimeoutException) DiagnosticsLog.warn(Category.ENGINE,
                "HTTP 请求 ${context.id} 连接结束", error.javaClass.simpleName)
        } finally {
            context.finish()
            clients.remove(client)
            runCatching { client.close() }
            DiagnosticsLog.info(Category.ENGINE, "HTTP 请求 ${context.id} 已结束", "耗时 ${(System.nanoTime() - start) / 1_000_000} ms")
        }
    }
}

/** Same rules, segmentation, retries and cache as system/app synthesis, without local playback. */
internal class EngineForwardBackend(context: Context, private val settings: SettingsStore) : ForwardSynthesisBackend {
    private val app = context.applicationContext
    private val registry = VoiceRegistry(app, VoicePackStore(app))
    private val rules = RulesRepository(app)
    private val engines = EngineManager(app, settings)
    private val synth = MultiSegmentSynthesizer(engines, File(app.cacheDir, "spool"),
        cacheDir = File(app.cacheDir, AudioCache.DIR_NAME))
    private val catalog = VoiceCatalog(load = {
        registry.voices(settings.hasLicense(), settings.localTtsEnginePackage)
    })
    override fun voices(): List<VoiceDescriptor> = catalog.voices()
    override fun synthesize(voiceId: String?, text: String, rate: Float, pitch: Float, volume: Float): ForwardAudio =
        synthesize(voiceId, text, rate, pitch, volume, ForwardRequestContext())
    override fun synthesize(voiceId: String?, text: String, rate: Float, pitch: Float, volume: Float,
        context: ForwardRequestContext): ForwardAudio {
        context.ensureActive()
        val prepareStarted = System.nanoTime()
        val (snapshot, config) = rules.withConfigurationLock {
            val config = settings.synthesisSnapshot()
            val available = voices()
            val requested = voiceId?.takeIf { it.isNotBlank() }
            if (requested != null && available.none { it.systemName == requested && it.usable })
                throw ForwardHttpException(400, "unknown_voice", "请求音色不存在或不可用")
            val snapshot = try { SynthesisPlanBuilder.Snapshot(text,
                config.builtInRoles.withVoices(requested ?: config.narratorVoice, config.dialogueVoice), rules.loadRoles(), rules.loadReplacements(), config.punctuationFilter,
                available, config.maxSegmentChars, config.unattributedPolicy) }
            catch (_: Exception) { throw ForwardHttpException(503, "rules_unavailable", "规则无法读取，请在应用内恢复配置") }
            snapshot to config
        }
        val plan = try { snapshot.build() }
        catch (error: app.noveltts.domain.PipelineLimitException) { throw ForwardHttpException(400, "rules_limit", error.message ?: "规则处理失败") }
        context.ensureActive()
        if (plan.segments.isEmpty()) throw ForwardHttpException(400, "no_audio", "没有可朗读片段或可用音色")
        val collector = ForwardPcmCollector()
        DiagnosticsLog.info(Category.SPEECH, "HTTP 请求 ${context.id} 开始合成",
            "${plan.segments.size} 段 · ${DiagnosticsLog.textHint(text)} · prepare=${(System.nanoTime() - prepareStarted) / 1_000_000}ms · rate=$rate pitch=$pitch volume=$volume")
        val handle = synchronized(synth) {
            synth.configure(RetryPolicy(config.maxRetryCount + 1), config.silenceBetweenSegmentsMs, config.options.timeoutSec)
            synth.configureCache(config.cacheEnabled, config.cacheMaxMb)
            synth.synthesize(plan.segments, rate, pitch, volume, collector, options = config.options, requestId = context.id)
        }
        context.attach(handle)
        return try { collector.await(context) }
        catch (error: Exception) { handle.cancel(); throw error }
    }
    fun shutdown() { synth.shutdown() }
}
