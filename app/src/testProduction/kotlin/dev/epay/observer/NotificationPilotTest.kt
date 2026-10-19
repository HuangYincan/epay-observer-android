package dev.epay.observer

import dev.epay.observer.capture.*
import dev.epay.observer.parsing.*
import dev.epay.observer.pilot.*
import dev.epay.observer.storage.*
import org.junit.Assert.*
import org.junit.Test

/** All notification fields and review facts here are synthetic. No compatibility approval. */
class NotificationPilotTest {
    private val binding = BindingSnapshot("https://observer.example", "test", "device", "receiver", 1,
        Provider.ALIPAY, BindingSnapshot.SourceClass.NOTIFICATION)
    private val review = PilotReview("synthetic-test-only", true, true, true, true)
    private val config = PilotConfiguration(binding, 1234, 100_000, 400_000, review)
    private val source = SourceIdentity(PilotAlipaySource.PACKAGE, 0, PilotAlipaySource.VERSION,
        setOf(PilotAlipaySource.CERTIFICATE))
    private val fields = NotificationFields("你已成功收款12.34元（合成不透明字段）", "合成甲转入余额宝 合成乙1合成丙2合成丁",
        null, "synthetic-channel", null, 0x10, "synthetic-group")
    private fun observation(pilot: NotificationPilot = NotificationPilot(config,100_000,10_000),
                            current: NotificationFields = fields): NotificationObservation {
        val permit = pilot.admit(source,101_000,101_000,11_000,false)!!
        assertNotNull(permit.readFields(101_000,11_000) { current })
        val input = ObservationInput("synthetic-key", source,101_000,101_000,11_000,false,current,permit)
        return NotificationObservation(input,101_000,11_000, uncertain=true,everRestored=false)
    }

    @Test fun actualProductionRegistryAndWireKeepNotificationScope() {
        val o = observation()
        val parsed = ParserRegistry.analyze(o,setOf(Provider.ALIPAY))
        if (!PilotBuild.allowed) {
            assertTrue(parsed is ParseResult.Unsupported)
            return
        }
        val candidate = parsed as ParseResult.Candidate
        assertEquals(1234L,candidate.amountMinor)
        assertTrue(OutboundPolicy.permits(binding,candidate))
        val identity = OutboundPolicy.identityForNewObservation(o.latest,candidate,binding,false)
        assertEquals(EvidenceIdentity.DISTINCT,identity)
        val wire = EventWire.encode("11111111-1111-4111-8111-111111111111",candidate,101_000,binding,identity).toString(Charsets.UTF_8)
        assertTrue(wire.contains("\"source_class\":\"notification\""))
        assertFalse(wire.contains("合成")); assertFalse(wire.contains("order"))
        assertFalse(OutboundPolicy.permits(binding.copy(receiverId="different"),candidate))
        assertFalse(OutboundPolicy.permits(binding.copy(version=2),candidate))
        assertFalse(OutboundPolicy.permits(binding.copy(sourceClass=BindingSnapshot.SourceClass.SYNTHETIC),candidate))
        assertEquals(EvidenceIdentity.UNCERTAIN,OutboundPolicy.identityForNewObservation(o.latest,candidate,binding,true))
        assertEquals(EvidenceIdentity.UNCERTAIN,OutboundPolicy.identityForNewObservation(o.latest.copy(restored=true),candidate,binding,false))
        assertEquals(EvidenceIdentity.UNCERTAIN,OutboundPolicy.identityForNewObservation(o.latest.copy(postTime=100_999),candidate,binding,false))
    }

    @Test fun gateStopsAtTwoReadsBeforeExtrasAndNeverResumes() {
        val p=NotificationPilot(config,100_000,10_000)
        assertNull(p.admit(source,100_000,100_000,10_000,true))
        assertNull(p.admit(source.copy(version=1),100_000,100_000,10_000,false))
        assertEquals(0,p.readCount())
        val first=p.admit(source,100_000,100_000,10_000,false)
        assertNotNull(first)
        assertNotNull(p.admit(source,100_001,100_001,10_001,false))
        assertEquals(NotificationPilot.Phase.LIMIT,p.status(100_001,10_001))
        assertNull(p.admit(source,100_002,100_002,10_002,false))
        p.stop()
        assertEquals(2,p.readCount())
        // Captured authority remains frozen for queued DB work after the local window ends.
        assertTrue(first!!.configuration == config)
    }

