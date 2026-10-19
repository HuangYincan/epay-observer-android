package dev.epay.observer.pilot

import dev.epay.observer.capture.CaptureState

/** Quiesce callbacks before draining persistence and dropping the last raw preview references. */
object PilotShutdown {
    fun finish(onCaptureThread: (() -> Unit) -> Unit, awaitPersistence: () -> Unit): Boolean {
        PilotRuntime.stop()
        var clean = true
        // An admitted callback can still be between field extraction and journal.record().
        // This barrier must precede the DB drain, and clear must follow that callback.
        try { onCaptureThread { } } catch (_: Exception) { clean = false }
        try { awaitPersistence() } catch (_: Exception) { clean = false }
        try { onCaptureThread { CaptureState.journal.clear() } } catch (_: Exception) { clean = false }
        return clean
    }
}
