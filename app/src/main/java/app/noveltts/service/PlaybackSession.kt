package app.noveltts.service

import android.content.Context
import android.content.Intent
import java.util.UUID
import app.noveltts.engine.DiagnosticsLog
import app.noveltts.engine.Category

/** Each owner holds one idempotent lease; stop never targets an unrelated request. */
object PlaybackSession {
    const val ACTION_STOP_SYNTHESIS = "app.noveltts.action.STOP_SYNTHESIS"
    const val EXTRA_SESSION_ID = "sessionId"
    private data class Session(val network: Boolean, val stop: () -> Unit)
    private val sessions = linkedMapOf<String, Session>()
    private val legacy = java.util.ArrayDeque<AutoCloseable>()

    @Synchronized
    fun open(context: Context, requiresNetwork: Boolean, onStop: () -> Unit): AutoCloseable {
        val app = context.applicationContext
        val id = UUID.randomUUID().toString()
        sessions[id] = Session(requiresNetwork, onStop)
        update(app)
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) synchronized(this) {
            sessions.remove(id); update(app)
        } }
    }

    @Synchronized fun begin(context: Context, requiresNetwork: Boolean) {
        legacy.addLast(open(context, requiresNetwork) {})
    }
    @Synchronized fun end(context: Context) { legacy.pollFirst()?.close() }
    @Synchronized fun activeCount(): Int = sessions.size
    @Synchronized fun currentId(): String? = sessions.keys.lastOrNull()
    @Synchronized fun requiresNetwork(): Boolean = sessions.values.any { it.network }
    fun stop(id: String?) {
        val stop = synchronized(this) { sessions[id]?.stop }
        stop?.invoke()
    }

    private fun update(app: Context) {
        // Even a very short cached request must let the service promote itself before stopping.
        // Stopping from this caller can race the pending onCreate/onStartCommand delivery.
        runCatching { app.startForegroundService(Intent(app, PlaybackForegroundService::class.java)) }
            .onFailure { DiagnosticsLog.warn(Category.SPEECH, "无法启动后台朗读服务", it.message) }
    }
}
