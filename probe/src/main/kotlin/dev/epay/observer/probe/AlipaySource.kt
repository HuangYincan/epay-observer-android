package dev.epay.observer.probe

/** Pins only this D0 artifact for a local delivery experiment, not payment evidence. */
object AlipaySource {
    const val PACKAGE = "com.eg.android.AlipayGphone"
    const val VERSION = 212310L
    const val CERTIFICATE = "389b49f7832f53e9017923220aa85e14dfaa4886ecd7428818bf339543cf498a"

    fun permitsEnvelope(packageName: String, postingPackage: String, sameUser: Boolean): Boolean =
        packageName == PACKAGE && postingPackage == PACKAGE && sameUser

    fun accepts(packageName: String, postingPackage: String, sameUser: Boolean, sameUid: Boolean,
                version: Long, certificates: Set<String>): Boolean =
        permitsEnvelope(packageName, postingPackage, sameUser) && sameUid &&
            version == VERSION && certificates == setOf(CERTIFICATE)
}
