package com.ezhou.sttbutton

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.io.IOException

/** Category knobs, then each app and the fields inserts have seen in it. A change is read on the next tap. */
class StylesSection(private val activity: Activity) {
    private val density = activity.resources.displayMetrics.density
    private val prefs = activity.getSharedPreferences(StyleStore.PREFS, Context.MODE_PRIVATE)
    private val configBytes = activity.assets.open("pipeline.json").use { it.readBytes() }
    private val cfg = PipelineConfig.fromAssets(activity.assets, configBytes)
    private val blocks = LinkedHashMap<Category, Block>()
    private lateinit var apps: LinearLayout
    private var updating = false

    fun addTo(content: LinearLayout) {
        for (category in Category.entries) content.addView(categoryBlock(category))
        content.addView(text(activity.getString(R.string.styles_apps), 16f, bold = true).apply {
            setPadding(0, dp(24), 0, dp(4))
        })
        apps = column()
        content.addView(apps)
        refresh()
    }

    fun refresh() {
        showKnobs()
        showApps()
    }

    private fun categoryBlock(category: Category): View {
        val shipped = cfg.styles.getValue(category)
        val styling = choice(STYLING, STYLING_LABELS) { editKnob(category, StyleStore.STYLING, it, shipped.styling) }
        val structure = choice(STRUCTURE, STRUCTURE_LABELS) { editKnob(category, StyleStore.STRUCTURE, it, shipped.structure) }
        val casual = toggle(R.string.knob_casual) { editKnob(category, StyleStore.CASUAL, it, shipped.casual) }
        val lowercase = toggle(R.string.knob_lowercase_starts) { editKnob(category, StyleStore.LOWERCASE_STARTS, it, shipped.lowercaseStarts) }
        val keepEnd = toggle(R.string.knob_keep_end) { editKnob(category, StyleStore.KEEP_END, it, shipped.keepEnd) }
        val cleanShort = toggle(R.string.knob_clean_short) { editKnob(category, StyleStore.CLEAN_SHORT, it, shipped.cleanShort) }
        blocks[category] = Block(styling, structure, casual, lowercase, keepEnd, cleanShort)
        return column().apply {
            addView(text(categoryName(category), 16f, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) })
            addView(text(activity.getString(R.string.styling_label), 15f))
            addView(styling.group)
            addView(text(activity.getString(R.string.structure_label), 15f))
            addView(structure.group)
            addView(casual)
            addView(lowercase)
            addView(keepEnd)
            addView(cleanShort)
            addView(Button(activity).apply {
                setText(R.string.style_reset)
                setOnClickListener { reset(category) }
            })
        }
    }

    private fun showKnobs() {
        val book = StyleBook(cfg, StyleStore.load(prefs).overrides())
        updating = true
        try {
            for ((category, block) in blocks) block.show(book.styleFor(category))
        } finally {
            updating = false
        }
    }

    private fun editKnob(category: Category, knob: String, value: Any, shipped: Any) {
        if (updating) return
        val store = StyleStore.load(prefs)
        val next = if (value == shipped) store.clearKnob(category, knob) else store.setKnob(category, knob, value)
        next.save(prefs)
    }

    private fun reset(category: Category) {
        var store = StyleStore.load(prefs)
        for (knob in KNOBS) store = store.clearKnob(category, knob)
        store.save(prefs)
        showKnobs()
    }

    private fun showApps() {
        apps.removeAllViews()
        val seen = try {
            SeenFields.read(File(activity.filesDir, "logs/edits.jsonl"))
        } catch (e: IOException) {
            Log.w(DictationService.TAG, "could not read the edit log", e)
            emptyList()
        }
        for (row in AppRows.rows(cfg, seen, StyleStore.load(prefs).overrides())) apps.addView(appBlock(row))
    }

    private fun appBlock(row: AppRow) = column().apply {
        addView(column().apply {
            minimumHeight = dp(48)
            setPadding(0, dp(8), 0, dp(4))
            tappable()
            addView(text(appLabel(row.pkg), 16f, bold = true))
            addView(text(row.pkg, 13f))
            addView(text(styleLine(row.category, row.source), 15f))
            setOnClickListener { pickApp(row.pkg) }
        })
        for (field in row.fields) addView(fieldBlock(row.pkg, field))
    }

    private fun fieldBlock(pkg: String, field: FieldRow) = column().apply {
        setPadding(dp(16), dp(4), 0, dp(4))
        addView(text(field.viewId ?: activity.getString(R.string.field_no_view_id), 15f))
        addView(text(field.hint ?: activity.getString(R.string.field_no_hint), 13f))
        val line = if (field.multiLine) R.string.field_multi_line else R.string.field_single_line
        addView(text(activity.getString(line, field.inputType), 13f))
        addView(text(styleLine(field.category, field.source), 15f))
        val viewId = field.viewId
        if (viewId != null) {
            minimumHeight = dp(48)
            tappable()
            setOnClickListener { pickField(pkg, viewId) }
        }
    }

    private fun pickApp(pkg: String) {
        pick(activity.getString(R.string.style_for_app, appLabel(pkg)), activity.getString(R.string.style_automatic)) { category ->
            val store = StyleStore.load(prefs)
            val next = if (category == null) store.clearApp(pkg) else store.setApp(pkg, category)
            next.save(prefs)
            refresh()
        }
    }

    private fun pickField(pkg: String, viewId: String) {
        pick(activity.getString(R.string.style_for_field), activity.getString(R.string.style_same_as_app)) { category ->
            val store = StyleStore.load(prefs)
            val next = if (category == null) store.clearField(pkg, viewId) else store.setField(pkg, viewId, category)
            next.save(prefs)
            refresh()
        }
    }

    private fun pick(title: String, first: String, onPick: (Category?) -> Unit) {
        val labels = arrayOf<CharSequence>(first, *Category.entries.map { categoryName(it) }.toTypedArray())
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(labels) { _, which ->
                onPick(if (which == 0) null else Category.entries[which - 1])
            }
            .show()
    }

    /** Other apps are invisible without a queries element, so an unknown package keeps its package name. */
    private fun appLabel(pkg: String): String = try {
        val info = activity.packageManager.getApplicationInfo(pkg, 0)
        activity.packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun styleLine(category: Category, source: StyleSource) =
        activity.getString(R.string.style_with_reason, categoryName(category), sourceName(source))

    private fun categoryName(category: Category) = activity.getString(when (category) {
        Category.DEFAULT -> R.string.category_default
        Category.CHAT -> R.string.category_chat
        Category.EMAIL -> R.string.category_email
        Category.NOTES -> R.string.category_notes
        Category.FORM -> R.string.category_form
    })

    private fun sourceName(source: StyleSource) = activity.getString(when (source) {
        StyleSource.APP -> R.string.source_your_choice
        StyleSource.FIELD -> R.string.source_this_field
        StyleSource.BUILTIN_APP -> R.string.source_builtin
        StyleSource.INPUT_TYPE -> R.string.source_field_type
        StyleSource.SHORT_MESSAGE -> R.string.source_short_message
        StyleSource.SINGLE_LINE -> R.string.source_single_line
        StyleSource.DEFAULT -> R.string.source_default
    })

    private fun choice(values: List<String>, labels: List<Int>, onPick: (String) -> Unit): Choice {
        val ids = List(values.size) { View.generateViewId() }
        val group = RadioGroup(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            for (index in values.indices) {
                addView(RadioButton(activity).apply {
                    id = ids[index]
                    setText(labels[index])
                    textSize = 15f
                })
            }
            setOnCheckedChangeListener { _, checkedId ->
                val index = ids.indexOf(checkedId)
                if (index >= 0) onPick(values[index])
            }
        }
        return Choice(group, ids, values)
    }

    private fun toggle(label: Int, onPick: (Boolean) -> Unit) = Switch(activity).apply {
        setText(label)
        textSize = 15f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setPadding(0, dp(4), 0, dp(4))
        setOnCheckedChangeListener { _, checked -> onPick(checked) }
    }

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun LinearLayout.tappable() {
        isClickable = true
        isFocusable = true
        val ripple = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
    }

    private fun text(value: String, sp: Float, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = sp
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(value: Int) = (value * density).toInt()

    private class Choice(val group: RadioGroup, private val ids: List<Int>, private val values: List<String>) {
        fun show(value: String) {
            val index = values.indexOf(value)
            if (index >= 0) group.check(ids[index]) else group.clearCheck()
        }
    }

    private class Block(
        val styling: Choice,
        val structure: Choice,
        val casual: Switch,
        val lowercaseStarts: Switch,
        val keepEnd: Switch,
        val cleanShort: Switch,
    ) {
        fun show(style: Style) {
            styling.show(style.styling)
            structure.show(style.structure)
            casual.isChecked = style.casual
            lowercaseStarts.isChecked = style.lowercaseStarts
            keepEnd.isChecked = style.keepEnd
            cleanShort.isChecked = style.cleanShort
        }
    }

    private companion object {
        val KNOBS = listOf(
            StyleStore.STYLING,
            StyleStore.STRUCTURE,
            StyleStore.CASUAL,
            StyleStore.LOWERCASE_STARTS,
            StyleStore.KEEP_END,
            StyleStore.CLEAN_SHORT,
        )
        val STYLING = listOf("formal", "semi-formal", "semi-casual", "casual")
        val STYLING_LABELS = listOf(
            R.string.styling_formal,
            R.string.styling_semi_formal,
            R.string.styling_semi_casual,
            R.string.styling_casual,
        )
        val STRUCTURE = listOf("prose", "lists")
        val STRUCTURE_LABELS = listOf(R.string.structure_prose, R.string.structure_lists)
    }
}
