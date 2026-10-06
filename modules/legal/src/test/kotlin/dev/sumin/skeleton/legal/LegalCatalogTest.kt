package dev.sumin.skeleton.legal

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegalCatalogTest {
    private val sources = mutableMapOf<String, String>()

    private fun source(type: String, version: String, locale: String, text: String) = text.also { sources["$type/$version.$locale"] = it }

    private fun v(
        type: String, version: String, from: String?, status: DocumentStatus = DocumentStatus.REVIEWED, locales: List<String> = listOf("ko", "en"),
        template: Boolean = false, text: String = "# $type $version\n\nbody", hashes: Boolean = true,
    ): ManifestVersion {
        locales.forEach { source(type, version, it, "$text ($it)") }
        return ManifestVersion(
            type, version, status, from?.let(Instant::parse), template, locales,
            if (hashes) locales.associateWith { LegalText.sha256("$text ($it)") } else emptyMap(),
        )
    }

    private fun catalog(vararg manifest: ManifestVersion, grace: Duration = Duration.ZERO, facts: Map<String, String> = emptyMap(), required: Set<String> = setOf("terms")) =
        LegalCatalog(manifest.toList(), { t, ver, l -> sources["$t/$ver.$l"] }, LegalRules(required, required, "ko", grace, facts))

    private val t0 = Instant.parse("2026-10-01T00:00:00Z")

    @Test
    fun `current is the latest version that is already in force, future ones are not`() {
        val c = catalog(v("terms", "v1", "2026-01-01T00:00:00Z"), v("terms", "v2", "2026-10-01T00:00:00Z"), v("terms", "v3", "2026-12-01T00:00:00Z"))

        assertEquals("v1", c.current("terms", t0.minusSeconds(1))!!.version)
        assertEquals("v2", c.current("terms", t0)!!.version, "in force exactly at its effective instant")
        assertEquals("v2", c.current("terms", t0.plusSeconds(86_400 * 30L))!!.version)
        assertNull(c.current("terms", Instant.parse("2025-12-31T00:00:00Z")))
        assertNull(c.current("unknown", t0))
    }

    @Test
    fun `next is the earliest version that takes effect later`() {
        val c = catalog(v("terms", "v1", "2026-01-01T00:00:00Z"), v("terms", "v3", "2027-01-01T00:00:00Z"), v("terms", "v2", "2026-12-01T00:00:00Z"))
        assertEquals("v2", c.next("terms", t0)!!.version)
        assertNull(c.next("terms", Instant.parse("2027-02-01T00:00:00Z")))
    }

    @Test
    fun `a draft without an effective date is never current and never next`() {
        val c = catalog(v("terms", "v1", "2026-01-01T00:00:00Z"), v("terms", "v2", null, status = DocumentStatus.DRAFT, hashes = false))
        assertEquals("v1", c.current("terms", t0)!!.version)
        assertNull(c.next("terms", t0))
    }

    @Test
    fun `the current version is agreeable, the previous one only inside the grace window of the current one`() {
        val c = catalog(v("terms", "v1", "2026-01-01T00:00:00Z"), v("terms", "v2", "2026-10-01T00:00:00Z"), grace = Duration.ofHours(24))

        assertEquals("v2", c.agreeable("terms", "v2", t0)!!.version)
        assertEquals("v1", c.agreeable("terms", "v1", t0.plusSeconds(3600))!!.version)
        assertEquals("v1", c.agreeable("terms", "v1", t0.plus(Duration.ofHours(24)).minusSeconds(1))!!.version)
        assertNull(c.agreeable("terms", "v1", t0.plus(Duration.ofHours(24))), "the window ends exactly 24 h after the new version took effect")
        assertEquals(t0.plus(Duration.ofHours(24)), c.graceUntil("terms", t0.plusSeconds(10)))
    }

    @Test
    fun `with grace zero only the current version is agreeable, and nothing older than the previous one ever is`() {
        val none = catalog(v("terms", "v1", "2026-01-01T00:00:00Z"), v("terms", "v2", "2026-10-01T00:00:00Z"))
        assertNull(none.agreeable("terms", "v1", t0.plusSeconds(1)))
        assertNull(none.graceUntil("terms", t0.plusSeconds(1)))

        val long = catalog(
            v("terms", "v0", "2025-01-01T00:00:00Z"), v("terms", "v1", "2026-01-01T00:00:00Z"), v("terms", "v2", "2026-10-01T00:00:00Z"),
            grace = Duration.ofDays(365),
        )
        assertNull(long.agreeable("terms", "v0", t0.plusSeconds(1)))
        assertNull(long.agreeable("terms", "v3", t0), "a version that does not exist is not agreeable")
    }

    @Test
    fun `a version that does not take effect yet cannot be agreed to and cannot be read`() {
        val c = catalog(v("terms", "v1", "2026-01-01T00:00:00Z"), v("terms", "v2", "2026-12-01T00:00:00Z"), grace = Duration.ofDays(1))
        assertNull(c.agreeable("terms", "v2", t0))
        assertNull(c.find("terms", "v2", t0))
        assertEquals("v1", c.find("terms", "v1", t0)!!.version)
        assertEquals("v2", c.find("terms", "v2", Instant.parse("2026-12-01T00:00:00Z"))!!.version)
    }

    @Test
    fun `rendering fills the facts, reports the missing ones and falls back to the default locale`() {
        val c = catalog(
            v("terms", "v1", "2026-01-01T00:00:00Z", locales = listOf("ko"), text = "# 약관\n\n{{company-name}} / {{contact-email}}"),
            facts = mapOf("company-name" to "ACME"),
        )

        val rendered = c.render(c.current("terms", t0)!!, "en")

        assertEquals("ko", rendered.locale)
        assertEquals("en", rendered.requestedLocale)
        assertTrue(rendered.markdown.contains("ACME / {{contact-email}}"), rendered.markdown)
        assertEquals(setOf("contact-email"), rendered.missingFacts)
        assertEquals("약관", rendered.title)
        assertEquals(LegalText.sha256("# 약관\n\n{{company-name}} / {{contact-email}} (ko)"), rendered.sha256, "the hash is of the source, not of the filled text")
    }

    @Test
    fun `a clean manifest has no problems`() {
        val c = catalog(v("terms", "v1", "2026-01-01T00:00:00Z"), v("terms", "v2", "2026-10-01T00:00:00Z"))
        assertEquals(emptyList(), c.problems)
        c.requireValid()
    }

    @Test
    fun `startup problems are collected and readable`() {
        val c = catalog(
            v("terms", "v1", "2026-01-01T00:00:00Z", text = "<b>raw</b>"),
            ManifestVersion("terms", "v2", DocumentStatus.REVIEWED, null, false, listOf("ko"), emptyMap()),
            ManifestVersion("terms", "v3", DocumentStatus.REVIEWED, Instant.parse("2026-11-01T00:00:00Z"), false, listOf("ko"), mapOf("ko" to "0".repeat(64))),
            ManifestVersion("terms", "v4", DocumentStatus.DRAFT, Instant.parse("2026-11-01T00:00:00Z"), false, listOf("en"), emptyMap()),
            ManifestVersion("Bad Type", "x y", DocumentStatus.DRAFT, null, false, listOf("ko"), emptyMap()),
            required = setOf("terms", "privacy"),
        )
        sources["terms/v3.ko"] = "# changed after review"

        val text = c.problems.joinToString("\n")

        assertTrue(text.contains("terms v1 ko: line 1: raw HTML"), text)
        assertTrue(text.contains("terms v2 is REVIEWED but has no effectiveFrom"), text)
        assertTrue(text.contains("terms v2 ko: source file is missing"), text)
        assertTrue(text.contains("terms v3 ko: sha256 in the manifest differs from the source"), text)
        assertTrue(text.contains("computed " + LegalText.sha256("# changed after review")), "the readable message carries the hash to paste: $text")
        assertTrue(text.contains("terms v4 has no source in the default locale ko"), text)
        assertTrue(text.contains("terms v3 and terms v4 take effect at the same instant"), text)
        assertTrue(text.contains("document type 'Bad Type'"), text)
        assertTrue(text.contains("version 'x y'"), text)
        assertTrue(text.contains("required document type 'privacy' has no version"), text)
        assertFailsWith<IllegalStateException> { c.requireValid() }.message!!.let { assertTrue(it.startsWith("skeleton.legal: "), it) }
    }

    @Test
    fun `a reviewed version has to declare its hash, a draft may leave it out`() {
        val c = catalog(v("terms", "v1", "2026-01-01T00:00:00Z", hashes = false), v("terms", "v2", null, status = DocumentStatus.DRAFT, hashes = false))
        val text = c.problems.joinToString("\n")
        assertTrue(text.contains("terms v1 ko: REVIEWED needs sha256 in the manifest (computed "), text)
        assertTrue(!text.contains("terms v2"), text)
    }

    @Test
    fun `missing facts of the versions that can be served are listed`() {
        val c = catalog(v("terms", "v1", "2026-01-01T00:00:00Z", text = "# T\n\n{{a}} {{b}}"), facts = mapOf("a" to "x"))
        assertEquals(setOf("b"), c.missingFacts(t0))
    }
}
