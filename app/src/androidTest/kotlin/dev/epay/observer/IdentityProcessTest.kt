package dev.epay.observer

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.parsing.Provider
import dev.epay.observer.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run by smoke_avd.py in two separate instrumentation processes, never in the ordinary suite. */
@RunWith(AndroidJUnit4::class)
class IdentityProcessTest {
    @Test fun persistsAcrossActualProcessDeath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("identityPhase")
        require(phase == "prepare" || phase == "verify")
        val name = "test-identity-process.db"
        val reference = java.io.File(context.filesDir, "test-identity-process.json")
        if (phase == "prepare") { context.deleteDatabase(name); reference.delete() }
        val db = ObserverDatabase.open(context, name)
        try {
            val store = IdentityStore(db)
            if (phase == "prepare") {
                val row = store.record(identityInput("process-event"), Provider.entries.toSet())
                val event = store.event(row.eventId!!)!!
                reference.writeText(JSONObject().put("epoch", store.epoch()).put("eventId", event.eventId)
                    .put("payload", android.util.Base64.encodeToString(event.payload, android.util.Base64.NO_WRAP))
                    .put("pid", Process.myPid()).toString())
            } else {
                val expected = JSONObject(reference.readText())
                assertNotEquals(expected.getInt("pid"), Process.myPid())
                assertEquals(expected.getString("epoch"), store.epoch())
                val row = store.record(identityInput("process-event", restored = true).copy(wallTime = 9999), Provider.entries.toSet())
                assertEquals(expected.getString("eventId"), row.eventId)
                assertArrayEquals(android.util.Base64.decode(expected.getString("payload"), android.util.Base64.NO_WRAP), store.event(row.eventId!!)!!.payload)
                assertEquals(1000, store.event(row.eventId)!!.observedAt)
                assertEquals(1, store.stats().events)
            }
        } finally { db.close() }
        if (phase == "verify") { context.deleteDatabase(name); reference.delete() }
    }
}
