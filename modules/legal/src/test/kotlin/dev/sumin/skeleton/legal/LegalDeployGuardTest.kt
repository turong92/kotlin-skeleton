package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegalDeployGuardTest {
    private val now = Instant.parse("2026-10-07T00:00:00Z")
    private val sources = mutableMapOf<String, String>()
    private val prod = DeployContext(DeployEnv.PROD, emptySet())
    private val stage = DeployContext(DeployEnv.STAGE, emptySet())
    private val local = DeployContext(DeployEnv.LOCAL, emptySet())
    private val unset = DeployContext(null, emptySet())

    private fun v(type: String, status: DocumentStatus = DocumentStatus.REVIEWED, from: String? = "2026-01-01T00:00:00Z", template: Boolean = false, text: String = "# $type\n\nbody"): ManifestVersion {
        sources["$type/v1.ko"] = text
        return ManifestVersion(type, "v1", status, from?.let(Instant::parse), template, listOf("ko"), if (status == DocumentStatus.REVIEWED) mapOf("ko" to LegalText.sha256(text)) else emptyMap())
    }

    private fun guard(vararg m: ManifestVersion, facts: Map<String, String> = emptyMap(), acknowledge: Boolean = false) =
        LegalDeployGuard(LegalCatalog(m.toList(), { t, ver, l -> sources["$t/$ver.$l"] }, LegalRules(emptySet(), emptySet(), "ko", Duration.ZERO, facts)), acknowledge) { now }

    @Test
    fun `reviewed, real documents with all facts pass in every environment`() {
        val g = guard(v("terms", text = "# T\n\n{{company-name}}"), facts = mapOf("company-name" to "ACME"))
        assertEquals(emptyList(), g.problems(prod))
        assertEquals(emptyList(), g.problems(stage))
        assertEquals(emptyList(), g.warnings(prod))
    }

    @Test
    fun `template text is refused in stage and prod, with the way out named, and only warned about elsewhere`() {
        val g = guard(v("terms", status = DocumentStatus.DRAFT, template = true))

        val problem = g.problems(prod).single()
        assertTrue(problem.contains("terms") && problem.contains("TEMPLATE"), problem)
        assertTrue(problem.contains("skeleton.legal.location") && problem.contains("skeleton.legal.acknowledge-template"), problem)
        assertEquals(1, g.problems(stage).size)
        assertEquals(emptyList(), g.problems(local))
        assertEquals(emptyList(), g.problems(unset))
        assertTrue(g.warnings(local).single().contains("TEMPLATE"))
    }

    @Test
    fun `acknowledging the template lets it run in prod, and says so in a warning`() {
        val g = guard(v("terms", status = DocumentStatus.DRAFT, template = true), acknowledge = true)
        assertEquals(emptyList(), g.problems(prod))
        assertTrue(g.warnings(prod).single().contains("acknowledge-template"))
    }

    @Test
    fun `a draft with an effective date would become current, which only reviewed documents may`() {
        val g = guard(v("terms"), v("privacy", status = DocumentStatus.DRAFT))
        val problem = g.problems(prod).single()
        assertTrue(problem.contains("privacy v1") && problem.contains("DRAFT"), problem)
        assertEquals(emptyList(), g.problems(local))
        assertTrue(g.warnings(local).single().contains("privacy v1"))
    }

    @Test
    fun `a draft without an effective date is work in progress and never served, so it is no problem`() {
        val g = guard(v("terms"), v("privacy", status = DocumentStatus.DRAFT, from = null))
        assertEquals(emptyList(), g.problems(prod))
    }

    @Test
    fun `acknowledging the template does not excuse a real draft`() {
        val g = guard(v("terms", status = DocumentStatus.DRAFT, template = true), v("privacy", status = DocumentStatus.DRAFT), acknowledge = true)
        assertEquals(1, g.problems(prod).size)
        assertTrue(g.problems(prod).single().contains("privacy"))
    }

    @Test
    fun `placeholders without a fact are a problem in stage and prod, naming the keys and never a value`() {
        val g = guard(v("terms", text = "# T\n\n{{company-name}} {{contact-email}}"), facts = mapOf("company-name" to "ACME"))

        val problem = g.problems(prod).single()
        assertTrue(problem.contains("skeleton.legal.facts.contact-email"), problem)
        assertTrue(!problem.contains("ACME"), problem)
        assertEquals(emptyList(), g.problems(local))
        assertTrue(g.warnings(local).single().contains("contact-email"))
    }

    @Test
    fun `the placeholders of a template are examples, so their missing facts are not the guard's business`() {
        val g = guard(v("terms", status = DocumentStatus.DRAFT, template = true, text = "# T\n\n{{company-name}}"), acknowledge = true)
        assertEquals(emptyList(), g.problems(prod))
        assertTrue(g.warnings(prod).none { it.contains("company-name") }, g.warnings(prod).toString())
        assertTrue(g.warnings(local).none { it.contains("company-name") })
    }

    @Test
    fun `the guard has a name for the startup summary`() {
        assertEquals("legal", guard(v("terms")).name)
    }
}
