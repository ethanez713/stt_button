package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpanTest {
    @Test fun syncMiddleReplacement() = assertEquals(Edit(6, 4, 4), FieldMirror("alpha beta gamma").sync("alpha BETA gamma"))
    @Test fun syncPureInsert() = assertEquals(Edit(6, 0, 5), FieldMirror("alpha gamma").sync("alpha beta gamma"))
    @Test fun syncPureDelete() = assertEquals(Edit(6, 5, 0), FieldMirror("alpha beta gamma").sync("alpha gamma"))
    @Test fun syncEqualTextsIsNull() = assertNull(FieldMirror("same").sync("same"))
    @Test fun syncRepeatedCharactersAtTheEdges() {
        assertEquals(Edit(3, 0, 1), FieldMirror("aaa").sync("aaaa"))
        assertEquals(Edit(3, 1, 0), FieldMirror("aaaa").sync("aaa"))
    }
    @Test fun syncTracksTheLastSeenText() {
        val mirror = FieldMirror("ab")
        mirror.sync("abc")
        assertEquals(Edit(3, 0, 1), mirror.sync("abcd"))
    }

    private val span = Span(5, 10)

    @Test fun editBeforeShifts() = assertEquals(Span(7, 12), span.apply(Edit(0, 0, 2)))
    @Test fun editBeforeThatDeletesShifts() = assertEquals(Span(3, 8), span.apply(Edit(0, 2, 0)))
    @Test fun editInsideGrows() = assertEquals(Span(5, 12), span.apply(Edit(7, 0, 2)))
    @Test fun editAfterLeavesIt() = assertEquals(span, span.apply(Edit(12, 0, 3)))
    @Test fun insertExactlyAtStartStaysOutside() = assertEquals(Span(7, 12), span.apply(Edit(5, 0, 2)))
    @Test fun insertExactlyAtEndStaysOutside() = assertEquals(Span(5, 10), span.apply(Edit(10, 0, 2)))
    @Test fun editAcrossStartLandsOnIt() = assertEquals(Span(3, 7), span.apply(Edit(3, 4, 1)))
    @Test fun editAcrossEndKeepsTheStartAndLandsTheEnd() = assertEquals(Span(5, 8), span.apply(Edit(8, 4, 0)))
    @Test fun deletingTheWholeSpanCollapsesIt() {
        assertEquals(Span(5, 5), span.apply(Edit(5, 5, 0)))
        assertEquals(Span(4, 4), span.apply(Edit(4, 8, 0)))
    }
    @Test fun insertAtACollapsedSpanKeepsItCollapsed() {
        val collapsed = Span(5, 5).apply(Edit(5, 0, 2))
        assertEquals(collapsed.start, collapsed.end)
    }

    @Test fun locatorPrefersTheCaret() = assertEquals(Span(4, 7), SpanLocator.find("abc abc", "abc", 7))
    @Test fun locatorByCaretWithTwoOccurrences() = assertEquals(Span(0, 3), SpanLocator.find("abc abc", "abc", 3))
    @Test fun locatorByUniqueOccurrence() = assertEquals(Span(3, 6), SpanLocator.find("xx abc yy", "abc", 99))
    @Test fun locatorNullForTwoOccurrencesAndAMissedCaret() = assertNull(SpanLocator.find("abc abc", "abc", 5))
    @Test fun locatorNullWhenAbsent() = assertNull(SpanLocator.find("xyz", "abc", null))
}
