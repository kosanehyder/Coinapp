package com.coinfutures.app

import com.coinfutures.app.ui.l10n.EnglishStrings
import com.coinfutures.app.ui.l10n.RomanUrduStrings
import com.coinfutures.engine.AlertEventType
import com.coinfutures.engine.ErrorCode
import com.coinfutures.engine.OverallStatus
import com.coinfutures.engine.ReasonCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AT14 (partial): every state, reason and error has readable text in English and Roman Urdu. */
class StringsTest {
    private val catalogs = listOf(EnglishStrings, RomanUrduStrings)

    @Test fun everyCodeHasText() {
        for (s in catalogs) {
            ReasonCode.entries.forEach { assertTrue(it.name, s.reason(it).isNotBlank()) }
            ErrorCode.entries.forEach { assertTrue(it.name, s.error(it).isNotBlank()) }
            OverallStatus.entries.forEach { assertTrue(it.name, s.status(it).isNotBlank()) }
            AlertEventType.entries.forEach { assertTrue(it.name, s.eventType(it).isNotBlank()) }
        }
    }

    @Test fun conditionalLanguageNeverImpliesExecution() {
        for (s in catalogs) {
            val text = s.reason(ReasonCode.ENTRY_READY) + s.notAnOrder + s.planSummary(true, "100.00", "100.25", "98.00", "104.00", "108.00")
            assertFalse(text.contains("guarantee", ignoreCase = true))
            assertFalse(text.contains("filled", ignoreCase = true))
        }
        assertTrue(RomanUrduStrings.reason(ReasonCode.WAITING_CONFIRMATION).contains("Abhi wait karein, confirmation baqi hai"))
        assertTrue(RomanUrduStrings.reason(ReasonCode.PREVIOUS_SETUP_INVALIDATED).contains("Is setup ki condition toot gayi hai"))
        assertTrue(RomanUrduStrings.zoneSupportExplain.contains("Yahan pehle buying aayi thi"))
        assertTrue(RomanUrduStrings.reason(ReasonCode.RR_TOO_LOW).contains("Is waqt risk ke muqablay mein munasib target nahin mil raha"))
    }
}
