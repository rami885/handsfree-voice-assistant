package com.handsfree.probes

/** Process-local, per-capability consent, independent of experiment parameters. */
class ProbeConsentStore {
    @Volatile private var granted = emptySet<String>()
    operator fun contains(capability: String): Boolean = capability in granted
    @Synchronized
    internal fun set(capability: String, consent: Boolean): Boolean {
        if ((capability in granted) == consent) return false
        granted = if (consent) granted + capability else granted - capability
        return true
    }
}
