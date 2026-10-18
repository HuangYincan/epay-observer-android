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
        val directory = context.noBackupFilesDir
        return SampleGate({ SystemClock.elapsedRealtime() }, {
            val marker = File(directory, "used")
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
        }).also { instance = it }
    }
}
