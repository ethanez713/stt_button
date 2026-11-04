package com.ezhou.sttbutton

/** The knobs a user changed for one category. A null knob stays at the shipped value. */
data class KnobOverride(
    val styling: String? = null,
    val structure: String? = null,
    val casual: Boolean? = null,
    val lowercaseStarts: Boolean? = null,
    val keepEnd: Boolean? = null,
    val cleanShort: Boolean? = null,
) {
    fun isEmpty(): Boolean = styling == null && structure == null && casual == null &&
        lowercaseStarts == null && keepEnd == null && cleanShort == null
}

/**
 * User overrides stored on the phone. [knobs] holds only the knobs the user changed. [fields] is keyed
 * `pkg/viewId`.
 */
data class StyleOverrides(
    val knobs: Map<Category, KnobOverride> = emptyMap(),
    val apps: Map<String, Category> = emptyMap(),
    val fields: Map<String, Category> = emptyMap(),
) {
    companion object {
        fun fieldKey(pkg: String, viewId: String) = "$pkg/$viewId"
    }
}

data class Resolved(val category: Category, val style: Style, val source: StyleSource)

class StyleBook(cfg: PipelineConfig, private val overrides: StyleOverrides = StyleOverrides()) {
    private val styles = cfg.styles
    private val takeStyle = TakeStyle(cfg.appCategories)

    /** [hint] does not choose a style; it is logged with the take. */
    fun resolve(pkg: String?, inputType: Int, multiLine: Boolean, hint: String?, viewId: String?): Resolved {
        if (pkg != null && viewId != null) {
            overrides.fields[StyleOverrides.fieldKey(pkg, viewId)]?.let { return resolved(it, StyleSource.FIELD) }
        }
        val shipped = takeStyle.shipped(pkg, inputType, multiLine)
        if (shipped.source != StyleSource.INPUT_TYPE) {
            pkg?.let { overrides.apps[it] }?.let { return resolved(it, StyleSource.APP) }
        }
        return resolved(shipped.category, shipped.source)
    }

    private fun resolved(category: Category, source: StyleSource) = Resolved(category, styleFor(category), source)

    fun styleFor(category: Category): Style {
        val shipped = styles.getValue(category)
        val over = overrides.knobs[category] ?: return shipped
        return shipped.copy(
            styling = over.styling ?: shipped.styling,
            structure = over.structure ?: shipped.structure,
            casual = over.casual ?: shipped.casual,
            lowercaseStarts = over.lowercaseStarts ?: shipped.lowercaseStarts,
            keepEnd = over.keepEnd ?: shipped.keepEnd,
            cleanShort = over.cleanShort ?: shipped.cleanShort,
        )
    }
}
