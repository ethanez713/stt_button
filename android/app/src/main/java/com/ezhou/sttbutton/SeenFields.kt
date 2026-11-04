package com.ezhou.sttbutton

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * One field an insert line named. [inputType] and [multiLine] are null when that take had no field.
 * [category] and [source] are what the take logged; a later override can choose something else.
 */
data class SeenField(
    val pkg: String,
    val viewId: String?,
    val hint: String?,
    val inputType: Int?,
    val multiLine: Boolean?,
    val category: Category?,
    val source: StyleSource?,
)

/**
 * Distinct fields from insert lines, in the order of each field's latest line. A later line for the
 * same package and view replaces the earlier one. A line that is not an insert, has no package, is
 * not JSON, or was written before the field keys, is skipped. A null view id is kept.
 */
object SeenFields {
    fun read(file: File): List<SeenField> =
        if (file.isFile) fromLines(file.readLines()) else emptyList()

    fun fromLines(lines: List<String>): List<SeenField> {
        val found = LinkedHashMap<Pair<String, String?>, SeenField>()
        for (line in lines) {
            val field = parse(line) ?: continue
            val key = field.pkg to field.viewId
            found.remove(key)
            found[key] = field
        }
        return found.values.toList()
    }

    private fun parse(line: String): SeenField? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val obj = try {
            JSONObject(trimmed)
        } catch (_: JSONException) {
            return null
        }
        if (obj.optString("event") != "insert") return null
        val pkg = text(obj, "pkg")?.takeIf { it.isNotEmpty() } ?: return null
        if (FIELD_KEYS.none { obj.has(it) }) return null
        if (badText(obj, "field_view_id") || badText(obj, "field_hint")) return null
        if (badNumber(obj, "field_input_type") || badBool(obj, "field_multi_line")) return null
        return SeenField(
            pkg,
            text(obj, "field_view_id"),
            text(obj, "field_hint"),
            integer(obj, "field_input_type"),
            boolean(obj, "field_multi_line"),
            enumOrNull<Category>(text(obj, "category")),
            enumOrNull<StyleSource>(text(obj, "style_source")),
        )
    }

    private fun text(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.opt(key) as? String
    }

    private fun badText(obj: JSONObject, key: String) = obj.has(key) && !obj.isNull(key) && obj.opt(key) !is String

    private fun badNumber(obj: JSONObject, key: String) = obj.has(key) && !obj.isNull(key) && obj.opt(key) !is Number

    private fun badBool(obj: JSONObject, key: String) = obj.has(key) && !obj.isNull(key) && obj.opt(key) !is Boolean

    private fun integer(obj: JSONObject, key: String): Int? =
        if (!obj.has(key) || obj.isNull(key)) null else (obj.get(key) as Number).toInt()

    private fun boolean(obj: JSONObject, key: String): Boolean? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getBoolean(key)

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? {
        if (name == null) return null
        return try {
            enumValueOf<T>(name.uppercase(Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private val FIELD_KEYS = arrayOf("field_view_id", "field_hint", "field_input_type", "field_multi_line")
}
