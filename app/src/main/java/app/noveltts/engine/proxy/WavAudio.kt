package app.noveltts.engine.proxy

import android.util.Log
import java.io.File

/** 一段已解码、已混为单声道的 16-bit PCM。 */
internal class PcmAudio(val sampleRateHz: Int, val pcm: ByteArray)

/**
 * 最小 WAV 读取器。
 *
 * 委托引擎返回的音频格式不可控，这里只做一件事：把 WAV 解成单声道 16-bit PCM，
 * 交给 [android.speech.tts.SynthesisCallback] 直接输出。非 WAV 或无法识别的编码
 * 会抛 [UnsupportedAudioException]，由调用方转成引擎错误码。
 */
internal object WavAudio {

    private const val TAG = "WavAudio"

    class UnsupportedAudioException(message: String) : Exception(message)

    fun read(file: File, volume: Float): PcmAudio {
        val bytes = file.readBytes()
        if (bytes.size < 44) {
            throw UnsupportedAudioException("audio file too short: ${bytes.size} bytes")
        }
        if (ascii(bytes, 0) != "RIFF" || ascii(bytes, 8) != "WAVE") {
            throw UnsupportedAudioException("not a RIFF/WAVE container")
        }

        var formatTag = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = ascii(bytes, pos)
            val chunkSize = intLe(bytes, pos + 4)
            val body = pos + 8
            when (chunkId) {
                "fmt " -> {
                    if (body + 16 > bytes.size) break
                    formatTag = shortLe(bytes, body)
                    channels = shortLe(bytes, body + 2)
                    sampleRate = intLe(bytes, body + 4)
                    bitsPerSample = shortLe(bytes, body + 14)
                }
                "data" -> {
                    dataOffset = body
                    dataSize = if (chunkSize <= 0 || body + chunkSize > bytes.size) {
                        bytes.size - body
                    } else {
                        chunkSize
                    }
                }
            }
            val advance = if (chunkSize <= 0) bytes.size - body else chunkSize + (chunkSize % 2)
            if (advance <= 0) break
            pos = body + advance
            if (dataOffset >= 0 && formatTag != 0) break
        }

        if (formatTag == 0 || dataOffset < 0) throw UnsupportedAudioException("missing fmt/data chunk")
        if (channels <= 0) throw UnsupportedAudioException("invalid channel count: $channels")
        if (sampleRate <= 0) throw UnsupportedAudioException("invalid sample rate: $sampleRate")
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample <= 0) throw UnsupportedAudioException("invalid bit depth: $bitsPerSample")

        val frameBytes = channels * bytesPerSample
        val frames = dataSize / frameBytes
        if (frames <= 0) throw UnsupportedAudioException("no audio frames in file")

        val sampleAt: (Int) -> Float = when {
            formatTag == 1 && bitsPerSample == 8 -> { off ->
                ((bytes[off].toInt() and 0xFF) - 128) / 128f
            }
            formatTag == 1 && bitsPerSample == 16 -> { off ->
                shortLe(bytes, off) / 32768f
            }
            formatTag == 1 && bitsPerSample == 24 -> { off ->
                int24Le(bytes, off) / 8388608f
            }
            formatTag == 1 && bitsPerSample == 32 -> { off ->
                intLe(bytes, off) / 2147483648f
            }
            formatTag == 3 && bitsPerSample == 32 -> { off ->
                Float.fromBits(intLe(bytes, off))
            }
            else -> throw UnsupportedAudioException(
                "unsupported encoding: format=$formatTag bits=$bitsPerSample"
            )
        }

        val gain = volume.coerceIn(0f, 1f)
        val out = ByteArray(frames * 2)
        for (frame in 0 until frames) {
            val base = dataOffset + frame * frameBytes
            var mixed = 0f
            for (ch in 0 until channels) {
                mixed += sampleAt(base + ch * bytesPerSample)
            }
            mixed /= channels

            val clamped = (mixed * gain).coerceIn(-1f, 1f)
            val value = (clamped * 32767f).toInt()
            out[frame * 2] = (value and 0xFF).toByte()
            out[frame * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }

        Log.d(TAG, "decoded ${bytes.size} bytes -> $frames frames @ ${sampleRate}Hz")
        return PcmAudio(sampleRate, out)
    }

    private fun ascii(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun shortLe(bytes: ByteArray, offset: Int): Int {
        val lo = bytes[offset].toInt() and 0xFF
        val hi = bytes[offset + 1].toInt() and 0xFF
        return ((lo or (hi shl 8)) shl 16) shr 16
    }

    private fun int24Le(bytes: ByteArray, offset: Int): Int {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        return ((b0 or (b1 shl 8) or (b2 shl 16)) shl 8) shr 8
    }

    private fun intLe(bytes: ByteArray, offset: Int): Int {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
