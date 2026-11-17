package app.noveltts.engine

import app.noveltts.data.voicepack.VoiceDescriptor

interface SegmentPlaybackListener {
    fun onVoiceSelected(index: Int, voice: VoiceDescriptor, fallbackReason: String?) {}
    fun onPlaybackStart(index: Int)
    /** Output may wait for its audio device to drain before the next segment. */
    fun onPlaybackComplete(index: Int)
}
