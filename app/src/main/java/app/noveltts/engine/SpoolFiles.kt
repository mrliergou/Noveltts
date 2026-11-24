package app.noveltts.engine

import app.noveltts.data.cache.ActiveAudioFiles
import java.io.File

/** Clean only known orphan files, once per process and directory, before its first writer. */
internal object SpoolFiles {
    private val prepared = HashSet<String>()
    fun prepare(directory: File) = synchronized(prepared) {
        val root = directory.canonicalFile
        if (!prepared.add(root.path)) return@synchronized
        root.listFiles()?.filter { it.isFile && it.name.startsWith("segment-") && it.name.endsWith(".pcm") &&
            it.canonicalFile.parentFile == root }?.forEach { ActiveAudioFiles.deleteIfIdle(it) }
    }
}