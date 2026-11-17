package app.noveltts.ui

import android.app.Application
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.noveltts.audio.BgmPlayer
import app.noveltts.data.backup.BackupCredentials
import app.noveltts.data.backup.BackupManager
import app.noveltts.data.backup.BackupRestorePreview
import app.noveltts.data.cache.AudioCache
import app.noveltts.data.cache.CacheManager
import app.noveltts.data.cache.CacheUsage
import app.noveltts.data.config.ReadingProfile
import app.noveltts.data.config.ReadingProfiles
import app.noveltts.data.config.ProfileSwitchPreview
import app.noveltts.data.config.RuleRecoveryCandidate
import app.noveltts.data.config.RulesImport
import app.noveltts.data.config.RuleDrafts
import app.noveltts.data.config.RuleEditorDraft
import app.noveltts.data.config.RulesImportPreview
import app.noveltts.data.config.RuleImportStrategy
import app.noveltts.data.config.RulesKind
import app.noveltts.data.config.RulesRepository
import app.noveltts.data.config.RulesStoreException
import app.noveltts.data.config.normalizeRuleGroups
import app.noveltts.data.config.nextRuleGroupOrder
import app.noveltts.data.settings.SettingsStore
import app.noveltts.ui.theme.ThemeMode
import app.noveltts.data.voicepack.CatalogEntry
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.data.voicepack.VoicePack
import app.noveltts.data.voicepack.VoicePackCatalog
import app.noveltts.data.voicepack.VoicePackEvents
import app.noveltts.data.voicepack.VoicePackStore
import app.noveltts.data.voicepack.VoiceRegistry
import app.noveltts.domain.BuiltInRole
import app.noveltts.domain.BuiltInRoleSettings
import app.noveltts.domain.BuiltSynthesisPlan
import app.noveltts.domain.ReplacementScope
import app.noveltts.domain.RoleMatchScope
import app.noveltts.domain.RolePlaybackSettings
import app.noveltts.domain.PipelineIssue
import app.noveltts.domain.PipelineSegment
import app.noveltts.domain.ReplacementRule
import app.noveltts.domain.RoleRule
import app.noveltts.domain.RuleGroup
import app.noveltts.domain.RuleSet
import app.noveltts.domain.SynthesisPlanBuilder
import app.noveltts.domain.TextPipeline
import app.noveltts.domain.RuleValidation
import app.noveltts.engine.EngineManager
import app.noveltts.engine.MultiSegmentSynthesizer
import app.noveltts.engine.RetryPolicy
import app.noveltts.engine.PlannedSegment
import app.noveltts.engine.SegmentPlaybackListener
import app.noveltts.service.PlaybackSession
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import app.noveltts.engine.api.SynthesisError
import app.noveltts.engine.api.SynthesisHandle
import app.noveltts.engine.api.SynthesisListener
import app.noveltts.forward.ForwardConfig
import app.noveltts.forward.ForwardController
import app.noveltts.ui.common.formatSize
import java.io.File
import java.util.UUID

enum class AppSection(val label: String) {
    WORKBENCH("工作台"),
    PACKS("语音包"),
    ROLES("角色"),
    REPLACEMENTS("替换"),
    SETTINGS("设置"),
}

internal const val PACK_BUSY_IMPORT = "import"

data class PreviewSegment(
    val text: String,
    val isDialogue: Boolean,
    val voiceId: String?,
    val voiceName: String,
    val roleName: String?,
    val id: String = "",
    val explanation: String = "",
)

