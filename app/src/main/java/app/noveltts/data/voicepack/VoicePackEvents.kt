package app.noveltts.data.voicepack

import android.content.Context
import android.content.Intent

object VoicePackEvents {
    const val ACTION_CHANGED = "app.noveltts.VOICE_PACKS_CHANGED"

    fun notifyChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    }
}
