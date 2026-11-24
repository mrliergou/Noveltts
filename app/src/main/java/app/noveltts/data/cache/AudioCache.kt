package app.noveltts.data.cache

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class CacheMetrics(val hits: Long = 0, val misses: Long = 0) {
    val hitPercent: Int get() = if (hits + misses == 0L) 0 else (hits * 100 / (hits + misses)).toInt()
}

class CacheReadLease internal constructor(
    val file: File, val input: InputStream, private val protection: AutoCloseable,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { input.close() } finally { protection.close() }
        }
    }
}

/** PCM16 cache. All instances and manual cleanup share protection for active files. */
class AudioCache(
    val dir: File, @Volatile var maxBytes: Long, @Volatile var enabled: Boolean = true,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = ActiveAudioFiles.lock
    private val pending = mutableMapOf<File, AutoCloseable>()
    private val rootKey = dir.canonicalPath

    fun lookup(key: String): File? = synchronized(lock) {
        val file = fileFor(key)
        val size = file.length()
        val root = rootKey
        val previous = metrics[root] ?: CacheMetrics()
        if (file.isFile && size > 0L && size % 2 == 0L) {
            metrics[root] = previous.copy(hits = previous.hits + 1)
            file.setLastModified(System.currentTimeMillis())
            file
        } else {
            metrics[root] = previous.copy(misses = previous.misses + 1)
            ActiveAudioFiles.deleteIfIdle(file)
            null
        }
    }

    fun acquireRead(key: String): CacheReadLease? = synchronized(lock) {
        val file = lookup(key) ?: return@synchronized null
        val protection = ActiveAudioFiles.protect(file)
        try { CacheReadLease(file, FileInputStream(file), protection) }
        catch (_: Exception) { protection.close(); null }
    }

    fun beginWrite(key: String): File = synchronized(lock) {
        require(key.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid cache key" }
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create audio cache directory" }
        val tmp = File.createTempFile("$key-cache-", ".tmp", dir)
        pending[tmp] = ActiveAudioFiles.protect(tmp)
        tmp
    }

    fun commitWrite(tmp: File, key: String): Boolean = synchronized(lock) {
        val protection = pending.remove(tmp) ?: return@synchronized false
        try {
            if (!enabled || tmp.length() <= 0L || tmp.length() % 2L != 0L) return@synchronized false
            val target = fileFor(key)
            val growth = (tmp.length() - target.length()).coerceAtLeast(0L)
            // Preserve an existing reader's file; two complete writers may safely share the first result.
            if (ActiveAudioFiles.isProtected(target)) return@synchronized false
            val committed = runCatching {
                try { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }.isSuccess
            if (committed) {
                val state = trimStates.getOrPut(rootKey) { TrimState() }
                // Keep a conservative count across every cache instance; deletion only overestimates it.
                state.bytes = state.bytes?.let { it + growth }
            }
            committed
        } finally { protection.close(); if (tmp.exists()) tmp.delete() }
    }

    fun abortWrite(tmp: File) = synchronized(lock) {
        pending.remove(tmp)?.close()
        ActiveAudioFiles.deleteIfIdle(tmp)
        Unit
    }

    fun invalidate(key: String): Boolean = ActiveAudioFiles.deleteIfIdle(fileFor(key))
    fun totalBytes(): Long = synchronized(lock) { cacheFiles().sumOf { it.length() } }

    fun evictIfNeeded(force: Boolean = true) = synchronized(lock) {
        val now = nowMillis()
        val limit = maxBytes.coerceAtLeast(0L)
        val state = trimStates.getOrPut(rootKey) { TrimState() }
        val previousScan = state.scannedAt
        if (!force && previousScan != null && now - previousScan < RESCAN_INTERVAL_MILLIS &&
            state.bytes?.let { it <= limit } == true) return@synchronized
        val files = cacheFiles().map { EvictionEntry(it, it.length(), it.lastModified()) }
        var bytes = files.sumOf { it.bytes }
        if (bytes > limit) {
            for (entry in files.sortedWith(compareBy({ it.modified }, { it.file.name }))) {
                if (bytes <= limit) break
                if (ActiveAudioFiles.deleteIfIdle(entry.file)) bytes -= entry.bytes
            }
        }
        state.scannedAt = now
        state.bytes = bytes
    }

    private fun fileFor(key: String): File {
        require(key.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid cache key" }
        return File(dir, "$key.pcm")
    }
    private fun cacheFiles(): List<File> = buildList {
        fun collect(directory: File) {
            directory.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    if (file.name.lowercase() !in setOf("voicepacks", "spool")) collect(file)
                } else if (file.extension.lowercase() in setOf("pcm", "tmp") &&
                    !(file.extension == "tmp" && ActiveAudioFiles.isProtected(file))) add(file)
            }
        }
        collect(dir)
    }

    companion object {
        const val DIR_NAME = "audiocache"
        private const val RESCAN_INTERVAL_MILLIS = 30_000L
        private data class TrimState(var bytes: Long? = null, var scannedAt: Long? = null)
        private data class EvictionEntry(val file: File, val bytes: Long, val modified: Long)
        private val trimStates = mutableMapOf<String, TrimState>()
        private val metrics = mutableMapOf<String, CacheMetrics>()
        fun metrics(dir: File): CacheMetrics = synchronized(ActiveAudioFiles.lock) {
            metrics[dir.canonicalPath] ?: CacheMetrics()
        }
    }
}

internal fun cacheKey(voice: String, rate: Int, pitch: Int, volume: Int, text: String, revision: String = ""): String {
    // Length prefixes avoid ambiguous boundaries even when a field contains NUL.
    val payload = listOf("pcm24-v2", revision, voice, rate.toString(), pitch.toString(), volume.toString(), text)
        .joinToString("") { "${it.length}:$it" }
    val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(64) { digest.forEach { byte ->
        val value = byte.toInt() and 255
        append(hex[value ushr 4]); append(hex[value and 15])
    } }
}