data class MainUiState(
    val section: AppSection = AppSection.WORKBENCH,
    val text: String = "",
    val speed: Int = 50,
    val volume: Int = 50,
    val pitch: Int = 25,
    val builtInRoles: BuiltInRoleSettings = BuiltInRoleSettings(),
    val globalControlsExpanded: Boolean = true,
    val systemVoiceId: String = "",
    val voices: List<VoiceDescriptor> = emptyList(),
    val packs: List<VoicePack> = emptyList(),
    val roles: List<RoleRule> = emptyList(),
    val replacements: List<ReplacementRule> = emptyList(),
    val readingProfiles: List<ReadingProfile> = emptyList(),
    val profilesLoadError: String? = null,
    val activeProfileId: String = "",
    val recoveryCandidates: List<RuleRecoveryCandidate> = emptyList(),
    val roleGroups: List<RuleGroup> = listOf(RuleGroup.DEFAULT),
    val replacementGroups: List<RuleGroup> = listOf(RuleGroup.DEFAULT),
    /** 角色规则文件读取失败的原因；非空时禁止保存角色规则，避免覆盖待恢复的文件。 */
    val rolesLoadError: String? = null,
    val replacementsLoadError: String? = null,
    val preview: List<PreviewSegment> = emptyList(),
    val pipelineIssues: List<PipelineIssue> = emptyList(),
    val previewBusy: Boolean = false,
    val punctuationFilter: Boolean = true,
    val licenseConfigured: Boolean = false,
    val licenseMask: String = "",
    val edgeEndpoint: String = SettingsStore.DEFAULT_EDGE_ENDPOINT,
    val compatibilityProxy: Boolean = false,
    /** 外观：白天 / 夜间 / 跟随系统；见 [ThemeMode]。 */
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    val localTtsEnginePackage: String = "",
    val maxRetryCount: Int = SettingsStore.DEFAULT_MAX_RETRY_COUNT,
    val requestTimeoutSec: Int = SettingsStore.DEFAULT_REQUEST_TIMEOUT_SEC,
    val maxSegmentChars: Int = SettingsStore.DEFAULT_MAX_SEGMENT_CHARS,
    val silenceBetweenSegmentsMs: Int = SettingsStore.DEFAULT_SILENCE_MS,
    val cacheEnabled: Boolean = SettingsStore.DEFAULT_CACHE_ENABLED,
    val cacheMaxMb: Int = SettingsStore.DEFAULT_CACHE_MAX_MB,
    val bgmEnabled: Boolean = SettingsStore.DEFAULT_BGM_ENABLED,
    val bgmVolume: Int = SettingsStore.DEFAULT_BGM_VOLUME,
    val bgmLoop: Boolean = SettingsStore.DEFAULT_BGM_LOOP,
    val bgmDuck: Boolean = SettingsStore.DEFAULT_BGM_DUCK,
    val bgmUri: String = "",
    val forwardEnabled: Boolean = SettingsStore.DEFAULT_FORWARD_ENABLED,
    val forwardPort: Int = SettingsStore.DEFAULT_FORWARD_PORT,
    val forwardBind: String = SettingsStore.DEFAULT_FORWARD_BIND,
    val forwardToken: String = "",
    /** HTTP 转发服务是否真的在监听；由 [MainViewModel.refreshForwardServer] 刷新。 */
    val forwardRunning: Boolean = false,
    val forwardApplying: Boolean = false,
    val forwardError: String? = null,
    val forwardActiveRequests: Int = 0,
    val forwardQueuedRequests: Int = 0,
    val cacheHits: Long = 0,
    val cacheMisses: Long = 0,
    /** 缓存目录里 *.mp3 / audiocache 下 *.pcm / *.tmp 的占用，由 [MainViewModel.refreshCacheUsage] 刷新。 */
    val cacheBytes: Long = 0L,
    val cacheFileCount: Int = 0,
    val isSpeaking: Boolean = false,
    val isPreview: Boolean = false,
    val previewVoiceId: String? = null,
    val isPaused: Boolean = false,
    val completedSegments: Int = 0,
    val synthesisSegmentIndex: Int = -1,
    val playbackVoiceName: String = "",
    val playbackIsDialogue: Boolean = false,
    val playbackFallbackReason: String? = null,
    val playbackError: Boolean = false,
    /** 正在朗读的分段下标（从 0 开始）；-1 表示尚未开始。 */
    val currentSegmentIndex: Int = -1,
    val totalSegments: Int = 0,
    /** 单段试听中的分段下标；整篇朗读时为 null。 */
    val playingSegmentIndex: Int? = null,
    val statusMessage: String = "导入语音包后即可在系统 TTS 中选择 NovelTTS 音色。",
    val catalog: List<CatalogEntry> = emptyList(),
    val catalogUrl: String = "",
    val packBusyId: String? = null,
    val packProgress: Int = -1,
    val packStatus: String? = null,
) {
    val narratorVoiceId: String get() = builtInRoles.narrator.voiceId
    val dialogueVoiceId: String get() = builtInRoles.dialogue.voiceId
    val usableVoices: List<VoiceDescriptor> get() = voices.filter { it.usable }
    val isImportingPack: Boolean get() = packBusyId == PACK_BUSY_IMPORT
    val roleSet: RuleSet<RoleRule> get() = RuleSet(roleGroups, roles)
    val replacementSet: RuleSet<ReplacementRule> get() = RuleSet(replacementGroups, replacements)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("multitts", android.content.Context.MODE_PRIVATE)
    private val settings = SettingsStore(application)
    private val packStore = VoicePackStore(application)
    private val registry = VoiceRegistry(application, packStore)
    private val rulesRepository = RulesRepository(application)
    private val engines = EngineManager(application, settings)
    private val synthesizer = MultiSegmentSynthesizer(
        engines = engines,
        spoolDir = File(application.cacheDir, "spool"),
        cacheDir = File(application.cacheDir, AudioCache.DIR_NAME),
        cacheMaxMb = settings.cacheMaxMb,
        cacheEnabled = settings.cacheEnabled,
    )
    private val trackLock = Any()

    private var playbackHandle: SynthesisHandle? = null
    private var audioTrack: AudioTrack? = null
    private var previewJob: Job? = null
    private var previewGeneration = 0L
    private val playbackGeneration = AtomicLong()
    private var playbackLease: AutoCloseable? = null
    @Volatile private var writtenFrames = 0L
    @Volatile private var readingIndex = 0
    private data class ReadingSession(val state: MainUiState, val segments: List<PlannedSegment>)
    @Volatile private var readingSession: ReadingSession? = null
    @Volatile private var readingHadError = rulesRepository.withConfigurationLock { preferences.getBoolean("readingError", false) }
    @Volatile private var activePlaybackSettings: MainUiState? = null

    /** 背景音乐播放器；懒建，没用过背景音乐就不创建线程与 MediaPlayer。 */
    private var bgmPlayer: BgmPlayer? = null

    /** Legado HTTP 转发服务；只在设置里启用时才真正监听端口。 */


    private val _uiState = MutableStateFlow(loadBaseState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            ForwardController.state.collect { runtime ->
                _uiState.update { it.copy(forwardRunning = runtime.running, forwardApplying = runtime.applying,
                    forwardError = runtime.error, forwardActiveRequests = runtime.activeRequests,
                    forwardQueuedRequests = runtime.queuedRequests, forwardEnabled = settings.forwardEnabled,
                    forwardPort = settings.forwardPort, forwardBind = settings.forwardBind, forwardToken = settings.forwardToken) }
            }
        }
        refreshAll()
    }

    fun selectSection(section: AppSection) {
        _uiState.update { it.copy(section = section) }
    }

    fun updateText(value: String) {
        _uiState.update { it.copy(text = value) }
        rulesRepository.withConfigurationLock { preferences.edit().putString(KEY_LAST_TEXT, value).apply() }
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

    fun selectNarrator(voiceId: String) = selectBuiltInVoice(BuiltInRole.NARRATOR, voiceId)

    fun selectDialogue(voiceId: String) = selectBuiltInVoice(BuiltInRole.DIALOGUE, voiceId)

    private fun selectBuiltInVoice(role: BuiltInRole, voiceId: String) =
        updateBuiltInRole(role, _uiState.value.builtInRoles[role].copy(voiceId = voiceId))

    fun updateBuiltInRole(role: BuiltInRole, settings: RolePlaybackSettings) {
        val saved = settings.normalized()
        this.settings.commitBuiltInRole(role, saved)
        _uiState.update { it.copy(builtInRoles = it.builtInRoles.with(role, saved)) }
        refreshPreview()
    }

    /**
     * 只改倍率、保留已保存的音色：界面上的音色可能是原音色暂不可用时的回落值，
     * 不能因为用户调了语速就把回落值写成新的选择。
     */
    fun updateBuiltInMultipliers(role: BuiltInRole, rate: Float, pitch: Float, volume: Float) {
        val saved = settings.builtInRoles()[role].copy(rate = rate, pitch = pitch, volume = volume).normalized()
        settings.commitBuiltInRole(role, saved)
        _uiState.update { it.copy(builtInRoles = it.builtInRoles.with(role, saved.copy(voiceId = it.builtInRoles[role].voiceId))) }
        refreshPreview()
    }

    fun setGlobalControlsExpanded(expanded: Boolean) {
        rulesRepository.withConfigurationLock {
            preferences.edit().putBoolean(KEY_GLOBAL_CONTROLS_EXPANDED, expanded).apply()
        }
        _uiState.update { it.copy(globalControlsExpanded = expanded) }
    }

    /** 主界面快速添加角色：默认匹配对话前一句旁白里的称呼，完整选项仍在角色规则编辑器。 */
    fun addQuickRole(name: String, keyword: String, voiceId: String): Result<RoleRule> = runCatching {
        val state = _uiState.value
        check(state.rolesLoadError == null) { "角色规则文件无法读取，请先恢复" }
        val label = name.trim().take(60)
        require(label.isNotBlank()) { "请填写角色名称" }
        require(state.roles.none { it.name.trim().equals(label, ignoreCase = true) }) { "已有同名角色" }
        require(state.usableVoices.any { it.systemName == voiceId }) { "请选择可用音色" }
        val literal = keyword.trim().ifBlank { label }
        val rule = RoleRule(
            name = label,
            pattern = if (REGEX_META.containsMatchIn(literal)) Regex.escape(literal) else literal,
            voiceIds = listOf(voiceId),
            matchScope = RoleMatchScope.PREVIOUS_NARRATION,
            contextChars = QUICK_ROLE_CONTEXT_CHARS,
        )
        check(saveRole(rule)) { _uiState.value.statusMessage }
        _uiState.value.roles.first { it.id == rule.id }
    }

    /** 删除角色并连带删除只对它生效的替换规则；两个规则文件在同一事务内写入。 */
    fun removeRoleWithReplacements(roleId: String): Result<String> = runCatching {
        // 在配置锁内重新读盘：界面状态可能落后于刚完成的还原或其它规则写入。
        val (role, removed) = rulesRepository.withConfigurationLock {
            val roles = rulesRepository.loadRoles()
            val replacements = rulesRepository.loadReplacements()
            val role = roles.items.firstOrNull { it.id == roleId } ?: error("角色不存在")
            val (targeted, kept) = replacements.items.partition {
                it.scope == ReplacementScope.ROLE && it.targetRoleId == roleId
            }
            transactionalConfiguration {
                rulesRepository.restoreAll(
                    roles.copy(items = roles.items.filterNot { it.id == roleId }),
                    replacements.copy(items = kept),
                )
            }
            role to targeted.size
        }
        refreshAll()
        ("已删除角色「${role.name}」" + if (removed == 0) "" else "及 $removed 条定向替换规则")
            .also { message -> _uiState.update { it.copy(statusMessage = message) } }
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
        runCatching { settings.setLicense(value) }.onFailure { error ->
            _uiState.update { it.copy(statusMessage = "许可证未保存：${error.message}") }; return
        }
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

    fun setThemeMode(mode: ThemeMode) {
        if (_uiState.value.themeMode == mode) return
        settings.themeMode = mode.name
        _uiState.update { it.copy(themeMode = ThemeMode.fromStorage(settings.themeMode)) }
    }

    /** 本地系统 TTS 包名：语音包里写 "android-<包名>" 的引擎代码会委托给它。 */
    fun setLocalTtsEnginePackage(value: String) {
        settings.localTtsEnginePackage = value.trim()
        val saved = settings.localTtsEnginePackage
        // 音色的可用性跟着包名变：改完立刻重算，别让界面继续显示旧状态。
        refreshAll()
        _uiState.update {
            it.copy(
                localTtsEnginePackage = saved,
                statusMessage = if (saved.isBlank()) {
                    "已清除本地引擎包名。"
                } else {
                    "本地引擎包名已保存，下一次合成生效。"
                },
            )
        }
    }

    fun clearRecentText() {
        rulesRepository.withConfigurationLock { preferences.edit().remove(KEY_LAST_TEXT).apply() }
        _uiState.update { it.copy(text = "") }
        refreshPreview()
    }

    fun speak() {
        val state = _uiState.value
        if (state.usableVoices.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "还没有可用音色，请先导入语音包或配置离线许可证。") }
            return
        }
        stopPlayback(updateState = false)
        clearReadingSnapshot()
        val generation = playbackGeneration.get()
        _uiState.update { it.copy(isSpeaking = true, isPaused = false, statusMessage = "正在准备朗读计划…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { buildPlan(state) } }
            if (generation != playbackGeneration.get()) return@launch
            val plan = result.getOrNull()
            if (plan == null || plan.segments.isEmpty()) {
                _uiState.update { it.copy(isSpeaking = false, statusMessage = result.exceptionOrNull()?.message ?: "没有可朗读的文本片段。") }
                return@launch
            }
            playSegments(plan.segments, plan.pipeline.issues, "正在合成朗读…", playbackState = state)
        }
    }

    /** 只朗读分段预览中的某一段：用于逐段检查音色、断句是否正确。 */
    fun previewSegment(index: Int) {
        if (_uiState.value.previewBusy) return
        if (_uiState.value.isPreview && _uiState.value.playingSegmentIndex == index) { stopPreview(); return }
        val state = _uiState.value
        if (state.usableVoices.isEmpty()) { _uiState.update { it.copy(statusMessage = "没有可用音色") }; return }
        if (state.isSpeaking && !state.isPreview) pause()
        stopPlayback(updateState = false)
        val generation = playbackGeneration.get()
        _uiState.update { it.copy(isSpeaking = true, isPreview = true, playingSegmentIndex = index, statusMessage = "正在准备单段试听…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { buildPlan(state) } }
            if (generation != playbackGeneration.get()) return@launch
            val plan = result.getOrNull()
            val segment = plan?.pipeline?.segments?.getOrNull(index)
            val planned = plan?.segments?.firstOrNull { it.segment.id == segment?.id }
            if (planned == null) {
                stopPreview()
                _uiState.update { it.copy(statusMessage = result.exceptionOrNull()?.message ?: "这一段没有可用音色") }
                return@launch
            }
            playSegments(listOf(planned), requireNotNull(plan).pipeline.issues, "正在试听第 ${index + 1} 段…",
                segmentIndex = index, isPreview = true, playbackState = state)
        }
    }
    /** 用固定示例句试听单个音色；倍率用于按某个角色的设置试听。 */
    fun previewVoice(voice: VoiceDescriptor, rate: Float = 1f, pitch: Float = 1f, volume: Float = 1f) {
        if (!voice.usable) return
        val multipliers = RolePlaybackSettings(voice.systemName, rate, pitch, volume).normalized()
        val segment = PipelineSegment(
            text = "你好，我是${voice.displayName}。这是一段试听，用来确认这个声音是否合适。",
            isDialogue = false,
            voiceId = voice.systemName,
            roleName = null,
            speechRate = multipliers.rate,
            speechPitch = multipliers.pitch,
            speechVolume = multipliers.volume,
        )
        playSegments(
            segments = listOf(PlannedSegment(segment, voice)),
            status = "正在试听 ${voice.displayName}…",
            isPreview = true,
        )
    }

    private fun buildPlan(state: MainUiState): BuiltSynthesisPlan = SynthesisPlanBuilder.build(
        text = state.text,
        builtInRoles = state.builtInRoles,
        roles = state.roleSet,
        replacements = state.replacementSet,
        filterPurePunctuation = state.punctuationFilter,
        voices = state.voices,
        maxSegmentChars = state.maxSegmentChars,
    )

    /** Reading and preview use immutable snapshots and generation-guarded callbacks. */
    private fun playSegments(
        segments: List<PlannedSegment>, issues: List<PipelineIssue> = emptyList(), status: String,
        segmentIndex: Int? = null, isPreview: Boolean = false,
        playbackState: MainUiState = _uiState.value, startIndex: Int = 0,
    ) {
        if (isPreview && _uiState.value.isSpeaking && !_uiState.value.isPreview) pause()
        stopPlayback(updateState = false)
        if (!isPreview && startIndex == 0 && readingSession == null) {
            readingSession = ReadingSession(playbackState, segments)
            readingIndex = 0
            saveReadingSnapshot(playbackState, segments)
        }
        val generation = playbackGeneration.get()
        val finished = AtomicBoolean(false)
        val total = if (isPreview) segments.size else readingSession?.segments?.size ?: segments.size
        activePlaybackSettings = playbackState
        writtenFrames = 0L
        if (!isPreview) { readingHadError = false; saveReadingIndex() }
        _uiState.update { it.copy(isSpeaking = true, isPreview = isPreview,
            previewVoiceId = if (isPreview) segments.firstOrNull()?.voice?.systemName else null,
            isPaused = false, playbackError = false, currentSegmentIndex = if (isPreview) -1 else startIndex,
            playbackVoiceName = segments.first().voice.displayName, playbackIsDialogue = segments.first().segment.isDialogue,
            playbackFallbackReason = segments.first().segment.fallbackReason,
            completedSegments = if (isPreview) 0 else startIndex, totalSegments = total,
            playingSegmentIndex = segmentIndex, statusMessage = status, pipelineIssues = issues) }
        refreshBgm()
        synthesizer.configure(RetryPolicy(maxAttemptsPerVoice = playbackState.maxRetryCount + 1),
            playbackState.silenceBetweenSegmentsMs, playbackState.requestTimeoutSec)
        synthesizer.configureCache(playbackState.cacheEnabled, playbackState.cacheMaxMb)
        playbackLease = PlaybackSession.open(getApplication(), segments.any { it.voice.isOnline }) {
            if (generation == playbackGeneration.get()) stop()
        }
        val handle = synthesizer.synthesize(segments, sliderToRate(playbackState.speed),
            sliderToPitch(playbackState.pitch), sliderToVolume(playbackState.volume),
            listener = playbackListener(generation, isPreview, finished),
            options = app.noveltts.engine.api.EngineRequestOptions(playbackState.edgeEndpoint, playbackState.requestTimeoutSec, playbackState.localTtsEnginePackage),
            onProgress = { index, _, _ ->
                updatePlaybackState(generation) { _uiState.update { it.copy(
                    synthesisSegmentIndex = index + startIndex,
                    statusMessage = if (isPreview) "正在合成试听 · ${segments[index].voice.displayName}"
                        else "正在合成第 ${index + startIndex + 1}/$total 段 · ${segments[index].voice.displayName}") } }
            },
            onPlayback = object : SegmentPlaybackListener {
                override fun onVoiceSelected(index: Int, voice: VoiceDescriptor, fallbackReason: String?) {
                    updatePlaybackState(generation) { _uiState.update { it.copy(playbackVoiceName = voice.displayName,
                        playbackIsDialogue = segments[index].segment.isDialogue, playbackFallbackReason = fallbackReason) } }
                }
                override fun onPlaybackStart(index: Int) {
                    updatePlaybackState(generation) {
                    if (!isPreview) { readingIndex = startIndex + index; saveReadingIndex() }
                    _uiState.update { it.copy(currentSegmentIndex = startIndex + index,
                        statusMessage = if (isPreview) "正在播放试听 · ${it.playbackVoiceName}"
                            else "正在播放第 ${startIndex + index + 1}/$total 段 · ${it.playbackVoiceName}") } }
                }
                override fun onPlaybackComplete(index: Int) {
                    drainTrack(generation)
                    updatePlaybackState(generation) {
                    if (!isPreview) { readingIndex = (startIndex + index + 1).coerceAtMost(total - 1); saveReadingIndex() }
                    _uiState.update { it.copy(completedSegments = startIndex + index + 1) } }
                }
            })
        if (generation == playbackGeneration.get() && !finished.get()) playbackHandle = handle else handle.cancel()
    }

    private fun updatePlaybackState(generation: Long, action: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { if (generation == playbackGeneration.get()) action() }
    }

    private fun playbackListener(generation: Long, isPreview: Boolean, finished: AtomicBoolean): SynthesisListener =
        object : SynthesisListener {
            override fun onStart(sampleRateHz: Int, audioFormat: Int, channelCount: Int) {
                if (generation != playbackGeneration.get()) return
                val track = runCatching { createAudioTrack(sampleRateHz, audioFormat, channelCount) }.getOrElse {
                    onError(SynthesisError.SYNTHESIS_FAILED, "无法创建音频输出：${it.message}"); return
                }
                synchronized(trackLock) {
                    if (generation != playbackGeneration.get()) { track.release(); return }
                    audioTrack = track
                }
                runCatching { track.play() }.onFailure { onError(SynthesisError.SYNTHESIS_FAILED, it.message) }
            }
            override fun onAudio(pcm: ByteArray, offset: Int, length: Int) {
                if (finished.get()) return
                val track = synchronized(trackLock) { if (generation == playbackGeneration.get()) audioTrack else null } ?: return
                var position = offset
                while (position < offset + length && generation == playbackGeneration.get()) {
                    val count = runCatching { track.write(pcm, position, offset + length - position, AudioTrack.WRITE_BLOCKING) }
                        .getOrDefault(AudioTrack.ERROR_DEAD_OBJECT)
                    if (count <= 0) {
                        if (generation == playbackGeneration.get()) {
                            onError(SynthesisError.SYNTHESIS_FAILED, "音频输出失败（code=$count）")
                            playbackHandle?.cancel()
                        }
                        return
                    }
                    position += count
                    writtenFrames += count / 2
                }
            }
            override fun onComplete() {
                if (generation != playbackGeneration.get() || !finished.compareAndSet(false, true)) return
                val drained = runCatching { drainTrack(generation) }
                finishPlayback(generation, isPreview, drained.exceptionOrNull()?.message)
            }
            override fun onError(code: Int, message: String?) {
                if (generation != playbackGeneration.get() || !finished.compareAndSet(false, true)) return
                finishPlayback(generation, isPreview, message ?: "合成失败（code=$code）")
            }
        }

    private fun drainTrack(generation: Long) {
        val track = synchronized(trackLock) { audioTrack } ?: return
        val remaining = writtenFrames - (runCatching { track.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(writtenFrames))
        val deadline = android.os.SystemClock.elapsedRealtime() + remaining.coerceAtLeast(0) * 1000 / 24000 + 2500
        while (generation == playbackGeneration.get()) {
            val played = runCatching { track.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(writtenFrames)
            if (played >= writtenFrames) return
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "音频播放等待超时" }
            Thread.sleep(12)
        }
    }

    private fun finishPlayback(generation: Long, preview: Boolean, error: String?) { viewModelScope.launch(Dispatchers.Main.immediate) {
        if (generation != playbackGeneration.get()) return@launch
        releaseTrack(); playbackHandle = null
        playbackLease?.close(); playbackLease = null
        stopBgm(); activePlaybackSettings = null
        if (!preview) {
            readingHadError = error != null
            if (error == null) clearReadingSnapshot() else saveReadingIndex()
        }
        val resumable = readingSession != null || preferences.contains("readingSnapshot")
        _uiState.update { it.copy(isSpeaking = false, isPreview = false, previewVoiceId = null,
            isPaused = resumable, playingSegmentIndex = null, playbackError = resumable && readingHadError,
            statusMessage = error?.let {
                if (preview) "试听失败：$it" + if (resumable) "。原朗读进度已保留，可继续朗读。" else ""
                else "朗读失败：$it。可以重试当前段或使用备用音色。"
            }
                ?: if (preview && resumable) "试听结束，点击继续从第 ${readingIndex + 1} 段恢复"
                else if (preview) "试听完成" else "朗读完成") }
        refreshCacheUsage()
    } }

    fun stop() {
        stopPlayback(updateState = true)
        clearReadingSnapshot()
        _uiState.update { it.copy(isPaused = false, isPreview = false, previewVoiceId = null, playbackError = false) }
    }
    fun stopPreview() {
        if (!_uiState.value.isPreview) return
        stopPlayback(updateState = true)
        val resumable = readingSession != null || preferences.contains("readingSnapshot")
        _uiState.update { it.copy(isPaused = resumable,
            isPreview = false, previewVoiceId = null, playbackError = resumable && readingHadError,
            statusMessage = if (resumable) "试听已停止，可继续原来的朗读" else "试听已停止") }
    }
    fun pause() {
        if (_uiState.value.isPreview) { stopPreview(); return }
        if (!_uiState.value.isSpeaking || readingSession == null) return
        saveReadingIndex()
        stopPlayback(updateState = false)
        _uiState.update { it.copy(isSpeaking = false, isPaused = true,
            statusMessage = "已暂停，继续时从第 ${readingIndex + 1} 段开头恢复") }
    }
    fun resume() {
        if (_uiState.value.isSpeaking && !_uiState.value.isPreview) return
        withReadingSession { session ->
            playSegments(session.segments.drop(readingIndex), status = "正在继续朗读…", playbackState = session.state, startIndex = readingIndex)
        }
    }
    fun previousSegment() { moveReading(-1) }
    fun nextSegment() { moveReading(1) }
    private fun moveReading(delta: Int) = withReadingSession { session ->
        readingIndex = (readingIndex + delta).coerceIn(session.segments.indices)
        saveReadingIndex()
        playSegments(session.segments.drop(readingIndex), status = "正在切换片段…", playbackState = session.state, startIndex = readingIndex)
    }
    private fun withReadingSession(action: (ReadingSession) -> Unit) {
        val cached = readingSession
        val savedIndex = rulesRepository.withConfigurationLock {
            if (cached != null) readingIndex else preferences.getInt("readingIndex", 0)
        }
        stopPreview()
        stopPlayback(updateState = false)
        val generation = playbackGeneration.get()
        _uiState.update { it.copy(isSpeaking = true, isPreview = false, isPaused = false, statusMessage = "正在恢复朗读计划…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { cached ?: restoreReadingSnapshot() } }
            if (generation != playbackGeneration.get()) return@launch
            val session = result.getOrNull()
            if (session == null) {
                _uiState.update { it.copy(isSpeaking = false, isPaused = preferences.contains("readingSnapshot"),
                    statusMessage = "无法恢复朗读：${result.exceptionOrNull()?.message ?: "没有保存的进度"}") }
                return@launch
            }
            readingSession = session
            readingIndex = savedIndex.coerceIn(session.segments.indices)
            runCatching { action(session) }.onFailure { error ->
                stopPlayback(updateState = false)
                _uiState.update { it.copy(isSpeaking = false, isPaused = true, playbackError = true,
                    statusMessage = "朗读未开始：${error.message}") }
            }
        }
    }
    fun retryCurrentSegment() { resume() }
    fun useStandbyVoice() = withReadingSession { session ->
        val current = session.segments[readingIndex]
        val alternative = _uiState.value.voices.firstOrNull {
            it.usable && it.systemName != current.voice.systemName && it.localeTag == current.voice.localeTag &&
                (!it.engineCode.startsWith("android-") || it.engineCode == "android-${session.state.localTtsEnginePackage}")
        }
        if (alternative == null) {
            _uiState.update { it.copy(isSpeaking = false, isPaused = true, statusMessage = "没有可用的备用音色") }
        } else {
            val updated = session.copy(segments = session.segments.mapIndexed { index, planned ->
                if (index == readingIndex) planned.copy(voice = alternative,
                    segment = planned.segment.copy(voiceId = alternative.systemName, fallbackReason = "用户选择备用音色")) else planned })
            readingSession = updated
            rulesRepository.withConfigurationLock {
                val root = JSONObject(preferences.getString("readingSnapshot", "{}").orEmpty())
                val overrides = root.optJSONObject("overrides") ?: JSONObject()
                overrides.put(readingIndex.toString(), alternative.systemName)
                root.put("overrides", overrides)
                preferences.edit().putString("readingSnapshot", root.toString()).apply()
            }
            playSegments(updated.segments.drop(readingIndex), status = "正在使用备用音色…", playbackState = updated.state, startIndex = readingIndex)
        }
    }
    private fun saveReadingSnapshot(state: MainUiState, segments: List<PlannedSegment>) {
        val root = JSONObject().put("text", state.text)
            .put("roles", RulesImport.exportRoles(state.roleSet)).put("replacements", RulesImport.exportReplacements(state.replacementSet))
            .put("narrator", state.narratorVoiceId).put("dialogue", state.dialogueVoiceId)
            .put("roleMultipliers", JSONObject(SettingsStore.builtInMultiplierValues(state.builtInRoles)
                .mapValues { (it.value as Float).toDouble() }))
            .put("speed", state.speed).put("pitch", state.pitch).put("volume", state.volume)
            .put("maxSegmentChars", state.maxSegmentChars).put("punctuationFilter", state.punctuationFilter)
            .put("silence", state.silenceBetweenSegmentsMs).put("retry", state.maxRetryCount).put("timeout", state.requestTimeoutSec)
            .put("bgmEnabled", state.bgmEnabled).put("bgmUri", state.bgmUri).put("bgmVolume", state.bgmVolume)
            .put("bgmLoop", state.bgmLoop).put("bgmDuck", state.bgmDuck)
            .put("cacheEnabled", state.cacheEnabled).put("cacheMaxMb", state.cacheMaxMb)
            .put("edgeEndpoint", state.edgeEndpoint).put("localEngine", state.localTtsEnginePackage)
            .put("segments", app.noveltts.data.config.ReadingSnapshot.encode(segments))
        rulesRepository.withConfigurationLock { preferences.edit().putString("readingSnapshot", root.toString()).putInt("readingIndex", 0).putInt("readingTotal", segments.size).apply() }
    }
    private fun saveReadingIndex() { rulesRepository.withConfigurationLock {
        preferences.edit().putInt("readingIndex", readingIndex).putBoolean("readingError", readingHadError).apply()
    } }
    private fun clearReadingSnapshot() {
        readingSession = null
        readingHadError = false
        rulesRepository.withConfigurationLock { preferences.edit().remove("readingSnapshot").remove("readingIndex").remove("readingTotal").remove("readingError").apply() }
    }
    private fun restoreReadingSnapshot(): ReadingSession {
        val root = JSONObject(preferences.getString("readingSnapshot", null) ?: error("没有保存的朗读快照"))
        val roles = RulesImport.previewRoles(root.getString("roles"), emptyList(), emptyList())
        val replacements = RulesImport.previewReplacements(root.getString("replacements"), emptyList(), emptyList())
        val multipliers = root.optJSONObject("roleMultipliers")
        fun builtIn(role: BuiltInRole, voiceKey: String): RolePlaybackSettings {
            val keys = SettingsStore.builtInRoleKeys(role)
            fun value(key: String) = multipliers?.optDouble(key, 1.0)?.toFloat() ?: 1f
            return RolePlaybackSettings(root.optString(voiceKey), value(keys.rate), value(keys.pitch), value(keys.volume)).normalized()
        }
        val state = _uiState.value.copy(text = root.getString("text"), roles = roles.entries.map { it.rule }, roleGroups = roles.groups,
            replacements = replacements.entries.map { it.rule }, replacementGroups = replacements.groups,
            builtInRoles = BuiltInRoleSettings(builtIn(BuiltInRole.NARRATOR, "narrator"), builtIn(BuiltInRole.DIALOGUE, "dialogue")),
            speed = root.optInt("speed", 50), pitch = root.optInt("pitch", 25), volume = root.optInt("volume", 50),
            maxSegmentChars = root.optInt("maxSegmentChars", 500), punctuationFilter = root.optBoolean("punctuationFilter", true),
            silenceBetweenSegmentsMs = root.optInt("silence", 80), maxRetryCount = root.optInt("retry", 1), requestTimeoutSec = root.optInt("timeout", 60),
            bgmEnabled = root.optBoolean("bgmEnabled"), bgmUri = root.optString("bgmUri"), bgmVolume = root.optInt("bgmVolume", 30),
            bgmLoop = root.optBoolean("bgmLoop", true), bgmDuck = root.optBoolean("bgmDuck", true),
            cacheEnabled = root.optBoolean("cacheEnabled", true), cacheMaxMb = root.optInt("cacheMaxMb", 100),
            edgeEndpoint = root.optString("edgeEndpoint", SettingsStore.DEFAULT_EDGE_ENDPOINT), localTtsEnginePackage = root.optString("localEngine"))
        val frozen = root.optJSONArray("segments")?.let {
            app.noveltts.data.config.ReadingSnapshot.decode(it, state.voices, state.narratorVoiceId, state.dialogueVoiceId)
        } ?: buildPlan(state).segments
        check(frozen.isNotEmpty()) { "快照没有可用音色" }
        val overrides = root.optJSONObject("overrides")
        val segments = frozen.mapIndexed { index, planned ->
            val override = overrides?.optString(index.toString()).orEmpty()
            val voice = state.voices.firstOrNull { it.systemName == override && it.usable }
            if (voice == null) planned else planned.copy(voice = voice, segment = planned.segment.copy(voiceId = override, fallbackReason = "用户选择备用音色"))
        }
        return ReadingSession(state, segments)
    }

    fun refreshAll() = rulesRepository.withConfigurationLock {
        val packs = packStore.installed()
        val voices = registry.voices(settings.hasLicense(), settings.localTtsEnginePackage)
        val narrator = voices.firstOrNull {
            it.usable && it.systemName == settings.narratorVoice
        } ?: voices.firstOrNull { it.usable }
        val dialogue = voices.firstOrNull {
            it.usable && it.systemName == settings.dialogueVoice
        } ?: narrator
        val systemVoice = voices.firstOrNull {
            it.usable && it.systemName == settings.systemVoice
        } ?: narrator
        val profiles = runCatching { ReadingProfiles.decode(preferences.getString(ReadingProfiles.KEY, null)) }
        val roles = loadRules { rulesRepository.loadRoles() }
        val replacements = loadRules { rulesRepository.loadReplacements() }
        _uiState.update {
            it.copy(
                packs = packs,
                readingProfiles = profiles.getOrNull() ?: it.readingProfiles,
                profilesLoadError = profiles.exceptionOrNull()?.message,
                activeProfileId = preferences.getString("activeProfileId", "").orEmpty(),
                voices = voices,
                roles = roles.getOrNull()?.items ?: it.roles,
                roleGroups = roles.getOrNull()?.groups ?: it.roleGroups,
                rolesLoadError = roles.exceptionOrNull()?.message,
                replacements = replacements.getOrNull()?.items ?: it.replacements,
                replacementGroups = replacements.getOrNull()?.groups ?: it.replacementGroups,
                replacementsLoadError = replacements.exceptionOrNull()?.message,
                builtInRoles = settings.builtInRoles().withVoices(narrator?.systemName, dialogue?.systemName),
                systemVoiceId = systemVoice?.systemName.orEmpty(),
                punctuationFilter = settings.punctuationFilter,
                licenseConfigured = settings.hasLicense(),
                licenseMask = settings.licenseMask(),
                edgeEndpoint = settings.edgeEndpoint,
                compatibilityProxy = settings.compatibilityProxy,
                localTtsEnginePackage = settings.localTtsEnginePackage,
                maxRetryCount = settings.maxRetryCount,
                requestTimeoutSec = settings.requestTimeoutSec,
                maxSegmentChars = settings.maxSegmentChars,
                silenceBetweenSegmentsMs = settings.silenceBetweenSegmentsMs,
                cacheEnabled = settings.cacheEnabled,
                cacheMaxMb = settings.cacheMaxMb,
                bgmEnabled = settings.bgmEnabled,
                bgmVolume = settings.bgmVolume,
                bgmLoop = settings.bgmLoop,
                bgmDuck = settings.bgmDuck,
                bgmUri = settings.bgmUri,
                forwardEnabled = settings.forwardEnabled,
                forwardPort = settings.forwardPort,
                forwardBind = settings.forwardBind,
                forwardToken = settings.forwardToken,
                catalogUrl = preferences.getString(KEY_CATALOG_URL, "").orEmpty(),
            )
        }
        refreshPreview()
        refreshForwardServer()
    }

    /** 保存角色规则；[newGroup] 非空时与规则一起落盘，避免"先建组再保存"两次写入。 */
    fun saveRole(rule: RoleRule, newGroup: RuleGroup? = null): Boolean {
        RuleValidation.roleError(rule)?.let { error ->
            _uiState.update { it.copy(statusMessage = error) }; return false
        }
        val state = _uiState.value
        val created = newGroup?.let { group ->
            if (state.roleGroups.any { it.id == group.id }) group
            else group.copy(order = nextRuleGroupOrder(state.roleGroups))
        }
        val groups = if (created == null) state.roleGroups else state.roleGroups + created
        val known = groups.mapTo(HashSet()) { it.id }
        val targetGroup = when {
            created != null -> created.id
            rule.groupId in known -> rule.groupId
            else -> RuleGroup.DEFAULT_ID
        }
        val existing = state.roles.firstOrNull { it.id == rule.id }
        val sortIndex = if (existing != null && existing.groupId == targetGroup) {
            existing.sortIndex
        } else {
            (state.roles.filter { it.groupId == targetGroup && it.id != rule.id }
                .maxOfOrNull { it.sortIndex } ?: -1) + 1
        }
        return saveRoleSet(
            RuleSet(
                groups = groups,
                items = state.roles.filterNot { it.id == rule.id } +
                    rule.copy(groupId = targetGroup, sortIndex = sortIndex),
            ),
            "角色规则「${rule.name}」已保存",
        )
    }

    fun deleteRole(rule: RoleRule) {
        saveRoleSet(
            RuleSet(_uiState.value.roleGroups, _uiState.value.roles.filterNot { it.id == rule.id }),
            "已删除角色规则「${rule.name}」",
        )
    }

    fun toggleRole(rule: RoleRule, enabled: Boolean) {
        saveRole(rule.copy(enabled = enabled))
    }

    /** 保存替换规则；[newGroup] 非空时与规则一起落盘。 */
    fun saveReplacement(rule: ReplacementRule, newGroup: RuleGroup? = null): Boolean {
        RuleValidation.replacementError(rule)?.let { error ->
            _uiState.update { it.copy(statusMessage = error) }; return false
        }
        val state = _uiState.value
        val created = newGroup?.let { group ->
            if (state.replacementGroups.any { it.id == group.id }) group
            else group.copy(order = nextRuleGroupOrder(state.replacementGroups))
        }
        val groups = if (created == null) state.replacementGroups else state.replacementGroups + created
        val known = groups.mapTo(HashSet()) { it.id }
        val targetGroup = when {
            created != null -> created.id
            rule.groupId in known -> rule.groupId
            else -> RuleGroup.DEFAULT_ID
        }
        val groupItems = state.replacements.filter { it.groupId == targetGroup && it.id != rule.id }.sortedBy { it.sortIndex }.toMutableList()
        groupItems.add(rule.sortIndex.coerceIn(0, groupItems.size), rule.copy(groupId = targetGroup))
        val reordered = groupItems.mapIndexed { index, item -> item.copy(sortIndex = index) }
        return saveReplacementSet(
            RuleSet(
                groups = groups,
                items = state.replacements.filterNot { it.id == rule.id || it.groupId == targetGroup } + reordered,
            ),
            "替换规则「${rule.name}」已保存",
        )
    }

    fun deleteReplacement(rule: ReplacementRule) {
        saveReplacementSet(
            RuleSet(
                _uiState.value.replacementGroups,
                _uiState.value.replacements.filterNot { it.id == rule.id },
            ),
            "已删除替换规则「${rule.name}」",
        )
    }

    fun toggleReplacement(rule: ReplacementRule, enabled: Boolean) {
        saveReplacement(rule.copy(enabled = enabled))
    }

    fun testReplacements(text: String, draft: ReplacementRule? = null, includeSaved: Boolean = true): String {
        val state = _uiState.value
        val rules = if (draft == null) state.replacements else
            (if (includeSaved) state.replacements.filterNot { it.id == draft.id } else emptyList()) +
                draft.copy(enabled = if (includeSaved) draft.enabled else true)
        val result = TextPipeline.build(
        text = text,
        narratorVoiceId = null,
        dialogueVoiceId = null,
        roles = state.roles,
        replacements = rules,
        filterPurePunctuation = false,
        roleGroups = state.roleGroups,
        replacementGroups = if (includeSaved) state.replacementGroups else emptyList(),
    )
        return buildString {
            append(result.segments.joinToString("\n") { it.text })
            result.replacements.forEach { append("\n${it.ruleName}：${it.before} → ${it.after}") }
            result.issues.forEach { append("\n${it.message}") }
        }
    }

    fun rememberRoleDraft(rule: RoleRule, newGroupName: String, fields: Map<String, String>) {
        rulesRepository.withConfigurationLock { preferences.edit().putString("roleDraft", RuleDrafts.encodeRole(rule, _uiState.value.roleGroups, newGroupName, fields))
            .putString("roleDraftGroup", newGroupName).apply() }
    }
    fun rememberReplacementDraft(rule: ReplacementRule, newGroupName: String, fields: Map<String, String>) {
        rulesRepository.withConfigurationLock { preferences.edit().putString("replacementDraft", RuleDrafts.encodeReplacement(rule, _uiState.value.replacementGroups, newGroupName, fields))
            .putString("replacementDraftGroup", newGroupName).apply() }
    }
    fun roleDraft(): RuleEditorDraft<RoleRule>? = rulesRepository.withConfigurationLock { runCatching {
        val text = preferences.getString("roleDraft", null) ?: return@withConfigurationLock null
        RuleDrafts.readRole(text, preferences.getString("roleDraftGroup", "").orEmpty())
    }.getOrNull() }
    fun replacementDraft(): RuleEditorDraft<ReplacementRule>? = rulesRepository.withConfigurationLock { runCatching {
        val text = preferences.getString("replacementDraft", null) ?: return@withConfigurationLock null
        RuleDrafts.readReplacement(text, preferences.getString("replacementDraftGroup", "").orEmpty())
    }.getOrNull() }
    fun clearRuleDraft(kind: RulesKind) {
        val key = if (kind == RulesKind.ROLE) "roleDraft" else "replacementDraft"
        rulesRepository.withConfigurationLock { preferences.edit().remove(key).remove(key + "Group").apply() }
    }

    fun setCatalogUrl(url: String) {
        val normalized = url.trim()
        rulesRepository.withConfigurationLock { preferences.edit().putString(KEY_CATALOG_URL, normalized).apply() }
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
        val busy = _uiState.value.packBusyId
        if (normalized.isEmpty() || busy != null) {
            Log.w(TAG, "installPackFromUrl ignored: url='$normalized' busy='$busy'")
            return
        }
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
        bgmPlayer?.release()
        bgmPlayer = null
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
        result.onFailure { Log.w(TAG, "pack operation failed", it) }
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
        previewJob?.cancel()
        val generation = ++previewGeneration
        val state = _uiState.value
        _uiState.update { it.copy(previewBusy = true) }
        previewJob = viewModelScope.launch {
        delay(180)
        val built = withContext(Dispatchers.Default) { runCatching { buildPlan(state).pipeline } }
        if (generation != previewGeneration) return@launch
        val result = built.getOrElse { error ->
            _uiState.update { it.copy(previewBusy = false, statusMessage = "分段预览失败：${error.message}") }
            return@launch
        }
        if (generation != previewGeneration) return@launch
        val names = state.voices.associate { it.systemName to it.displayName }
        val preview = result.segments.map { segment ->
            PreviewSegment(
                text = segment.text,
                isDialogue = segment.isDialogue,
                voiceId = segment.voiceId,
                voiceName = names[segment.voiceId] ?: "未选择音色",
                roleName = segment.roleName,
                id = segment.id,
                explanation = segment.fallbackReason ?: if (segment.roleName != null)
                    "命中 ${segment.roleName} · 请求音色 ${segment.requestedVoiceId.orEmpty()}"
                    else if (segment.isDialogue) "未命中角色，使用默认对话音色" else "使用旁白音色",
            )
        }
        _uiState.update { it.copy(preview = preview, pipelineIssues = result.issues, previewBusy = false) }
        }
    }

    private fun <T> loadRules(load: () -> RuleSet<T>): Result<RuleSet<T>> =
        try {
            Result.success(load())
        } catch (error: RulesStoreException) {
            Log.w(TAG, "rules load failed", error)
            Result.failure(error)
        }

    // ---- 规则：分组 / 拖拽排序 / 导入导出（阶段 2） --------------------------

    /** 角色规则整体落盘：分组按 order 排好，组内按 sortIndex 重新编号；返回是否成功。 */
    fun saveRoleSet(set: RuleSet<RoleRule>, message: String = "角色规则已保存"): Boolean {
        val loadError = _uiState.value.rolesLoadError
        if (loadError != null) {
            _uiState.update { it.copy(statusMessage = "角色规则文件无法读取，已阻止保存：$loadError") }
            return false
        }
        val groups = sortedGroups(set.groups)
        val rules = orderByGroups(
            items = set.items,
            groups = groups,
            groupOf = RoleRule::groupId,
            sortIndexOf = RoleRule::sortIndex,
            withGroup = { rule, groupId -> rule.copy(groupId = groupId) },
            withSort = { rule, index -> rule.copy(sortIndex = index) },
        )
        try {
            rulesRepository.saveRoles(RuleSet(groups, rules))
        } catch (error: RulesStoreException) {
            Log.w(TAG, "saveRoles failed", error)
            _uiState.update { it.copy(statusMessage = "角色规则保存失败：${error.message}") }
            return false
        }
        _uiState.update { it.copy(roleGroups = groups, roles = rules, statusMessage = message) }
        refreshPreview()
        return true
    }

    /** 替换规则整体落盘；返回是否成功。 */
    fun saveReplacementSet(set: RuleSet<ReplacementRule>, message: String = "替换规则已保存"): Boolean {
        val loadError = _uiState.value.replacementsLoadError
        if (loadError != null) {
            _uiState.update { it.copy(statusMessage = "替换规则文件无法读取，已阻止保存：$loadError") }
            return false
        }
        val groups = sortedGroups(set.groups)
        val items = orderByGroups(
            items = set.items,
            groups = groups,
            groupOf = ReplacementRule::groupId,
            sortIndexOf = ReplacementRule::sortIndex,
            withGroup = { rule, groupId -> rule.copy(groupId = groupId) },
            withSort = { rule, index -> rule.copy(sortIndex = index) },
        )
        try {
            rulesRepository.saveReplacements(RuleSet(groups, items))
        } catch (error: RulesStoreException) {
            Log.w(TAG, "saveReplacements failed", error)
            _uiState.update { it.copy(statusMessage = "替换规则保存失败：${error.message}") }
            return false
        }
        _uiState.update { it.copy(replacementGroups = groups, replacements = items, statusMessage = message) }
        refreshPreview()
        return true
    }

    /** 新建或重命名角色分组。 */
    fun saveRoleGroup(group: RuleGroup) {
        val state = _uiState.value
        val exists = state.roleGroups.any { it.id == group.id }
        val saved = if (exists) group else group.copy(order = nextRuleGroupOrder(state.roleGroups))
        val groups = state.roleGroups.filterNot { it.id == group.id } + saved
        saveRoleSet(RuleSet(groups, state.roles), "分组「${saved.name}」已保存")
    }

    /** 删除分组，组内规则移到默认分组而不是一起删除。 */
    fun deleteRoleGroup(group: RuleGroup) {
        if (group.id == RuleGroup.DEFAULT_ID) {
            _uiState.update { it.copy(statusMessage = "默认分组不能删除，可以改名或停用") }
            return
        }
        val state = _uiState.value
        val groups = state.roleGroups.filterNot { it.id == group.id }
        val rules = state.roles.map { if (it.groupId == group.id) it.copy(groupId = RuleGroup.DEFAULT_ID) else it }
        saveRoleSet(RuleSet(groups, rules), "已删除分组「${group.name}」，组内规则移到默认分组")
    }

    fun saveReplacementGroup(group: RuleGroup) {
        val state = _uiState.value
        val exists = state.replacementGroups.any { it.id == group.id }
        val saved = if (exists) group else group.copy(order = nextRuleGroupOrder(state.replacementGroups))
        val groups = state.replacementGroups.filterNot { it.id == group.id } + saved
        saveReplacementSet(RuleSet(groups, state.replacements), "分组「${saved.name}」已保存")
    }

    fun deleteReplacementGroup(group: RuleGroup) {
        if (group.id == RuleGroup.DEFAULT_ID) {
            _uiState.update { it.copy(statusMessage = "默认分组不能删除，可以改名或停用") }
            return
        }
        val state = _uiState.value
        val groups = state.replacementGroups.filterNot { it.id == group.id }
        val items = state.replacements.map {
            if (it.groupId == group.id) it.copy(groupId = RuleGroup.DEFAULT_ID) else it
        }
        saveReplacementSet(RuleSet(groups, items), "已删除分组「${group.name}」，组内规则移到默认分组")
    }

    /** 拖拽落点：把规则移动到 [targetGroupId] 的第 [targetIndex] 个位置（含跨组）。 */
    fun moveRole(ruleId: String, targetGroupId: String, targetIndex: Int) {
        val state = _uiState.value
        moveRule(
            items = state.roles,
            groups = state.roleGroups,
            ruleId = ruleId,
            targetGroupId = targetGroupId,
            targetIndex = targetIndex,
            idOf = RoleRule::id,
            groupOf = RoleRule::groupId,
            sortIndexOf = RoleRule::sortIndex,
            withPlacement = { rule, groupId, index -> rule.copy(groupId = groupId, sortIndex = index) },
            save = { set, message -> saveRoleSet(set, message) },
            nameOf = RoleRule::name,
        )
    }

    fun moveReplacement(ruleId: String, targetGroupId: String, targetIndex: Int) {
        val state = _uiState.value
        moveRule(
            items = state.replacements,
            groups = state.replacementGroups,
            ruleId = ruleId,
            targetGroupId = targetGroupId,
            targetIndex = targetIndex,
            idOf = ReplacementRule::id,
            groupOf = ReplacementRule::groupId,
            sortIndexOf = ReplacementRule::sortIndex,
            withPlacement = { rule, groupId, index -> rule.copy(groupId = groupId, sortIndex = index) },
            save = { set, message -> saveReplacementSet(set, message) },
            nameOf = ReplacementRule::name,
        )
    }

    fun rulesExportJson(): String = RulesImport.exportRoles(RuleSet(_uiState.value.roleGroups, _uiState.value.roles))

    fun replacementsExportJson(): String =
        RulesImport.exportReplacements(RuleSet(_uiState.value.replacementGroups, _uiState.value.replacements))

    /** 导出建议文件名，如 roles-export-20261115.json。 */
    fun exportFileName(kind: RulesKind): String = RulesImport.exportFileName(kind)

    suspend fun exportRulesTo(uri: Uri, kind: RulesKind): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val (json, count) = when (kind) {
                RulesKind.ROLE -> rulesExportJson() to _uiState.value.roles.size
                RulesKind.REPLACEMENT -> replacementsExportJson() to _uiState.value.replacements.size
            }
            writeJsonTo(uri, json)
            "已导出 $count 条${kind.label}"
        }
    }

    /** 第一步：读取并解析导入文件，产出冲突预览（不写盘）。 */
    suspend fun prepareRoleImport(uri: Uri): Result<RulesImportPreview<RoleRule>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = readImportText(uri)
            RulesImport.previewRoles(text, _uiState.value.roleGroups, _uiState.value.roles)
        }
    }

    suspend fun prepareReplacementImport(uri: Uri): Result<RulesImportPreview<ReplacementRule>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = readImportText(uri)
                RulesImport.previewReplacements(text, _uiState.value.replacementGroups, _uiState.value.replacements)
            }
        }

    /** 第二步：按用户选择的策略合并并落盘。 */
    fun applyRoleImport(preview: RulesImportPreview<RoleRule>, strategy: RuleImportStrategy): Result<String> {
        val state = _uiState.value
        val loadError = state.rolesLoadError
        if (loadError != null) {
            return Result.failure(RulesStoreException("角色规则文件无法读取，已阻止导入：$loadError"))
        }
        val merged = RulesImport.mergeRoles(preview, RuleSet(state.roleGroups, state.roles), strategy)
        if (!saveRoleSet(merged, "")) {
            return Result.failure(RulesStoreException(_uiState.value.statusMessage.ifBlank { "导入失败" }))
        }
        val message = importMessage(preview, strategy, RulesKind.ROLE)
        _uiState.update { it.copy(statusMessage = message) }
        return Result.success(message)
    }

    fun applyReplacementImport(
        preview: RulesImportPreview<ReplacementRule>,
        strategy: RuleImportStrategy,
    ): Result<String> {
        val state = _uiState.value
        val loadError = state.replacementsLoadError
        if (loadError != null) {
            return Result.failure(RulesStoreException("替换规则文件无法读取，已阻止导入：$loadError"))
        }
        val merged = RulesImport.mergeReplacements(
            preview,
            RuleSet(state.replacementGroups, state.replacements),
            strategy,
        )
        if (!saveReplacementSet(merged, "")) {
            return Result.failure(RulesStoreException(_uiState.value.statusMessage.ifBlank { "导入失败" }))
        }
        val message = importMessage(preview, strategy, RulesKind.REPLACEMENT)
        _uiState.update { it.copy(statusMessage = message) }
        return Result.success(message)
    }

    /** 用"已保存"的角色规则跑一次测试，列出命中的角色与音色（编辑中的内容不参与）。 */
    fun testRoleMatch(text: String, draft: RoleRule? = null, includeSaved: Boolean = true): String {
        if (text.isBlank()) return "请输入测试文本"
        val state = _uiState.value
        val rules = if (draft == null) state.roles else
            (if (includeSaved) state.roles.filterNot { it.id == draft.id } else emptyList()) +
                draft.copy(enabled = if (includeSaved) draft.enabled else true)
        val result = SynthesisPlanBuilder.build(
            text = text,
            builtInRoles = state.builtInRoles,
            roles = RuleSet(if (includeSaved) state.roleGroups else emptyList(), rules),
            replacements = state.replacementSet,
            filterPurePunctuation = false,
            voices = state.voices,
        ).pipeline
        if (result.segments.isEmpty()) return "没有可测试的片段。"
        val matched = result.segments.mapIndexed { index, segment ->
            "第 ${index + 1} 段「${segment.text.take(30)}」→ ${segment.roleName ?: if (segment.isDialogue) "默认对话" else "旁白"} · ${voiceName(segment.voiceId)}" +
                (segment.fallbackReason?.let { "\n$it" } ?: "")
        }
        if (matched.isEmpty()) {
            return "没有命中角色规则，对话会使用「对话音色」。" +
                result.issues.joinToString("") { "\n${it.message}" }
        }
        return matched.joinToString("\n") + result.issues.joinToString("") { "\n${it.message}" }
    }

    private fun voiceName(voiceId: String?): String {
        if (voiceId.isNullOrBlank()) return "未选择音色"
        return _uiState.value.voices.firstOrNull { it.systemName == voiceId }?.displayName ?: voiceId
    }

    private fun <T> importMessage(
        preview: RulesImportPreview<T>,
        strategy: RuleImportStrategy,
        kind: RulesKind,
    ): String = when (strategy) {
        RuleImportStrategy.REPLACE_ALL ->
            "已用导入文件整体替换${kind.label}：${preview.entries.size} 条"

        RuleImportStrategy.OVERWRITE ->
            "已导入${kind.label}：新增 ${preview.newCount} 条，覆盖 ${preview.conflictCount} 条"

        RuleImportStrategy.SKIP ->
            "已导入${kind.label}：新增 ${preview.newCount} 条，跳过 ${preview.conflictCount} 条冲突"
    }

    // ---- 设置项、备份还原与缓存（阶段 7） ------------------------------------

    fun setMaxRetryCount(value: Int) = saveIntSetting(
        value = value,
        range = SettingsStore.RETRY_COUNT_RANGE,
        setter = { settings.maxRetryCount = it },
        render = { state, v -> state.copy(maxRetryCount = v) },
    )

    fun setRequestTimeoutSec(value: Int) = saveIntSetting(
        value = value,
        range = SettingsStore.REQUEST_TIMEOUT_RANGE,
        setter = { settings.requestTimeoutSec = it },
        render = { state, v -> state.copy(requestTimeoutSec = v) },
    )

    fun setMaxSegmentChars(value: Int) = saveIntSetting(
        value = value,
        range = SettingsStore.SEGMENT_CHARS_RANGE,
        setter = { settings.maxSegmentChars = it },
        render = { state, v -> state.copy(maxSegmentChars = v) },
    )

    fun setSilenceBetweenSegmentsMs(value: Int) = saveIntSetting(
        value = value,
        range = SettingsStore.SILENCE_RANGE,
        setter = { settings.silenceBetweenSegmentsMs = it },
        render = { state, v -> state.copy(silenceBetweenSegmentsMs = v) },
    )

    fun setCacheEnabled(enabled: Boolean) {
        saveBoolSetting(
            value = enabled,
            setter = { settings.cacheEnabled = it },
            render = { state, v -> state.copy(cacheEnabled = v) },
        )
        // 设置变化立即生效：同步合成器并驱逐一次超限文件（不重建 synthesizer）。
        synthesizer.configureCache(settings.cacheEnabled, settings.cacheMaxMb)
    }

    fun setCacheMaxMb(value: Int) {
        saveIntSetting(
            value = value,
            range = SettingsStore.CACHE_MAX_MB_RANGE,
            setter = { settings.cacheMaxMb = it },
            render = { state, v -> state.copy(cacheMaxMb = v) },
        )
        synthesizer.configureCache(settings.cacheEnabled, settings.cacheMaxMb)
    }

    fun setBgmEnabled(enabled: Boolean) {
        saveBoolSetting(
            value = enabled,
            setter = { settings.bgmEnabled = it },
            render = { state, v -> state.copy(bgmEnabled = v) },
        )
        refreshBgm()
    }

    fun setBgmVolume(value: Int) {
        saveIntSetting(
            value = value,
            range = SettingsStore.BGM_VOLUME_RANGE,
            setter = { settings.bgmVolume = it },
            render = { state, v -> state.copy(bgmVolume = v) },
        )
        refreshBgm()
    }

    fun setBgmLoop(enabled: Boolean) {
        saveBoolSetting(
            value = enabled,
            setter = { settings.bgmLoop = it },
            render = { state, v -> state.copy(bgmLoop = v) },
        )
        refreshBgm()
    }

    fun setBgmDuck(enabled: Boolean) {
        saveBoolSetting(
            value = enabled,
            setter = { settings.bgmDuck = it },
            render = { state, v -> state.copy(bgmDuck = v) },
        )
        refreshBgm()
    }

    /** 记住 SAF 选中的音频文件；拿不到持久授权时仍然保存 URI，只是重启后可能失效。 */
    fun setBgmUri(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val text = uri.toString()
        settings.bgmUri = text
        _uiState.update { it.copy(bgmUri = text) }
        refreshBgm()
    }

    fun clearBgmUri() {
        settings.bgmUri = ""
        _uiState.update { it.copy(bgmUri = "") }
        refreshBgm()
    }

    /** 背景音乐文件显示名；未选择时返回空串。 */
    fun bgmFileName(): String {
        val uri = _uiState.value.bgmUri
        if (uri.isBlank()) return ""
        return Uri.parse(uri).lastPathSegment?.substringAfterLast('/') ?: uri.takeLast(24)
    }

    suspend fun applyForwardConfig(config: ForwardConfig): Result<String> {
        val result = ForwardController.apply(getApplication(), config)
        return result.map { runtime ->
            val message = if (runtime.running) "HTTP 转发已监听 ${runtime.host}:${runtime.port}" else "HTTP 转发已停止"
            _uiState.update { it.copy(forwardEnabled = settings.forwardEnabled, forwardPort = settings.forwardPort,
                forwardBind = settings.forwardBind, forwardToken = settings.forwardToken, statusMessage = message) }
            message
        }
    }
    fun setForwardEnabled(enabled: Boolean) { viewModelScope.launch {
        applyForwardConfig(ForwardController.configured(getApplication()).copy(enabled = enabled)) } }
    fun setForwardPort(port: Int) { viewModelScope.launch {
        applyForwardConfig(ForwardController.configured(getApplication()).copy(port = port)) } }
    fun setForwardBind(address: String) { viewModelScope.launch {
        applyForwardConfig(ForwardController.configured(getApplication()).copy(bindAddress = address)) } }
    fun ensureForwardToken(): String = settings.forwardToken.ifBlank { regenerateForwardToken() }
    fun regenerateForwardToken(): String {
        val token = UUID.randomUUID().toString()
        viewModelScope.launch { applyForwardConfig(ForwardController.configured(getApplication()).copy(token = token)) }
        return token
    }
    private fun refreshForwardServer() { ForwardController.startConfigured(getApplication()) }
    fun legadoJson(): String = app.noveltts.forward.LegadoIntegration.configJson(ForwardController.configured(getApplication()))
    suspend fun exportLegadoTo(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching { writeJsonTo(uri, legadoJson()); "已导出阅读朗读配置，包含本机访问令牌" }
    }
    fun backupFileName(): String = BackupManager.exportFileName()

    /** 生成备份 JSON；[includeSensitive] 为 false 时不写许可证与访问令牌。 */
    fun backupJson(includeSensitive: Boolean): String = BackupManager.exportJson(
        roleSet = _uiState.value.roleSet,
        replacementSet = _uiState.value.replacementSet,
        settings = settingsSnapshot(),
        profiles = _uiState.value.readingProfiles,
        voiceHints = _uiState.value.voices.associate { it.systemName to it.displayName },
        credentials = if (includeSensitive) {
            BackupCredentials(settings.license(), settings.forwardToken)
        } else {
            null
        },
    )

    /** 导出到 SAF 选定的文件；失败以 Result 返回，由 UI 提示。 */
    suspend fun exportBackupTo(uri: Uri, includeSensitive: Boolean): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val state = _uiState.value
                writeJsonTo(uri, backupJson(includeSensitive))
                buildString {
                    append("已导出备份：角色 ")
                    append(state.roles.size)
                    append(" 条、替换 ")
                    append(state.replacements.size)
                    append(" 条、设置 ")
                    append(settingsSnapshot().size)
                    append(" 项")
                    append(if (includeSensitive) "（包含许可证与访问令牌）" else "（不含许可证与访问令牌）")
                }
            }
        }

    /** 第一步：读取并校验备份文件，算出冲突预览。 */
    suspend fun prepareBackupRestore(uri: Uri): Result<BackupRestorePreview> =
        withContext(Dispatchers.IO) {
            runCatching {
                val parsed = BackupManager.parse(readBackupText(uri))
                val state = _uiState.value
                BackupManager.previewRestore(
                    backup = parsed,
                    currentRoleGroups = state.roleGroups,
                    currentRoles = state.roles,
                    currentReplacementGroups = state.replacementGroups,
                    currentReplacements = state.replacements,
                )
            }
        }

    /**
     * 第二步：按预览策略合并，两个规则文件一起原子还原，成功后可选恢复设置与敏感凭据。
     */
    fun applyBackupRestore(
        preview: BackupRestorePreview, strategy: RuleImportStrategy, applySettings: Boolean = true,
        applyCredentials: Boolean = false, voiceMapping: Map<String, String> = emptyMap(),
        keepMissingVoices: Boolean = false, repairCorrupt: Boolean = false,
    ): Result<String> = runCatching {
        val state = _uiState.value
        check(repairCorrupt || (state.rolesLoadError == null && state.replacementsLoadError == null && state.profilesLoadError == null)) {
            "现有配置无法读取，请勾选使用备份修复，或先从本机备份恢复" }
        val usable = state.usableVoices.map { it.systemName }.toSet()
        check(voiceMapping.values.all { it in usable }) { "映射音色已不可用，请重新选择" }
        val mapped = BackupManager.remapVoices(preview, voiceMapping)
        check(keepMissingVoices || BackupManager.missingVoiceIds(mapped, usable, applySettings).isEmpty()) { "请先映射缺失音色，或选择保留缺失引用" }
        val roles = RulesImport.mergeRoles(mapped.roles, state.roleSet, strategy)
        val roleIds = mapped.roles.entries.associate { entry ->
            entry.rule.id to if (strategy == RuleImportStrategy.REPLACE_ALL) entry.rule.id else
                (state.roles.firstOrNull { it.id == entry.rule.id } ?: state.roles.firstOrNull { it.name.trim() == entry.rule.name.trim() })?.id.orEmpty().ifBlank { entry.rule.id }
        }
        val replacementPreview = mapped.replacements.copy(entries = mapped.replacements.entries.map { entry ->
            entry.copy(rule = entry.rule.copy(targetRoleId = roleIds[entry.rule.targetRoleId] ?: entry.rule.targetRoleId)) })
        val replacements = RulesImport.mergeReplacements(replacementPreview, state.replacementSet, strategy)
        fun groupMap(source: List<RuleGroup>, current: List<RuleGroup>): Map<String, String> = source.associate { group ->
            group.id to if (strategy == RuleImportStrategy.REPLACE_ALL) group.id else
                (current.firstOrNull { it.id == group.id } ?: current.firstOrNull { it.name.trim() == group.name.trim() })?.id.orEmpty().ifBlank { group.id }
        }
        val roleGroupMap = groupMap(mapped.roles.groups, state.roleGroups)
        val replacementGroupMap = groupMap(mapped.replacements.groups, state.replacementGroups)
        val incomingProfiles = mapped.backup.profiles.map { profile -> profile.copy(
            roleGroups = profile.roleGroups.map { roleGroupMap[it] ?: it }, replacementGroups = profile.replacementGroups.map { replacementGroupMap[it] ?: it }) }
        val profiles = if (!mapped.backup.includesProfiles) state.readingProfiles else if (strategy == RuleImportStrategy.REPLACE_ALL) incomingProfiles else {
            val combined = state.readingProfiles.toMutableList()
            incomingProfiles.forEach { profile ->
                val index = combined.indexOfFirst { it.id == profile.id || it.name.trim() == profile.name.trim() }
                if (index < 0) combined += profile
                else if (strategy == RuleImportStrategy.OVERWRITE) combined[index] = profile.copy(id = combined[index].id)
            }
            combined.toList()
        }
        val encodedProfiles = ReadingProfiles.encode(profiles)
        transactionalConfiguration {
            rulesRepository.restoreAll(roles, replacements)
            if (applySettings) applyBackupSettings(mapped.backup.settings)
            if (applyCredentials) mapped.backup.credentials?.let { credentials ->
                credentials.license?.let(settings::setLicense)
                credentials.forwardToken?.let { settings.commitValues(mapOf("forwardToken" to it)) }
            }
            if (mapped.backup.includesProfiles && (incomingProfiles.isNotEmpty() || strategy == RuleImportStrategy.REPLACE_ALL))
                settings.commitValues(mapOf(ReadingProfiles.KEY to encodedProfiles, "activeProfileId" to ""))
        }
        refreshAll()
        refreshRecoveryCandidates()
        VoicePackEvents.notifyChanged(getApplication())
        "已还原：角色 ${roles.items.size} 条、替换 ${replacements.items.size} 条、阅读方案 ${profiles.size} 个"
            .also { message -> _uiState.update { it.copy(statusMessage = message) } }
    }

    private fun transactionalConfiguration(apply: () -> Unit) = rulesRepository.withConfigurationLock {
        settings.flush()
        val settingsBefore = settings.snapshot()
        val credentialsBefore = settings.credentialSnapshot()
        val restoreApp = getApplication<Application>()
        val shared = File(restoreApp.applicationInfo.dataDir, "shared_prefs")
        app.noveltts.data.backup.ConfigurationRecovery.transaction(restoreApp).run(
            rulesRepository.transactionFiles() + listOf(File(shared, "multitts.xml"), File(shared, "multitts.xml.bak"),
                File(shared, "multitts.secrets.xml"), File(shared, "multitts.secrets.xml.bak")),
            rollbackMemory = { settings.commitValues(settingsBefore, replace = true); settings.restoreCredentialSnapshot(credentialsBefore) },
            apply = apply)
    }

    fun saveReadingProfile(name: String): Result<String> = runCatching { rulesRepository.withConfigurationLock {
        val state = _uiState.value
        check(state.profilesLoadError == null) { "阅读方案存储不可读，请先恢复" }
        val label = name.trim().take(60)
        require(label.isNotBlank()) { "请填写方案名称" }
        require(state.readingProfiles.none { it.name == label }) { "已有同名方案" }
        val profile = ReadingProfile(name = label, narratorVoice = state.narratorVoiceId, dialogueVoice = state.dialogueVoiceId,
            systemVoice = state.systemVoiceId, roleGroups = state.roleGroups.filter { it.enabled }.map { it.id },
            replacementGroups = state.replacementGroups.filter { it.enabled }.map { it.id }, speed = state.speed, pitch = state.pitch, volume = state.volume,
            punctuationFilter = state.punctuationFilter, silenceMs = state.silenceBetweenSegmentsMs, maxSegmentChars = state.maxSegmentChars,
            voiceHints = state.voices.associate { it.systemName to it.displayName },
            groupHints = (state.roleGroups + state.replacementGroups).associate { it.id to it.name },
            narratorRate = state.builtInRoles.narrator.rate, narratorPitch = state.builtInRoles.narrator.pitch,
            narratorVolume = state.builtInRoles.narrator.volume, dialogueRate = state.builtInRoles.dialogue.rate,
            dialoguePitch = state.builtInRoles.dialogue.pitch, dialogueVolume = state.builtInRoles.dialogue.volume)
        val profiles = state.readingProfiles + profile
        settings.commitValues(mapOf(ReadingProfiles.KEY to ReadingProfiles.encode(profiles)))
        _uiState.update { it.copy(readingProfiles = profiles) }
        "已保存方案「$label」"
    } }
    fun deleteReadingProfile(profile: ReadingProfile): Result<String> = runCatching { rulesRepository.withConfigurationLock {
        val state = _uiState.value
        check(state.profilesLoadError == null) { "阅读方案存储不可读，请先恢复" }
        val profiles = state.readingProfiles.filterNot { it.id == profile.id }
        val activeId = state.activeProfileId.takeUnless { it == profile.id }.orEmpty()
        settings.commitValues(mapOf(ReadingProfiles.KEY to ReadingProfiles.encode(profiles), "activeProfileId" to activeId))
        _uiState.update { it.copy(readingProfiles = profiles, activeProfileId = activeId) }
        "已删除方案「${profile.name}」"
    } }
    fun previewReadingProfile(profile: ReadingProfile): ProfileSwitchPreview = ReadingProfiles.preview(profile,
        _uiState.value.voices, _uiState.value.roleGroups, _uiState.value.replacementGroups)
    fun applyReadingProfile(preview: ProfileSwitchPreview, mapping: Map<String, String>, dropMissingGroups: Boolean): Result<String> = runCatching {
        val state = _uiState.value
        check(state.rolesLoadError == null && state.replacementsLoadError == null) { "规则文件不可读，请先恢复" }
        val mapped = preview.profile.remapVoices(mapping)
        val fresh = ReadingProfiles.preview(mapped, state.voices, state.roleGroups, state.replacementGroups)
        check(fresh.missingVoices.isEmpty()) { "请先映射缺失音色" }
        check(dropMissingGroups || (fresh.missingRoleGroups.isEmpty() && fresh.missingReplacementGroups.isEmpty())) { "请确认忽略已删除的分组" }
        val saved = mapped.copy(roleGroups = mapped.roleGroups.filter { id -> state.roleGroups.any { it.id == id } },
            replacementGroups = mapped.replacementGroups.filter { id -> state.replacementGroups.any { it.id == id } })
        transactionalConfiguration {
            rulesRepository.restoreAll(state.roleSet.copy(groups = state.roleGroups.map { it.copy(enabled = it.id in saved.roleGroups) }),
                state.replacementSet.copy(groups = state.replacementGroups.map { it.copy(enabled = it.id in saved.replacementGroups) }))
            settings.commitValues(mapOf("narratorVoice" to saved.narratorVoice, "dialogueVoice" to saved.dialogueVoice, "systemVoice" to saved.systemVoice,
                KEY_SPEED to saved.speed, KEY_PITCH to saved.pitch, KEY_VOLUME to saved.volume,
                "punctuationFilter" to saved.punctuationFilter, "silenceBetweenSegmentsMs" to saved.silenceMs,
                "maxSegmentChars" to saved.maxSegmentChars, "activeProfileId" to saved.id,
                ReadingProfiles.KEY to ReadingProfiles.encode(state.readingProfiles.map { if (it.id == saved.id) saved else it }))
                + SettingsStore.builtInMultiplierValues(saved.builtInRoles))
        }
        refreshAll()
        _uiState.update { it.copy(speed = saved.speed, pitch = saved.pitch, volume = saved.volume) }
        ("已切换为「${saved.name}」" + if (state.isSpeaking) "，当前朗读继续使用原计划" else "")
            .also { message -> _uiState.update { it.copy(statusMessage = message) } }
    }
    fun refreshRecoveryCandidates() { viewModelScope.launch {
        val candidates = withContext(Dispatchers.IO) { rulesRepository.recoveryCandidates() }
        _uiState.update { it.copy(recoveryCandidates = candidates) }
    } }
    fun restoreRuleCandidate(candidate: RuleRecoveryCandidate): Result<String> = runCatching {
        transactionalConfiguration { rulesRepository.restoreCandidate(candidate) }
        refreshAll(); refreshRecoveryCandidates()
        "已从本机备份恢复 ${candidate.ruleCount} 条规则"
    }
    fun refreshCacheUsage() {
        viewModelScope.launch {
            val usage = withContext(Dispatchers.IO) { CacheManager.stats(cacheDirectory()) }
            _uiState.update { it.copy(cacheBytes = usage.bytes, cacheFileCount = usage.fileCount, cacheHits = AudioCache.metrics(File(cacheDirectory(), AudioCache.DIR_NAME)).hits, cacheMisses = AudioCache.metrics(File(cacheDirectory(), AudioCache.DIR_NAME)).misses) }
        }
    }

    /** 统计 API：UI 打开数据管理页时可直接查询一次。 */
    suspend fun cacheUsage(): CacheUsage = withContext(Dispatchers.IO) { CacheManager.stats(cacheDirectory()) }

    /** 只删 *.mp3 / *.tmp 与 audiocache 下的 *.pcm；语音包、spool 与 SharedPreferences 不在删除范围内。 */
    fun clearCache() {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { CacheManager.clear(cacheDirectory()) }
            val left = withContext(Dispatchers.IO) { CacheManager.stats(cacheDirectory()) }
            val message = if (removed.fileCount == 0) {
                "缓存目录里没有可清理的 mp3 / pcm / tmp 文件。"
            } else {
                "已清理 " + formatSize(removed.bytes) + "，共 " + removed.fileCount + " 个文件。"
            }
            _uiState.update {
                it.copy(
                    cacheBytes = left.bytes,
                    cacheFileCount = left.fileCount,
                    statusMessage = message,
                )
            }
        }
    }

    private fun cacheDirectory(): File = getApplication<Application>().cacheDir

    private fun saveIntSetting(
        value: Int,
        range: IntRange,
        setter: (Int) -> Unit,
        render: (MainUiState, Int) -> MainUiState,
    ) {
        val clamped = value.coerceIn(range)
        setter(clamped)
        _uiState.update { render(it, clamped) }
    }

    private fun saveBoolSetting(
        value: Boolean,
        setter: (Boolean) -> Unit,
        render: (MainUiState, Boolean) -> MainUiState,
    ) {
        setter(value)
        _uiState.update { render(it, value) }
    }

    /** 导出/还原设置时的白名单快照；bgmUri 是设备本地授权，不迁移。 */
    private fun settingsSnapshot(): Map<String, Any?> = mapOf(
        "punctuationFilter" to settings.punctuationFilter,
        "narratorVoice" to settings.narratorVoice,
        "dialogueVoice" to settings.dialogueVoice,
        "systemVoice" to settings.systemVoice,
        "edgeEndpoint" to settings.edgeEndpoint,
        "compatibilityProxy" to settings.compatibilityProxy,
        "themeMode" to settings.themeMode,
        "maxRetryCount" to settings.maxRetryCount,
        "requestTimeoutSec" to settings.requestTimeoutSec,
        "maxSegmentChars" to settings.maxSegmentChars,
        "silenceBetweenSegmentsMs" to settings.silenceBetweenSegmentsMs,
        "cacheEnabled" to settings.cacheEnabled,
        "cacheMaxMb" to settings.cacheMaxMb,
        "bgmEnabled" to settings.bgmEnabled,
        "bgmVolume" to settings.bgmVolume,
        "bgmLoop" to settings.bgmLoop,
        "bgmDuck" to settings.bgmDuck,
        "forwardEnabled" to settings.forwardEnabled,
        "forwardPort" to settings.forwardPort,
        "forwardBind" to settings.forwardBind,
    ) + SettingsStore.builtInMultiplierValues(settings.builtInRoles())

    private fun applyBackupSettings(values: Map<String, Any>) {
        val ranges = mapOf("maxRetryCount" to SettingsStore.RETRY_COUNT_RANGE, "requestTimeoutSec" to SettingsStore.REQUEST_TIMEOUT_RANGE,
            "maxSegmentChars" to SettingsStore.SEGMENT_CHARS_RANGE, "silenceBetweenSegmentsMs" to SettingsStore.SILENCE_RANGE,
            "cacheMaxMb" to SettingsStore.CACHE_MAX_MB_RANGE, "bgmVolume" to SettingsStore.BGM_VOLUME_RANGE,
            "forwardPort" to SettingsStore.FORWARD_PORT_RANGE)
        val normalized = values.mapValues { (key, value) ->
            val range = ranges[key]
            val multiplierRange = SettingsStore.BUILT_IN_MULTIPLIER_RANGES[key]
            when {
                multiplierRange != null && value is Number -> RolePlaybackSettings.multiplier(value.toFloat(), multiplierRange)
                range != null && value is Number -> value.toInt().coerceIn(range)
                key == "forwardBind" -> if (value == SettingsStore.FORWARD_BIND_ALL) value else SettingsStore.FORWARD_BIND_LOOPBACK
                key == "edgeEndpoint" && (value as? String).isNullOrBlank() -> SettingsStore.DEFAULT_EDGE_ENDPOINT
                key == "themeMode" && value !is String ->
                    SettingsStore.DEFAULT_THEME_MODE
                key == "themeMode" ->
                    (value as String).uppercase().takeIf { it in SettingsStore.THEME_MODES }
                        ?: SettingsStore.DEFAULT_THEME_MODE
                else -> value
            }
        }
        settings.commitValues(normalized)
        _uiState.update { it.copy(themeMode = ThemeMode.fromStorage(settings.themeMode)) }
    }
    private fun readBackupText(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val stream = resolver.openInputStream(uri) ?: throw IllegalStateException("无法读取所选文件")
        return stream.use { BackupManager.readText(it) }
    }

    private fun writeJsonTo(uri: Uri, json: String) {
        val resolver = getApplication<Application>().contentResolver
        val stream = resolver.openOutputStream(uri) ?: throw IllegalStateException("无法写入所选文件")
        stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    private fun readImportText(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val stream = resolver.openInputStream(uri) ?: throw IllegalStateException("无法读取所选文件")
        return stream.use { RulesImport.readText(it) }
    }

    private fun sortedGroups(groups: List<RuleGroup>): List<RuleGroup> =
        normalizeRuleGroups(groups).sortedWith(compareBy<RuleGroup> { it.order }.thenBy { it.name })

    private fun <T> orderByGroups(
        items: List<T>,
        groups: List<RuleGroup>,
        groupOf: (T) -> String,
        sortIndexOf: (T) -> Int,
        withGroup: (T, String) -> T,
        withSort: (T, Int) -> T,
    ): List<T> {
        val order = groups.map { it.id }
        val known = order.toSet()
        val fixed = items.map { item -> if (groupOf(item) in known) item else withGroup(item, RuleGroup.DEFAULT_ID) }
        return order.flatMap { groupId ->
            fixed.filter { groupOf(it) == groupId }
                .sortedBy(sortIndexOf)
                .mapIndexed { index, item -> withSort(item, index) }
        }
    }

    private fun <T> moveRule(
        items: List<T>,
        groups: List<RuleGroup>,
        ruleId: String,
        targetGroupId: String,
        targetIndex: Int,
        idOf: (T) -> String,
        groupOf: (T) -> String,
        sortIndexOf: (T) -> Int,
        withPlacement: (T, String, Int) -> T,
        save: (RuleSet<T>, String) -> Unit,
        nameOf: (T) -> String,
    ) {
        val rule = items.firstOrNull { idOf(it) == ruleId } ?: return
        if (groups.none { it.id == targetGroupId }) return
        val groupItems = items.filter { groupOf(it) == targetGroupId }.sortedBy(sortIndexOf)
        val currentIndex = groupItems.indexOfFirst { idOf(it) == ruleId }
        var index = targetIndex.coerceAtLeast(0)
        if (currentIndex >= 0) {
            if (index == currentIndex || index == currentIndex + 1) return
            if (currentIndex < index) index--
        }
        val others = groupItems.filterNot { idOf(it) == ruleId }
        val insertAt = index.coerceIn(0, others.size)
        val reordered = (others.take(insertAt) + rule + others.drop(insertAt))
            .mapIndexed { position, item -> withPlacement(item, targetGroupId, position) }
        val rest = items.filterNot { groupOf(it) == targetGroupId || idOf(it) == ruleId }
        save(RuleSet(groups, rest + reordered), "已调整「${nameOf(rule)}」的位置")
    }

    private fun loadBaseState(): MainUiState = MainUiState(
        isPaused = preferences.contains("readingSnapshot"),
        playbackError = preferences.contains("readingSnapshot") && preferences.getBoolean("readingError", false),
        currentSegmentIndex = preferences.getInt("readingIndex", -1),
        totalSegments = preferences.getInt("readingTotal", 0),
        text = preferences.getString(KEY_LAST_TEXT, "").orEmpty().ifBlank { DEFAULT_TEST_TEXT },
        speed = preferences.getInt(KEY_SPEED, 50),
        volume = preferences.getInt(KEY_VOLUME, 50),
        pitch = preferences.getInt(KEY_PITCH, 25),
        builtInRoles = settings.builtInRoles(),
        globalControlsExpanded = preferences.getBoolean(KEY_GLOBAL_CONTROLS_EXPANDED, true),
        systemVoiceId = settings.systemVoice,
        punctuationFilter = settings.punctuationFilter,
        licenseConfigured = settings.hasLicense(),
        licenseMask = settings.licenseMask(),
        edgeEndpoint = settings.edgeEndpoint,
        compatibilityProxy = settings.compatibilityProxy,
        themeMode = ThemeMode.fromStorage(settings.themeMode),
        catalogUrl = preferences.getString(KEY_CATALOG_URL, "").orEmpty(),
    )

    private fun updateSlider(
        key: String,
        value: Int,
        transform: (MainUiState, Int) -> MainUiState,
    ) {
        rulesRepository.withConfigurationLock { preferences.edit().putInt(key, value).apply() }
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
        playbackGeneration.incrementAndGet()
        playbackLease?.close(); playbackLease = null
        playbackHandle?.cancel()
        playbackHandle = null
        releaseTrack()
        // 朗读停止时背景音乐一起停；stopBgm() 可重复调用，不会重复释放。
        stopBgm()
        if (updateState) {
            _uiState.update {
                it.copy(
                    isSpeaking = false,
                    currentSegmentIndex = -1,
                    playingSegmentIndex = null,
                    statusMessage = "已停止朗读",
                )
            }
        }
    }

    /** 懒建背景音乐播放器：context 用 applicationContext，避免持有 Activity。 */
    private fun bgm(): BgmPlayer =
        bgmPlayer ?: BgmPlayer(getApplication<Application>()).also { bgmPlayer = it }

    /**
     * 按当前设置刷新背景音乐：朗读中才播放，未启用/未选文件时停止。
     *
     * [BgmPlayer.play] 对同一个 uri 只更新参数、不重建播放器，所以设置项连续变化
     * （例如拖动音量滑块）不会反复解码；换文件或停止后再次朗读才真正重建。
     */
    private fun refreshBgm() {
        val state = activePlaybackSettings ?: _uiState.value
        if (!_uiState.value.isSpeaking || !state.bgmEnabled || state.bgmUri.isBlank()) {
            bgmPlayer?.stop()
            return
        }
        bgm().play(
            uri = state.bgmUri,
            volumePercent = state.bgmVolume,
            loop = state.bgmLoop,
            ducked = state.bgmDuck,
        )
    }

    /** 朗读结束时先恢复音量再停止背景音乐；尚未创建播放器时什么也不做。 */
    private fun stopBgm() {
        bgmPlayer?.duck(false)
        bgmPlayer?.stop()
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

        /** 首次打开时预填的测试段落：旁白 + 两段中文引号对话，便于导入语音包后立刻试听。 */
        private const val DEFAULT_TEST_TEXT = """傍晚的风掠过屋檐，远处的铃声一阵一阵。
「你终于来了。」她抬起头，眼睛里映着最后一缕霞光。
他没有回答，只是把手里的灯往前递了递。
「走吧，天要黑了。」"""
        const val KEY_SPEED = "speed"
        const val KEY_VOLUME = "volume"
        const val KEY_PITCH = "pitch"
        const val KEY_GLOBAL_CONTROLS_EXPANDED = "globalControlsExpanded"
        const val QUICK_ROLE_CONTEXT_CHARS = 30
        val REGEX_META = Regex("""[\\^$.|?*+()\[\]{}]""")
        const val KEY_CATALOG_URL = "catalogUrl"
        const val PACK_BUSY_CATALOG = "catalog"
    }
}
