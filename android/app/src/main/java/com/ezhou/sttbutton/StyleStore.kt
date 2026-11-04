package com.ezhou.sttbutton

import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * The user's style overrides in the `styles` preferences file. Keys are `style.<category>` (JSON of only the
 * knobs they changed), `app.<pkg>`, and `field.<pkg>/<viewId>`. A value that does not parse is ignored.
 */
class StyleStore(stored: Map<String, String> = emptyMap()) {
    private val entries = stored.filterKeys { isStyleKey(it) }

    fun overrides(): StyleOverrides {
        val knobs = mutableMapOf<Category, KnobOverride>()
        val apps = mutableMapOf<String, Category>()
        val fields = mutableMapOf<String, Category>()
        for ((key, value) in entries) {
            when {
                key.startsWith(STYLE_PREFIX) -> {
                    val category = categoryOrNull(key.removePrefix(STYLE_PREFIX)) ?: continue
                    knobsOf(value)?.takeUnless { it.isEmpty() }?.let { knobs[category] = it }
                }
                key.startsWith(APP_PREFIX) -> {
                    val pkg = key.removePrefix(APP_PREFIX)
                    if (pkg.isNotEmpty()) categoryOrNull(value)?.let { apps[pkg] = it }
                }
                key.startsWith(FIELD_PREFIX) -> {
                    val field = key.removePrefix(FIELD_PREFIX)
                    if (field.isNotEmpty()) categoryOrNull(value)?.let { fields[field] = it }
                }
            }
        }
        return StyleOverrides(knobs, apps, fields)
    }

    fun setKnob(category: Category, knob: String, value: Any): StyleStore {
        if (knob !in KNOBS || (value !is String && value !is Boolean)) return this
        val json = readJson(styleKey(category))
        json.put(knob, value)
        return put(styleKey(category), json.toString())
    }

    fun clearKnob(category: Category, knob: String): StyleStore {
        if (knob !in KNOBS) return this
        val json = readJson(styleKey(category))
        json.remove(knob)
        return if (json.length() == 0) remove(styleKey(category)) else put(styleKey(category), json.toString())
    }

    fun setApp(pkg: String, category: Category): StyleStore {
        if (pkg.isEmpty()) return this
        return put(APP_PREFIX + pkg, category.name.lowercase(Locale.ROOT))
    }

    fun clearApp(pkg: String): StyleStore = remove(APP_PREFIX + pkg)

    fun setField(pkg: String, viewId: String, category: Category): StyleStore {
        if (pkg.isEmpty() || viewId.isEmpty()) return this
        return put(FIELD_PREFIX + StyleOverrides.fieldKey(pkg, viewId), category.name.lowercase(Locale.ROOT))
    }

    fun clearField(pkg: String, viewId: String): StyleStore = remove(FIELD_PREFIX + StyleOverrides.fieldKey(pkg, viewId))

    fun save(prefs: SharedPreferences) {
        val editor = prefs.edit()
        for (key in prefs.all.keys) if (isStyleKey(key) && key !in entries) editor.remove(key)
        for ((key, value) in entries) editor.putString(key, value)
        editor.apply()
    }

    private fun readJson(key: String): JSONObject {
        val raw = entries[key] ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: JSONException) {
            JSONObject()
        }
    }

    private fun put(key: String, value: String) = StyleStore(entries + (key to value))

    private fun remove(key: String) = StyleStore(entries - key)

    companion object {
        const val PREFS = "styles"
        const val STYLING = "styling"
        const val STRUCTURE = "structure"
        const val CASUAL = "casual"
        const val LOWERCASE_STARTS = "lowercase_starts"
        const val KEEP_END = "keep_end"
        const val CLEAN_SHORT = "clean_short"

        private const val STYLE_PREFIX = "style."
        private const val APP_PREFIX = "app."
        private const val FIELD_PREFIX = "field."
        private val KNOBS = setOf(STYLING, STRUCTURE, CASUAL, LOWERCASE_STARTS, KEEP_END, CLEAN_SHORT)

        fun load(prefs: SharedPreferences) = StyleStore(
            prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap(),
        )

        private fun isStyleKey(key: String) =
            key.startsWith(STYLE_PREFIX) || key.startsWith(APP_PREFIX) || key.startsWith(FIELD_PREFIX)

        private fun styleKey(category: Category) = STYLE_PREFIX + category.name.lowercase(Locale.ROOT)

        private fun categoryOrNull(name: String): Category? = try {
            Category.valueOf(name.uppercase(Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun knobsOf(raw: String): KnobOverride? = try {
            val obj = JSONObject(raw)
            KnobOverride(
                styling = obj.text(STYLING),
                structure = obj.text(STRUCTURE),
                casual = obj.bool(CASUAL),
                lowercaseStarts = obj.bool(LOWERCASE_STARTS),
                keepEnd = obj.bool(KEEP_END),
                cleanShort = obj.bool(CLEAN_SHORT),
            )
        } catch (_: JSONException) {
            null
        }

        private fun JSONObject.text(key: String): String? = if (has(key) && get(key) is String) getString(key) else null

        private fun JSONObject.bool(key: String): Boolean? = if (has(key) && get(key) is Boolean) getBoolean(key) else null
    }
}
