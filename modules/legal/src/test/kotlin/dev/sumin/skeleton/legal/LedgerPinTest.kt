package dev.sumin.skeleton.legal

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LedgerPinTest {
    private val now = Instant.parse("2026-10-07T00:00:00Z")
    private val ledger = FakeLedger()
    private val sources = mutableMapOf<String, String>()

    private fun manifest(type: String, version: String, status: DocumentStatus, text: String, from: String? = "2026-01-01T00:00:00Z"): ManifestVersion {
        sources["$type/$version.ko"] = text
        return ManifestVersion(type, version, status, from?.let(Instant::parse), false, listOf("ko"), if (status == DocumentStatus.REVIEWED) mapOf("ko" to LegalText.sha256(text)) else emptyMap())
    }

    private fun catalog(vararg m: ManifestVersion) =
        LegalCatalog(m.toList(), { t, v, l -> sources["$t/$v.$l"] }, LegalRules(emptySet(), emptySet(), "ko", Duration.ZERO, emptyMap()))

    private fun pin(vararg m: ManifestVersion) = LedgerPin(ledger) { now }.sync(catalog(*m))

    @Test
    fun `a version seen for the first time is appended with its status, hash and effective date`() {
        assertEquals(emptyList(), pin(manifest("terms", "v1", DocumentStatus.REVIEWED, "# T v1")))

        val row = ledger.find("terms", "v1", "ko")!!
        assertEquals(DocumentStatus.REVIEWED, row.status)
        assertEquals(LegalText.sha256("# T v1"), row.sha256)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), row.effectiveFrom)
        assertEquals(now, row.recordedAt)
    }

    @Test
    fun `booting again with the same content changes nothing`() {
        val m = manifest("terms", "v1", DocumentStatus.REVIEWED, "# T v1")
        pin(m)
        assertEquals(emptyList(), pin(m))
        assertEquals(1, ledger.all().size)
    }

    @Test
    fun `a published text that changed is refused with a message that names the version and says what to do`() {
        pin(manifest("terms", "v1", DocumentStatus.REVIEWED, "You may cancel within 30 days."))

        val problems = pin(manifest("terms", "v1", DocumentStatus.REVIEWED, "You may cancel within 3 days."))

        val text = problems.single()
        assertTrue(text.startsWith("terms v1 ko: the text changed after it was published"), text)
        assertTrue(text.contains(LegalText.sha256("You may cancel within 30 days.").take(12)), text)
        assertTrue(text.contains(LegalText.sha256("You may cancel within 3 days.").take(12)), text)
        assertTrue(text.contains("add a new version"), text)
        assertEquals(LegalText.sha256("You may cancel within 30 days."), ledger.find("terms", "v1", "ko")!!.sha256, "the ledger row is never rewritten")
    }

    @Test
    fun `a published effective date cannot move`() {
        pin(manifest("terms", "v1", DocumentStatus.REVIEWED, "# T", from = "2026-01-01T00:00:00Z"))
        val problems = pin(manifest("terms", "v1", DocumentStatus.REVIEWED, "# T", from = "2026-02-01T00:00:00Z"))
        assertTrue(problems.single().contains("effective date changed after it was published"), problems.toString())
    }

    @Test
    fun `a draft that was recorded as a draft cannot be promoted in place, but may be edited`() {
        pin(manifest("terms", "v1", DocumentStatus.DRAFT, "# draft 1", from = null))

        assertEquals(emptyList(), pin(manifest("terms", "v1", DocumentStatus.DRAFT, "# draft 2", from = null)), "drafts are editable")
        val problems = pin(manifest("terms", "v1", DocumentStatus.REVIEWED, "# draft 2"))
        assertTrue(problems.single().contains("was recorded as DRAFT") && problems.single().contains("new version"), problems.toString())
    }

    @Test
    fun `a published version removed from the manifest is refused, a removed draft is fine`() {
        pin(manifest("terms", "v1", DocumentStatus.REVIEWED, "# T v1"), manifest("terms", "v2", DocumentStatus.DRAFT, "# T v2", from = null))

        val problems = pin(manifest("terms", "v2", DocumentStatus.DRAFT, "# T v2", from = null))

        assertTrue(problems.single().contains("terms v1 ko: published, but missing from the manifest"), problems.toString())
        assertEquals(emptyList(), pin(manifest("terms", "v1", DocumentStatus.REVIEWED, "# T v1")), "dropping the draft v2 is allowed")
    }

    @Test
    fun `a published status cannot go back to draft`() {
        pin(manifest("terms", "v1", DocumentStatus.REVIEWED, "# T"))
        val problems = pin(manifest("terms", "v1", DocumentStatus.DRAFT, "# T", from = null))
        assertTrue(problems.single().contains("was published as REVIEWED"), problems.toString())
    }

    @Test
    fun `nothing is appended while there are conflicts`() {
        pin(manifest("terms", "v1", DocumentStatus.REVIEWED, "# one"))
        pin(manifest("terms", "v1", DocumentStatus.REVIEWED, "# changed"), manifest("privacy", "v1", DocumentStatus.REVIEWED, "# new"))
        assertEquals(setOf("terms"), ledger.all().map { it.type }.toSet())
    }

    @Test
    fun `two instances racing to append the same row both succeed`() {
        var otherInstanceWon = false
        val racing = object : LegalLedger by ledger {
            override fun find(type: String, version: String, locale: String): LedgerEntry? = if (otherInstanceWon) ledger.find(type, version, locale) else null
            override fun append(entry: LedgerEntry): Boolean {
                ledger.append(entry)   // the other instance appended between our read and our write
                otherInstanceWon = true
                return false
            }
        }
        val m = manifest("terms", "v1", DocumentStatus.REVIEWED, "# T")
        assertEquals(emptyList(), LedgerPin(racing) { now }.sync(catalog(m)))
    }

    @Test
    fun `requireClean throws one readable exception listing every conflict`() {
        pin(manifest("terms", "v1", DocumentStatus.REVIEWED, "# one"), manifest("privacy", "v1", DocumentStatus.REVIEWED, "# p"))
        val e = assertFailsWith<IllegalStateException> {
            LedgerPin(ledger) { now }.requireClean(catalog(manifest("terms", "v1", DocumentStatus.REVIEWED, "# two"), manifest("privacy", "v1", DocumentStatus.REVIEWED, "# q")))
        }
        assertTrue(e.message!!.startsWith("skeleton.legal: "), e.message)
        assertTrue(e.message!!.contains("terms v1 ko") && e.message!!.contains("privacy v1 ko"), e.message)
    }
}
