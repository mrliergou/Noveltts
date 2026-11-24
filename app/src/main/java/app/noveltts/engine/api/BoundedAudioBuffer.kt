package app.noveltts.engine.api

/** Used by native push callbacks before allocating or copying another accumulated PCM block. */
class BoundedAudioBuffer(private val maximum: Long = AudioBudget.NORMALIZED_PCM) {
    private val bytes = java.io.ByteArrayOutputStream()
    @Synchronized fun write(data: ByteArray): Int {
        AudioBudget.check(bytes.size().toLong(), data.size.toLong(), maximum, "原生输出 PCM")
        bytes.write(data)
        return data.size
    }
    @Synchronized fun toByteArray(): ByteArray = bytes.toByteArray()
}