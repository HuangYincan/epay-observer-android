package dev.epay.observer.protocol

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.NetworkType

/** Local synthetic lab networking only; TLS and device authentication are unchanged. */
object UploadNetworkPolicy {
    const val MARKER = "fixture-local-network-v1"
    fun constraints(): Constraints = Constraints.Builder().setRequiredNetworkRequest(
        NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED).build(),
        NetworkType.CONNECTED,
    ).build()
}
