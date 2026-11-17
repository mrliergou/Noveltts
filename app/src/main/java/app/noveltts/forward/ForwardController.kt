package app.noveltts.forward

import android.content.Context
import android.content.Intent
import app.noveltts.data.settings.SettingsStore
import app.noveltts.service.ForwardForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ForwardRuntimeState(val desiredEnabled: Boolean = false, val running: Boolean = false,
    val applying: Boolean = false, val error: String? = null, val host: String = "", val port: Int = 0,
    val activeRequests: Int = 0, val queuedRequests: Int = 0)

/** The UI observes service state and submits one complete draft; it never owns the listener. */
object ForwardController {
    internal data class Command(val config: ForwardConfig, val persist: Boolean,
        val result: CompletableDeferred<Result<ForwardRuntimeState>> = CompletableDeferred()) {
        val lock = Mutex()
        var cancelled = false
    }
    private val commands = ConcurrentHashMap<String, Command>()
    private val mutableState = MutableStateFlow(ForwardRuntimeState())
    val state = mutableState.asStateFlow()
    fun publish(value: ForwardRuntimeState) { mutableState.value = value }
    internal fun take(id: String?): Command? = id?.let { commands.remove(it) }
    fun configured(context: Context): ForwardConfig = SettingsStore(context).let { store -> store.withConfigurationLock {
        ForwardConfig(store.forwardEnabled, store.forwardBind, store.forwardPort, store.forwardToken)
    }
    }

    suspend fun apply(context: Context, config: ForwardConfig): Result<ForwardRuntimeState> {
        config.validationError()?.let { return Result.failure(IllegalArgumentException(it)) }
        val (id, command) = enqueue(context, config, true)
        return try { withTimeout(15_000) { command.result.await() } }
        catch (error: Exception) { withContext(NonCancellable) { command.lock.withLock {
            if (command.result.isCompleted) command.result.await() else {
                command.cancelled = true
                commands.remove(id)
                publish(state.value.copy(applying = false, error = "监听配置未应用：${error.javaClass.simpleName}"))
                Result.failure(error)
            }
        } } }
    }
    fun startConfigured(context: Context) {
        val config = configured(context)
        if (config.enabled && !state.value.running && !state.value.applying) enqueue(context, config, false)
    }
    private fun enqueue(context: Context, config: ForwardConfig, persist: Boolean): Pair<String, Command> {
        val command = Command(config, persist)
        val id = UUID.randomUUID().toString()
        commands[id] = command
        publish(state.value.copy(desiredEnabled = config.enabled, applying = true, error = null))
        try { context.applicationContext.startForegroundService(Intent(context, ForwardForegroundService::class.java)
            .putExtra(ForwardForegroundService.EXTRA_COMMAND, id)) }
        catch (error: Exception) {
            commands.remove(id)
            publish(state.value.copy(applying = false, error = "后台转发服务启动失败：${error.javaClass.simpleName}"))
            command.result.complete(Result.failure(error))
        }
        return id to command
    }
}
