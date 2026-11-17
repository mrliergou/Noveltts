package app.noveltts.forward

import org.json.JSONArray
import org.json.JSONObject

/** Matches LegadoTeam/legado HttpTTS and AnalyzeUrl's speakText/speakSpeed inputs. */
object LegadoIntegration {
    fun configJson(config: ForwardConfig): String {
        config.validationError()?.let { throw IllegalArgumentException(it) }
        val entry = JSONObject().put("id", 2026111601L).put("name", "NovelTTS · 本机规则")
            .put("url", "http://127.0.0.1:${config.port}/tts," +
                """{"method":"POST","headers":{"Content-Type":"application/json"},"body":{"text":{{JSON.stringify(String(speakText))}},"rate":{{Math.max(0.25,Math.min(4,speakSpeed/10))}}},"timeout":180000}""")
            .put("contentType", "audio/wav").put("pauseDuration", 0).put("concurrentRate", "1")
            .put("header", JSONObject().put("X-Token", config.token).toString()).put("enabledCookieJar", false)
        return JSONArray().put(entry).toString(2)
    }
}
