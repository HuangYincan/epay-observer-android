package dev.epay.observer.protocol

import androidx.work.Constraints
import androidx.work.NetworkType

object UploadNetworkPolicy {
    const val MARKER = "system-validated-network-v1"
    fun constraints(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}
