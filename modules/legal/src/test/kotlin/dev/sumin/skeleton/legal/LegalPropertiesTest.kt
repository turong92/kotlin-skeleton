package dev.sumin.skeleton.legal

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class LegalPropertiesTest {
    private fun bind(vararg pairs: Pair<String, String>): LegalProperties =
        Binder(MapConfigurationPropertySource(mapOf(*pairs))).bind("skeleton.legal", LegalProperties::class.java).orElseGet { LegalProperties() }

    @Test
    fun `the defaults are neutral - terms and privacy required, no grace, no filter, personal data kept a year`() {
        val p = LegalProperties()

        assertEquals("classpath:legal/", p.location)
        assertEquals("ko", p.defaultLocale)
        assertEquals(setOf("terms", "privacy"), p.required)
        assertEquals(setOf("terms", "privacy"), p.effectiveRequiredAtSignUp)
        assertEquals(Duration.ZERO, p.previousVersionGrace)
        assertEquals(emptyMap(), p.facts)
        assertEquals(false, p.acknowledgeTemplate)
        assertEquals("ADMIN", p.adminRole)
        assertEquals(true, p.record.storeIp)
        assertEquals(true, p.record.storeUserAgent)
        assertEquals(Duration.ofDays(365), p.record.personalDataRetention)
        assertEquals(200, p.record.maxEventsPerDay)
        assertEquals(ErasureMode.ANONYMIZE, p.erasure.mode)
        assertEquals(true, p.http.enabled)
        assertEquals("/api/v1/legal", p.http.basePath)
        assertEquals(Duration.ofMinutes(5), p.http.cacheMaxAge)
        assertEquals(false, p.reconsent.enabled)
        assertTrue("/api/v1/auth/**" in p.reconsent.excludePaths && "/api/v1/legal/**" in p.reconsent.excludePaths && "/api/v1/account/**" in p.reconsent.excludePaths)
        assertEquals(listOf("/api/**"), p.reconsent.includePaths)
    }

    @Test
    fun `values bind from kebab case keys, lists replace the defaults, and required-at-sign-up follows required unless set`() {
        val p = bind(
            "skeleton.legal.required" to "terms,privacy,age",
            "skeleton.legal.required-at-sign-up" to "terms",
            "skeleton.legal.previous-version-grace" to "7d",
            "skeleton.legal.facts.company-name" to "ACME",
            "skeleton.legal.record.store-ip" to "false",
            "skeleton.legal.record.personal-data-retention" to "0s",
            "skeleton.legal.erasure.mode" to "delete",
            "skeleton.legal.reconsent.enabled" to "true",
        )

        assertEquals(setOf("terms", "privacy", "age"), p.required)
        assertEquals(setOf("terms"), p.effectiveRequiredAtSignUp)
        assertEquals(Duration.ofDays(7), p.previousVersionGrace)
        assertEquals(mapOf("company-name" to "ACME"), p.facts)
        assertEquals(false, p.record.storeIp)
        assertEquals(Duration.ZERO, p.record.personalDataRetention)
        assertEquals(ErasureMode.DELETE, p.erasure.mode)
        assertEquals(true, p.reconsent.enabled)
        assertEquals(setOf("terms", "privacy", "age"), bind("skeleton.legal.required" to "terms,privacy,age").effectiveRequiredAtSignUp)
    }

    @Test
    fun `nonsense is refused at startup with the key in the message`() {
        fun fails(vararg pairs: Pair<String, String>) = assertFailsWith<Exception> { bind(*pairs) }.let { generateSequence<Throwable>(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        assertTrue(fails("skeleton.legal.required" to "Bad Type").contains("skeleton.legal.required"))
        assertTrue(fails("skeleton.legal.facts.1abc" to "x").contains("skeleton.legal.facts"))
        assertTrue(fails("skeleton.legal.previous-version-grace" to "-1s").contains("previous-version-grace"))
        assertTrue(fails("skeleton.legal.default-locale" to "Korean").contains("default-locale"))
        assertTrue(fails("skeleton.legal.admin-role" to " ").contains("admin-role"))
        assertTrue(fails("skeleton.legal.record.max-events-per-day" to "0").contains("max-events-per-day"))
        assertTrue(fails("skeleton.legal.http.base-path" to "legal").contains("base-path"))
        assertNull(runCatching { bind("skeleton.legal.facts.company-name" to "ACME") }.exceptionOrNull())
    }
}
