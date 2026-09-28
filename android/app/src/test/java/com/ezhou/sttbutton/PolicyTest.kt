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
    fun given_emptyTagAndLocale_when_resolved_then_unknownAndNotEnglish() {
        assertNull(ImeLanguage.subtag("", ""))
        assertFalse(ImeLanguage.isEnglish(null, null))
    }

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
    fun given_plainText_when_checked_then_dictatable() =
        assertTrue(FieldPolicy.isDictatable(true, false, text))

    @Test
    fun given_multilineMessage_when_checked_then_dictatable() =
        assertTrue(FieldPolicy.isDictatable(true, false, text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))

    @Test
    fun given_webFieldWithNoInputType_when_checked_then_dictatable() =
        assertTrue(FieldPolicy.isDictatable(true, false, 0))

    @Test
    fun given_nodeFlaggedPassword_when_checked_then_not() =
        assertFalse(FieldPolicy.isDictatable(true, true, text))

    @Test
    fun given_passwordVariation_when_checked_then_not() =
        assertFalse(FieldPolicy.isDictatable(true, false, text or InputType.TYPE_TEXT_VARIATION_PASSWORD))

    @Test
    fun given_visiblePasswordVariation_when_checked_then_not() =
        assertFalse(FieldPolicy.isDictatable(true, false, text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))

    @Test
    fun given_webPasswordVariation_when_checked_then_not() =
        assertFalse(FieldPolicy.isDictatable(true, false, text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))

    @Test
    fun given_numericPin_when_checked_then_not() =
        assertFalse(FieldPolicy.isDictatable(true, false, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))

    @Test
    fun given_numberField_when_checked_then_not() =
        assertFalse(FieldPolicy.isDictatable(true, false, InputType.TYPE_CLASS_NUMBER))

    @Test
    fun given_phoneField_when_checked_then_not() =
        assertFalse(FieldPolicy.isDictatable(true, false, InputType.TYPE_CLASS_PHONE))

    @Test
    fun given_dateField_when_checked_then_not() =
        assertFalse(FieldPolicy.isDictatable(true, false, InputType.TYPE_CLASS_DATETIME))

    @Test
    fun given_readOnlyNode_when_checked_then_not() =
        assertFalse(FieldPolicy.isDictatable(false, false, text))
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
