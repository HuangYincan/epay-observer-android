package dev.epay.observer

import dev.epay.observer.parsing.*
import dev.epay.observer.storage.*
import org.junit.Assert.*
import dev.epay.observer.capture.*
import org.junit.Test

class ProductionOutboundIsolationTest {
    @Test fun rejectsAllUnverifiedOutboundScopes() {
        for (source in BindingSnapshot.SourceClass.entries) for (provider in Provider.entries) {
            val binding = BindingSnapshot("https://observer.example", "test", "device", "receiver", 1, provider, source)
            val candidate = ParseResult.Candidate(provider, 1000, "CNY", "fixture-wx-v1")
            assertFalse(OutboundPolicy.permits(binding, candidate))
            val input = ObservationInput("fresh", SourceIdentity("dev.epay.observer.fixture", 0, 1, setOf("synthetic")),
                1000, 1000, 1000, false, NotificationFields(null,null,null,null,null,0,null))
            assertEquals(EvidenceIdentity.UNCERTAIN, OutboundPolicy.identityForNewObservation(input,candidate,binding,false))
            assertTrue(runCatching { EventWire.encode("11111111-1111-4111-8111-111111111111",candidate,1000,binding,EvidenceIdentity.DISTINCT) }.isFailure)
        }
    }
}
