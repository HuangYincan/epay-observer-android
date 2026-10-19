package dev.epay.observer

import dev.epay.observer.capture.*
import dev.epay.observer.pilot.PilotShutdown
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PilotShutdownTest {
    @Test fun stoppedCallbackCannotRepopulatePreviewAfterCleanup() {
        val capture = Executors.newSingleThreadExecutor()
        val persistence = Executors.newSingleThreadExecutor()
        val cleanup = Executors.newSingleThreadExecutor()
        val extracted = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val barrierSubmitted = CountDownLatch(1)
        val persisted = CountDownLatch(1)
        CaptureState.journal.clear()
        try {
            capture.submit {
                extracted.countDown()
                check(resume.await(5,TimeUnit.SECONDS))
                // Reproduce the exact gap after field extraction and before the callback's two writes.
                persistence.submit { persisted.countDown() }
                CaptureState.journal.record(ObservationInput("synthetic-key",SourceIdentity("synthetic-source",0,1,setOf("synthetic")),
                    1,1,1,false,NotificationFields("synthetic-private-text",null,null,null,null,0,null)))
            }
            assertTrue(extracted.await(5,TimeUnit.SECONDS))
            val result = cleanup.submit<Boolean> {
                PilotShutdown.finish({ action ->
                    val pending = capture.submit { action() }
                    barrierSubmitted.countDown()
                    pending.get(5,TimeUnit.SECONDS)
                }, { persistence.submit { }.get(5,TimeUnit.SECONDS) })
            }
            assertTrue(barrierSubmitted.await(5,TimeUnit.SECONDS))
            assertFalse(result.isDone)
            resume.countDown()
            assertTrue(result.get(5,TimeUnit.SECONDS))
            assertTrue(persisted.await(5,TimeUnit.SECONDS))
            assertTrue(CaptureState.journal.snapshots().isEmpty())
        } finally {
            resume.countDown()
            capture.shutdownNow();persistence.shutdownNow();cleanup.shutdownNow()
            CaptureState.journal.clear()
        }
    }

    @Test fun failedBarrierCannotBeReportedAsSuccessfulCleanup() {
        assertFalse(PilotShutdown.finish({ throw IllegalStateException("synthetic-barrier-failure") }, { }))
    }
}
