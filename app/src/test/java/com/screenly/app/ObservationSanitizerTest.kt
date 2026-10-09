package com.screenly.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservationSanitizerTest {
    @Test
    fun removesControlCharactersAndLogLineBreaks() {
        assertEquals("Wi-Fi connected", sanitizeObservationText("  Wi-Fi\n\r\t\u0000\u202Econnected  "))
    }

    @Test
    fun preservesOrdinaryUnicodeLabels() {
        assertEquals("設定 ⚙️", sanitizeObservationText("設定 ⚙️"))
    }

    @Test
    fun treatsMissingAndBlankLabelsAsAbsent() {
        assertNull(sanitizeObservationText(null))
        assertNull(sanitizeObservationText(" \n\t "))
    }

    @Test
    fun boundsLongAppProvidedValues() {
        assertEquals(160, sanitizeObservationText("a".repeat(10_000))?.length)
    }

    @Test
    fun truncationDoesNotSplitAnEmojiSurrogatePair() {
        assertEquals("a".repeat(159), sanitizeObservationText("a".repeat(159) + "😀label"))
    }

    @Test
    fun normalizesUnicodeSeparators() {
        assertEquals("Network settings", sanitizeObservationText("Network\u00A0\u2003settings"))
    }
}
