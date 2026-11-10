package org.nobody.multitts.replica.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nobody.multitts.replica.data.SpeechController
import org.nobody.multitts.replica.data.SpeechEngineState
import org.nobody.multitts.replica.data.SpeechVoice
import org.nobody.multitts.replica.data.voicepack.CatalogEntry
import org.nobody.multitts.replica.data.voicepack.PackVoice
import org.nobody.multitts.replica.data.voicepack.VoicePack
import org.nobody.multitts.replica.data.voicepack.VoicePackCatalog
import org.nobody.multitts.replica.data.voicepack.VoicePackStore
import org.nobody.multitts.replica.engine.EngineCatalog
import org.nobody.multitts.replica.engine.TtsEngineInfo
import java.io.File

data class MainUiState(
    val text: String = "",
    val speed: Int = 50,
    val volume: Int = 50,
    val pitch: Int = 25,
    val engines: List<TtsEngineInfo> = emptyList(),
    val enginePackage: String? = null,
    val systemDefaultPackage: String? = null,
    val speech: SpeechEngineState = SpeechEngineState(),
    val roles: Set<String> = emptySet(),
    val replacements: Set<String> = emptySet(),
    val packs: List<VoicePack> = emptyList(),
    val catalog: List<CatalogEntry> = emptyList(),
    val catalogUrl: String = "",
    val packBusyId: String? = null,
    val packProgress: Int = -1,
    val packStatus: String? = null,
)

class MainViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("multitts", Context.MODE_PRIVATE)
    private val engines = EngineCatalog.installedEngines(application)
    private val systemDefaultPackage = EngineCatalog.defaultEnginePackage(application)
    private val initialEngine: String? = savedStateHandle.get<String>(EXTRA_ENGINE)
        ?.takeIf { it.isNotBlank() }
        ?: preferences.getString(KEY_ENGINE, null)
            ?.takeIf { saved -> engines.any { it.packageName == saved } }
    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val speechController = SpeechController(
        context = application,
        onStateChanged = ::onSpeechStateChanged,
        enginePackage = initialEngine,
    )
    private val packStore = VoicePackStore(application)

    init {
        speechController.initialize()
        val installed = packStore.installed()
        Log.i(TAG, "packs loaded: ${installed.size} (${installed.joinToString { "${it.id}@${it.version}" }})")
        _uiState.update {
            it.copy(
                packs = installed,
                catalogUrl = preferences.getString(KEY_CATALOG_URL, DEFAULT_CATALOG_URL).orEmpty(),
            )
        }
    }

    fun updateText(value: String) {
        _uiState.update { it.copy(text = value) }
        preferences.edit().putString(KEY_LAST_TEXT, value).apply()
    }

    fun updateSpeed(value: Int) = updateSetting(KEY_SPEED, value) { state, progress ->
        state.copy(speed = progress)
    }

    fun updateVolume(value: Int) = updateSetting(KEY_VOLUME, value) { state, progress ->
        state.copy(volume = progress)
    }

    fun updatePitch(value: Int) = updateSetting(KEY_PITCH, value) { state, progress ->
        state.copy(pitch = progress)
    }

    fun selectEngine(packageName: String?) {
        val normalized = packageName?.takeIf { it.isNotBlank() }
        if (normalized == _uiState.value.enginePackage) return
        preferences.edit().putString(KEY_ENGINE, normalized.orEmpty()).remove(KEY_VOICE).apply()
        _uiState.update { it.copy(enginePackage = normalized) }
        speechController.useEngine(normalized)
    }

    fun selectVoice(voice: SpeechVoice) {
        preferences.edit().putString(KEY_VOICE, voice.name).apply()
        speechController.selectVoice(voice.name)
    }

    fun speak() {
        val state = _uiState.value
        speechController.speak(state.text, state.speed, state.pitch, state.volume)
    }

    fun stop() = speechController.stop()

    fun addRole(value: String) = updateCollection(KEY_ROLES, value) { roles ->
        copy(roles = roles)
    }

    fun removeRole(value: String) = removeFromCollection(KEY_ROLES, value) { roles ->
        copy(roles = roles)
    }

    fun addReplacement(find: String, replaceWith: String) {
        if (find.isBlank()) return
        val entry = "$find → $replaceWith"
        updateCollection(KEY_REPLACEMENTS, entry) { replacements ->
            copy(replacements = replacements)
        }
    }

    fun removeReplacement(value: String) = removeFromCollection(KEY_REPLACEMENTS, value) { replacements ->
        copy(replacements = replacements)
    }

    fun clearRecentText() {
        preferences.edit().remove(KEY_LAST_TEXT).apply()
        _uiState.update { it.copy(text = "") }
    }

    fun setCatalogUrl(url: String) {
        preferences.edit().putString(KEY_CATALOG_URL, url.trim()).apply()
        _uiState.update { it.copy(catalogUrl = url.trim()) }
    }

    /** 从 SAF 选中的 zip 导入语音包。 */
    fun importPack(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        updatePackBusy(PACK_BUSY_IMPORT, "正在导入语音包…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openInputStream(uri)?.use { stream ->
                        packStore.install(stream, expectedSha256 = null, sourceUrl = null)
                    } ?: throw IllegalArgumentException("无法读取所选文件")
                }
            }
            finishPackOperation(
                result = result,
                success = { pack -> "已导入「${pack.name}」v${pack.version}，共 ${pack.voices.size} 个音色" },
                failure = { error -> "导入失败：${error.message ?: error.javaClass.simpleName}" },
            )
        }
    }

    fun refreshCatalog() {
        val url = _uiState.value.catalogUrl.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(packStatus = "请先填写在线目录地址") }
            return
        }
        updatePackBusy(PACK_BUSY_CATALOG, "正在拉取在线语音包目录…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { VoicePackCatalog.fetch(url) } }
            result.onSuccess { entries ->
                Log.i(TAG, "catalog loaded: ${entries.size} entries from $url")
                _uiState.update {
                    it.copy(
                        catalog = entries,
                        packBusyId = null,
                        packProgress = -1,
                        packStatus = if (entries.isEmpty()) "目录里暂时没有语音包" else "在线语音包 ${entries.size} 个",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        packBusyId = null,
                        packProgress = -1,
                        packStatus = "目录获取失败：${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun downloadPack(entry: CatalogEntry) {
        if (_uiState.value.packBusyId != null) return
        val app = getApplication<Application>()
        updatePackBusy(entry.id, "正在下载「${entry.name}」…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(app.cacheDir, "voicepack-${entry.id}.zip")
                    VoicePackCatalog.download(entry, temp) { received, total ->
                        val percent = if (total > 0) (received * 100 / total).toInt() else -1
                        _uiState.update { it.copy(packProgress = percent) }
                    }
                    try {
                        temp.inputStream().use { stream ->
                            packStore.install(stream, expectedSha256 = entry.sha256, sourceUrl = entry.url)
                        }
                    } finally {
                        temp.delete()
                    }
                }
            }
            finishPackOperation(
                result = result,
                success = { pack -> "已安装「${pack.name}」v${pack.version}，共 ${pack.voices.size} 个音色" },
                failure = { error -> "下载失败：${error.message ?: error.javaClass.simpleName}" },
            )
        }
    }

    /** 直接按 zip 直链安装语音包（供外部链接 / 自动化验证使用）。 */
    fun installPackFromUrl(url: String) {
        val normalized = url.trim()
        if (normalized.isEmpty() || _uiState.value.packBusyId != null) return
        val app = getApplication<Application>()
        updatePackBusy(normalized, "正在下载语音包…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(app.cacheDir, "voicepack-direct.zip")
                    VoicePackCatalog.download(
                        CatalogEntry(id = "direct", name = normalized.substringAfterLast('/'), url = normalized),
                        temp,
                    ) { received, total ->
                        val percent = if (total > 0) (received * 100 / total).toInt() else -1
                        _uiState.update { it.copy(packProgress = percent) }
                    }
                    try {
                        temp.inputStream().use { stream ->
                            packStore.install(stream, expectedSha256 = null, sourceUrl = normalized)
                        }
                    } finally {
                        temp.delete()
                    }
                }
            }
            finishPackOperation(
                result = result,
                success = { pack -> "已安装「${pack.name}」v${pack.version}，共 ${pack.voices.size} 个音色" },
                failure = { error -> "安装失败：${error.message ?: error.javaClass.simpleName}" },
            )
        }
    }

    fun removePack(pack: VoicePack) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { packStore.delete(pack.id) }
            _uiState.update {
                it.copy(
                    packs = packStore.installed(),
                    packStatus = if (removed) "已删除「${pack.name}」" else "删除失败：${pack.name}",
                )
            }
        }
    }

    /** 应用语音包中的音色：切换其绑定的引擎，并选中引擎内对应的语音。 */
    fun applyPackVoice(pack: VoicePack, voice: PackVoice) {
        val engine = voice.engine
        if (engine != null && engine != _uiState.value.enginePackage) {
            selectEngine(engine)
        }
        val voiceName = voice.voiceName
        if (voiceName.isNullOrBlank()) {
            _uiState.update {
                it.copy(packStatus = "「${voice.name}」尚未绑定引擎内语音名，只切换了引擎")
            }
            return
        }
        preferences.edit().putString(KEY_VOICE, voiceName).apply()
        speechController.selectVoice(voiceName)
        _uiState.update { it.copy(packStatus = "已切换到「${pack.name} · ${voice.name}」") }
    }

    private fun updatePackBusy(id: String, status: String) {
        _uiState.update { it.copy(packBusyId = id, packProgress = -1, packStatus = status) }
    }

    private fun finishPackOperation(
        result: Result<VoicePack>,
        success: (VoicePack) -> String,
        failure: (Throwable) -> String,
    ) {
        _uiState.update { current ->
            current.copy(
                packs = packStore.installed(),
                packBusyId = null,
                packProgress = -1,
                packStatus = result.fold(success, failure),
            )
        }
    }

    override fun onCleared() {
        speechController.shutdown()
        super.onCleared()
    }

    private fun onSpeechStateChanged(speechState: SpeechEngineState) {
        val savedVoice = preferences.getString(KEY_VOICE, null)
        val preferredVoice = savedVoice
            ?.takeIf { name -> speechState.voices.any { it.name == name } }
        if (preferredVoice != null && preferredVoice != speechState.selectedVoice) {
            speechController.selectVoice(preferredVoice)
            return
        }
        _uiState.update { current -> current.copy(speech = speechState) }
    }

    private fun updateSetting(
        key: String,
        value: Int,
        transform: (MainUiState, Int) -> MainUiState,
    ) {
        preferences.edit().putInt(key, value).apply()
        _uiState.update { transform(it, value) }
    }

    private fun updateCollection(
        key: String,
        value: String,
        updateState: MainUiState.(Set<String>) -> MainUiState,
    ) {
        if (value.isBlank()) return
        val values = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        values.add(value.trim())
        preferences.edit().putStringSet(key, values).apply()
        _uiState.update { it.updateState(values) }
    }

    private fun removeFromCollection(
        key: String,
        value: String,
        updateState: MainUiState.(Set<String>) -> MainUiState,
    ) {
        val values = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        values.remove(value)
        preferences.edit().putStringSet(key, values).apply()
        _uiState.update { it.updateState(values) }
    }

    private fun loadInitialState(): MainUiState = MainUiState(
        text = preferences.getString(KEY_LAST_TEXT, "").orEmpty(),
        speed = preferences.getInt(KEY_SPEED, 50),
        volume = preferences.getInt(KEY_VOLUME, 50),
        pitch = preferences.getInt(KEY_PITCH, 25),
        engines = engines,
        enginePackage = initialEngine,
        systemDefaultPackage = systemDefaultPackage,
        roles = preferences.getStringSet(KEY_ROLES, emptySet()).orEmpty(),
        replacements = preferences.getStringSet(KEY_REPLACEMENTS, emptySet()).orEmpty(),
        speech = SpeechEngineState(
            selectedVoice = preferences.getString(KEY_VOICE, "").orEmpty(),
        ),
    )

    private companion object {
        const val KEY_LAST_TEXT = "lastText"
        const val KEY_SPEED = "speed"
        const val KEY_VOLUME = "volume"
        const val KEY_PITCH = "pitch"
        const val KEY_ENGINE = "engine"
        const val KEY_VOICE = "voice"
        const val KEY_ROLES = "roles"
        const val KEY_REPLACEMENTS = "replacements"
        const val KEY_CATALOG_URL = "catalogUrl"
        const val EXTRA_ENGINE = "engine"
        const val PACK_BUSY_IMPORT = "import"
        const val PACK_BUSY_CATALOG = "catalog"
        const val DEFAULT_CATALOG_URL = ""
        const val TAG = "MainViewModel"
    }
}
