package org.nobody.multitts.replica.ui

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nobody.multitts.replica.data.config.RulesRepository
import org.nobody.multitts.replica.data.settings.SettingsStore
import org.nobody.multitts.replica.data.voicepack.CatalogEntry
import org.nobody.multitts.replica.data.voicepack.VoiceDescriptor
import org.nobody.multitts.replica.data.voicepack.VoicePack
import org.nobody.multitts.replica.data.voicepack.VoicePackCatalog
import org.nobody.multitts.replica.data.voicepack.VoicePackEvents
import org.nobody.multitts.replica.data.voicepack.VoicePackStore
import org.nobody.multitts.replica.data.voicepack.VoiceRegistry
import org.nobody.multitts.replica.domain.PipelineIssue
import org.nobody.multitts.replica.domain.ReplacementRule
import org.nobody.multitts.replica.domain.RoleRule
import org.nobody.multitts.replica.domain.SynthesisPlanBuilder
import org.nobody.multitts.replica.domain.TextPipeline
import org.nobody.multitts.replica.engine.EngineManager
import org.nobody.multitts.replica.engine.MultiSegmentSynthesizer
import org.nobody.multitts.replica.engine.api.SynthesisError
import org.nobody.multitts.replica.engine.api.SynthesisHandle
import org.nobody.multitts.replica.engine.api.SynthesisListener
import java.io.File

enum class AppSection(val label: String) {
    WORKBENCH("工作台"),
    PACKS("语音包"),
    ROLES("角色"),
    REPLACEMENTS("替换"),
    SETTINGS("设置"),
}

data class PreviewSegment(
    val text: String,
    val isDialogue: Boolean,
    val voiceName: String,
    val roleName: String?,
)

