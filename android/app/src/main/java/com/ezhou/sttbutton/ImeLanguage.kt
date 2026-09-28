package com.ezhou.sttbutton

object ImeLanguage {
    /**
     * Primary language subtag of an IME subtype, or null when the subtype names none.
     *
     * Gboard fills `languageTag` for enabled layouts but leaves it empty on its implicit subtypes,
     * which carry only a locale such as `en_US`. The physical-keyboard tag is never the language:
     * the 拼音 layout is locale `zh_CN` with physical-keyboard tag `en`.
     */
    fun subtag(languageTag: String?, locale: String?): String? {
        val source = languageTag?.takeIf { it.isNotBlank() } ?: locale?.takeIf { it.isNotBlank() } ?: return null
        return source.replace('_', '-').substringBefore('-').lowercase().takeIf { it.isNotEmpty() }
    }

    fun isEnglish(languageTag: String?, locale: String?): Boolean = subtag(languageTag, locale) == "en"
}
