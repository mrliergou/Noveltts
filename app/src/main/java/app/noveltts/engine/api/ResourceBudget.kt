package app.noveltts.engine.api

class ResourceLimitException(message: String) : java.io.IOException(message)

object AudioBudget {
    const val COMPRESSED = 16L * 1024 * 1024
    const val RAW_PCM = 64L * 1024 * 1024
    const val NORMALIZED_PCM = 16L * 1024 * 1024
    const val SPOOL = 16L * 1024 * 1024
    fun check(current: Long, incoming: Long, maximum: Long, stage: String) {
        if (current < 0 || incoming < 0 || current > maximum || incoming > maximum - current)
            throw ResourceLimitException("$stage 超出音频资源上限（${maximum / 1024 / 1024} MiB）")
    }
    fun normalizedBytes(frames: Long, sourceRate: Int): Int {
        require(sourceRate in 1..384_000 && frames >= 0) { "Invalid PCM sample rate or frame count" }
        val outputFrames = Math.multiplyExact(frames, 24_000L) / sourceRate
        val bytes = Math.multiplyExact(outputFrames, 2L)
        check(0, bytes, NORMALIZED_PCM, "标准化 PCM")
        return bytes.toInt()
    }
}

object SynthesisParameters {
    const val MIN_RATE = 0.25f
    const val MAX_RATE = 4f
    const val MIN_PITCH = 0.25f
    const val MAX_PITCH = 2f
    fun globalRate(value: Int): Float = if (value <= 50) (0.5f + value / 100f).coerceIn(0.5f, 1f) else (1f + (value - 50) / 50f).coerceIn(1f, 2f)
    fun globalPitch(value: Int): Float = (0.5f + value / 50f).coerceIn(0.5f, 2f)
    fun globalVolume(value: Int): Float = (value / 50f).coerceIn(0f, 1f)
    fun rate(value: Float) = value.takeIf { it.isFinite() }?.coerceIn(MIN_RATE, MAX_RATE) ?: 1f
    fun pitch(value: Float) = value.takeIf { it.isFinite() }?.coerceIn(MIN_PITCH, MAX_PITCH) ?: 1f
    fun volume(value: Float) = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
}