    @Test fun expiryDisconnectAndClockChangesAreTerminal() {
        for ((wall,elapsed,phase) in listOf(Triple(220_000L,130_000L,NotificationPilot.Phase.EXPIRED),
            Triple(101_000L,9_999L,NotificationPilot.Phase.CLOCK_ERROR),
            Triple(104_000L,11_000L,NotificationPilot.Phase.CLOCK_ERROR),
            Triple(Long.MIN_VALUE,11_000L,NotificationPilot.Phase.CLOCK_ERROR))) {
            val p=NotificationPilot(config,100_000,10_000)
            assertEquals(phase,p.status(wall,elapsed))
            assertNull(p.admit(source,101_000,101_000,11_000,false))
        }
        val p=NotificationPilot(config,100_000,10_000);p.stop()
        assertNull(p.admit(source,100_001,100_001,10_001,false))
        val early=NotificationPilot(config.copy(serverExpiresAt=101_000),100_000,10_000)
        assertNull(early.admit(source,101_000,101_000,11_000,false))
    }

    @Test fun expiryOrDisconnectBetweenSourceCheckAndFieldReadDoesNotReadExtras() {
        for (expired in listOf(true,false)) {
            val p=NotificationPilot(config,100_000,10_000)
            val permit=p.admit(source,100_000,100_000,10_000,false)!!
            if (!expired) p.stop()
            var called=false
            assertNull(permit.readFields(if(expired)220_000 else 100_000,if(expired)130_000 else 10_000) {
                called=true;fields
            })
            assertFalse(called);assertEquals(0,p.fieldReadCount())
        }
        val p=NotificationPilot(config,100_000,10_000)
        val permit=p.admit(source,100_000,100_000,10_000,false)!!
        assertNotNull(permit.readFields(100_000,10_000) { fields })
        assertNull(permit.readFields(100_000,10_000) { error("second read") })
        assertEquals(1,p.fieldReadCount())
        val failed=NotificationPilot(config,100_000,10_000)
        val failedPermit=failed.admit(source,100_000,100_000,10_000,false)!!
        assertTrue(runCatching { failedPermit.readFields(100_000,10_000) { error("synthetic-field-error") } }.isFailure)
        assertNull(failedPermit.readFields(100_000,10_000) { error("retried raw read") })
        assertEquals(1,failed.fieldReadCount())
    }

    @Test fun everyMissingReviewAndWrongScopePreventsStarting() {
        for (bad in listOf(review.copy(templateVerified=false),review.copy(chatBoundaryVerified=false),
            review.copy(identityPolicyApproved=false),review.copy(amountPolicyApproved=false),
            review.copy(evidenceReference=""))) {
            assertTrue(runCatching { NotificationPilot(config.copy(review=bad),100_000,10_000) }.isFailure)
        }
        for (bad in listOf(config.copy(amountMinor=0),config.copy(serverExpiresAt=800_001),
            config.copy(binding=binding.copy(provider=Provider.WXPAY)),
            config.copy(binding=binding.copy(sourceClass=BindingSnapshot.SourceClass.SYNTHETIC)))) {
            assertTrue(runCatching { NotificationPilot(bad,100_000,10_000) }.isFailure)
        }
    }

    @Test fun unknownAndChatFieldsCannotBecomeCandidates() {
        val invalid=listOf(fields.copy(title="私信：${fields.title}"),fields.copy(title=fields.title+"\n"),
            fields.copy(title="你已成功收款01元（合成）"),fields.copy(title="你已成功收款0元（合成）"),
            fields.copy(title="你已成功收款12.35元（合成）"),fields.copy(title="你已成功收款１２.３４元（合成）"),
            fields.copy(title="你已成功收款12.34元（合成\u202e）"),fields.copy(flags=0x210),fields.copy(flags=0x12),
            fields.copy(group=null),fields.copy(channel=null),fields.copy(category="msg"),
            fields.copy(template="android.app.Notification\$MessagingStyle"),fields.copy(bigText=fields.text),
            fields.copy(hasAdditionalText=true),fields.copy(unsupported=true),fields.copy(text=null),
            fields.copy(text="合成聊天：我收款12.34元"),fields.copy(text=fields.text+"\n"),
            fields.copy(text="x".repeat(513)))
        for (f in invalid) assertFalse("unexpected candidate",AlipayReceiptSyntax.analyze(observation(current=f),setOf(Provider.ALIPAY)) is ParseResult.Candidate)
        val o=observation()
        assertFalse(AlipayReceiptSyntax.analyze(o.copy(everRestored=true),setOf(Provider.ALIPAY)) is ParseResult.Candidate)
        assertFalse(AlipayReceiptSyntax.analyze(o.copy(revision=2),setOf(Provider.ALIPAY)) is ParseResult.Candidate)
        assertFalse(AlipayReceiptSyntax.analyze(o.copy(latest=o.latest.copy(pilotPermit=null)),setOf(Provider.ALIPAY)) is ParseResult.Candidate)
        assertFalse(AlipayReceiptSyntax.analyze(o,emptySet()) is ParseResult.Candidate)
    }
}
