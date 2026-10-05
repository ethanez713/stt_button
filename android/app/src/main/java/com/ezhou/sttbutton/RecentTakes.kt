package com.ezhou.sttbutton

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException

/** One dictation the app screen can show: when it landed, which app it was for, and the text. */
data class RecentTake(val at: Instant, val pkg: String, val text: String)

/** Newest insert and clipboard lines from the edit log. A line that is not one of those, or that is incomplete, is skipped. */
object RecentTakes {
    const val LIMIT = 10

    fun read(file: File, limit: Int = LIMIT): List<RecentTake> =
        if (file.isFile) fromLines(file.readLines(), limit) else emptyList()

    fun fromLines(lines: List<String>, limit: Int = LIMIT): List<RecentTake> {
        val found = ArrayList<Indexed>(lines.size)
        for ((index, line) in lines.withIndex()) parse(line)?.let { found += Indexed(index, it) }
        return found
            .sortedWith(compareByDescending<Indexed> { it.take.at }.thenByDescending { it.index })
            .take(limit)
            .map { it.take }
    }

    private fun parse(line: String): RecentTake? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val obj = try {
            JSONObject(trimmed)
        } catch (_: JSONException) {
            return null
        }
        val text = when (obj.optString("event")) {
            "insert" -> string(obj, "cleaned")
            "clipboard" -> string(obj, "text")
            else -> null
        } ?: return null
        val at = try {
            Instant.parse(string(obj, "ts") ?: return null)
        } catch (_: DateTimeParseException) {
            return null
        }
        return RecentTake(at, string(obj, "pkg") ?: return null, text)
    }

    private fun string(obj: JSONObject, key: String): String? {
        val value = obj.opt(key)
        return value as? String
    }

    private data class Indexed(val index: Int, val take: RecentTake)
}
