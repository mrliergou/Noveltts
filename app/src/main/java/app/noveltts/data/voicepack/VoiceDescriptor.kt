package app.noveltts.data.voicepack

import java.io.File

/** 一个可以被本应用引擎真实合成的音色。 */
data class VoiceDescriptor(
    val systemName: String,
    val packId: String,
    val packName: String,
    val engineCode: String,
    val voiceCode: String,
    val param: String,
    val displayName: String,
    val localeTag: String,
    val gender: Int,
    val isOnline: Boolean,
    val sampleRate: Int,
    val defaultSpeed: Float,
    val defaultPitch: Float,
    val defaultVolume: Float,
    val description: String,
    val licenseRequired: Boolean,
    val usable: Boolean,
    val modelDirectory: File?,
    val unusableReason: String? = null,
) {
    val isOffline: Boolean get() = !isOnline
}
