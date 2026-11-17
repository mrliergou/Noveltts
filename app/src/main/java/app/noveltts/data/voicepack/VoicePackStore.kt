package app.noveltts.data.voicepack

import android.content.Context
import android.util.Log
import app.noveltts.engine.Category
import app.noveltts.engine.DiagnosticsLog
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** 计算文件 SHA-256（小写十六进制）。 */
internal fun File.sha256(): String = inputStream().use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = stream.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * 语音包的本地仓库。
 *
 * 同时支持原版 zip（config.yaml + engines.yaml）和旧版 pack.json。安装采用
 * 临时目录 + 解析校验 + 旧目录备份替换，任何一步失败都保留已安装版本。
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
        if (!work.mkdirs()) throw IOException("无法创建语音包临时目录")
        val zipFile = File(work, "pack.zip")
        try {
            FileOutputStream(zipFile).use { out -> input.use { it.copyTo(out) } }
            if (zipFile.length() == 0L) throw IOException("下载到的语音包为空")
            if (zipFile.length() > MAX_ZIP_BYTES) {
                throw IOException("语音包超过大小限制（${MAX_ZIP_BYTES / 1024 / 1024} MB）")
            }
            if (root.usableSpace in 1 until zipFile.length() * 3) {
                throw IOException("可用存储空间不足")
            }
            val actual = zipFile.sha256()
            if (expectedSha256 != null) {
                if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                    throw IOException("语音包校验失败：期望 $expectedSha256，实际 $actual")
                }
            }

            val payload = File(work, "payload").apply { mkdirs() }
            unzip(zipFile, payload)
            val detected = detect(payload, sourceUrl)
            val installedAt = System.currentTimeMillis()
            val pack = detected.copy(
                installedAt = installedAt,
                contentRevision = actual,
                sourceUrl = sourceUrl ?: detected.sourceUrl,
            )
            writeMetadata(payload, pack)

            val target = File(root, pack.id)
            val backup = File(root, ".backup-${pack.id}-${UUID.randomUUID()}")
            if (target.exists() && !target.renameTo(backup)) {
                throw IOException("无法备份已安装的语音包 ${pack.id}")
            }
            if (!payload.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IOException("安装 ${pack.id} 失败")
            }
            backup.deleteRecursively()
            DiagnosticsLog.info(Category.PACK, "音色包导入成功：${pack.name}", "包 ${pack.id} · 格式 ${pack.format} · ${pack.voices.size} 个音色")
            Log.i(TAG, "installed ${pack.format} pack ${pack.id} (${pack.voices.size} voices)")
            return pack
        } catch (error: Exception) {
            DiagnosticsLog.error(Category.PACK, "音色包导入失败", error.message?.take(200))
            throw error
        } finally {
            work.deleteRecursively()
        }
    }

    private fun detect(payload: File, sourceUrl: String?): VoicePack = when {
        OriginalPackParser.isOriginalPack(payload) ->
            OriginalPackParser.parse(payload, sourceUrl)
        File(payload, LEGACY_MANIFEST).isFile ->
            VoicePack.fromJson(JSONObject(File(payload, LEGACY_MANIFEST).readText()))
        else -> throw IOException("语音包缺少 config.yaml + engines.yaml 或 pack.json")
    }

    private fun readPack(dir: File): VoicePack {
        val metadata = readMetadata(dir)
        val pack = when {
            OriginalPackParser.isOriginalPack(dir) -> OriginalPackParser.parse(
                directory = dir,
                sourceUrl = metadata?.optString("sourceUrl")?.takeIf { it.isNotBlank() },
                installedAt = metadata?.optLong("installedAt", dir.lastModified()) ?: dir.lastModified(),
            )
            File(dir, LEGACY_MANIFEST).isFile ->
                VoicePack.fromJson(JSONObject(File(dir, LEGACY_MANIFEST).readText()))
            else -> throw IOException("缺少可识别的语音包清单")
        }
        return pack.copy(
            contentRevision = metadata?.optString("contentRevision")?.takeIf { it.isNotBlank() }
                ?: "legacy:${pack.version}:${metadata?.optLong("installedAt", pack.installedAt) ?: pack.installedAt}",
            installedAt = metadata?.optLong("installedAt", pack.installedAt) ?: pack.installedAt,
            sourceUrl = metadata?.optString("sourceUrl")?.takeIf { it.isNotBlank() } ?: pack.sourceUrl,
        )
    }

    private fun writeMetadata(payload: File, pack: VoicePack) {
        val json = JSONObject().apply {
            put("schema", METADATA_SCHEMA)
            put("id", pack.id)
            put("format", pack.format.name)
            put("installedAt", pack.installedAt)
            put("contentRevision", pack.contentRevision)
            pack.sourceUrl?.let { put("sourceUrl", it) }
            put("engines", pack.engines.joinToString(",") { it.code })
        }
        File(payload, METADATA).writeText(json.toString(2))
    }

    private fun readMetadata(dir: File): JSONObject? {
        val file = File(dir, METADATA)
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }
            .onFailure { Log.w(TAG, "读取 ${dir.name}/$METADATA 失败", it) }
            .getOrNull()
    }

    private fun unzip(zipFile: File, dest: File) {
        val destPath = dest.canonicalPath + File.separator
        var entries = 0
        var totalBytes = 0L
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) throw IOException("语音包文件过多（上限 $MAX_ENTRIES）")
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
                    FileOutputStream(out).use { fileOut ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            totalBytes += read
                            if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                                throw IOException("语音包解压后超过大小限制")
                            }
                            fileOut.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (entries == 0) throw IOException("语音包为空")
    }

    private companion object {
        const val TAG = "VoicePackStore"
        const val DIR = "voicepacks"
        const val LEGACY_MANIFEST = "pack.json"
        const val METADATA = "installed.json"
        const val METADATA_SCHEMA = 1
        const val TEMP_PREFIX = ".tmp-"
        const val MAX_ENTRIES = 5_000
        const val MAX_ZIP_BYTES = 1_073_741_824L
        const val MAX_UNCOMPRESSED_BYTES = 1_610_612_736L
    }
}
