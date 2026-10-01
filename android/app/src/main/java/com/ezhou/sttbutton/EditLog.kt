package com.ezhou.sttbutton

import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Append-only JSONL of the design's § Edit log; pull it with `adb exec-out run-as ... cat files/logs/edits.jsonl`. */
class EditLog(private val file: File) {
    init {
        file.parentFile?.mkdirs()
    }

    fun write(event: String, id: String, vararg fields: Pair<String, Any?>, tsMs: Long? = null) = synchronized(this) {
        val ts = if (tsMs == null) Instant.now() else Instant.ofEpochMilli(tsMs)
        val line = JSONObject().put("event", event).put("ts", ts.toString())
        if (id.isNotEmpty()) line.put("id", id)
        for ((key, value) in fields) line.put(key, value ?: JSONObject.NULL)
        file.appendText(line.toString() + "\n")
    }
}
