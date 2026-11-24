package app.noveltts.data.voicepack

import java.io.File

/** Called once before an install can create staging files. Ambiguous backups are preserved. */
internal object PackInstallRecovery {
    private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val temporary = Regex("\\.tmp-$UUID")
    private val backup = Regex("\\.backup-(.+)-$UUID")

    fun recover(directory: File, readable: (File) -> Boolean) {
        val root = directory.canonicalFile
        val children = root.listFiles().orEmpty().filter { it.isDirectory && it.canonicalFile.parentFile == root }
        children.filter { temporary.matches(it.name) }.forEach { it.deleteRecursively() }
        val backups = children.mapNotNull { file ->
            backup.matchEntire(file.name)?.groupValues?.get(1)?.takeIf(VoicePack::isValidId)?.let { it to file }
        }.groupBy({ it.first }, { it.second })
        backups.forEach { (id, copies) ->
            val target = File(root, id)
            if (target.isDirectory && readable(target)) {
                copies.forEach { it.deleteRecursively() }
            } else if (!target.exists() && copies.size == 1 && readable(copies.single())) {
                copies.single().renameTo(target)
            }
        }
    }
}
