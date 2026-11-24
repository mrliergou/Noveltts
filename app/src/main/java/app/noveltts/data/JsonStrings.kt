package app.noveltts.data

import org.json.JSONArray
import org.json.JSONObject

/** Android optString coerces JSON null to "null"; keep persisted values platform independent. */
internal fun JSONObject.stringOrDefault(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)

internal fun JSONArray.stringOrDefault(index: Int, fallback: String = ""): String =
    if (isNull(index)) fallback else optString(index, fallback)