package org.nobody.multitts.replica.data.settings

import android.content.Context

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("multitts", Context.MODE_PRIVATE)
    private val secrets = SecretStore(appContext)

    var punctuationFilter: Boolean
        get() = preferences.getBoolean(KEY_PUNCTUATION_FILTER, true)
        set(value) {
            preferences.edit().putBoolean(KEY_PUNCTUATION_FILTER, value).apply()
        }

    var narratorVoice: String
        get() = preferences.getString(KEY_NARRATOR, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_NARRATOR, value).apply()
        }

    var dialogueVoice: String
        get() = preferences.getString(KEY_DIALOGUE, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_DIALOGUE, value).apply()
        }

    var systemVoice: String
        get() = preferences.getString(KEY_SYSTEM_VOICE, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_SYSTEM_VOICE, value).apply()
        }

    var edgeEndpoint: String
        get() = preferences.getString(KEY_EDGE_ENDPOINT, DEFAULT_EDGE_ENDPOINT).orEmpty()
        set(value) {
            preferences.edit().putString(KEY_EDGE_ENDPOINT, value.trim()).apply()
        }

    var compatibilityProxy: Boolean
        get() = preferences.getBoolean(KEY_COMPATIBILITY_PROXY, false)
        set(value) {
            preferences.edit().putBoolean(KEY_COMPATIBILITY_PROXY, value).apply()
        }

    fun setLicense(value: String?) {
        secrets.put(KEY_LICENSE, value)
    }

    fun license(): String? = secrets.get(KEY_LICENSE)

    fun hasLicense(): Boolean = !license().isNullOrBlank()

    fun licenseMask(): String {
        val license = license() ?: return ""
        if (license.length <= 8) return "••••••••"
        return license.take(4) + "••••••••" + license.takeLast(4)
    }

    companion object {
        const val DEFAULT_EDGE_ENDPOINT =
            "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1"

        private const val KEY_PUNCTUATION_FILTER = "punctuationFilter"
        private const val KEY_NARRATOR = "narratorVoice"
        private const val KEY_DIALOGUE = "dialogueVoice"
        private const val KEY_SYSTEM_VOICE = "systemVoice"
        private const val KEY_EDGE_ENDPOINT = "edgeEndpoint"
        private const val KEY_COMPATIBILITY_PROXY = "compatibilityProxy"
        private const val KEY_LICENSE = "embeddedSpeechLicense"
    }
}
