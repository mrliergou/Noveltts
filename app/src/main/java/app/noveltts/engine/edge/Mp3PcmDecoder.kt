package app.noveltts.engine.edge

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 使用平台 MediaCodec 把 Edge 返回的 MP3 解成 24 kHz 单声道 PCM16。 */
internal object Mp3PcmDecoder {
    private const val TARGET_SAMPLE_RATE = 24_000
    private const val TIMEOUT_US = 10_000L

    fun decodeToPcm16(file: File, gain: Float): ByteArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw IOException("MP3 中没有音频轨道")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("MP3 轨道缺少 MIME")
            var sampleRate = inputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
            var channels = inputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var encoding = inputFormat.intOrDefault(
                MediaFormat.KEY_PCM_ENCODING,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
            )

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val raw = ByteArrayOutputStream()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IOException("MediaCodec 未提供输入缓冲区")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        sampleRate = output.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = output.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        encoding = output.intOrDefault(MediaFormat.KEY_PCM_ENCODING, encoding)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val chunk = ByteArray(bufferInfo.size)
                                outputBuffer.get(chunk)
                                raw.write(chunk)
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            if (raw.size() == 0) throw IOException("MP3 解码后没有音频")
            return normalize(raw.toByteArray(), sampleRate, channels, encoding, gain)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun normalize(
        data: ByteArray,
        sourceRate: Int,
        channels: Int,
        encoding: Int,
        gain: Float,
    ): ByteArray {
        val bytesPerSample = when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
            android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
            else -> throw IOException("不支持的解码输出格式：$encoding")
        }
        val frameBytes = channels.coerceAtLeast(1) * bytesPerSample
        val sourceFrames = data.size / frameBytes
        if (sourceFrames <= 0) throw IOException("MP3 解码结果为空")
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(sourceFrames)
        for (frame in 0 until sourceFrames) {
            var mixed = 0f
            for (channel in 0 until channels.coerceAtLeast(1)) {
                val offset = frame * frameBytes + channel * bytesPerSample
                mixed += when (bytesPerSample) {
                    2 -> buffer.getShort(offset) / 32768f
                    else -> buffer.getFloat(offset)
                }
            }
            samples[frame] = mixed / channels.coerceAtLeast(1)
        }

        val outputFrames = if (sourceRate == TARGET_SAMPLE_RATE) {
            sourceFrames
        } else {
            (sourceFrames.toLong() * TARGET_SAMPLE_RATE / sourceRate).toInt()
        }
        val output = ByteArray(outputFrames * 2)
        val outputBuffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        val safeGain = gain.coerceIn(0f, 1f)
        for (frame in 0 until outputFrames) {
            val sample = if (sourceRate == TARGET_SAMPLE_RATE) {
                samples[frame]
            } else {
                val position = frame.toDouble() * sourceRate / TARGET_SAMPLE_RATE
                val left = position.toInt().coerceIn(0, sourceFrames - 1)
                val right = (left + 1).coerceAtMost(sourceFrames - 1)
                val fraction = (position - left).toFloat()
                samples[left] * (1f - fraction) + samples[right] * fraction
            }
            val value = (sample * safeGain).coerceIn(-1f, 1f)
            outputBuffer.putShort((value * 32767f).toInt().toShort())
        }
        return output
    }

    private fun MediaFormat.intOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}
