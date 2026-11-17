package app.noveltts.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import app.noveltts.engine.Category
import app.noveltts.engine.DiagnosticsLog
import java.util.concurrent.atomic.AtomicBoolean

/** 「朗读时压低」的比例：开启后背景音乐按设定音量的 35% 播放。 */
internal const val BGM_DUCK_FACTOR = 0.35f

/**
 * 背景音乐目标音量（0f..1f）。
 *
 * 纯函数、不依赖 Android，方便单测；实际下发由 [BgmPlayer] 负责。
 */
internal fun bgmTargetVolume(volumePercent: Int, ducked: Boolean): Float {
    val base = volumePercent.coerceIn(0, 100) / 100f
    return if (ducked) base * BGM_DUCK_FACTOR else base
}

/**
 * 朗读期间的背景音乐播放器（[android.media.MediaPlayer] 实现）。
 *
 * 设计要点：
 * - 内部只有一条 [HandlerThread]，MediaPlayer 的创建、setDataSource、prepareAsync 与释放
 *   都在该线程执行，主线程不会因为读取 SAF 文件而卡顿。
 * - 换文件时整只重建 MediaPlayer；旧实例的监听器用「身份比较」丢弃迟到回调，
 *   避免用户换歌/停止后旧回调干扰新播放器。
 * - URI 读不到（文件被删、授权过期、解码器缺失）只写一条诊断日志，绝不向外抛异常。
 * - 不使用 setWakeMode：前台服务已经持有唤醒锁，这里再设会互相干扰。
 */
