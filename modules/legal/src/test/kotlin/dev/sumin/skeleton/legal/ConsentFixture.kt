package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant

class MutableClock(var now: Instant) : TimeProvider {
    override fun now(): Instant = now
}

/**
 * terms: v1 (2026-01-01) → v2 (2026-10-01) → v3 (2026-12-01, 아직 시행 전). privacy: v1. marketing: v1 (선택).
 * 시계는 2026-10-07 에서 시작한다 — v2 가 시행된 지 6 일.
 */
class ConsentFixture(
    grace: Duration = Duration.ZERO,
    required: Set<String> = setOf("terms", "privacy"),
    requiredAtSignUp: Set<String> = required,
    val options: ConsentOptions = ConsentOptions(),
) {
    val sources = mutableMapOf<String, String>()
    val clock = MutableClock(Instant.parse("2026-10-07T00:00:00Z"))
    val store = FakeConsentStore()
    val v2Start: Instant = Instant.parse("2026-10-01T00:00:00Z")

    private fun v(type: String, version: String, from: String): ManifestVersion {
        listOf("ko", "en").forEach { sources["$type/$version.$it"] = "# $type $version $it\n\nbody of $type $version" }
        return ManifestVersion(
            type, version, DocumentStatus.REVIEWED, Instant.parse(from), false, listOf("ko", "en"),
            listOf("ko", "en").associateWith { LegalText.sha256(sources["$type/$version.$it"]!!) },
        )
    }

    val manifest = listOf(
        v("terms", "v1", "2026-01-01T00:00:00Z"), v("terms", "v2", "2026-10-01T00:00:00Z"), v("terms", "v3", "2026-12-01T00:00:00Z"),
        v("privacy", "v1", "2026-01-01T00:00:00Z"), v("marketing", "v1", "2026-01-01T00:00:00Z"),
    )
    val rules = LegalRules(required, requiredAtSignUp, "ko", grace, emptyMap())
    val catalog = LegalCatalog(manifest, { t, ver, l -> sources["$t/$ver.$l"] }, rules)
    val service = ConsentService(catalog, rules, store, clock, options)

    fun hash(type: String, version: String, locale: String = "ko") = LegalText.sha256(sources["$type/$version.$locale"]!!)
}
