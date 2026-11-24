package app.noveltts.data.voicepack

import android.content.Context
import app.noveltts.data.JsonInput
import app.noveltts.data.config.CONFIGURATION_LOCK
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.nio.file.AtomicMoveNotSupportedException

/** Stable voice IDs survive package removal; null sound settings inherit the package. */
data class VoiceOverride(val alias: String? = null, val favorite: Boolean = false, val enabled: Boolean = true,
    val rate: Float? = null, val pitch: Float? = null, val volume: Float? = null) {
    fun validate() {
        require(alias == null || alias.length <= 120) { "音色别名最长 120 字符" }
        require(rate == null || rate.isFinite() && rate in 0.25f..4f) { "语速范围为 0.25–4" }
        require(pitch == null || pitch.isFinite() && pitch in 0.25f..2f) { "音调范围为 0.25–2" }
        require(volume == null || volume.isFinite() && volume in 0f..1f) { "音量范围为 0–1" }
    }
    fun apply(voice: VoiceDescriptor) = voice.copy(
        displayName = alias?.trim()?.takeIf { it.isNotEmpty() } ?: voice.displayName,
        favorite = favorite, userEnabled = enabled, usable = voice.usable && enabled,
        unusableReason = if (!enabled) "音色已停用，引用仍保留；启用后恢复" else voice.unusableReason,
        defaultSpeed = rate ?: voice.defaultSpeed, defaultPitch = pitch ?: voice.defaultPitch,
        defaultVolume = volume ?: voice.defaultVolume)
}

class VoiceOverrides(private val directory: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "config"))
    private val file get() = File(directory, FILE)
    fun files() = listOf(file, File(directory, "$FILE.bak"))
    fun load(): Map<String, VoiceOverride> = synchronized(CONFIGURATION_LOCK) {
        if (!file.exists()) emptyMap() else decode(file.inputStream().use { PackInput.readBytes(it, MAX_BYTES.toLong()).toString(Charsets.UTF_8) })
    }
    fun save(id: String, draft: VoiceOverride, expected: VoiceOverride?): Map<String, VoiceOverride> = synchronized(CONFIGURATION_LOCK) {
        val current = load()
        check(current[id] == expected) { "音色配置已变化，请关闭后重新编辑；草稿已保留" }
        val next = current + (id to draft)
        write(next)
        next
    }
    fun restore(values: Map<String, VoiceOverride>) = synchronized(CONFIGURATION_LOCK) { write(values) }
    fun recoverBackup() = synchronized(CONFIGURATION_LOCK) {
        val backup = File(directory, "$FILE.bak")
        val values = decode(backup.inputStream().use { PackInput.readBytes(it, MAX_BYTES.toLong()).toString(Charsets.UTF_8) })
        val recovery = File(directory, "$FILE.before-recovery")
        if (file.exists()) Files.copy(file.toPath(), recovery.toPath(), REPLACE_EXISTING)
        write(values, backupCurrent = false)
    }
    fun resetCorrupt() = synchronized(CONFIGURATION_LOCK) {
        if (file.exists()) {
            val preserved = File(directory, "$FILE.before-reset-${java.util.UUID.randomUUID()}")
            Files.copy(file.toPath(), preserved.toPath())
        }
        write(emptyMap(), backupCurrent = false)
    }
    private fun write(values: Map<String, VoiceOverride>, backupCurrent: Boolean = true) {
        val text = encode(values)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "音色配置超过文件上限" }
        decode(text)
        directory.mkdirs()
        val temp = File.createTempFile("voices-", ".tmp", directory)
        try {
            java.io.FileOutputStream(temp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            if (backupCurrent && file.exists()) Files.copy(file.toPath(), File(directory, "$FILE.bak").toPath(), REPLACE_EXISTING)
            try { Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), REPLACE_EXISTING) }
            VoicePackEvents.invalidate()
        } finally { temp.delete() }
    }
    companion object {
        const val FILE = "voice-overrides.json"
        const val MAX_BYTES = 1024 * 1024
        fun encode(values: Map<String, VoiceOverride>): String {
            require(values.size <= 5000) { "音色覆盖配置过多" }
            val entries = JSONObject()
            values.toSortedMap().forEach { (id, value) ->
                require(id.startsWith("multitts:") && id.length <= 512) { "音色 ID 无效" }
                value.validate()
                entries.put(id, JSONObject().put("alias", value.alias).put("favorite", value.favorite).put("enabled", value.enabled)
                    .put("rate", value.rate).put("pitch", value.pitch).put("volume", value.volume))
            }
            return JSONObject().put("schema", 1).put("entries", entries).toString()
        }
        fun decode(text: String): Map<String, VoiceOverride> {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "音色配置超过文件上限" }
            JsonInput.checkDepth(text, 5)
            val root = JSONObject(text.removePrefix("\uFEFF"))
            require(root.getInt("schema") == 1) { "音色配置版本过新或无效" }
            val entries = root.getJSONObject("entries")
            require(entries.length() <= 5000) { "音色覆盖配置过多" }
            return entries.keys().asSequence().associateWith { id ->
                require(id.startsWith("multitts:") && id.length <= 512) { "音色 ID 无效" }
                val obj = entries.getJSONObject(id)
                fun value(key: String) = if (obj.isNull(key)) null else obj.getDouble(key).toFloat()
                VoiceOverride(if (obj.isNull("alias")) null else obj.getString("alias"), obj.getBoolean("favorite"), obj.getBoolean("enabled"), value("rate"), value("pitch"), value("volume")).also { it.validate() }
            }
        }
    }
}
