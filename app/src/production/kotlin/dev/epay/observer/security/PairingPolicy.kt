package dev.epay.observer.security

import dev.epay.observer.storage.BindingSnapshot

object PairingPolicy {
    fun permits(source: BindingSnapshot.SourceClass) = source == BindingSnapshot.SourceClass.NOTIFICATION
}
