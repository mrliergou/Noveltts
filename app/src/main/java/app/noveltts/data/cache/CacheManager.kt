package app.noveltts.data.cache

import java.io.File

/** 缓存目录的统计结果。 */
data class CacheUsage(val bytes: Long, val fileCount: Int) {
    companion object {
        val EMPTY = CacheUsage(0L, 0)
    }

    val isClean: Boolean get() = fileCount == 0
}

/**
 * cacheDir 的统计与清理。
 *
 * 认三类文件：`*.mp3`、`*.tmp`，以及音频缓存目录 `audiocache` 下的 `*.pcm`
 * （[AudioCache] 的裸 PCM 段落）。pcm 只在这个子目录里才算缓存，这样
 * `spool/segment-*.pcm` 这种正在回放的临时文件不会被顺手删掉。
 * 已安装的语音包在 filesDir/voicepacks 下，就算有人把它挪进 cacheDir 也会整目录跳过。
 */
object CacheManager {
    private val EXTENSIONS = setOf("mp3", "tmp")

    /** 音频缓存子目录：只有它里面的 pcm 才算缓存文件。 */
    private val AUDIO_CACHE_DIR = AudioCache.DIR_NAME

    /** 即使出现在缓存目录里也不删除的目录名。 */
    private const val PROTECTED_DIR = "voicepacks"

    fun stats(root: File): CacheUsage {
        if (!root.isDirectory) return CacheUsage.EMPTY
        var bytes = 0L
        var count = 0
        forEachCacheFile(root) { file ->
            bytes += file.length()
            count += 1
        }
        return CacheUsage(bytes, count)
    }

    /** 删除所有缓存文件，返回实际删掉的字节数与文件数。 */
    fun clear(root: File): CacheUsage {
        var bytes = 0L
        var count = 0
        forEachCacheFile(root) { file ->
            val size = file.length()
            if (ActiveAudioFiles.deleteIfIdle(file)) {
                bytes += size
                count += 1
            }
        }
        return CacheUsage(bytes, count)
    }

    private fun forEachCacheFile(root: File, action: (File) -> Unit) {
        val inAudioCache = root.name.equals(AUDIO_CACHE_DIR, ignoreCase = true)
        walk(root, inAudioCache, action)
    }

    private fun walk(directory: File, inAudioCache: Boolean, action: (File) -> Unit) {
        val children = directory.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name.equals(PROTECTED_DIR, ignoreCase = true) || child.name.equals("spool", ignoreCase = true)) continue
                val nested = inAudioCache || child.name.equals(AUDIO_CACHE_DIR, ignoreCase = true)
                walk(child, nested, action)
            } else {
                val extension = child.extension.lowercase()
                if (extension in EXTENSIONS || (inAudioCache && extension == "pcm")) action(child)
            }
        }
    }
}