data class MainUiState(
    val section: AppSection = AppSection.WORKBENCH,
    val text: String = "",
    val speed: Int = 50,
    val volume: Int = 50,
    val pitch: Int = 25,
    val narratorVoiceId: String = "",
    val dialogueVoiceId: String = "",
    val systemVoiceId: String = "",
    val voices: List<VoiceDescriptor> = emptyList(),
    val packs: List<VoicePack> = emptyList(),
    val roles: List<RoleRule> = emptyList(),
    val replacements: List<ReplacementRule> = emptyList(),
    val preview: List<PreviewSegment> = emptyList(),
    val pipelineIssues: List<PipelineIssue> = emptyList(),
    val punctuationFilter: Boolean = true,
    val licenseConfigured: Boolean = false,
    val licenseMask: String = "",
    val edgeEndpoint: String = SettingsStore.DEFAULT_EDGE_ENDPOINT,
    val compatibilityProxy: Boolean = false,
    val isSpeaking: Boolean = false,
    val statusMessage: String = "导入语音包后即可在系统 TTS 中选择 MultiTTS 音色。",
    val catalog: List<CatalogEntry> = emptyList(),
    val catalogUrl: String = "",
    val packBusyId: String? = null,
    val packProgress: Int = -1,
    val packStatus: String? = null,
) {
    val usableVoices: List<VoiceDescriptor> get() = voices.filter { it.usable }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("multitts", android.content.Context.MODE_PRIVATE)
    private val settings = SettingsStore(application)
    private val packStore = VoicePackStore(application)
    private val registry = VoiceRegistry(application, packStore)
    private val rulesRepository = RulesRepository(application)
    private val engines = EngineManager(application, settings)
    private val synthesizer = MultiSegmentSynthesizer(engines)
    private val trackLock = Any()

    private var playbackHandle: SynthesisHandle? = null
    private var audioTrack: AudioTrack? = null

    private val _uiState = MutableStateFlow(loadBaseState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        refreshAll()
    }

    fun selectSection(section: AppSection) {
        _uiState.update { it.copy(section = section) }
    }

    fun updateText(value: String) {
        _uiState.update { it.copy(text = value) }
        preferences.edit().putString(KEY_LAST_TEXT, value).apply()
        refreshPreview()
    }

    fun updateSpeed(value: Int) = updateSlider(KEY_SPEED, value) { state, progress ->
        state.copy(speed = progress)
    }

    fun updateVolume(value: Int) = updateSlider(KEY_VOLUME, value) { state, progress ->
        state.copy(volume = progress)
    }

    fun updatePitch(value: Int) = updateSlider(KEY_PITCH, value) { state, progress ->
        state.copy(pitch = progress)
    }

    fun selectNarrator(voiceId: String) {
        settings.narratorVoice = voiceId
        _uiState.update { it.copy(narratorVoiceId = voiceId) }
        refreshPreview()
    }

    fun selectDialogue(voiceId: String) {
        settings.dialogueVoice = voiceId
        _uiState.update { it.copy(dialogueVoiceId = voiceId) }
        refreshPreview()
    }

    fun selectSystemVoice(voiceId: String) {
        settings.systemVoice = voiceId
        _uiState.update { it.copy(systemVoiceId = voiceId) }
        VoicePackEvents.notifyChanged(getApplication())
    }

    fun setPunctuationFilter(enabled: Boolean) {
        settings.punctuationFilter = enabled
        _uiState.update { it.copy(punctuationFilter = enabled) }
        refreshPreview()
    }

    fun setLicense(value: String) {
        settings.setLicense(value)
        VoicePackEvents.notifyChanged(getApplication())
        refreshAll()
        _uiState.update {
            it.copy(
                statusMessage = if (value.isBlank()) {
                    "已清除 Embedded Speech 许可证。"
                } else {
                    "许可证已加密保存，离线音色重新扫描完成。"
                },
            )
        }
    }

    fun setEdgeEndpoint(value: String) {
        settings.edgeEndpoint = value
        _uiState.update {
            it.copy(
                edgeEndpoint = settings.edgeEndpoint,
                statusMessage = "Edge 端点已保存，下一次在线合成生效。",
            )
        }
    }

    fun setCompatibilityProxy(enabled: Boolean) {
        settings.compatibilityProxy = enabled
        _uiState.update { it.copy(compatibilityProxy = enabled) }
    }

    fun clearRecentText() {
        preferences.edit().remove(KEY_LAST_TEXT).apply()
        _uiState.update { it.copy(text = "") }
        refreshPreview()
    }

    fun speak() {
        val state = _uiState.value
        if (state.usableVoices.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "还没有可用音色，请先导入语音包或配置离线许可证。") }
            return
        }
        val plan = SynthesisPlanBuilder.build(
            text = state.text,
            narratorVoiceId = state.narratorVoiceId,
            dialogueVoiceId = state.dialogueVoiceId,
            roles = state.roles,
            replacements = state.replacements,
            filterPurePunctuation = state.punctuationFilter,
            voices = state.voices,
        )
        if (plan.segments.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "没有可朗读的文本片段。") }
            return
        }
        stopPlayback(updateState = false)
        _uiState.update {
            it.copy(
                isSpeaking = true,
                statusMessage = "正在合成本地预览…",
                pipelineIssues = plan.pipeline.issues,
            )
        }
        val listener = object : SynthesisListener {
            override fun onStart(sampleRateHz: Int, audioFormat: Int, channelCount: Int) {
                val track = runCatching {
                    createAudioTrack(sampleRateHz, audioFormat, channelCount)
                }.getOrElse { error ->
                    _uiState.update {
                        it.copy(isSpeaking = false, statusMessage = "无法创建音频输出：${error.message}")
                    }
                    playbackHandle?.cancel()
                    return
                }
                synchronized(trackLock) {
                    audioTrack = track
                }
                runCatching { track.play() }
                _uiState.update { it.copy(isSpeaking = true, statusMessage = "正在朗读") }
            }

            override fun onAudio(pcm: ByteArray, offset: Int, length: Int) {
                val track = synchronized(trackLock) { audioTrack } ?: return
                val written = track.write(pcm, offset, length, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    _uiState.update {
                        it.copy(isSpeaking = false, statusMessage = "音频输出失败（code=$written）")
                    }
                    playbackHandle?.cancel()
                }
            }

            override fun onComplete() {
                releaseTrack()
                playbackHandle = null
                _uiState.update { it.copy(isSpeaking = false, statusMessage = "朗读完成") }
            }

            override fun onError(code: Int, message: String?) {
                releaseTrack()
                playbackHandle = null
                _uiState.update {
                    it.copy(
                        isSpeaking = false,
                        statusMessage = when (code) {
                            SynthesisError.LICENSE_REQUIRED -> "需要先在设置中配置 Embedded Speech 许可证"
                            SynthesisError.NETWORK_FAILED -> "在线语音失败：${message ?: "网络错误"}"
                            else -> "合成失败：${message ?: "未知错误"}"
                        },
                    )
                }
            }
        }
        playbackHandle = synthesizer.synthesize(
            segments = plan.segments,
            rate = sliderToRate(state.speed),
            pitch = sliderToPitch(state.pitch),
            volume = sliderToVolume(state.volume),
            listener = listener,
        )
    }

    fun stop() {
        stopPlayback(updateState = true)
    }

    fun refreshAll() {
        val packs = packStore.installed()
        val voices = registry.voices(settings.hasLicense())
        val narrator = voices.firstOrNull {
            it.usable && it.systemName == settings.narratorVoice
        } ?: voices.firstOrNull { it.usable }
        val dialogue = voices.firstOrNull {
            it.usable && it.systemName == settings.dialogueVoice
        } ?: narrator
        val systemVoice = voices.firstOrNull {
            it.usable && it.systemName == settings.systemVoice
        } ?: narrator
        _uiState.update {
            it.copy(
                packs = packs,
                voices = voices,
                roles = rulesRepository.loadRoles(),
                replacements = rulesRepository.loadReplacements(),
                narratorVoiceId = narrator?.systemName.orEmpty(),
                dialogueVoiceId = dialogue?.systemName.orEmpty(),
                systemVoiceId = systemVoice?.systemName.orEmpty(),
                punctuationFilter = settings.punctuationFilter,
                licenseConfigured = settings.hasLicense(),
                licenseMask = settings.licenseMask(),
                edgeEndpoint = settings.edgeEndpoint,
                compatibilityProxy = settings.compatibilityProxy,
                catalogUrl = preferences.getString(KEY_CATALOG_URL, "").orEmpty(),
            )
        }
        refreshPreview()
    }

    fun saveRole(rule: RoleRule) {
        val rules = _uiState.value.roles.toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule else rules += rule
        val sorted = rules.sortedWith(
            compareByDescending<RoleRule> { it.priority }.thenBy { it.name },
        )
        rulesRepository.saveRoles(sorted)
        _uiState.update { it.copy(roles = sorted, statusMessage = "角色规则已保存") }
        refreshPreview()
    }

    fun deleteRole(rule: RoleRule) {
        val rules = _uiState.value.roles.filterNot { it.id == rule.id }
        rulesRepository.saveRoles(rules)
        _uiState.update { it.copy(roles = rules, statusMessage = "已删除角色规则「${rule.name}」") }
        refreshPreview()
    }

    fun toggleRole(rule: RoleRule, enabled: Boolean) {
        saveRole(rule.copy(enabled = enabled))
    }

    fun saveReplacement(rule: ReplacementRule) {
        val rules = _uiState.value.replacements.toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule else rules += rule
        val sorted = rules.sortedBy { it.order }
        rulesRepository.saveReplacements(sorted)
        _uiState.update { it.copy(replacements = sorted, statusMessage = "替换规则已保存") }
        refreshPreview()
    }

    fun deleteReplacement(rule: ReplacementRule) {
        val rules = _uiState.value.replacements.filterNot { it.id == rule.id }
        rulesRepository.saveReplacements(rules)
        _uiState.update {
            it.copy(replacements = rules, statusMessage = "已删除替换规则「${rule.name}」")
        }
        refreshPreview()
    }

    fun toggleReplacement(rule: ReplacementRule, enabled: Boolean) {
        saveReplacement(rule.copy(enabled = enabled))
    }

    fun testReplacements(text: String): String = TextPipeline.build(
        text = text,
        narratorVoiceId = null,
        dialogueVoiceId = null,
        roles = emptyList(),
        replacements = _uiState.value.replacements,
        filterPurePunctuation = false,
    ).transformedText

    fun setCatalogUrl(url: String) {
        val normalized = url.trim()
        preferences.edit().putString(KEY_CATALOG_URL, normalized).apply()
        _uiState.update { it.copy(catalogUrl = normalized) }
    }

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
                success = { pack ->
                    "已导入「${pack.name}」，共 ${pack.voices.size} 个音色"
                },
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
                success = { pack -> "已安装「${pack.name}」，共 ${pack.voices.size} 个音色" },
                failure = { error -> "下载失败：${error.message ?: error.javaClass.simpleName}" },
            )
        }
    }

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
                success = { pack -> "已安装「${pack.name}」，共 ${pack.voices.size} 个音色" },
                failure = { error -> "安装失败：${error.message ?: error.javaClass.simpleName}" },
            )
        }
    }

    fun removePack(pack: VoicePack) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { packStore.delete(pack.id) }
            if (removed) {
                VoicePackEvents.notifyChanged(getApplication())
                refreshAll()
            }
            _uiState.update {
                it.copy(packStatus = if (removed) "已删除「${pack.name}」" else "删除失败：${pack.name}")
            }
        }
    }

    fun voiceDisplayName(voiceId: String?): String =
        _uiState.value.voices.firstOrNull { it.systemName == voiceId }?.displayName
            ?: "未选择"

    override fun onCleared() {
        stopPlayback(updateState = false)
        synthesizer.shutdown()
        super.onCleared()
    }

    private fun finishPackOperation(
        result: Result<VoicePack>,
        success: (VoicePack) -> String,
        failure: (Throwable) -> String,
    ) {
        result.onSuccess {
            VoicePackEvents.notifyChanged(getApplication())
            refreshAll()
        }
        _uiState.update {
            it.copy(
                packBusyId = null,
                packProgress = -1,
                packStatus = result.fold(success, failure),
            )
        }
    }

    private fun updatePackBusy(id: String, status: String) {
        _uiState.update { it.copy(packBusyId = id, packProgress = -1, packStatus = status) }
    }

    private fun refreshPreview() {
        val state = _uiState.value
        val result = TextPipeline.build(
            text = state.text,
            narratorVoiceId = state.narratorVoiceId,
            dialogueVoiceId = state.dialogueVoiceId,
            roles = state.roles,
            replacements = state.replacements,
            filterPurePunctuation = state.punctuationFilter,
        )
        val names = state.voices.associate { it.systemName to it.displayName }
        val preview = result.segments.map { segment ->
            PreviewSegment(
                text = segment.text,
                isDialogue = segment.isDialogue,
                voiceName = names[segment.voiceId] ?: "未选择音色",
                roleName = segment.roleName,
            )
        }
        _uiState.update { it.copy(preview = preview, pipelineIssues = result.issues) }
    }

    private fun loadBaseState(): MainUiState = MainUiState(
        text = preferences.getString(KEY_LAST_TEXT, "").orEmpty(),
        speed = preferences.getInt(KEY_SPEED, 50),
        volume = preferences.getInt(KEY_VOLUME, 50),
        pitch = preferences.getInt(KEY_PITCH, 25),
        narratorVoiceId = settings.narratorVoice,
        dialogueVoiceId = settings.dialogueVoice,
        systemVoiceId = settings.systemVoice,
        punctuationFilter = settings.punctuationFilter,
        licenseConfigured = settings.hasLicense(),
        licenseMask = settings.licenseMask(),
        edgeEndpoint = settings.edgeEndpoint,
        compatibilityProxy = settings.compatibilityProxy,
        catalogUrl = preferences.getString(KEY_CATALOG_URL, "").orEmpty(),
    )

    private fun updateSlider(
        key: String,
        value: Int,
        transform: (MainUiState, Int) -> MainUiState,
    ) {
        preferences.edit().putInt(key, value).apply()
        _uiState.update { transform(it, value) }
    }

    private fun createAudioTrack(sampleRate: Int, audioFormat: Int, channels: Int): AudioTrack {
        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, audioFormat)
        val bufferSize = maxOf(minBuffer, sampleRate * 2 * channels / 4)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    private fun stopPlayback(updateState: Boolean) {
        playbackHandle?.cancel()
        playbackHandle = null
        releaseTrack()
        if (updateState) {
            _uiState.update { it.copy(isSpeaking = false, statusMessage = "已停止朗读") }
        }
    }

    private fun releaseTrack() {
        val track = synchronized(trackLock) {
            val current = audioTrack
            audioTrack = null
            current
        } ?: return
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun sliderToRate(value: Int): Float = if (value <= 50) {
        (0.5f + value / 100f).coerceIn(0.5f, 1f)
    } else {
        (1f + (value - 50) / 50f).coerceIn(1f, 2f)
    }

    private fun sliderToPitch(value: Int): Float =
        (0.5f + value / 50f).coerceIn(0.5f, 2f)

    private fun sliderToVolume(value: Int): Float =
        (value / 50f).coerceIn(0f, 1f)

    private companion object {
        const val TAG = "MainViewModel"
        const val KEY_LAST_TEXT = "lastText"
        const val KEY_SPEED = "speed"
        const val KEY_VOLUME = "volume"
        const val KEY_PITCH = "pitch"
        const val KEY_CATALOG_URL = "catalogUrl"
        const val PACK_BUSY_IMPORT = "import"
        const val PACK_BUSY_CATALOG = "catalog"
    }
}
