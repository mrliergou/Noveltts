package app.noveltts.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Only a result code and timestamp; never persists speech text, credentials or raw backend errors. */
data class EngineHealthResult(val code: Int, val at: Long)
object EngineHealth {
    private val current = MutableStateFlow<Map<String, EngineHealthResult>>(emptyMap())
    val state = current.asStateFlow()
    fun record(engine: String, code: Int) {
        if (engine !in setOf("edgetts", "microsoft") || code == app.noveltts.engine.api.SynthesisError.CANCELLED) return
        current.update { it + (engine to EngineHealthResult(code, System.currentTimeMillis())) }
    }
}