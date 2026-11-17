package app.noveltts.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import app.noveltts.MainActivity

/**
 * 朗读期间的前台服务：保活 + 唤醒锁 + （联网任务时）WiFi 锁。
 *
 * 与系统绑定的 [MultiTtsService] 分开，避免 TTS 服务本身被系统置为前台而影响
 * 其他调用方的绑定语义。生命周期由 [PlaybackSession] 的引用计数驱动。
 */
class PlaybackForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sessionId: String? = null
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PlaybackSession.ACTION_STOP_SYNTHESIS)
                PlaybackSession.stop(intent.getStringExtra(PlaybackSession.EXTRA_SESSION_ID))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopReceiver,
            IntentFilter(PlaybackSession.ACTION_STOP_SYNTHESIS), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(stopReceiver,
            IntentFilter(PlaybackSession.ACTION_STOP_SYNTHESIS))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知必须在 5 秒内发出，否则系统会抛 ForegroundServiceDidNotStartInTimeException。
        sessionId = PlaybackSession.currentId()
        startAsForeground()
        if (PlaybackSession.activeCount() == 0) {
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (PlaybackSession.requiresNetwork()) acquireWifiLock()
        else { runCatching { wifiLock?.takeIf { it.isHeld }?.release() }; wifiLock = null }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(stopReceiver) }
        releaseLocks()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            this,
            1,
            Intent(PlaybackSession.ACTION_STOP_SYNTHESIS).setPackage(packageName)
                .putExtra(PlaybackSession.EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("正在朗读")
            .setContentText("点击返回工作台，或在此停止朗读")
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "停止朗读", stop).build())
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "朗读状态", NotificationManager.IMPORTANCE_LOW).apply {
            description = "朗读期间显示的前台状态，可在这里停止朗读。"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        // 锁带超时兜底，避免异常路径下永久持锁；长时间朗读由后续任务续期。
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = wakeLock ?: power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val lock = wifiLock ?: wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG).also {
            it.setReferenceCounted(false)
            wifiLock = it
        }
        runCatching { lock.acquire() }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    companion object {
        const val EXTRA_NETWORK = "network"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "NovelTTS:playback"
        private const val WIFI_LOCK_TAG = "NovelTTS:playback-wifi"
        private const val WAKE_LOCK_TIMEOUT_MS = 20 * 60 * 1000L
    }
}
