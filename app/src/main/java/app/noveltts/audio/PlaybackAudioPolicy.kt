package app.noveltts.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Audio policy belongs to application playback, never to system TTS or HTTP synthesis. */
internal class PlaybackAudioPolicy(context: Context, private val pause: () -> Unit,
    private val resume: () -> Unit, stop: () -> Unit, previous: () -> Unit, next: () -> Unit,
    private val canResumeAfterFocus: () -> Boolean) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = app.getSystemService(AudioManager::class.java)
    private var registered = false
    private var retainForFocus = false
    private var resumeOnFocus = false
    private var hasFocus = false
    private val session = MediaSession(app, "NovelTTS").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { resumeOnFocus = false; resume() }
            override fun onPause() { resumeOnFocus = false; pause() }
            override fun onStop() { resumeOnFocus = false; stop() }
            override fun onSkipToPrevious() { previous() }
            override fun onSkipToNext() { next() }
        }, handler)
    }
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) {
                    resumeOnFocus = false
                    resume()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (hasFocus) {
                        resumeOnFocus = canResumeAfterFocus()
                        retainForFocus = resumeOnFocus
                        try { pause() } finally { retainForFocus = false }
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; pause() }
            }
        }, handler).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                resumeOnFocus = false
                pause()
            }
        }
    }

    fun start(): Boolean {
        resumeOnFocus = false
        hasFocus = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) return false
        if (!registered) {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            registered = true
        }
        state(PlaybackState.STATE_PLAYING)
        return true
    }

    fun stop() {
        if (registered) { runCatching { app.unregisterReceiver(noisy) }; registered = false }
        if (!retainForFocus) {
            resumeOnFocus = false
            if (hasFocus) manager.abandonAudioFocusRequest(focus)
            hasFocus = false
        }
        state(PlaybackState.STATE_STOPPED)
    }

    fun paused() { state(PlaybackState.STATE_PAUSED) }
    private fun state(value: Int) {
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SKIP_TO_NEXT).setState(value, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (value == PlaybackState.STATE_PLAYING) 1f else 0f).build())
        session.isActive = value != PlaybackState.STATE_STOPPED
    }
    fun release() { retainForFocus = false; stop(); session.release() }
}