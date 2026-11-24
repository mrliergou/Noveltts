package app.noveltts.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.noveltts.MainActivity
import app.noveltts.data.settings.SettingsStore
import app.noveltts.engine.DiagnosticsLog
import app.noveltts.forward.ForwardController
import app.noveltts.forward.ForwardRuntimeState
import app.noveltts.forward.ForwardServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ForwardForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandsLock = Mutex()
    private lateinit var server: ForwardServer
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "HTTP 语音转发", NotificationManager.IMPORTANCE_LOW))
        promote()
        app.noveltts.data.backup.ConfigurationRecovery.recover(this)
        DiagnosticsLog.attachStore(filesDir)
        DiagnosticsLog.restoreRecent()
        server = ForwardServer(this)
        scope.launch {
            while (true) {
                delay(1000)
                val current = ForwardController.state.value
                if (current.running) {
                    ForwardController.publish(current.copy(running = server.isRunning,
                        error = server.lastError, activeRequests = server.activeRequests, queuedRequests = server.queuedRequests))
                    if (!server.isRunning) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        val commandId = intent?.getStringExtra(EXTRA_COMMAND)
        val command = ForwardController.take(commandId)
            .also { if (commandId != null && it == null) {
                if (!server.isRunning) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
                return START_NOT_STICKY
            } }
            ?: ForwardController.Command(ForwardController.configured(this).let {
                if (intent?.action == ACTION_STOP) it.copy(enabled = false) else it
            }, persist = intent?.action == ACTION_STOP)
        scope.launch {
            commandsLock.withLock {
                command.lock.withLock commandLock@ {
                if (command.cancelled) {
                    if (!server.isRunning) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
                    return@commandLock
                }
                val previous = server.runningConfig
                val result = runCatching {
                    val config = command.config
                    config.validationError()?.let { error(it) }
                    if (config.enabled) check(server.start(config)) { server.lastError ?: "服务未开始监听" }
                    else server.stop()
                    if (command.persist) SettingsStore(this@ForwardForegroundService).commitForward(config)
                    ForwardRuntimeState(config.enabled, server.isRunning, false, host = config.host, port = config.port)
                }
                if (result.isFailure) {
                    val message = result.exceptionOrNull()?.message ?: "配置应用失败"
                    if (previous != null) server.start(previous) else server.stop()
                    ForwardController.publish(ForwardRuntimeState(command.config.enabled, server.isRunning,
                        error = message, host = previous?.host.orEmpty(), port = previous?.port ?: 0))
                } else ForwardController.publish(result.getOrThrow())
                command.result.complete(result)
                if (!server.isRunning) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                } else promote()
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        if (::server.isInitialized) server.shutdown()
        ForwardController.publish(ForwardController.state.value.copy(running = false, applying = false,
            activeRequests = 0, queuedRequests = 0))
        super.onDestroy()
    }
    private fun promote() {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java)
            .putExtra("open", "external"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getForegroundService(this, 3, Intent(this, ForwardForegroundService::class.java)
            .setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val runtime = ForwardController.state.value
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("NovelTTS HTTP 转发")
            .setContentText(if (runtime.running) "监听 ${runtime.host}:${runtime.port}" else "正在应用监听配置")
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "停止转发", stop).build())
            .setOngoing(true).setShowWhen(false).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }
    companion object {
        const val EXTRA_COMMAND = "command"
        private const val ACTION_STOP = "app.noveltts.action.STOP_FORWARD"
        private const val CHANNEL = "forward"
        private const val NOTIFICATION_ID = 1002
    }
}
