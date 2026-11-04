package com.ezhou.sttbutton

/** One app on the styles list, and the fields inserts have seen in it. */
data class AppRow(
    val pkg: String,
    val category: Category,
    val source: StyleSource,
    val fields: List<FieldRow>,
)

/** One seen field with the style [StyleBook] gives it now. [inputType] and [multiLine] are what that resolution used. */
data class FieldRow(
    val viewId: String?,
    val hint: String?,
    val inputType: Int,
    val multiLine: Boolean,
    val category: Category,
    val source: StyleSource,
)

/**
 * Apps to list: shipped categories, packages an insert has named, and packages the user has pinned.
 * A row's style is its most recently seen field. With none, it is a multi-line field of input type 0.
 */
object AppRows {
    fun rows(cfg: PipelineConfig, seen: List<SeenField>, overrides: StyleOverrides): List<AppRow> {
        val book = StyleBook(cfg, overrides)
        val pkgs = (cfg.appCategories.keys + seen.map { it.pkg } + overrides.apps.keys).distinct().sorted()
        val byPkg = seen.groupBy { it.pkg }
        return pkgs.map { pkg ->
            val fields = byPkg[pkg].orEmpty()
            val app = resolve(book, pkg, fields.lastOrNull())
            AppRow(pkg, app.category, app.source, fields.map { fieldRow(book, pkg, it) })
        }
    }

    private fun fieldRow(book: StyleBook, pkg: String, field: SeenField): FieldRow {
        val resolved = resolve(book, pkg, field)
        return FieldRow(field.viewId, field.hint, inputTypeOf(field), multiLineOf(field), resolved.category, resolved.source)
    }

    private fun resolve(book: StyleBook, pkg: String, field: SeenField?): Resolved {
        if (field == null) return book.resolve(pkg, 0, true, null, null)
        // A take with no field logs nulls; the tap resolves those as 0 and multi-line.
        return book.resolve(pkg, inputTypeOf(field), multiLineOf(field), field.hint, field.viewId)
    }

    private fun inputTypeOf(field: SeenField) = field.inputType ?: 0

    private fun multiLineOf(field: SeenField) = field.multiLine ?: true
}
