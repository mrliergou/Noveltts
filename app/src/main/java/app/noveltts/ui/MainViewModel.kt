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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.noveltts.audio.BgmPlayer
import app.noveltts.data.backup.BackupCredentials
import app.noveltts.data.backup.BackupManager
import app.noveltts.data.backup.BackupRestorePreview
import app.noveltts.data.cache.AudioCache
import app.noveltts.data.cache.CacheManager
import app.noveltts.data.cache.CacheUsage
import app.noveltts.data.config.ReadingProfile
import app.noveltts.data.config.BookRolesRepository
import app.noveltts.domain.BookLibrary
import app.noveltts.domain.BookRoleProfile
import app.noveltts.domain.BookCharacter
import app.noveltts.data.config.ReadingProfiles
import app.noveltts.data.config.ProfileSwitchPreview
import app.noveltts.data.config.RuleRecoveryCandidate
import app.noveltts.data.config.RulesImport
import app.noveltts.data.config.RuleDrafts
import app.noveltts.data.config.RuleEditorDraft
import app.noveltts.data.config.RulesImportPreview
import app.noveltts.data.config.RuleImportStrategy
import app.noveltts.data.config.RulesKind
import app.noveltts.data.config.RuleMutations
import app.noveltts.data.config.RulesImportApplyResult
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
import app.noveltts.domain.AttributionKind
import app.noveltts.domain.RoleAliases
import app.noveltts.domain.UnattributedPolicy
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
    BOOKS("书籍角色"),
    ROLES("全局角色"),
    REPLACEMENTS("替换"),
    SETTINGS("设置"),
}

internal const val PACK_BUSY_IMPORT = "import"
data class PackUpdatePreview(val name: String, val previousVersion: String, val nextVersion: String,
    val linkedRoles: Int, val linkedDefaults: Int)

