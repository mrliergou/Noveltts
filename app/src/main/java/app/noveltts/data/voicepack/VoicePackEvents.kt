package app.noveltts.data.voicepack

import android.content.Context
import android.content.Intent

object VoicePackEvents {
    private val generation = java.util.concurrent.atomic.AtomicLong()
    val revision: Long get() = generation.get()
    internal fun invalidate() { generation.incrementAndGet() }
    const val INTERNAL_PERMISSION = "app.noveltts.permission.INTERNAL_EVENT"
    const val ACTION_CHANGED = "app.noveltts.VOICE_PACKS_CHANGED"

    fun notifyChanged(context: Context) {
        invalidate()
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName), INTERNAL_PERMISSION)
    }
}
