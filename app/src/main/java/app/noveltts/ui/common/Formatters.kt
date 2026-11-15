package app.noveltts.ui.common

import app.noveltts.data.voicepack.VoiceDescriptor
import java.util.Locale

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun voiceStatusLabel(voice: VoiceDescriptor): String = when {
    voice.usable && voice.isOnline -> "在线可用"
    voice.usable -> "离线可用"
    voice.licenseRequired -> "缺许可证"
    else -> "不可用"
}

fun voiceSubtitle(voice: VoiceDescriptor): String = buildString {
    append(voice.packName)
    append(" · ").append(voice.engineCode)
    if (voice.localeTag.isNotBlank()) append(" · ").append(voice.localeTag)
    append(if (voice.isOnline) " · 在线" else " · 离线")
}
