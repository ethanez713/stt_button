package com.ezhou.sttbutton

import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Append-only JSONL of the design's § Edit log; pull it with `adb exec-out run-as ... cat files/logs/edits.jsonl`. */
class EditLog(private val file: File) {
    init {
        file.parentFile?.mkdirs()
    }

    fun write(event: String, id: String, vararg fields: Pair<String, Any?>) = synchronized(this) {
        val line = JSONObject().put("event", event).put("id", id).put("ts", Instant.now().toString())
        for ((key, value) in fields) line.put(key, value ?: JSONObject.NULL)
        file.appendText(line.toString() + "\n")
    }
}
