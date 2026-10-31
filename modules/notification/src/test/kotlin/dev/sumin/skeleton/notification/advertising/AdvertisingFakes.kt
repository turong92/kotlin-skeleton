package dev.sumin.skeleton.notification.advertising

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import java.time.ZoneId

class FixedClock(@Volatile var now: Instant) : TimeProvider {
    override fun now(): Instant = now
}

class StubPolicy(private val answers: Map<String, AdvertisingConsent> = emptyMap()) : AdvertisingConsentPolicy {
    @Volatile var failure: RuntimeException? = null

    override fun consentOf(accountId: String, now: Instant): AdvertisingConsent {
        failure?.let { throw it }
        return answers[accountId] ?: AdvertisingConsent(send = false, night = false)
    }
}

class StubZones(private val zones: Map<String, ZoneId?> = emptyMap(), private val failure: RuntimeException? = null) : AdvertisingRecipientZones {
    override fun zoneOf(accountId: String): ZoneId? {
        failure?.let { throw it }
        return zones[accountId]
    }
}