class BgmPlayer(private val context: Context) {
    private val appContext: Context = context.applicationContext
    private val thread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)
    private val released = AtomicBoolean(false)

    /** 当前 MediaPlayer；只在工作线程上读写。 */
    private var player: MediaPlayer? = null

    /** [player] 对应的 uri；uri 变化时要整只重建。 */
    private var playerUri: String? = null

    private var volumePercent = 0
    private var loop = false
    private var ducked = false

    /** 上一次真正下发给 MediaPlayer 的音量，渐变需要它做起点。 */
    private var currentVolume = 0f

    /** 每次开始新渐变就自增，让之前排队的渐变回调自动失效。 */
    private var rampToken = 0

    /**
     * 播放 [uri]：与当前播放器是同一个文件时只更新参数（音量、循环、压低），
     * 不同文件则释放后重建。[uri] 为空或不可读时静默停止并写一条警告。
     */
    fun play(uri: String?, volumePercent: Int, loop: Boolean, ducked: Boolean) = runOnWorker {
        applyPlay(uri, volumePercent, loop, ducked)
    }

    /** 朗读开始/结束时调用：开启时把音量线性降到 35%，关闭时恢复（200ms 渐变）。 */
    fun duck(on: Boolean) = runOnWorker {
        if (ducked == on) return@runOnWorker
        ducked = on
        applyVolume(immediate = false)
    }

    /** 停止并释放 MediaPlayer，保留工作线程以便下次 [play]。可重复调用。 */
    fun stop() = runOnWorker {
        ducked = false
        releasePlayer()
    }

    /** 彻底释放：停掉 MediaPlayer 并结束工作线程，之后所有调用都是空操作。 */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        val posted = handler.post {
            ducked = false
            releasePlayer()
            thread.quitSafely()
        }
        if (!posted) thread.quitSafely()
    }

    private fun runOnWorker(block: () -> Unit) {
        if (released.get()) return
        handler.post {
            if (released.get()) return@post
            // 工作线程上的异常会让 RenderThread/Looper 直接退出，这里必须自己兜住。
            runCatching { block() }.onFailure { error ->
                DiagnosticsLog.warn(Category.SPEECH, "背景音乐播放器异常", error.javaClass.simpleName)
                runCatching { releasePlayer() }
            }
        }
    }

    private fun applyPlay(rawUri: String?, volumePercent: Int, loop: Boolean, ducked: Boolean) {
        val uri = rawUri?.trim().orEmpty()
        this.volumePercent = volumePercent.coerceIn(0, 100)
        this.loop = loop
        this.ducked = ducked
        if (uri.isEmpty()) {
            releasePlayer()
            return
        }
        val current = player
        if (current != null && playerUri == uri) {
            // 同一个文件：只更新参数，避免每次拖动音量滑块都重新解码。
            runCatching { current.isLooping = loop }
            applyVolume(immediate = true)
            val playing = runCatching { current.isPlaying }.getOrDefault(false)
            if (!playing) {
                // 还没 prepare 完成时 start() 会抛 IllegalStateException，交给 OnPrepared 启动。
                runCatching { current.start() }
            }
            return
        }
        releasePlayer()
        prepare(uri)
    }

    private fun prepare(uri: String) {
        val created = MediaPlayer()
        player = created
        playerUri = uri
        runCatching {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
        }
        runCatching { created.isLooping = loop }
        created.setOnPreparedListener { prepared ->
            // 用户已换歌/停止时 player 会被换掉或置空，迟到的回调直接丢弃。
            if (player !== prepared) return@setOnPreparedListener
            runCatching { prepared.isLooping = loop }
            applyVolume(immediate = true)
            runCatching { prepared.start() }
        }
        created.setOnCompletionListener { completed ->
            if (player !== completed) return@setOnCompletionListener
            // loop=true 时不会回调这里；只播一次时停在末尾，下一次 play() 会从头重播。
            runCatching { completed.seekTo(0) }
        }
        created.setOnErrorListener { failed, what, extra ->
            // 返回 true 表示错误已消费，避免系统继续走 OnCompletion。
            if (player === failed) {
                DiagnosticsLog.warn(Category.SPEECH, "背景音乐播放失败（what=" + what + " extra=" + extra + "）")
                releasePlayer()
            }
            true
        }
        try {
            created.setDataSource(appContext, Uri.parse(uri))
            created.prepareAsync()
        } catch (error: Exception) {
            // 用户删除文件、SAF 授权过期都会走到这里：静默降级，不影响朗读。
            DiagnosticsLog.warn(
                Category.SPEECH,
                "背景音乐无法读取，已跳过本次播放",
                error.javaClass.simpleName,
            )
            releasePlayer()
        }
    }

    private fun applyVolume(immediate: Boolean) {
        val active = player ?: return
        val target = bgmTargetVolume(volumePercent, ducked)
        rampToken++
        if (immediate) {
            setVolume(active, target)
            return
        }
        val token = rampToken
        val from = currentVolume
        for (step in 1..RAMP_STEPS) {
            handler.postDelayed(rampStep(token, from, target, step), RAMP_STEP_MS * step)
        }
    }

    private fun rampStep(token: Int, from: Float, target: Float, step: Int): Runnable = Runnable {
        if (token != rampToken) return@Runnable
        val active = player ?: return@Runnable
        val ratio = step / RAMP_STEPS.toFloat()
        setVolume(active, from + (target - from) * ratio)
    }

    private fun setVolume(target: MediaPlayer, value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        currentVolume = clamped
        runCatching { target.setVolume(clamped, clamped) }
    }

    private fun releasePlayer() {
        val current = player
        player = null
        playerUri = null
        currentVolume = 0f
        rampToken++
        if (current == null) return
        // 未 prepare 完成的实例调 stop() 会抛 IllegalStateException，先判断 isPlaying。
        val playing = runCatching { current.isPlaying }.getOrDefault(false)
        if (playing) runCatching { current.stop() }
        runCatching { current.reset() }
        runCatching { current.release() }
    }

    private companion object {
        const val THREAD_NAME = "noveltts-bgm"

        /** 渐变 10 步 × 20ms = 200ms，落在要求的 150~250ms 区间内。 */
        const val RAMP_STEPS = 10
        const val RAMP_STEP_MS = 20L
    }
}
