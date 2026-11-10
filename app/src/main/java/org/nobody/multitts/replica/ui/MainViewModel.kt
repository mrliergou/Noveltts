package org.nobody.multitts.replica.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.nobody.multitts.replica.data.SpeechController
import org.nobody.multitts.replica.data.SpeechEngineState
import org.nobody.multitts.replica.data.SpeechVoice

data class MainUiState(
    val text: String = "",
    val speed: Int = 50,
    val volume: Int = 50,
    val pitch: Int = 25,
    val channel: String = "搜狗语音",
    val speech: SpeechEngineState = SpeechEngineState(),
    val roles: Set<String> = emptySet(),
    val replacements: Set<String> = emptySet(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("multitts", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val speechController = SpeechController(application, ::onSpeechStateChanged)

    init {
        speechController.initialize()
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

    fun selectChannel(channel: String) {
        preferences.edit().putString(KEY_CHANNEL, channel).apply()
        _uiState.update { it.copy(channel = channel) }
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
        channel = preferences.getString(KEY_CHANNEL, "搜狗语音") ?: "搜狗语音",
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
        const val KEY_CHANNEL = "channel"
        const val KEY_VOICE = "voice"
        const val KEY_ROLES = "roles"
        const val KEY_REPLACEMENTS = "replacements"
    }
}
