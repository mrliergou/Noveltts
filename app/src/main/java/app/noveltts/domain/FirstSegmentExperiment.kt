package app.noveltts.domain

import kotlin.math.ceil

/** Measurements must be paired on the same device, voice/revision, text and effective parameters. */
data class FirstAudioPair(val controlMs: Long, val candidateMs: Long)
data class FirstSegmentEvidence(val pairs: List<FirstAudioPair>, val cancelResumePassed: Boolean,
    val device: String, val voiceId: String, val voiceRevision: String, val parameters: String) {
    val accepted: Boolean get() {
        if (pairs.size < 30 || !cancelResumePassed || device.isBlank() || voiceId.isBlank() || voiceRevision.isBlank() || parameters.isBlank() ||
            pairs.any { it.controlMs <= 0 || it.candidateMs <= 0 }) return false
        fun percentile(values: List<Long>, fraction: Double): Double {
            val sorted = values.sorted()
            if (fraction == 0.5 && sorted.size % 2 == 0) return (sorted[sorted.size / 2 - 1].toDouble() + sorted[sorted.size / 2]) / 2
            return sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceAtLeast(0)].toDouble()
        }
        val control = pairs.map { it.controlMs }
        val candidate = pairs.map { it.candidateMs }
        return percentile(candidate, 0.5) <= percentile(control, 0.5) * 0.9 && percentile(candidate, 0.95) <= percentile(control, 0.95)
    }
    fun matches(device: String, voiceId: String, voiceRevision: String, parameters: String): Boolean =
        accepted && this.device == device && this.voiceId == voiceId && this.voiceRevision == voiceRevision && this.parameters == parameters

    companion object {
        fun parse(report: String): FirstSegmentEvidence {
            require(report.length <= 100_000) { "测量报告过大" }
            app.noveltts.data.JsonInput.checkDepth(report, 5)
            val json = org.json.JSONObject(report)
            val pairs = json.getJSONArray("pairs")
            require(pairs.length() in 1..1000) { "测量组数无效" }
            return FirstSegmentEvidence((0 until pairs.length()).map {
                val pair = pairs.getJSONObject(it)
                FirstAudioPair(pair.getLong("controlMs"), pair.getLong("candidateMs")).also { p -> require(p.controlMs > 0 && p.candidateMs > 0) { "首音耗时必须大于零" } }
            }, json.getBoolean("cancelResumePassed"), json.getString("device"), json.getString("voiceId"), json.getString("voiceRevision"), json.getString("parameters"))
        }
    }
}

object FirstSegmentExperiment {
    const val FIRST_CHARS = 96
    /** App only. The resolved voice, attribution and multipliers are copied to both pieces. */
    fun apply(plan: BuiltSynthesisPlan, enabled: Boolean): BuiltSynthesisPlan {
        val first = plan.segments.firstOrNull() ?: return plan
        val text = first.segment.text
        if (!enabled || text.length <= FIRST_CHARS) return plan
        var cut = (FIRST_CHARS downTo FIRST_CHARS / 2).firstOrNull { text[it - 1] in "，,。！？!?；;：: " } ?: FIRST_CHARS
        if (text[cut - 1].isHighSurrogate() && text[cut].isLowSurrogate()) cut--
        val children = listOf(text.substring(0, cut), text.substring(cut)).mapIndexed { index, value ->
            first.copy(segment = first.segment.copy(text = value, id = "${first.segment.id}:first96:$index"))
        }
        val segments = children + plan.segments.drop(1)
        val pipeline = plan.pipeline.copy(segments = plan.pipeline.segments.flatMap {
            if (it.id == first.segment.id) children.map { child -> child.segment } else listOf(it)
        })
        return BuiltSynthesisPlan(pipeline, segments)
    }
}