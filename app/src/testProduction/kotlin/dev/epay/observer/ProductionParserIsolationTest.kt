package dev.epay.observer

import dev.epay.observer.capture.*
import dev.epay.observer.parsing.*
import org.junit.Assert.*
import org.junit.Test

class ProductionParserIsolationTest {
    @Test fun enablingBothProvidersCannotActivateUnverifiedRules() {
        val source = SourceIdentity("dev.epay.observer.fixture", 0, 1, setOf("synthetic"))
        val fields = NotificationFields("微信形态 · 合成收款", "收款 10.00 元", null, "synthetic-v1", "status", 0, null)
        val observation = NotificationObservation(ObservationInput("key", source, 1, 2, 3, false, fields),
            2, 3, uncertain = true, everRestored = false)
        assertFalse(ParserRegistry.available)
        assertEquals(ParseResult.Unsupported(ParseReason.NO_VERIFIED_RULE), ParserRegistry.analyze(observation, Provider.entries.toSet()))
    }
}
