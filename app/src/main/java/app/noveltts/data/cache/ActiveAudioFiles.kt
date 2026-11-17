package app.noveltts.data.cache

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Shared by app playback, system TTS and HTTP; a cleaner must use the same lock. */
object ActiveAudioFiles {
    internal val lock = Any()
    private val readers = mutableMapOf<String, Int>()
    private fun key(file: File): String = file.canonicalPath

    fun protect(file: File): AutoCloseable = synchronized(lock) {
        val path = key(file)
        readers[path] = (readers[path] ?: 0) + 1
        val closed = AtomicBoolean()
        AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(lock) {
                val remaining = (readers[path] ?: 1) - 1
                if (remaining == 0) readers.remove(path) else readers[path] = remaining
            }
        }
    }

    fun isProtected(file: File): Boolean = synchronized(lock) { (readers[key(file)] ?: 0) > 0 }
    fun deleteIfIdle(file: File): Boolean = synchronized(lock) {
        !isProtected(file) && runCatching { file.delete() }.getOrDefault(false)
    }
}
