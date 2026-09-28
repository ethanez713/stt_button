package com.ezhou.sttbutton

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeLanguageTest {
    @Test
    fun given_enabledEnglishLayout_when_resolved_then_english() =
        assertTrue(ImeLanguage.isEnglish("en-US", "en_US"))

    @Test
    fun given_implicitSubtypeWithEmptyTag_when_resolved_then_localeDecides() =
        assertEquals("en", ImeLanguage.subtag("", "en_US"))

    @Test
    fun given_pinyinLayout_when_resolved_then_notEnglish() =
        // Its physical-keyboard tag is `en`; only the language tag and locale may decide.
        assertFalse(ImeLanguage.isEnglish("zh-CN", "zh_CN"))

    @Test
    fun given_implicitChineseSubtype_when_resolved_then_notEnglish() =
        assertFalse(ImeLanguage.isEnglish("", "zh_CN"))

    @Test
    fun given_emptyTagAndLocale_when_resolved_then_unknown() =
        assertNull(ImeLanguage.subtag("", ""))

    @Test
    fun given_britishLayout_when_resolved_then_english() =
        assertTrue(ImeLanguage.isEnglish("en-GB", ""))

    @Test
    fun given_upperCaseUnderscoreLocale_when_resolved_then_english() =
        assertTrue(ImeLanguage.isEnglish("", "EN_us"))

    @Test
    fun given_threeLetterCodeStartingWithEn_when_resolved_then_notEnglish() =
        assertFalse(ImeLanguage.isEnglish("eng", ""))
}

class FieldPolicyTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun given_plainText_when_checked_then_nothingExcludesIt() =
        assertNull(FieldPolicy.exclusion(false, text))

    @Test
    fun given_searchFieldVariation_when_checked_then_nothingExcludesIt() =
        assertNull(FieldPolicy.exclusion(false, text or InputType.TYPE_TEXT_VARIATION_URI))

    @Test
    fun given_webFieldWithNoInputType_when_checked_then_nothingExcludesIt() =
        assertNull(FieldPolicy.exclusion(false, 0))

    @Test
    fun given_nodeFlaggedPassword_when_checked_then_excluded() =
        assertEquals("password field", FieldPolicy.exclusion(true, text))

    @Test
    fun given_passwordVariation_when_checked_then_excluded() =
        assertEquals("password field", FieldPolicy.exclusion(false, text or InputType.TYPE_TEXT_VARIATION_PASSWORD))

    @Test
    fun given_visiblePasswordVariation_when_checked_then_excluded() =
        assertEquals("password field", FieldPolicy.exclusion(false, text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))

    @Test
    fun given_webPasswordVariation_when_checked_then_excluded() =
        assertEquals("password field", FieldPolicy.exclusion(false, text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))

    @Test
    fun given_numericPin_when_checked_then_excludedAsNumber() =
        assertEquals("number field", FieldPolicy.exclusion(false, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))

    @Test
    fun given_phoneField_when_checked_then_excluded() =
        assertEquals("phone field", FieldPolicy.exclusion(false, InputType.TYPE_CLASS_PHONE))

    @Test
    fun given_dateField_when_checked_then_excluded() =
        assertEquals("date field", FieldPolicy.exclusion(false, InputType.TYPE_CLASS_DATETIME))
}

class MicLocatorTest {
    @Test
    fun given_voiceInputLabel_when_matched_then_voiceKey() = assertTrue(MicLocator.isVoiceKey("Voice input"))

    @Test
    fun given_lowercaseVoiceTypingLabel_when_matched_then_voiceKey() =
        assertTrue(MicLocator.isVoiceKey("use voice typing"))

    @Test
    fun given_otherKeyLabel_when_matched_then_not() = assertFalse(MicLocator.isVoiceKey("Emoji"))

    @Test
    fun given_noDescription_when_matched_then_not() = assertFalse(MicLocator.isVoiceKey(null))
}

class DropZoneTest {
    private val width = 1080
    private val height = 2340
    private val zone = 300

    @Test
    fun given_dropAboveTheStrip_when_resolved_then_noZone() =
        assertNull(DropZone.at(100f, height - zone - 1f, width, height, zone))

    @Test
    fun given_dropBottomLeft_when_resolved_then_hide() =
        assertEquals(DropZone.HIDE, DropZone.at(100f, height - 10f, width, height, zone))

    @Test
    fun given_dropBottomRight_when_resolved_then_reset() =
        assertEquals(DropZone.RESET, DropZone.at(width - 100f, height - 10f, width, height, zone))

    @Test
    fun given_dropOnTheStripTopEdge_when_resolved_then_inZone() =
        assertEquals(DropZone.HIDE, DropZone.at(0f, (height - zone).toFloat(), width, height, zone))
}