data class PreviewSegment(
    val text: String,
    val isDialogue: Boolean,
    val voiceId: String?,
    val voiceName: String,
    val roleName: String?,
    val id: String = "",
    val explanation: String = "",
    /** 已配角色但依据不够确定，需要用户核对。 */
    val uncertain: Boolean = false,
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
    val packProblems: List<String> = emptyList(),
    val pendingPackUpdate: PackUpdatePreview? = null,
    val firstSegmentEnabled: Boolean = false,
    val firstSegmentReady: Boolean = false,
    val engineHealth: Map<String, app.noveltts.engine.EngineHealthResult> = emptyMap(),
    val voiceOverrides: Map<String, app.noveltts.data.voicepack.VoiceOverride> = emptyMap(),
    val voiceOverridesError: String? = null,
    val recoveryWarning: String? = null,
    val roles: List<RoleRule> = emptyList(),
    val replacements: List<ReplacementRule> = emptyList(),
    val readingProfiles: List<ReadingProfile> = emptyList(),
    val bookLibrary: BookLibrary = BookLibrary(),
    val booksLoadError: String? = null,
    val bookBusy: Boolean = false,
    val bookOperationId: String = "",
    val bookOperationError: String? = null,
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
    val previewError: String? = null,
    val keepAwake: Boolean = false,
    val punctuationFilter: Boolean = true,
    /** 没写是谁说的对话怎么读。 */
    val unattributedPolicy: UnattributedPolicy = UnattributedPolicy.DEFAULT,
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
    private val readingPreferences = app.noveltts.data.settings.contentPreferences(application, "multitts.reading", listOf("readingSnapshot"))
    private val textPreferences = app.noveltts.data.settings.contentPreferences(application, "multitts.text", listOf(KEY_LAST_TEXT))
    private val initialLoad = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var textSaveJob: Job? = null
    private val settings = SettingsStore(application)
    private val packStore = VoicePackStore(application)
    private val registry = VoiceRegistry(application, packStore)
    private val voiceOverrides = app.noveltts.data.voicepack.VoiceOverrides(application)
    private val rulesRepository = RulesRepository(application)
    private val bookRepository = BookRolesRepository(application)
    private val engines = EngineManager(application, settings)
    private val synthesizer = MultiSegmentSynthesizer(
        engines = engines,
        spoolDir = File(application.cacheDir, "spool"),
        cacheDir = File(application.cacheDir, AudioCache.DIR_NAME),
        cacheMaxMb = settings.cacheMaxMb,
        cacheEnabled = settings.cacheEnabled,
    )
    private val trackLock = Any()
    private class PackDecision { val ready = java.util.concurrent.CountDownLatch(1); @Volatile var accepted = false }
    private val packDecision = java.util.concurrent.atomic.AtomicReference<PackDecision?>(null)

    private var playbackHandle: SynthesisHandle? = null
    private var audioTrack: AudioTrack? = null
    private var previewJob: Job? = null
    private val ruleWriteMutex = Mutex()
    private fun enqueueRuleChange(action: () -> Unit) {
        viewModelScope.launch {
            ruleWriteMutex.withLock { withContext(Dispatchers.IO) { action() } }
        }
    }
    private var previewGeneration = 0L
    private val playbackGeneration = AtomicLong()
    private var playbackLease: AutoCloseable? = null
    @Volatile private var readingIndex = 0
    private data class ReadingSession(val state: MainUiState, val segments: List<PlannedSegment>, val restoredIndex: Int = 0)
    @Volatile private var readingSession: ReadingSession? = null
    @Volatile private var readingHadError = rulesRepository.withConfigurationLock { preferences.getBoolean("readingError", false) }
    @Volatile private var activePlaybackSettings: MainUiState? = null

    /** 背景音乐播放器；懒建，没用过背景音乐就不创建线程与 MediaPlayer。 */
    private var bgmPlayer: BgmPlayer? = null
    private val audioPolicyDelegate = lazy {
        app.noveltts.audio.PlaybackAudioPolicy(getApplication(), ::pause, ::resume, ::stop, ::previousSegment, ::nextSegment) {
            _uiState.value.isSpeaking && !_uiState.value.isPreview && readingSession != null
        }
    }
    private val audioPolicy by audioPolicyDelegate

    /** Legado HTTP 转发服务；只在设置里启用时才真正监听端口。 */


    private val _uiState = MutableStateFlow(loadBaseState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { app.noveltts.engine.EngineHealth.state.collect { health -> _uiState.update { it.copy(engineHealth = health) } } }
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
        if (section == AppSection.BOOKS) refreshAll()
    }

    // Jobs belong to the ViewModel: rotating or leaving an editor cannot cancel an accepted write.
    private fun changeBooks(operationId: String, action: () -> Unit) {
        if (_uiState.value.bookBusy) return
        _uiState.update { it.copy(bookBusy = true, bookOperationId = operationId, bookOperationError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { action(); refreshAll() }
            _uiState.update { it.copy(bookBusy = false, bookOperationError = result.exceptionOrNull()?.message,
                statusMessage = result.exceptionOrNull()?.message ?: "书籍角色已保存，对下一次新朗读生效") }
        }
    }

    private fun usableBookVoices() = registry.voices(settings.hasLicense(), settings.localTtsEnginePackage)
        .filter { it.usable }.mapTo(HashSet()) { it.systemName }

    fun createBook(operationId: String, book: BookRoleProfile) = changeBooks(operationId) {
        bookRepository.create(book, select = true)
    }

    fun selectBook(id: String, expectedRevision: String) = changeBooks(UUID.randomUUID().toString()) {
        rulesRepository.withConfigurationLock { bookRepository.select(id, expectedRevision, usableBookVoices()) }
    }

    fun updateBook(operationId: String, expected: BookRoleProfile, name: String, pool: List<String>) = changeBooks(operationId) {
        rulesRepository.withConfigurationLock { bookRepository.update(expected, name, pool, usableBookVoices()) }
    }

    fun saveBookCharacter(operationId: String, expected: BookRoleProfile, character: BookCharacter) = changeBooks(operationId) {
        rulesRepository.withConfigurationLock { bookRepository.saveCharacter(expected, character, usableBookVoices()) }
    }

    fun deleteBook(operationId: String, expected: BookRoleProfile) = changeBooks(operationId) { bookRepository.delete(expected) }
    fun deleteBookCharacter(operationId: String, expected: BookRoleProfile, id: String) = changeBooks(operationId) {
        bookRepository.deleteCharacter(expected, id)
    }
    fun mergeBookCharacters(operationId: String, expected: BookRoleProfile, source: String, target: String) = changeBooks(operationId) {
        bookRepository.mergeCharacters(expected, source, target)
    }
    suspend fun bookRecoveryPreview(): Result<Pair<String, Int>> = withContext(Dispatchers.IO) { runCatching {
        rulesRepository.withConfigurationLock {
            val fingerprint = bookRepository.recoveryFingerprint()
            val backup = bookRepository.readBackup()
            fingerprint to backup.books.size
        }
    } }
    fun recoverBooks(operationId: String, fingerprint: String) = changeBooks(operationId) {
        transactionalConfiguration { bookRepository.recoverBackup(fingerprint) }
    }

    fun updateText(value: String) {
        _uiState.update { it.copy(text = value) }
        textSaveJob?.cancel()
        textSaveJob = viewModelScope.launch {
            delay(300)
            withContext(Dispatchers.IO) { textPreferences.edit().putString(KEY_LAST_TEXT, value).apply() }
        }
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

    /** Save a complete sheet draft once; the ViewModel owns the operation across rotation. */
    suspend fun saveConfiguredRole(rule: RoleRule, isNew: Boolean, expected: RoleRule? = null): Result<RoleRule> = viewModelScope.async {
        runCatching {
            check(_uiState.value.rolesLoadError == null) { "角色规则文件无法读取，请先恢复" }
            withContext(Dispatchers.Default) { RuleValidation.roleError(rule) }?.let { error(it) }
            if (isNew) require(rule.voiceIds.all { id -> _uiState.value.usableVoices.any { it.systemName == id } }) {
                "请选择可用音色"
            }
            val saved = withContext(Dispatchers.IO) { rulesRepository.upsertRole(rule, requireUniqueName = isNew, expected = expected) }
            _uiState.update { it.copy(roleGroups = saved.groups, roles = saved.items,
                statusMessage = "角色规则「${rule.name}」已保存") }
            refreshPreview()
            saved.items.first { it.id == rule.id }
        }
    }.await()

    /** Delete only the role; targeted replacements retain their original binding. */
    fun removeRolePreservingReplacements(roleId: String): Result<String> = runCatching {
        val (saved, role, linked) = rulesRepository.withConfigurationLock {
            val current = rulesRepository.loadRoles()
            val role = current.items.firstOrNull { it.id == roleId } ?: error("角色不存在")
            val linked = rulesRepository.loadReplacements().items.count {
                it.scope == ReplacementScope.ROLE && it.targetRoleId == roleId
            }
            val saved = RuleMutations(rulesRepository).roles { it.copy(items = it.items.filterNot { rule -> rule.id == roleId }) }
            Triple(saved, role, linked)
        }
        val message = "已删除角色「${role.name}」" + if (linked > 0) "，保留 $linked 条定向替换规则，重新绑定后生效" else ""
        _uiState.update { it.copy(roleGroups = saved.groups, roles = saved.items, statusMessage = message) }
        refreshPreview()
        message
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

    fun setUnattributedPolicy(policy: UnattributedPolicy) {
        if (_uiState.value.unattributedPolicy == policy) return
        settings.unattributedDialogue = policy.name
        _uiState.update { it.copy(unattributedPolicy = policy) }
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
        runCatching { settings.edgeEndpoint = value }.onFailure { error ->
            _uiState.update { it.copy(statusMessage = error.message.orEmpty()) }; return
        }
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

    fun importFirstSegmentEvidence(uri: Uri) { viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { settings.setFirstSegmentEvidence(readBackupText(uri)) } }
        refreshAll()
        _uiState.update { it.copy(statusMessage = result.exceptionOrNull()?.message ?: "测量报告已导入；达到门槛且匹配设备后可启用，当前仍为关闭") }
    } }
    fun setFirstSegmentExperiment(enabled: Boolean) {
        runCatching { settings.enableFirstSegmentExperiment(enabled) }.onFailure { error -> _uiState.update { it.copy(statusMessage = error.message.orEmpty()) } }
        refreshAll()
    }
    fun setKeepAwake(enabled: Boolean) {
        settings.keepAwake = enabled
        _uiState.update { it.copy(keepAwake = enabled) }
        if (PlaybackSession.activeCount() > 0) getApplication<Application>().startForegroundService(Intent(getApplication(), app.noveltts.service.PlaybackForegroundService::class.java))
    }

    fun clearRecentText() {
        textSaveJob?.cancel()
        textPreferences.edit().remove(KEY_LAST_TEXT).apply()
        _uiState.update { it.copy(text = "") }
        refreshPreview()
    }

    fun speak() {
        if (!initialLoad.isCompleted) {
            val generation = playbackGeneration.get()
            viewModelScope.launch { initialLoad.await(); if (generation == playbackGeneration.get()) speak() }
            return
        }
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
            val snapshot = withContext(Dispatchers.IO) { runCatching { rulesRepository.withConfigurationLock {
                val books = bookRepository.prepare(usableBookVoices())
                _uiState.update { it.copy(bookLibrary = books, booksLoadError = null) }
                state.copy(bookLibrary = books, booksLoadError = null)
            } } }
            val result = withContext(Dispatchers.Default) { snapshot.mapCatching { buildPlan(it) } }
            if (generation != playbackGeneration.get()) return@launch
            val plan = result.getOrNull()
            if (plan == null || plan.segments.isEmpty()) {
                _uiState.update { it.copy(isSpeaking = false, statusMessage = result.exceptionOrNull()?.message ?: "没有可朗读的文本片段。") }
                return@launch
            }
            playSegments(plan.segments, plan.pipeline.issues, "正在合成朗读…", playbackState = snapshot.getOrThrow())
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

    private fun buildPlan(state: MainUiState): BuiltSynthesisPlan = with(SynthesisPlanBuilder.build(
        text = state.text.also { check(state.booksLoadError == null) { state.booksLoadError.orEmpty() } },
        builtInRoles = state.builtInRoles,
        roles = state.roleSet,
        replacements = state.replacementSet,
        filterPurePunctuation = state.punctuationFilter,
        voices = state.voices,
        maxSegmentChars = state.maxSegmentChars,
        unattributedPolicy = state.unattributedPolicy,
        book = state.bookLibrary.activeBook,
    )) {
        val first = segments.firstOrNull()
        val effective = first?.let {
            val rate = app.noveltts.engine.api.SynthesisParameters.rate(sliderToRate(state.speed) * it.voice.defaultSpeed * it.segment.speechRate)
            val pitch = app.noveltts.engine.api.SynthesisParameters.pitch(sliderToPitch(state.pitch) * it.voice.defaultPitch * it.segment.speechPitch)
            val volume = app.noveltts.engine.api.SynthesisParameters.volume(sliderToVolume(state.volume) * it.voice.defaultVolume * it.segment.speechVolume)
            "$rate/$pitch/$volume"
        }
        val options = app.noveltts.engine.api.EngineRequestOptions(state.edgeEndpoint, state.requestTimeoutSec, state.localTtsEnginePackage)
        app.noveltts.domain.FirstSegmentExperiment.apply(this, first != null && settings.firstSegmentApplies(
            first.voice.systemName, engines.cacheRevision(first.voice, options), effective.orEmpty(),
        ))
    }

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
        val writtenFrames = AtomicLong()
        if (!isPreview) { readingHadError = false; saveReadingIndex() }
        _uiState.update { it.copy(isSpeaking = true, isPreview = isPreview, previewError = null,
            previewVoiceId = if (isPreview) segments.firstOrNull()?.voice?.systemName else null,
            // 朗读中点试听时保留「可继续」标记：播放条的控制行不再先收起、试听结束又弹出。
            isPaused = isPreview && it.isPaused, playbackError = false, currentSegmentIndex = if (isPreview) -1 else startIndex,
            playbackVoiceName = segments.first().voice.displayName, playbackIsDialogue = segments.first().segment.isDialogue,
            playbackFallbackReason = segments.first().segment.fallbackReason,
            completedSegments = if (isPreview) 0 else startIndex, totalSegments = total,
            playingSegmentIndex = segmentIndex, statusMessage = status, pipelineIssues = issues) }
        refreshBgm()
        synthesizer.configure(RetryPolicy(maxAttemptsPerVoice = playbackState.maxRetryCount + 1),
            playbackState.silenceBetweenSegmentsMs, playbackState.requestTimeoutSec)
        synthesizer.configureCache(playbackState.cacheEnabled, playbackState.cacheMaxMb)
        playbackLease = PlaybackSession.open(getApplication(), segments.any { it.voice.isOnline || it.standbyVoice?.isOnline == true }) {
            if (generation == playbackGeneration.get()) stop()
        }
        if (!audioPolicy.start()) {
            stopPlayback(updateState = false)
            _uiState.update { it.copy(isSpeaking = false, isPaused = !isPreview,
                statusMessage = "暂时无法获得音频焦点，请稍后继续朗读") }
            return
        }
        val requestId = java.util.UUID.randomUUID().toString().take(8)
        val handle = synthesizer.synthesize(segments, sliderToRate(playbackState.speed),
            sliderToPitch(playbackState.pitch), sliderToVolume(playbackState.volume),
            requestId = requestId,
            listener = playbackListener(generation, isPreview, finished, requestId, writtenFrames),
            options = app.noveltts.engine.api.EngineRequestOptions(playbackState.edgeEndpoint, playbackState.requestTimeoutSec, playbackState.localTtsEnginePackage),
            onProgress = { index, _, _ ->
                // 预取不改上一段的播放状态，避免合成提示覆盖正在播放的内容。
                updatePlaybackState(generation) { _uiState.update {
                    val waiting = isPreview || index + startIndex <= it.completedSegments
                    it.copy(
                    statusMessage = if (!waiting) it.statusMessage
                        else if (isPreview) "正在合成试听 · ${segments[index].voice.displayName}"
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
                    drainTrack(generation, writtenFrames.get())
                    updatePlaybackState(generation) {
                    if (!isPreview) { readingIndex = (startIndex + index + 1).coerceAtMost(total - 1); saveReadingIndex() }
                    _uiState.update { it.copy(completedSegments = startIndex + index + 1,
                        // 下一段还没备好时如实显示在等合成；已备好的话紧接着的 onPlaybackStart 会覆盖它。
                        statusMessage = if (!isPreview && index < segments.lastIndex)
                            "正在合成第 ${startIndex + index + 2}/$total 段 · ${segments[index + 1].voice.displayName}"
                            else it.statusMessage) } }
                }
            },
            // 整篇朗读时提前备好下一段，段间不再等一次完整的在线合成；试听只有一段，用不上。
            lookahead = !isPreview)
        if (generation == playbackGeneration.get() && !finished.get()) playbackHandle = handle else handle.cancel()
    }

    private fun updatePlaybackState(generation: Long, action: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { if (generation == playbackGeneration.get()) action() }
    }

    private fun playbackListener(generation: Long, isPreview: Boolean, finished: AtomicBoolean, requestId: String, writtenFrames: AtomicLong): SynthesisListener =
        object : SynthesisListener {
            private val firstWritten = AtomicBoolean(false)
            private val playbackStarted = System.nanoTime()
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
                    writtenFrames.addAndGet(count / 2L)
                    if (firstWritten.compareAndSet(false, true)) {
                        app.noveltts.engine.DiagnosticsLog.debug(app.noveltts.engine.Category.SPEECH,
                            "请求 $requestId 首次写入 AudioTrack", "elapsed=${(System.nanoTime() - playbackStarted) / 1_000_000}ms · format=24000/PCM16/mono")
                        viewModelScope.launch {
                            // Observe playback separately: a successful write only means PCM was queued.
                            while (generation == playbackGeneration.get()) {
                                if (synchronized(trackLock) { audioTrack !== track }) return@launch
                                val played = runCatching { track.playbackHeadPosition.toLong() and 0xffffffffL }.getOrNull() ?: return@launch
                                if (played > 0) {
                                    app.noveltts.engine.DiagnosticsLog.debug(app.noveltts.engine.Category.SPEECH,
                                        "请求 $requestId 播放头首次推进", "elapsed=${(System.nanoTime() - playbackStarted) / 1_000_000}ms · observedFrames=$played")
                                    return@launch
                                }
                                delay(5)
                            }
                        }
                    }
                }
            }
            override fun onComplete() {
                if (generation != playbackGeneration.get() || !finished.compareAndSet(false, true)) return
                val drained = runCatching { drainTrack(generation, writtenFrames.get()) }
                finishPlayback(generation, isPreview, drained.exceptionOrNull()?.message)
            }
            override fun onError(code: Int, message: String?) {
                if (generation != playbackGeneration.get() || !finished.compareAndSet(false, true)) return
                finishPlayback(generation, isPreview, message ?: "合成失败（code=$code）")
            }
        }

    private fun drainTrack(generation: Long, writtenFrames: Long) {
        val track = synchronized(trackLock) { audioTrack } ?: return
        val remaining = writtenFrames - (runCatching { track.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(writtenFrames))
        val deadline = android.os.SystemClock.elapsedRealtime() + remaining.coerceAtLeast(0) * 1000 / 24000 + 2500
        while (generation == playbackGeneration.get()) {
            val played = runCatching { track.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(writtenFrames)
            if (played >= writtenFrames) return
            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                app.noveltts.engine.DiagnosticsLog.warn(app.noveltts.engine.Category.SPEECH, "音频播放头等待超时，继续下一段")
                return
            }
            Thread.sleep(12)
        }
    }

    private fun finishPlayback(generation: Long, preview: Boolean, error: String?) { viewModelScope.launch(Dispatchers.Main.immediate) {
        if (generation != playbackGeneration.get()) return@launch
        releaseTrack(); playbackHandle = null
        if (audioPolicyDelegate.isInitialized()) audioPolicy.stop()
        playbackLease?.close(); playbackLease = null
        stopBgm(); activePlaybackSettings = null
        if (!preview) {
            readingHadError = error != null
            if (error == null) clearReadingSnapshot() else saveReadingIndex()
        }
        val resumable = readingSession != null || readingPreferences.contains("readingSnapshot")
        _uiState.update { (if (preview) it.withReadingProgress() else it).copy(isSpeaking = false, isPreview = false, previewVoiceId = null,
            isPaused = resumable, playingSegmentIndex = null, playbackError = resumable && readingHadError, previewError = if (preview) error else null,
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
        _uiState.update { it.copy(previewError = null) }
        if (!_uiState.value.isPreview) return
        stopPlayback(updateState = true)
        val resumable = readingSession != null || readingPreferences.contains("readingSnapshot")
        _uiState.update { it.withReadingProgress().copy(isPaused = resumable,
            isPreview = false, previewVoiceId = null, playbackError = resumable && readingHadError,
            statusMessage = if (resumable) "试听已停止，可继续原来的朗读" else "试听已停止") }
    }

    /** 试听会把进度改成「共 1 段」；试听结束后把进度行恢复成被暂停的那次朗读。 */
    private fun MainUiState.withReadingProgress(): MainUiState {
        val session = readingSession ?: return this
        return copy(totalSegments = session.segments.size, currentSegmentIndex = readingIndex, completedSegments = readingIndex)
    }
    fun pause() {
        if (_uiState.value.isPreview) { stopPreview(); return }
        if (!_uiState.value.isSpeaking || readingSession == null) return
        saveReadingIndex()
        stopPlayback(updateState = false)
        _uiState.update { it.copy(isSpeaking = false, isPaused = true,
            statusMessage = "已暂停，继续时从第 ${readingIndex + 1} 段开头恢复") }
        audioPolicy.paused()
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
            val result = withContext(Dispatchers.Default) { runCatching {
                val session = cached ?: restoreReadingSnapshot()
                val currentVoices = _uiState.value.voices
                session.copy(segments = app.noveltts.data.config.ReadingSnapshot.decode(
                    app.noveltts.data.config.ReadingSnapshot.encode(session.segments), currentVoices,
                    session.state.narratorVoiceId, session.state.dialogueVoiceId))
            } }
            if (generation != playbackGeneration.get()) return@launch
            val session = result.getOrNull()
            if (session == null) {
                _uiState.update { it.copy(isSpeaking = false, isPaused = readingPreferences.contains("readingSnapshot"),
                    statusMessage = "无法恢复朗读：${result.exceptionOrNull()?.message ?: "没有保存的进度"}") }
                return@launch
            }
            readingSession = session
            readingIndex = (if (cached == null) session.restoredIndex else savedIndex).coerceIn(session.segments.indices)
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
        val alternative = if (current.useDefaultStandby) _uiState.value.voices.firstOrNull {
            it.usable && it.systemName == current.standbyVoice?.systemName && it.systemName != current.voice.systemName
        } else _uiState.value.voices.firstOrNull {
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
                val root = JSONObject(readingPreferences.getString("readingSnapshot", "{}").orEmpty())
                val overrides = root.optJSONObject("overrides") ?: JSONObject()
                overrides.put(readingIndex.toString(), alternative.systemName)
                root.put("overrides", overrides)
                readingPreferences.edit().putString("readingSnapshot", root.toString()).apply()
            }
            playSegments(updated.segments.drop(readingIndex), status = "正在使用备用音色…", playbackState = updated.state, startIndex = readingIndex)
        }
    }
    private fun saveReadingSnapshot(state: MainUiState, segments: List<PlannedSegment>) {
        val snapshotId = UUID.randomUUID().toString()
        val root = JSONObject().put("readingId", snapshotId).put("text", state.text)
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
        readingPreferences.edit().putString("readingSnapshot", root.toString()).apply()
        preferences.edit().putString("readingId", snapshotId).putInt("readingIndex", 0).putInt("readingTotal", segments.size).apply()
    }
    private fun saveReadingIndex() { rulesRepository.withConfigurationLock {
        preferences.edit().putInt("readingIndex", readingIndex).putBoolean("readingError", readingHadError).apply()
    } }
    private fun clearReadingSnapshot() {
        readingSession = null
        readingHadError = false
        readingPreferences.edit().remove("readingSnapshot").apply()
        preferences.edit().remove("readingId").remove("readingIndex").remove("readingTotal").remove("readingError").apply()
    }
    private fun restoreReadingSnapshot(): ReadingSession {
        val root = JSONObject(readingPreferences.getString("readingSnapshot", null) ?: error("没有保存的朗读快照"))
        val roles = RulesImport.previewRoles(root.getString("roles"), emptyList(), emptyList(), externalImport = false)
        val replacements = RulesImport.previewReplacements(root.getString("replacements"), emptyList(), emptyList(), externalImport = false)
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
        val restoredIndex = if (root.optString("readingId", "") == preferences.getString("readingId", ""))
            preferences.getInt("readingIndex", 0) else 0
        return ReadingSession(state, segments, restoredIndex)
    }

    fun refreshAll() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            viewModelScope.launch(Dispatchers.IO) {
                try { refreshAll() }
                catch (error: Exception) { _uiState.update { it.copy(statusMessage = "配置读取失败：${error.message}") } }
                finally { initialLoad.complete(Unit) }
            }
            return
        }
        rulesRepository.withConfigurationLock {
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
        val books = runCatching { bookRepository.prepare(voices.filter { it.usable }.mapTo(HashSet()) { it.systemName }) }
        _uiState.update {
            it.copy(
                recoveryWarning = app.noveltts.data.backup.ConfigurationRecovery.lastError,
                packs = packs,
                packProblems = packStore.problems(),
                voiceOverrides = runCatching { voiceOverrides.load() }.getOrDefault(emptyMap()),
                voiceOverridesError = runCatching { voiceOverrides.load() }.exceptionOrNull()?.message,
                keepAwake = settings.keepAwake,
                firstSegmentEnabled = settings.firstSegmentEnabled,
                firstSegmentReady = settings.firstSegmentEvidence()?.accepted == true && settings.firstSegmentDeviceMatches(),
                readingProfiles = profiles.getOrNull() ?: it.readingProfiles,
                profilesLoadError = profiles.exceptionOrNull()?.message,
                bookLibrary = books.getOrNull() ?: it.bookLibrary,
                booksLoadError = books.exceptionOrNull()?.message,
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
                unattributedPolicy = UnattributedPolicy.fromStorage(settings.unattributedDialogue),
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
        initialLoad.complete(Unit)
    }

    /** 保存角色规则；[newGroup] 非空时与规则一起落盘，避免"先建组再保存"两次写入。 */
    fun saveRole(rule: RoleRule, newGroup: RuleGroup? = null, expected: RoleRule? = null): Boolean {
        RuleValidation.roleError(rule)?.let { error ->
            _uiState.update { it.copy(statusMessage = error) }; return false
        }
        val loadError = _uiState.value.rolesLoadError
        if (loadError != null) {
            _uiState.update { it.copy(statusMessage = "角色规则文件无法读取，已阻止保存：$loadError") }
            return false
        }
        val saved = try {
            rulesRepository.upsertRole(rule, newGroup = newGroup, expected = expected)
        } catch (error: RulesStoreException) {
            Log.w(TAG, "saveRole failed", error)
            _uiState.update { it.copy(statusMessage = "角色规则保存失败：${error.message}") }
            return false
        }
        _uiState.update { it.copy(roleGroups = saved.groups, roles = saved.items,
            statusMessage = "角色规则「${rule.name}」已保存") }
        refreshPreview()
        return true
    }

    /**
     * 把仍用旧式匹配范围的角色改为自动判断说话人。只改匹配范围；
     * 匹配词不是纯称呼（真正的正则）或还要匹配旁白的规则保持原样。
     */
    fun migrateRolesToAuto() = enqueueRuleChange {
        mutateRoles("角色匹配方式已更新") { current ->
            current.copy(items = current.items.map {
                if (RoleAliases.canSwitchToAuto(it)) it.copy(matchScope = RoleMatchScope.AUTO) else it
            })
        }
    }

    fun deleteRole(rule: RoleRule) = enqueueRuleChange {
        removeRolePreservingReplacements(rule.id).onFailure { error ->
            _uiState.update { it.copy(statusMessage = "删除失败：${error.message}") }
        }
    }

    fun toggleRole(rule: RoleRule, enabled: Boolean) = enqueueRuleChange {
        mutateRoles("角色启停状态已保存") { current ->
            check(current.items.any { it.id == rule.id }) { "角色已删除，请刷新后重试" }
            current.copy(items = current.items.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
        }
    }

    /** 保存替换规则；[newGroup] 非空时与规则一起落盘。 */
    fun saveReplacement(rule: ReplacementRule, newGroup: RuleGroup? = null, expected: ReplacementRule? = null): Boolean {
        RuleValidation.replacementError(rule)?.let { error ->
            _uiState.update { it.copy(statusMessage = error) }; return false
        }
        val loadError = _uiState.value.replacementsLoadError
        if (loadError != null) {
            _uiState.update { it.copy(statusMessage = "替换规则文件无法读取，已阻止保存：$loadError") }
            return false
        }
        val saved = try {
            rulesRepository.upsertReplacement(rule, newGroup, expected = expected)
        } catch (error: RulesStoreException) {
            Log.w(TAG, "saveReplacement failed", error)
            _uiState.update { it.copy(statusMessage = "替换规则保存失败：${error.message}") }
            return false
        }
        _uiState.update { it.copy(replacementGroups = saved.groups, replacements = saved.items,
            statusMessage = "替换规则「${rule.name}」已保存") }
        refreshPreview()
        return true
    }

    fun deleteReplacement(rule: ReplacementRule) = enqueueRuleChange {
        mutateReplacements("替换规则已删除") { current ->
            check(current.items.any { it.id == rule.id }) { "规则已删除，请刷新后重试" }
            current.copy(items = current.items.filterNot { it.id == rule.id })
        }
    }

    fun toggleReplacement(rule: ReplacementRule, enabled: Boolean) = enqueueRuleChange {
        mutateReplacements("替换规则启停状态已保存") { current ->
            check(current.items.any { it.id == rule.id }) { "规则已删除，请刷新后重试" }
            current.copy(items = current.items.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
        }
    }

    fun testReplacements(text: String, draft: ReplacementRule? = null, includeSaved: Boolean = true): String {
        val state = _uiState.value
        val rules = if (draft == null) state.replacements else
            (if (includeSaved) state.replacements.filterNot { it.id == draft.id } else emptyList()) +
                draft.copy(enabled = if (includeSaved) draft.enabled else true)
        val result = app.noveltts.domain.RuleRuntime.preview { TextPipeline.build(
        text = text,
        narratorVoiceId = null,
        dialogueVoiceId = null,
        roles = state.roles,
        replacements = rules,
        filterPurePunctuation = false,
        roleGroups = state.roleGroups,
        replacementGroups = if (includeSaved) state.replacementGroups else emptyList(),
    ) }
        return buildString {
            append(result.segments.joinToString("\n") { it.text })
            result.replacements.forEach { append("\n${it.ruleName}：${it.before} → ${it.after}") }
            result.issues.forEach { append("\n${it.message}") }
        }
    }

    fun rememberRoleDraft(rule: RoleRule, newGroupName: String, fields: Map<String, String>, expected: RoleRule?) {
        runCatching { rulesRepository.withConfigurationLock { preferences.edit().putString("roleDraft", RuleDrafts.encodeRole(rule, _uiState.value.roleGroups, newGroupName, fields, expected))
            .putString("roleDraftGroup", newGroupName).apply() } }
            .onFailure { error -> _uiState.update { it.copy(statusMessage = "草稿保存失败：${error.message}") } }
    }
    fun rememberReplacementDraft(rule: ReplacementRule, newGroupName: String, fields: Map<String, String>, expected: ReplacementRule?) {
        runCatching { rulesRepository.withConfigurationLock { preferences.edit().putString("replacementDraft", RuleDrafts.encodeReplacement(rule, _uiState.value.replacementGroups, newGroupName, fields, expected))
            .putString("replacementDraftGroup", newGroupName).apply() } }
            .onFailure { error -> _uiState.update { it.copy(statusMessage = "草稿保存失败：${error.message}") } }
    }
    fun roleDraft(): RuleEditorDraft<RoleRule>? = rulesRepository.withConfigurationLock { runCatching {
        val text = preferences.getString("roleDraft", null) ?: return@withConfigurationLock null
        RuleDrafts.readRole(text, preferences.getString("roleDraftGroup", "").orEmpty())
    }.onFailure { _uiState.update { it.copy(statusMessage = "角色草稿无法读取，原数据已保留") } }.getOrNull() }
    fun replacementDraft(): RuleEditorDraft<ReplacementRule>? = rulesRepository.withConfigurationLock { runCatching {
        val text = preferences.getString("replacementDraft", null) ?: return@withConfigurationLock null
        RuleDrafts.readReplacement(text, preferences.getString("replacementDraftGroup", "").orEmpty())
    }.onFailure { _uiState.update { it.copy(statusMessage = "替换草稿无法读取，原数据已保留") } }.getOrNull() }
    fun clearRuleDraft(kind: RulesKind) {
        val key = if (kind == RulesKind.ROLE) "roleDraft" else "replacementDraft"
        rulesRepository.withConfigurationLock { preferences.edit().remove(key).remove(key + "Group").apply() }
    }

    fun setCatalogUrl(url: String) {
        _uiState.update { it.copy(catalogUrl = url) }
        enqueueRuleChange { rulesRepository.withConfigurationLock { preferences.edit().putString(KEY_CATALOG_URL, url.trim()).apply() } }
    }

    fun importPack(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        updatePackBusy(PACK_BUSY_IMPORT, "正在导入语音包…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openInputStream(uri)?.use { stream ->
                        packStore.install(stream, expectedSha256 = null, sourceUrl = null, approveUpdate = ::approvePackUpdate)
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
                            packStore.install(stream, expectedSha256 = entry.sha256, sourceUrl = entry.url, approveUpdate = ::approvePackUpdate)
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
            _uiState.update { it.copy(packStatus = if (busy != null) "正在处理另一个语音包，请完成后重新安装" else "语音包地址为空") }
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
                            packStore.install(stream, expectedSha256 = null, sourceUrl = normalized, approveUpdate = ::approvePackUpdate)
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

    private fun approvePackUpdate(candidate: VoicePack, previous: VoicePack): Boolean {
        val current = _uiState.value
        val ids = current.voices.filter { it.packId == previous.id }.map { it.systemName }.toSet()
        val decision = PackDecision()
        check(packDecision.compareAndSet(null, decision)) { "已有等待确认的语音包更新" }
        _uiState.update { it.copy(pendingPackUpdate = PackUpdatePreview(candidate.name, previous.version, candidate.version,
            current.roles.count { role -> role.voiceIds.any { it in ids } },
            listOf(current.narratorVoiceId, current.dialogueVoiceId, current.systemVoiceId).count { it in ids })) }
        return try { decision.ready.await(5, java.util.concurrent.TimeUnit.MINUTES) && decision.accepted }
        finally { packDecision.compareAndSet(decision, null); _uiState.update { it.copy(pendingPackUpdate = null) } }
    }
    fun confirmPackUpdate(accepted: Boolean) {
        packDecision.get()?.let { it.accepted = accepted; it.ready.countDown() }
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
        packDecision.getAndSet(null)?.ready?.countDown()
        stopPlayback(updateState = false)
        bgmPlayer?.release()
        bgmPlayer = null
        synthesizer.shutdown()
        if (audioPolicyDelegate.isInitialized()) audioPolicy.release()
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
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            viewModelScope.launch { refreshPreview() }
            return
        }
        previewJob?.cancel()
        val generation = ++previewGeneration
        val state = _uiState.value
        _uiState.update { it.copy(previewBusy = true) }
        previewJob = viewModelScope.launch {
        delay(180)
        val built = withContext(Dispatchers.Default) { runCatching { app.noveltts.domain.RuleRuntime.preview { buildPlan(state).pipeline } } }
        if (generation != previewGeneration) return@launch
        val result = built.getOrElse { error ->
            _uiState.update { it.copy(preview = emptyList(), pipelineIssues = emptyList(), previewBusy = false,
                previewError = error.message, statusMessage = "分段预览失败：${error.message}") }
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
                explanation = segment.fallbackReason ?: speakerExplanation(segment),
                uncertain = segment.attribution?.uncertain == true,
            )
        }
        _uiState.update { it.copy(preview = preview, pipelineIssues = result.issues, previewBusy = false, previewError = null) }
        }
    }

    /** 一句话说明这一段为什么由这个角色朗读。 */
    private fun speakerExplanation(segment: PipelineSegment): String {
        val attribution = segment.attribution
        val evidence = attribution?.evidence.orEmpty()
        return when (attribution?.kind) {
            AttributionKind.LEAD_IN, AttributionKind.NEARBY -> "前文「$evidence」"
            AttributionKind.TRAILING -> "后文「$evidence」"
            AttributionKind.CONTINUED -> "没写是谁，沿用上一句的说话人"
            AttributionKind.LEGACY_RULE -> "命中角色规则「${segment.roleName}」"
            AttributionKind.UNKNOWN -> "没写是谁，使用默认对话音色"
            null -> when {
                segment.roleName != null -> "命中角色规则「${segment.roleName}」"
                segment.isDialogue -> "未命中角色，使用默认对话音色"
                else -> "使用旁白音色"
            }
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

    private fun mutateRoles(message: String, change: (RuleSet<RoleRule>) -> RuleSet<RoleRule>): Boolean = try {
        val saved = RuleMutations(rulesRepository).roles(change)
        _uiState.update { it.copy(roleGroups = saved.groups, roles = saved.items, statusMessage = message) }
        refreshPreview()
        true
    } catch (error: Exception) {
        _uiState.update { it.copy(statusMessage = "保存失败：${error.message}") }
        false
    }

    private fun mutateReplacements(message: String, change: (RuleSet<ReplacementRule>) -> RuleSet<ReplacementRule>): Boolean = try {
        val saved = RuleMutations(rulesRepository).replacements(change)
        _uiState.update { it.copy(replacementGroups = saved.groups, replacements = saved.items, statusMessage = message) }
        refreshPreview()
        true
    } catch (error: Exception) {
        _uiState.update { it.copy(statusMessage = "保存失败：${error.message}") }
        false
    }

    /** 新建或重命名角色分组。 */
    fun saveRoleGroup(group: RuleGroup) {
        val base = _uiState.value.roleGroups.firstOrNull { it.id == group.id }
        enqueueRuleChange { mutateRoles("角色分组已保存") { RuleMutations.saveGroup(it, group, base) } }
    }

    /** 删除分组，组内规则移到默认分组而不是一起删除。 */
    fun deleteRoleGroup(group: RuleGroup) = enqueueRuleChange {
        mutateRoles("分组已删除，规则已移到默认分组") { current ->
            check(group.id != RuleGroup.DEFAULT_ID) { "默认分组不能删除" }
            current.copy(groups = current.groups.filterNot { it.id == group.id }, items = current.items.map {
                if (it.groupId == group.id) it.copy(groupId = RuleGroup.DEFAULT_ID) else it
            })
        }
    }

    fun saveReplacementGroup(group: RuleGroup) {
        val base = _uiState.value.replacementGroups.firstOrNull { it.id == group.id }
        enqueueRuleChange { mutateReplacements("替换分组已保存") { RuleMutations.saveGroup(it, group, base) } }
    }

    fun deleteReplacementGroup(group: RuleGroup) = enqueueRuleChange {
        mutateReplacements("分组已删除，规则已移到默认分组") { current ->
            check(group.id != RuleGroup.DEFAULT_ID) { "默认分组不能删除" }
            current.copy(groups = current.groups.filterNot { it.id == group.id }, items = current.items.map {
                if (it.groupId == group.id) it.copy(groupId = RuleGroup.DEFAULT_ID) else it
            })
        }
    }

    /** 拖拽落点：把规则移动到 [targetGroupId] 的第 [targetIndex] 个位置（含跨组）。 */
    fun moveRole(ruleId: String, targetGroupId: String, targetIndex: Int) {
        val ordered = _uiState.value.roles.filter { it.groupId == targetGroupId }.sortedBy { it.sortIndex }
        val anchor = RuleMutations.anchor(ordered.map { it.id }, ruleId, targetIndex) ?: return
        enqueueRuleChange { mutateRoles("角色顺序已保存") { RuleMutations.move(it, ruleId, targetGroupId, anchor.id, anchor.after,
            RoleRule::id, RoleRule::groupId, RoleRule::sortIndex) { item, group, rank -> item.copy(groupId = group, sortIndex = rank) } } }
    }

    fun moveReplacement(ruleId: String, targetGroupId: String, targetIndex: Int) {
        val ordered = _uiState.value.replacements.filter { it.groupId == targetGroupId }.sortedBy { it.sortIndex }
        val anchor = RuleMutations.anchor(ordered.map { it.id }, ruleId, targetIndex) ?: return
        enqueueRuleChange { mutateReplacements("替换顺序已保存") { RuleMutations.move(it, ruleId, targetGroupId, anchor.id, anchor.after,
            ReplacementRule::id, ReplacementRule::groupId, ReplacementRule::sortIndex) { item, group, rank -> item.copy(groupId = group, sortIndex = rank) } } }
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
        }.also { result -> _uiState.update { it.copy(statusMessage = result.getOrElse { error -> "导出失败：${error.message}" }) } }
    }

    /** 第一步：读取并解析导入文件，产出冲突预览（不写盘）。 */
    suspend fun prepareRoleImport(uri: Uri): Result<RulesImportPreview<RoleRule>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = readImportText(uri)
            rulesRepository.withConfigurationLock {
                val current = rulesRepository.loadRoles()
                RulesImport.previewRoles(text, current.groups, current.items)
            }
        }
    }

    suspend fun prepareReplacementImport(uri: Uri): Result<RulesImportPreview<ReplacementRule>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = readImportText(uri)
                rulesRepository.withConfigurationLock {
                    val current = rulesRepository.loadReplacements()
                    RulesImport.previewReplacements(text, current.groups, current.items)
                }
            }
        }

    /** 第二步：按用户选择的策略合并并落盘。 */
    suspend fun applyRoleImport(preview: RulesImportPreview<RoleRule>, strategy: RuleImportStrategy): Result<RulesImportApplyResult<RoleRule>> = withContext(Dispatchers.IO) { runCatching {
        val result = RuleMutations(rulesRepository).applyRoles(preview, strategy)
        if (result is RulesImportApplyResult.Applied) {
            val saved = result.configuration
            _uiState.update { it.copy(roleGroups = saved.groups, roles = saved.items,
                statusMessage = "导入完成，当前共 ${saved.items.size} 条角色规则") }
            refreshPreview()
        }
        result
    } }

    suspend fun applyReplacementImport(preview: RulesImportPreview<ReplacementRule>, strategy: RuleImportStrategy): Result<RulesImportApplyResult<ReplacementRule>> = withContext(Dispatchers.IO) { runCatching {
        val result = RuleMutations(rulesRepository).applyReplacements(preview, strategy)
        if (result is RulesImportApplyResult.Applied) {
            val saved = result.configuration
            _uiState.update { it.copy(replacementGroups = saved.groups, replacements = saved.items,
                statusMessage = "导入完成，当前共 ${saved.items.size} 条替换规则") }
            refreshPreview()
        }
        result
    } }

    /** 用"已保存"的角色规则跑一次测试，列出命中的角色与音色（编辑中的内容不参与）。 */
    fun testRoleMatch(text: String, draft: RoleRule? = null, includeSaved: Boolean = true): String {
        if (text.isBlank()) return "请输入测试文本"
        val state = _uiState.value
        val rules = if (draft == null) state.roles else
            (if (includeSaved) state.roles.filterNot { it.id == draft.id } else emptyList()) +
                draft.copy(enabled = if (includeSaved) draft.enabled else true)
        val result = app.noveltts.domain.RuleRuntime.preview { SynthesisPlanBuilder.build(
            text = text,
            builtInRoles = state.builtInRoles,
            roles = RuleSet(if (includeSaved) state.roleGroups else emptyList(), rules),
            replacements = state.replacementSet,
            filterPurePunctuation = false,
            voices = state.voices,
            unattributedPolicy = state.unattributedPolicy,
        ).pipeline }
        if (result.segments.isEmpty()) return "没有可测试的片段。"
        val matched = result.segments.mapIndexed { index, segment ->
            "第 ${index + 1} 段「${segment.text.take(30)}」→ ${segment.roleName ?: if (segment.isDialogue) "默认对话" else "旁白"} · ${voiceName(segment.voiceId)}" +
                " · ${speakerExplanation(segment)}" + (if (segment.attribution?.uncertain == true) "（不确定）" else "") +
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
        viewModelScope.launch(Dispatchers.IO) { synthesizer.configureCache(settings.cacheEnabled, settings.cacheMaxMb) }
    }

    fun setCacheMaxMb(value: Int) {
        saveIntSetting(
            value = value,
            range = SettingsStore.CACHE_MAX_MB_RANGE,
            setter = { settings.cacheMaxMb = it },
            render = { state, v -> state.copy(cacheMaxMb = v) },
        )
        viewModelScope.launch(Dispatchers.IO) { synthesizer.configureCache(settings.cacheEnabled, settings.cacheMaxMb) }
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
        }.onFailure {
            _uiState.update { it.copy(statusMessage = "背景音乐已选择，但无法保留文件权限，重启后可能需要重新选择") }
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
    suspend fun saveVoiceOverride(id: String, draft: app.noveltts.data.voicepack.VoiceOverride,
        expected: app.noveltts.data.voicepack.VoiceOverride?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { voiceOverrides.save(id, draft, expected); VoicePackEvents.notifyChanged(getApplication()); refreshAll() }
    }
    fun recoverVoiceOverrides() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { voiceOverrides.recoverBackup(); VoicePackEvents.notifyChanged(getApplication()) } }
            refreshAll()
            _uiState.update { it.copy(statusMessage = result.exceptionOrNull()?.message ?: "音色配置备份已恢复") }
        }
    }
    fun resetVoiceOverrides() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { voiceOverrides.resetCorrupt() } }
            if (result.isSuccess) VoicePackEvents.notifyChanged(getApplication())
            refreshAll()
            _uiState.update { it.copy(statusMessage = result.exceptionOrNull()?.message ?: "音色配置已重置，原文件已保留") }
        }
    }
    fun backupFileName(): String = BackupManager.exportFileName()

    /** 生成备份 JSON；[includeSensitive] 为 false 时不写许可证与访问令牌。 */
    fun backupJson(includeSensitive: Boolean): String = rulesRepository.withConfigurationLock { BackupManager.exportJson(
        roleSet = rulesRepository.loadRoles(),
        replacementSet = rulesRepository.loadReplacements(),
        settings = settingsSnapshot(),
        appVersion = getApplication<Application>().let { app ->
            app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        },
        voiceOverrides = voiceOverrides.load(),
        profiles = ReadingProfiles.decode(preferences.getString(ReadingProfiles.KEY, null)),
        bookLibrary = bookRepository.load(),
        voiceHints = _uiState.value.voices.associate { it.systemName to it.displayName },
        credentials = if (includeSensitive) {
            BackupCredentials(settings.license(), settings.forwardToken)
        } else {
            null
        },
    ) }

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
                    append("、书籍 "); append(state.bookLibrary.books.size); append(" 本")
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
                rulesRepository.withConfigurationLock { BackupManager.previewRestore(
                    backup = parsed,
                    currentRoleGroups = state.roleGroups,
                    currentRoles = state.roles,
                    currentReplacementGroups = state.replacementGroups,
                    currentReplacements = state.replacements,
                    bookRevision = runCatching { bookRepository.load().revision }.getOrNull(),
                    bookFingerprint = bookRepository.recoveryFingerprint(),
                ) }
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
            mapped.backup.bookLibrary?.let { incoming ->
                check(mapped.bookFingerprint == bookRepository.recoveryFingerprint()) { "书籍配置已变化，请重新预览备份" }
                val current = runCatching { bookRepository.load() }
                check(current.isSuccess || repairCorrupt) { "书籍配置无法读取，请先确认使用备份修复" }
                check(current.getOrNull()?.revision == mapped.bookRevision) { "书籍配置已变化，请重新预览备份" }
                bookRepository.restore(BookRolesRepository.merge(current.getOrDefault(BookLibrary()), incoming, strategy),
                    mapped.bookRevision, repairCorrupt = current.isFailure && repairCorrupt)
            }
            if (!repairCorrupt) {
                check(mapped.roles.baseFingerprint == RulesImport.rolesFingerprint(rulesRepository.loadRoles()) &&
                    mapped.replacements.baseFingerprint == RulesImport.replacementsFingerprint(rulesRepository.loadReplacements())) {
                    "现有规则已变化，请重新导入备份并核对预览；未写入任何配置"
                }
            }
            rulesRepository.restoreAll(roles, replacements)
            if (applySettings) {
                applyBackupSettings(mapped.backup.settings)
                mapped.backup.voiceOverrides?.let { incoming ->
                    val current = if (repairCorrupt) runCatching { voiceOverrides.load() }.getOrDefault(emptyMap()) else voiceOverrides.load()
                    voiceOverrides.restore(when (strategy) {
                        RuleImportStrategy.REPLACE_ALL -> incoming
                        RuleImportStrategy.SKIP -> incoming + current
                        RuleImportStrategy.OVERWRITE -> current + incoming
                    })
                }
            }
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
        val message = "已还原：角色 ${roles.items.size} 条、替换 ${replacements.items.size} 条、阅读方案 ${profiles.size} 个；" +
            (if (mapped.backup.bookLibrary == null) "本机书籍保留" else "书籍已按所选策略合并")
        _uiState.update { it.copy(statusMessage = message) }
        message
    }

    private fun transactionalConfiguration(apply: () -> Unit) = rulesRepository.withConfigurationLock {
        settings.flush()
        val settingsBefore = settings.snapshot()
        val credentialsBefore = settings.credentialSnapshot()
        val restoreApp = getApplication<Application>()
        val shared = File(restoreApp.applicationInfo.dataDir, "shared_prefs")
        app.noveltts.data.backup.ConfigurationRecovery.transaction(restoreApp).run(
            rulesRepository.transactionFiles() + bookRepository.files() + voiceOverrides.files() + listOf(File(shared, "multitts.xml"), File(shared, "multitts.xml.bak"),
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
            val currentRoles = rulesRepository.loadRoles()
            val currentReplacements = rulesRepository.loadReplacements()
            check(dropMissingGroups || (saved.roleGroups.all { id -> currentRoles.groups.any { it.id == id } } &&
                saved.replacementGroups.all { id -> currentReplacements.groups.any { it.id == id } })) { "分组已变化，请重新预览阅读方案" }
            rulesRepository.restoreAll(currentRoles.copy(groups = currentRoles.groups.map { it.copy(enabled = it.id in saved.roleGroups) }),
                currentReplacements.copy(groups = currentReplacements.groups.map { it.copy(enabled = it.id in saved.replacementGroups) }))
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
        "keepAwake" to settings.keepAwake,
        "narratorVoice" to settings.narratorVoice,
        "dialogueVoice" to settings.dialogueVoice,
        "systemVoice" to settings.systemVoice,
        "edgeEndpoint" to settings.edgeEndpoint,
        "compatibilityProxy" to settings.compatibilityProxy,
        "themeMode" to settings.themeMode,
        "unattributedDialogue" to settings.unattributedDialogue,
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
                key == "unattributedDialogue" -> UnattributedPolicy.fromStorage(value as? String).name
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
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("无法写入所选文件")
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
        isPaused = readingPreferences.contains("readingSnapshot"),
        playbackError = readingPreferences.contains("readingSnapshot") && preferences.getBoolean("readingError", false),
        currentSegmentIndex = preferences.getInt("readingIndex", -1),
        totalSegments = preferences.getInt("readingTotal", 0),
        text = textPreferences.getString(KEY_LAST_TEXT, "").orEmpty().ifBlank { DEFAULT_TEST_TEXT },
        speed = preferences.getInt(KEY_SPEED, 50),
        volume = preferences.getInt(KEY_VOLUME, 50),
        pitch = preferences.getInt(KEY_PITCH, 25),
        keepAwake = settings.keepAwake,
        builtInRoles = settings.builtInRoles(),
        globalControlsExpanded = preferences.getBoolean(KEY_GLOBAL_CONTROLS_EXPANDED, true),
        systemVoiceId = settings.systemVoice,
        punctuationFilter = settings.punctuationFilter,
        unattributedPolicy = UnattributedPolicy.fromStorage(settings.unattributedDialogue),

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
        if (audioPolicyDelegate.isInitialized()) audioPolicy.stop()
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
        val state = _uiState.value
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

    private fun sliderToRate(value: Int): Float = app.noveltts.engine.api.SynthesisParameters.globalRate(value)
    private fun sliderToPitch(value: Int): Float = app.noveltts.engine.api.SynthesisParameters.globalPitch(value)
    private fun sliderToVolume(value: Int): Float = app.noveltts.engine.api.SynthesisParameters.globalVolume(value)
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

        const val KEY_CATALOG_URL = "catalogUrl"
        const val PACK_BUSY_CATALOG = "catalog"
    }
}
