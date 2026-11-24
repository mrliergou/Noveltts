package app.noveltts.data.voicepack

import app.noveltts.data.config.CONFIGURATION_LOCK

/** Short-lived availability snapshot. Mutations invalidate immediately; expiry catches file changes. */
internal class VoiceCatalog(
    private val load: () -> List<VoiceDescriptor>,
    private val revision: () -> Long = { VoicePackEvents.revision },
    private val now: () -> Long = System::nanoTime,
) {
    private data class Snapshot(val revision: Long, val expires: Long, val voices: List<VoiceDescriptor>)
    @Volatile private var cached: Snapshot? = null

    fun voices(): List<VoiceDescriptor> {
        fun current(): Snapshot? = cached?.takeIf { it.revision == revision() && now() < it.expires }
        current()?.let { return it.voices }
        return synchronized(CONFIGURATION_LOCK) {
            current()?.voices ?: run {
                val version = revision()
                val voices = load().toList()
                cached = Snapshot(version, now() + 5_000_000_000L, voices)
                voices
            }
        }
    }
}