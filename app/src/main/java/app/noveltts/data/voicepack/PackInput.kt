package app.noveltts.data.voicepack

import app.noveltts.data.JsonInput
import org.json.JSONObject

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Limits are checked while consuming the stream, including unknown or dishonest lengths. */
internal object PackInput {
    const val MAX_ARCHIVE_BYTES = 1_073_741_824L
    const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
    const val MAX_CATALOG_BYTES = 4L * 1024 * 1024

    fun checkLength(length: Long, maxBytes: Long) {
        if (length > maxBytes) throw IOException("文件超过大小限制（${maxBytes / 1024} KB）")
    }

    /** A null output discards bytes, but still applies the same decompression limit. */
    fun copy(input: InputStream, output: OutputStream?, maxBytes: Long,
        onProgress: (Long) -> Unit = {}): Long {
        require(maxBytes >= 0)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val remaining = maxBytes - total
            val requested = if (remaining >= buffer.size) buffer.size else (remaining + 1).toInt()
            val count = input.read(buffer, 0, requested)
            if (count < 0) return total
            if (count == 0) throw IOException("文件读取未取得进展")
            checkLength(total + count, maxBytes)
            output?.write(buffer, 0, count)
            total += count
            onProgress(total)
        }
    }

    fun readBytes(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        copy(input, output, maxBytes)
        return output.toByteArray()
    }

    fun readJson(file: File): JSONObject = JsonInput.parseObject(readFile(file).toString(Charsets.UTF_8))

    fun readFile(file: File, maxBytes: Long = MAX_MANIFEST_BYTES): ByteArray {
        checkLength(file.length(), maxBytes)
        return file.inputStream().use { readBytes(it, maxBytes) }
    }
}

/** Package-controlled voice codes must never direct the native engine outside its model tree. */
internal fun embeddedModelDirectory(packDirectory: File, voiceCode: String): File? = runCatching {
    if (voiceCode.isBlank() || '\u0000' in voiceCode) return null
    val pack = packDirectory.canonicalFile
    val models = File(pack, "microsoft").canonicalFile
    if (!models.path.startsWith(pack.path + File.separator)) return null
    File(models, voiceCode).canonicalFile.takeIf { it.path.startsWith(models.path + File.separator) }
}.getOrNull()
