package org.nobody.multitts.replica.data.voicepack

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** 计算文件 SHA-256（小写十六进制）。 */
private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * 语音包的本地仓库：每个包解压到 `files/voicepacks/<id>/`，包根目录必须包含 `pack.json`。
 * 安装采用"先解压到临时目录、校验通过后再替换"的方式，中途失败不会破坏已装版本。
 */
class VoicePackStore(context: Context) {
    private val appContext = context.applicationContext
    private val root: File = File(appContext.filesDir, DIR).apply { mkdirs() }

    fun installed(): List<VoicePack> {
        val dirs = root.listFiles { file -> file.isDirectory && !file.name.startsWith(TEMP_PREFIX) }
            ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching { readPack(dir) }
                .onFailure { Log.w(TAG, "跳过损坏的语音包 ${dir.name}: ${it.message}") }
                .getOrNull()
        }.sortedWith(compareByDescending<VoicePack> { it.installedAt }.thenBy { it.name })
    }

    fun find(id: String): VoicePack? = installed().firstOrNull { it.id == id }

    fun directoryOf(id: String): File = File(root, id)

    fun delete(id: String): Boolean {
        if (!VoicePack.isValidId(id)) return false
        return File(root, id).deleteRecursively()
    }

    /** 从任意 zip 字节流安装。校验 sha256（若提供），失败抛 [IOException]。 */
    fun install(input: InputStream, expectedSha256: String?, sourceUrl: String?): VoicePack {
        val work = File(root, TEMP_PREFIX + UUID.randomUUID())
        work.mkdirs()
        val zipFile = File(work, "pack.zip")
        try {
            FileOutputStream(zipFile).use { out -> input.copyTo(out) }
            if (zipFile.length() == 0L) throw IOException("下载到的语音包为空")
            if (expectedSha256 != null) {
                val actual = zipFile.sha256()
                if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                    throw IOException("语音包校验失败：期望 $expectedSha256，实际 $actual")
                }
            }
            val payload = File(work, "payload").apply { mkdirs() }
            unzip(zipFile, payload)
            val pack = readPack(payload)
            val target = File(root, pack.id)
            val meta = pack.copy(
                installedAt = System.currentTimeMillis(),
                sourceUrl = sourceUrl ?: pack.sourceUrl,
            )
            File(payload, MANIFEST).writeText(meta.toJson().toString(2))
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("无法覆盖已安装的语音包 ${pack.id}")
            }
            if (!payload.renameTo(target)) throw IOException("安装 ${pack.id} 失败")
            Log.i(TAG, "installed voice pack ${pack.id} v${pack.version} (${pack.voices.size} voices)")
            return meta
        } finally {
            work.deleteRecursively()
        }
    }

    private fun readPack(dir: File): VoicePack {
        val manifest = File(dir, MANIFEST)
        if (!manifest.isFile) throw IOException("缺少 $MANIFEST")
        return VoicePack.fromJson(JSONObject(manifest.readText()))
    }

    private fun unzip(zipFile: File, dest: File) {
        val destPath = dest.canonicalPath + File.separator
        var entries = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name.startsWith("__MACOSX") || name.endsWith(".DS_Store")) {
                    zip.closeEntry()
                    continue
                }
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                    throw IOException("语音包包含非法路径：$name")
                }
                val out = File(dest, name)
                if (!out.canonicalPath.startsWith(destPath)) {
                    throw IOException("语音包包含越界路径：$name")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fileOut -> zip.copyTo(fileOut) }
                    entries++
                }
                zip.closeEntry()
            }
        }
        if (entries == 0) throw IOException("语音包为空")
    }

    private companion object {
        const val TAG = "VoicePackStore"
        const val DIR = "voicepacks"
        const val MANIFEST = "pack.json"
        const val TEMP_PREFIX = ".tmp-"
    }
}
