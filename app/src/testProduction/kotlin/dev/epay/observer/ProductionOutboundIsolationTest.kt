package dev.epay.observer

import dev.epay.observer.parsing.*
import dev.epay.observer.storage.*
import org.junit.Assert.assertFalse
import org.junit.Test

class ProductionOutboundIsolationTest {
    @Test fun rejectsAllUnverifiedOutboundScopes() {
        for (source in BindingSnapshot.SourceClass.entries) for (provider in Provider.entries) {
            val binding = BindingSnapshot("https://observer.example", "test", "device", "receiver", 1, provider, source)
            assertFalse(OutboundPolicy.permits(binding, ParseResult.Candidate(provider, 1000, "CNY", "fixture-wx-v1")))
        }
    }
}
