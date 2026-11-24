package app.noveltts.data.settings

import android.content.Context
import app.noveltts.data.config.CONFIGURATION_LOCK
import app.noveltts.domain.BuiltInRole
import app.noveltts.domain.BuiltInRoleSettings
import app.noveltts.domain.RolePlaybackSettings
import app.noveltts.domain.UnattributedPolicy
import app.noveltts.engine.DiagnosticsLog
import app.noveltts.engine.api.EngineRequestOptions

data class SynthesisSettings(
    val narratorVoice: String, val dialogueVoice: String, val systemVoice: String,
    val punctuationFilter: Boolean, val maxSegmentChars: Int, val maxRetryCount: Int,
    val silenceBetweenSegmentsMs: Int, val cacheEnabled: Boolean, val cacheMaxMb: Int,
    val licenseConfigured: Boolean, val options: EngineRequestOptions,
    val builtInRoles: BuiltInRoleSettings,
    val unattributedPolicy: UnattributedPolicy,
)

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("multitts", Context.MODE_PRIVATE)
    private val secrets = SecretStore(appContext)

    fun claimNotificationPermissionPrompt(): Boolean = synchronized(CONFIGURATION_LOCK) {
        if (preferences.getBoolean("notificationPermissionRequested", false)) false
        else { preferences.edit().putBoolean("notificationPermissionRequested", true).apply(); true }
    }

    /** Default off. A passing, device-specific evidence report is required before enabling. */
    val firstSegmentEnabled: Boolean
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getBoolean("firstSegmentEnabled", false) && firstSegmentEvidence()?.accepted == true
        }
    fun firstSegmentEvidence(): app.noveltts.domain.FirstSegmentEvidence? = synchronized(CONFIGURATION_LOCK) {
        runCatching { preferences.getString("firstSegmentEvidence", null)?.let(app.noveltts.domain.FirstSegmentEvidence::parse) }.getOrNull()
    }
    fun setFirstSegmentEvidence(report: String) {
        app.noveltts.domain.FirstSegmentEvidence.parse(report)
        commitValues(mapOf("firstSegmentEvidence" to report, "firstSegmentEnabled" to false))
    }
    fun firstSegmentDeviceMatches(): Boolean = firstSegmentEvidence()?.device == "${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}/${android.os.Build.VERSION.SDK_INT}"
    fun firstSegmentApplies(voiceId: String, voiceRevision: String, parameters: String): Boolean = synchronized(CONFIGURATION_LOCK) {
        preferences.getBoolean("firstSegmentEnabled", false) && firstSegmentEvidence()?.matches(
            "${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}/${android.os.Build.VERSION.SDK_INT}", voiceId, voiceRevision, parameters,
        ) == true
    }
    fun enableFirstSegmentExperiment(enabled: Boolean) {
        require(!enabled || firstSegmentEvidence()?.accepted == true && firstSegmentDeviceMatches()) { "尚未达到首段实验启用门槛：至少 30 组配对，首音中位数改善 10%，P95 不变差且取消续读通过" }
        commitValues(mapOf("firstSegmentEnabled" to enabled))
    }

    var keepAwake: Boolean
        get() = synchronized(CONFIGURATION_LOCK) { preferences.getBoolean("keepAwake", false) }
        set(value) = commitValues(mapOf("keepAwake" to value))

    var punctuationFilter: Boolean
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getBoolean(KEY_PUNCTUATION_FILTER, true)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putBoolean(KEY_PUNCTUATION_FILTER, value).apply()
        }

    var narratorVoice: String
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getString(KEY_NARRATOR, "").orEmpty()
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putString(KEY_NARRATOR, value).apply()
        }

    var dialogueVoice: String
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getString(KEY_DIALOGUE, "").orEmpty()
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putString(KEY_DIALOGUE, value).apply()
        }

    /** 旁白/对话的音色沿用旧键，倍率是新增键；缺失或类型不符时按 1 倍处理。 */
    fun builtInRoles(): BuiltInRoleSettings = synchronized(CONFIGURATION_LOCK) {
        BuiltInRoleSettings(readBuiltInRole(BuiltInRole.NARRATOR), readBuiltInRole(BuiltInRole.DIALOGUE))
    }

    fun commitBuiltInRole(role: BuiltInRole, value: RolePlaybackSettings) =
        commitValues(builtInRoleValues(role, value))

    private fun readBuiltInRole(role: BuiltInRole): RolePlaybackSettings {
        val keys = builtInRoleKeys(role)
        fun multiplier(key: String) = runCatching { preferences.getFloat(key, 1f) }.getOrDefault(1f)
        return RolePlaybackSettings(
            voiceId = preferences.getString(keys.voice, "").orEmpty(),
            rate = multiplier(keys.rate), pitch = multiplier(keys.pitch), volume = multiplier(keys.volume),
        ).normalized()
    }

    var systemVoice: String
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getString(KEY_SYSTEM_VOICE, "").orEmpty()
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putString(KEY_SYSTEM_VOICE, value).apply()
        }

    var edgeEndpoint: String
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getString(KEY_EDGE_ENDPOINT, DEFAULT_EDGE_ENDPOINT)
            .orEmpty()
            .takeIf { it.isNotBlank() && it != LEGACY_EDGE_ENDPOINT }
            ?: DEFAULT_EDGE_ENDPOINT
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            val endpoint = value.trim().ifBlank { DEFAULT_EDGE_ENDPOINT }
            require(isSecureEdgeEndpoint(endpoint)) { "Edge 端点必须使用有效的 WSS 地址" }
            preferences.edit().putString(KEY_EDGE_ENDPOINT, endpoint).apply()
        }

    var compatibilityProxy: Boolean
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getBoolean(KEY_COMPATIBILITY_PROXY, false)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putBoolean(KEY_COMPATIBILITY_PROXY, value).apply()
        }

    // ---- 外观 ---------------------------------------------------------------

    /** 主题模式：LIGHT 固定浅色、DARK 固定深色、SYSTEM 跟随系统；未知值回落到默认。 */
    var themeMode: String
        get() = synchronized(CONFIGURATION_LOCK) {
            normalizeThemeMode(preferences.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE))
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putString(KEY_THEME_MODE, normalizeThemeMode(value)).apply()
        }

    /** 没写是谁说的对话怎么读，取值见 [UnattributedPolicy]；未知值回落到默认。 */
    var unattributedDialogue: String
        get() = synchronized(CONFIGURATION_LOCK) {
            UnattributedPolicy.fromStorage(preferences.getString(KEY_UNATTRIBUTED_DIALOGUE, null)).name
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putString(KEY_UNATTRIBUTED_DIALOGUE, UnattributedPolicy.fromStorage(value).name).apply()
        }

    // ---- 阶段 7：合成参数 ----------------------------------------------------

    /** 在线合成失败后的重试次数（0-5），默认 1。 */
    var maxRetryCount: Int
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getInt(KEY_MAX_RETRY_COUNT, DEFAULT_MAX_RETRY_COUNT)
            .coerceIn(RETRY_COUNT_RANGE)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putInt(KEY_MAX_RETRY_COUNT, value.coerceIn(RETRY_COUNT_RANGE)).apply()
        }

    /** 单次请求超时（秒，10-180），默认 60。 */
    var requestTimeoutSec: Int
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getInt(KEY_REQUEST_TIMEOUT_SEC, DEFAULT_REQUEST_TIMEOUT_SEC)
            .coerceIn(REQUEST_TIMEOUT_RANGE)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putInt(KEY_REQUEST_TIMEOUT_SEC, value.coerceIn(REQUEST_TIMEOUT_RANGE)).apply()
        }

    /** 单段最大字符数（100-1000），默认 500。 */
    var maxSegmentChars: Int
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getInt(KEY_MAX_SEGMENT_CHARS, DEFAULT_MAX_SEGMENT_CHARS)
            .coerceIn(SEGMENT_CHARS_RANGE)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putInt(KEY_MAX_SEGMENT_CHARS, value.coerceIn(SEGMENT_CHARS_RANGE)).apply()
        }

    /** 相邻片段之间的静音时长（毫秒），默认 80。 */
    var silenceBetweenSegmentsMs: Int
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getInt(KEY_SILENCE_MS, DEFAULT_SILENCE_MS).coerceIn(SILENCE_RANGE)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putInt(KEY_SILENCE_MS, value.coerceIn(SILENCE_RANGE)).apply()
        }

    // ---- 阶段 7：缓存 --------------------------------------------------------

    var cacheEnabled: Boolean
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getBoolean(KEY_CACHE_ENABLED, DEFAULT_CACHE_ENABLED)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putBoolean(KEY_CACHE_ENABLED, value).apply()
        }

    /** 缓存上限（MB，10-1024），默认 100。 */
    var cacheMaxMb: Int
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getInt(KEY_CACHE_MAX_MB, DEFAULT_CACHE_MAX_MB).coerceIn(CACHE_MAX_MB_RANGE)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putInt(KEY_CACHE_MAX_MB, value.coerceIn(CACHE_MAX_MB_RANGE)).apply()
        }

    // ---- 阶段 7：背景音乐 ----------------------------------------------------

    var bgmEnabled: Boolean
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getBoolean(KEY_BGM_ENABLED, DEFAULT_BGM_ENABLED)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putBoolean(KEY_BGM_ENABLED, value).apply()
        }

    /** 背景音乐音量（0-100），默认 30。 */
    var bgmVolume: Int
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getInt(KEY_BGM_VOLUME, DEFAULT_BGM_VOLUME).coerceIn(BGM_VOLUME_RANGE)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putInt(KEY_BGM_VOLUME, value.coerceIn(BGM_VOLUME_RANGE)).apply()
        }

    var bgmLoop: Boolean
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getBoolean(KEY_BGM_LOOP, DEFAULT_BGM_LOOP)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putBoolean(KEY_BGM_LOOP, value).apply()
        }

    /** 朗读时自动压低背景音乐。 */
    var bgmDuck: Boolean
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getBoolean(KEY_BGM_DUCK, DEFAULT_BGM_DUCK)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putBoolean(KEY_BGM_DUCK, value).apply()
        }

    /** SAF 授予的音频文件 URI；空串表示未选择。 */
    var bgmUri: String
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getString(KEY_BGM_URI, "").orEmpty()
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putString(KEY_BGM_URI, value).apply()
        }

    // ---- 阶段 7：外部调用（Legado HTTP 转发骨架） -----------------------------

    var forwardEnabled: Boolean
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getBoolean(KEY_FORWARD_ENABLED, DEFAULT_FORWARD_ENABLED)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putBoolean(KEY_FORWARD_ENABLED, value).apply()
        }

    /** 转发端口（1024-65535），默认 8080。 */
    var forwardPort: Int
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getInt(KEY_FORWARD_PORT, DEFAULT_FORWARD_PORT)
            .coerceIn(FORWARD_PORT_RANGE)
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            preferences.edit().putInt(KEY_FORWARD_PORT, value.coerceIn(FORWARD_PORT_RANGE)).apply()
        }

    /** 绑定地址：127.0.0.1 只监听本机，0.0.0.0 会监听所有网卡。 */
    var forwardBind: String
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getString(KEY_FORWARD_BIND, DEFAULT_FORWARD_BIND)
            .orEmpty()
            .takeIf { it == FORWARD_BIND_LOOPBACK || it == FORWARD_BIND_ALL }
            ?: DEFAULT_FORWARD_BIND
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            val normalized = if (value == FORWARD_BIND_ALL) FORWARD_BIND_ALL else FORWARD_BIND_LOOPBACK
            preferences.edit().putString(KEY_FORWARD_BIND, normalized).apply()
        }

    /** 外部调用访问令牌；空串表示尚未生成。 */
    var forwardToken: String
        get() = synchronized(CONFIGURATION_LOCK) {
            if (preferences.contains(KEY_FORWARD_TOKEN)) {
                val legacy = preferences.getString(KEY_FORWARD_TOKEN, "").orEmpty()
                if (secrets.encryptedValue(KEY_FORWARD_TOKEN) == null && legacy.isNotBlank()) secrets.put(KEY_FORWARD_TOKEN, legacy)
                check(preferences.edit().remove(KEY_FORWARD_TOKEN).commit()) { "旧令牌迁移失败" }
            }
            val encrypted = secrets.encryptedValue(KEY_FORWARD_TOKEN)
            if (encrypted == null) "" else secrets.get(KEY_FORWARD_TOKEN)
                ?: throw IllegalStateException("访问令牌无法解密，请重新设置")
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            secrets.put(KEY_FORWARD_TOKEN, value.trim())
            check(preferences.edit().remove(KEY_FORWARD_TOKEN).commit()) { "旧令牌清理失败" }
        }

    // ---- 阶段 5：本地系统 TTS 桥接 -------------------------------------------

    /**
     * 用户配置的本地系统 TTS 引擎包名；空串表示不桥接。
     *
     * 语音包里的引擎代码写成 "android-<包名>" 时，合成会委托给该本地引擎。
     * 本应用自身的包名会被拒绝，避免合成递归回自己。
     */
    var localTtsEnginePackage: String
        get() = synchronized(CONFIGURATION_LOCK) {
            preferences.getString(KEY_LOCAL_TTS_ENGINE_PACKAGE, "").orEmpty().trim()
        }
        set(value) = synchronized(CONFIGURATION_LOCK) {
            val trimmed = value.trim()
            // 本应用自己的包名一律落库为空：否则合成会递归回自己。
            val safe = if (trimmed == appContext.packageName) "" else trimmed
            preferences.edit().putString(KEY_LOCAL_TTS_ENGINE_PACKAGE, safe).apply()
            app.noveltts.data.voicepack.VoicePackEvents.invalidate()
        }

    fun setLicense(value: String?) = synchronized(CONFIGURATION_LOCK) {
        secrets.put(KEY_LICENSE, value)
        app.noveltts.data.voicepack.VoicePackEvents.invalidate()
    }

    fun license(): String? = synchronized(CONFIGURATION_LOCK) { secrets.get(KEY_LICENSE) }

    fun hasLicense(): Boolean = synchronized(CONFIGURATION_LOCK) {
        val encrypted = secrets.encryptedValue(KEY_LICENSE)
        if (!licenseStatusLoaded || encrypted != licenseStatusKey) {
            licenseStatusConfigured = !license().isNullOrBlank()
            licenseStatusKey = encrypted
            licenseStatusLoaded = true
        }
        licenseStatusConfigured
    }

    fun licenseMask(): String {
        val license = license() ?: return ""
        if (license.length <= 8) return "••••••••"
        return license.take(4) + "••••••••" + license.takeLast(4)
    }

    fun <T> withConfigurationLock(block: () -> T): T = synchronized(CONFIGURATION_LOCK, block)
    fun snapshot(): Map<String, Any?> = withConfigurationLock { preferences.all.toMap() }
    fun credentialSnapshot(): Map<String, String> = withConfigurationLock { secrets.encryptedSnapshot() }
    fun restoreCredentialSnapshot(snapshot: Map<String, String>) = withConfigurationLock {
        secrets.restoreEncryptedSnapshot(snapshot)
        app.noveltts.data.voicepack.VoicePackEvents.invalidate()
    }
    fun flush() = withConfigurationLock { check(preferences.edit().commit()) { "设置同步失败" }; secrets.flush() }

    fun synthesisSnapshot(): SynthesisSettings = withConfigurationLock {
        SynthesisSettings(narratorVoice, dialogueVoice, systemVoice, punctuationFilter, maxSegmentChars,
            maxRetryCount, silenceBetweenSegmentsMs, cacheEnabled, cacheMaxMb, hasLicense(),
            EngineRequestOptions(edgeEndpoint, requestTimeoutSec, localTtsEnginePackage), builtInRoles(),
            UnattributedPolicy.fromStorage(unattributedDialogue))
    }

    /** A checked, single SharedPreferences commit is used for configuration and restore transactions. */
    fun commitValues(values: Map<String, Any?>, replace: Boolean = false) = withConfigurationLock {
        val editor = preferences.edit()
        if (replace) editor.clear()
        val previousCredentials = if (KEY_FORWARD_TOKEN in values) secrets.encryptedSnapshot() else null
        if (KEY_FORWARD_TOKEN in values) {
            secrets.put(KEY_FORWARD_TOKEN, values[KEY_FORWARD_TOKEN] as? String)
            editor.remove(KEY_FORWARD_TOKEN)
        }
        values.filterKeys { it != KEY_FORWARD_TOKEN }.forEach { (key, value) -> when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> { if (key == KEY_FORWARD_TOKEN) DiagnosticsLog.registerSensitiveValue(value); editor.putString(key, value) }
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> throw IllegalArgumentException("Unsupported setting: $key")
        } }
        if (!editor.commit()) {
            previousCredentials?.let(secrets::restoreEncryptedSnapshot)
            error("设置写入失败")
        }
        if (replace || KEY_LOCAL_TTS_ENGINE_PACKAGE in values) app.noveltts.data.voicepack.VoicePackEvents.invalidate()
        if ("keepAwake" in values && app.noveltts.service.PlaybackSession.activeCount() > 0)
            runCatching { appContext.startForegroundService(android.content.Intent(appContext, app.noveltts.service.PlaybackForegroundService::class.java)) }
    }

    fun commitForward(config: app.noveltts.forward.ForwardConfig) {
        config.validationError()?.let { throw IllegalArgumentException(it) }
        commitValues(mapOf(KEY_FORWARD_ENABLED to config.enabled, KEY_FORWARD_BIND to config.host,
            KEY_FORWARD_PORT to config.port, KEY_FORWARD_TOKEN to config.token.trim()))
    }

    private fun normalizeThemeMode(value: String?): String =
        value?.trim()?.uppercase()?.takeIf { it in THEME_MODES } ?: DEFAULT_THEME_MODE

    companion object {
        fun isSecureEdgeEndpoint(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
        }.getOrDefault(false)
        private var licenseStatusLoaded = false
        private var licenseStatusKey: String? = null
        private var licenseStatusConfigured = false
        /**
         * Edge 朗读端点。必须与 edge-tts 参考实现保持一致：
         * 旧版 `api.msedgeservices.com/tts/cognitiveservices/websocket/v1` 会直接返回 403。
         */
        const val DEFAULT_EDGE_ENDPOINT =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

        /** 曾经写进默认值/用户设置的旧端点，读取时按"未设置"处理。 */
        private const val LEGACY_EDGE_ENDPOINT =
            "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1"

        /** 主题模式的三个取值；写入前会归一，读到未知值时按 [DEFAULT_THEME_MODE] 处理。 */
        const val THEME_MODE_LIGHT = "LIGHT"
        const val THEME_MODE_DARK = "DARK"
        const val THEME_MODE_SYSTEM = "SYSTEM"
        val THEME_MODES = setOf(THEME_MODE_LIGHT, THEME_MODE_DARK, THEME_MODE_SYSTEM)
        const val DEFAULT_THEME_MODE = THEME_MODE_SYSTEM

        const val DEFAULT_MAX_RETRY_COUNT = 1
        const val DEFAULT_REQUEST_TIMEOUT_SEC = 60
        const val DEFAULT_MAX_SEGMENT_CHARS = 500
        const val DEFAULT_SILENCE_MS = 80
        const val DEFAULT_CACHE_ENABLED = true
        const val DEFAULT_CACHE_MAX_MB = 100
        const val DEFAULT_BGM_ENABLED = false
        const val DEFAULT_BGM_VOLUME = 30
        const val DEFAULT_BGM_LOOP = true
        const val DEFAULT_BGM_DUCK = true
        const val DEFAULT_FORWARD_ENABLED = false
        const val DEFAULT_FORWARD_PORT = 8080
        const val FORWARD_BIND_LOOPBACK = "127.0.0.1"
        const val FORWARD_BIND_ALL = "0.0.0.0"
        const val DEFAULT_FORWARD_BIND = FORWARD_BIND_LOOPBACK

        val RETRY_COUNT_RANGE = 0..5
        val REQUEST_TIMEOUT_RANGE = 10..180
        val SEGMENT_CHARS_RANGE = 100..1000
        val SILENCE_RANGE = 0..2000
        val CACHE_MAX_MB_RANGE = 10..1024
        val BGM_VOLUME_RANGE = 0..100
        val FORWARD_PORT_RANGE = 1024..65535

        class BuiltInRoleKeys(val voice: String, val rate: String, val pitch: String, val volume: String)

        fun builtInRoleKeys(role: BuiltInRole): BuiltInRoleKeys = when (role) {
            BuiltInRole.NARRATOR -> BuiltInRoleKeys(KEY_NARRATOR, "narratorRate", "narratorPitch", "narratorVolume")
            BuiltInRole.DIALOGUE -> BuiltInRoleKeys(KEY_DIALOGUE, "dialogueRate", "dialoguePitch", "dialogueVolume")
        }

        /** 倍率设置键 → 允许范围；备份白名单与还原校验共用。 */
        val BUILT_IN_MULTIPLIER_RANGES: Map<String, ClosedFloatingPointRange<Float>> =
            BuiltInRole.entries.map(::builtInRoleKeys).flatMap { keys ->
                listOf(keys.rate to RolePlaybackSettings.RATE_RANGE, keys.pitch to RolePlaybackSettings.RATE_RANGE,
                    keys.volume to RolePlaybackSettings.VOLUME_RANGE)
            }.toMap()

        fun builtInRoleValues(role: BuiltInRole, value: RolePlaybackSettings): Map<String, Any?> {
            val keys = builtInRoleKeys(role)
            val saved = value.normalized()
            return mapOf(keys.voice to saved.voiceId, keys.rate to saved.rate, keys.pitch to saved.pitch,
                keys.volume to saved.volume)
        }

        fun builtInMultiplierValues(roles: BuiltInRoleSettings): Map<String, Any?> =
            BuiltInRole.entries.flatMap { role ->
                val keys = builtInRoleKeys(role)
                builtInRoleValues(role, roles[role]).filterKeys { it != keys.voice }.toList()
            }.toMap()

        private const val KEY_PUNCTUATION_FILTER = "punctuationFilter"
    private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_UNATTRIBUTED_DIALOGUE = "unattributedDialogue"
        private const val KEY_NARRATOR = "narratorVoice"
        private const val KEY_DIALOGUE = "dialogueVoice"
        private const val KEY_SYSTEM_VOICE = "systemVoice"
        private const val KEY_EDGE_ENDPOINT = "edgeEndpoint"
        private const val KEY_COMPATIBILITY_PROXY = "compatibilityProxy"
        private const val KEY_LICENSE = "embeddedSpeechLicense"
        private const val KEY_MAX_RETRY_COUNT = "maxRetryCount"
        private const val KEY_REQUEST_TIMEOUT_SEC = "requestTimeoutSec"
        private const val KEY_MAX_SEGMENT_CHARS = "maxSegmentChars"
        private const val KEY_SILENCE_MS = "silenceBetweenSegmentsMs"
        private const val KEY_CACHE_ENABLED = "cacheEnabled"
        private const val KEY_CACHE_MAX_MB = "cacheMaxMb"
        private const val KEY_BGM_ENABLED = "bgmEnabled"
        private const val KEY_BGM_VOLUME = "bgmVolume"
        private const val KEY_BGM_LOOP = "bgmLoop"
        private const val KEY_BGM_DUCK = "bgmDuck"
        private const val KEY_BGM_URI = "bgmUri"
        private const val KEY_FORWARD_ENABLED = "forwardEnabled"
        private const val KEY_FORWARD_PORT = "forwardPort"
        private const val KEY_FORWARD_BIND = "forwardBind"
        private const val KEY_FORWARD_TOKEN = "forwardToken"
        private const val KEY_LOCAL_TTS_ENGINE_PACKAGE = "localTtsEnginePackage"
    }
}
