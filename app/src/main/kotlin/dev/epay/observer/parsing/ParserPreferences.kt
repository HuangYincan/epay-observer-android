package dev.epay.observer.parsing

import android.content.Context

/** User configuration only, never receipt/event persistence. Accessed outside notification callbacks. */
class ParserPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("parser_preferences", Context.MODE_PRIVATE)
    fun enabled(): Set<Provider> = Provider.entries.filter { preferences.getBoolean(it.name, true) }.toSet()
    fun setEnabled(provider: Provider, enabled: Boolean) {
        preferences.edit().putBoolean(provider.name, enabled).apply()
        dev.epay.observer.storage.DurableCapture.providerChanged(provider, enabled)
    }
}
