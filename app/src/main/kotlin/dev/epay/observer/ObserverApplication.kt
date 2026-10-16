package dev.epay.observer

import android.app.Application
import dev.epay.observer.storage.DurableCapture

class ObserverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DurableCapture.start(this)
        dev.epay.observer.security.PairingRuntime.start(this)
        dev.epay.observer.protocol.UploadScheduler.initialize(this)
        dev.epay.observer.protocol.UploadRuntime.start(this)
    }
}
