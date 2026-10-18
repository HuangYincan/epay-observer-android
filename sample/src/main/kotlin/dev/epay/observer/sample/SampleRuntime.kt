package dev.epay.observer.sample

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object SampleRuntime {
    @Volatile private var instance: SampleGate? = null
    @Synchronized fun gate(context: Context): SampleGate {
        instance?.let { return it }
        return create(context, "used").also { instance = it }
    }

    /** Called only by explicit androidTest on the service main thread. Never reset old markers. */
    @Synchronized fun prepareScanPaymentWindow(context: Context) {
        val previous = instance?.snapshot()
        check(previous?.phase != SampleGate.Phase.RUNNING)
        check(!File(context.noBackupFilesDir, "samples.jsonl").exists())
        instance = create(context, "used-scan-payment-v1").apply {
            connection(previous?.connected == true)
        }
    }

    private fun create(context: Context, markerName: String): SampleGate {
        val directory = context.noBackupFilesDir
        return SampleGate({ SystemClock.elapsedRealtime() }, {
            val marker = File(directory, markerName)
            if (marker.createNewFile()) {
                FileOutputStream(marker).use { it.write(1); it.fd.sync() }
                true
            } else false
        }, { title, text ->
            val line = JSONObject().put("title", title ?: JSONObject.NULL)
                .put("text", text ?: JSONObject.NULL).toString() + "\n"
            FileOutputStream(File(directory, "samples.jsonl"), true).use {
                it.write(line.toByteArray(Charsets.UTF_8)); it.fd.sync()
            }
        })
    }
}
