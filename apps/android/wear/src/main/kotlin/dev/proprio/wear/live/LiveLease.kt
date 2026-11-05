package dev.proprio.wear.live

import dev.proprio.protocol.LiveSubscribe

/**
 * Subscription lease of the live preview (phone -> watch). The watch streams only while a lease is valid, so a phone
 * that went away (screen off, app killed, Bluetooth lost) stops costing battery within one lease period without any
 * message. The phone renews every [LiveSubscribe.RENEW_INTERVAL_MS]; renewing replaces the expiry (it does not add up).
 */
class LiveLease(private val nowMs: () -> Long) {
    @Volatile private var expiresAtMs = Long.MIN_VALUE

    /** Starts or renews the lease; the length is clamped to [LiveSubscribe.MIN_LEASE_MS]..[LiveSubscribe.MAX_LEASE_MS]. */
    fun subscribe(leaseMs: Long) {
        expiresAtMs = nowMs() + leaseMs.coerceIn(LiveSubscribe.MIN_LEASE_MS, LiveSubscribe.MAX_LEASE_MS)
    }

    fun unsubscribe() {
        expiresAtMs = Long.MIN_VALUE
    }

    fun isActive(): Boolean = nowMs() < expiresAtMs
}
