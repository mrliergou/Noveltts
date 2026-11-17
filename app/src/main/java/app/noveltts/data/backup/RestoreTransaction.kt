package app.noveltts.data.backup

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.json.JSONArray
import org.json.JSONObject

/** A private durable journal makes a rules/settings/encrypted-credentials restore recoverable. */
class RestoreTransaction(private val dataRoot: File, private val journal: File) {
    private val rootPath = dataRoot.canonicalFile.toPath()
    private fun checked(relative: String): File {
        val file = File(dataRoot, relative).canonicalFile
        require(file.toPath().startsWith(rootPath) && file.toPath() != rootPath) { "还原路径越界" }
        return file
    }

    fun <T> run(targets: List<File>, rollbackMemory: () -> Unit = {}, apply: () -> T): T = synchronized(LOCK) {
        recover()
        require(journal.canonicalFile.toPath().startsWith(rootPath)) { "还原日志路径越界" }
        check(journal.isDirectory || journal.mkdirs()) { "无法创建还原日志目录" }
        val entries = JSONArray()
        targets.distinctBy { it.canonicalPath }.forEachIndexed { index, target ->
            val path = target.canonicalFile.toPath()
            require(path.startsWith(rootPath) && path != rootPath) { "还原目标越界" }
            val existed = target.isFile
            if (existed) FileOutputStream(File(journal, "$index.bak")).use { output ->
                target.inputStream().use { it.copyTo(output) }; output.fd.sync()
            }
            entries.put(JSONObject().put("path", rootPath.relativize(path).toString())
                .put("backup", "$index.bak").put("existed", existed))
        }
        val manifest = JSONObject().put("schema", 1).put("phase", "prepared").put("entries", entries)
        writeManifest(manifest)
        try {
            val result = apply()
            writeManifest(manifest.put("phase", "committed"))
            runCatching { clearJournal() }
            result
        } catch (failure: Throwable) {
            try { restore(manifest); rollbackMemory(); writeManifest(manifest.put("phase", "committed")); runCatching { clearJournal() } }
            catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    /** Called before constructing SharedPreferences/JSON repositories on a cold service/app start. */
    fun recover(): Boolean = synchronized(LOCK) {
        val file = File(journal, "manifest.json")
        if (!file.isFile) return@synchronized false
        val manifest = JSONObject(file.readText())
        require(manifest.getInt("schema") == 1) { "还原日志版本不支持" }
        if (manifest.getString("phase") == "prepared") {
            restore(manifest)
            writeManifest(manifest.put("phase", "committed"))
        }
        else require(manifest.getString("phase") == "committed") { "还原日志阶段无效" }
        clearJournal()
        true
    }

    private fun restore(manifest: JSONObject) {
        val entries = manifest.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val target = checked(entry.getString("path"))
            val backupName = entry.getString("backup")
            require(backupName.matches(Regex("[0-9]+\\.bak"))) { "还原备份名称无效" }
            if (entry.getBoolean("existed")) {
                val backup = File(journal, backupName)
                check(backup.isFile) { "还原日志缺少备份" }
                target.parentFile?.mkdirs()
                Files.copy(backup.toPath(), target.toPath(), REPLACE_EXISTING)
                FileOutputStream(target, true).use { it.fd.sync() }
            } else if (target.exists()) check(target.isFile && target.delete()) { "无法回滚新文件" }
        }
    }
    private fun writeManifest(value: JSONObject) {
        val pending = File(journal, "manifest.tmp")
        FileOutputStream(pending).use { it.write(value.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        try { Files.move(pending.toPath(), File(journal, "manifest.json").toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(pending.toPath(), File(journal, "manifest.json").toPath(), REPLACE_EXISTING)
        }
    }
    private fun clearJournal() {
        journal.listFiles()?.filter { it.isFile && (it.name.matches(Regex("[0-9]+\\.bak")) ||
            it.name in setOf("manifest.json", "manifest.tmp")) }?.forEach { check(it.delete()) { "无法清理还原日志" } }
        journal.delete()
    }
    companion object { private val LOCK = Any() }
}

object ConfigurationRecovery {
    fun transaction(context: android.content.Context): RestoreTransaction = RestoreTransaction(
        File(context.applicationInfo.dataDir), File(context.filesDir, "restore-journal"))
    fun recover(context: android.content.Context) = synchronized(app.noveltts.data.config.CONFIGURATION_LOCK) {
        runCatching { transaction(context).recover() }.onFailure {
            app.noveltts.engine.DiagnosticsLog.error(app.noveltts.engine.Category.STORAGE, "未完成的还原无法自动恢复", it.javaClass.simpleName)
        }
    }
}
