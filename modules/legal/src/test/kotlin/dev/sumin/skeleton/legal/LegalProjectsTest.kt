package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.project.NoProjectContextException
import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 프로젝트 모드: 문서 위치 · 사실(facts) · 필수 종류가 프로젝트마다다 — 한 프로젝트의 운영자 이름이 다른 프로젝트의 약관에 들어가지 않는다 */
class LegalProjectsTest {
    /** 진짜 장부는 스키마마다 있다 — 프로젝트 문맥으로 갈린다 */
    private class PerProjectLedger : LegalLedger {
        private val ledgers = java.util.concurrent.ConcurrentHashMap<String, FakeLedger>()
        private val current get() = ledgers.computeIfAbsent(ProjectContext.current().id) { FakeLedger() }
        override fun find(type: String, version: String, locale: String) = current.find(type, version, locale)
        override fun all() = current.all()
        override fun append(entry: LedgerEntry) = current.append(entry)
    }

    private val clock = MutableClock(Instant.parse("2026-10-07T00:00:00Z"))

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, LegalAutoConfiguration::class.java))
        .withBean(LegalLedger::class.java, { PerProjectLedger() })
        .withBean(ConsentStore::class.java, { FakeConsentStore() })
        .withBean(TimeProvider::class.java, { clock })
        .withPropertyValues(
            "skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa",
            "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb",
            "skeleton.projects.c.hosts=c.test", "skeleton.projects.c.schema=pc",
        )

    private val a = "skeleton.projects.a.config.legal"
    private val b = "skeleton.projects.b.config.legal"
    private val c = "skeleton.projects.c.config.legal"

    private fun <T> Project.run(block: () -> T) = ProjectContext.runIn(this, block)

    private val docs = arrayOf(
        "$a.location=classpath:legal-web/", "$a.facts.company-name=ACME-A", "$a.facts.contact-email=a@a.test",
        "$b.location=classpath:legal-web/", "$b.facts.company-name=ACME-B", "$b.facts.contact-email=b@b.test", "$b.required=terms",
        "$c.required=terms",   // 문서 위치를 안 준 프로젝트는 모듈의 TEMPLATE 문서로 물러선다
    )

    @Test
    fun `each project reads its own documents, required types and facts`() {
        runner.withPropertyValues(*docs).run { ctx ->
            assertEquals(null, ctx.startupFailure, ctx.startupFailure?.toString())
            val catalog = ctx.getBean(LegalCatalog::class.java)
            val rules = ctx.getBean(LegalRules::class.java)
            val (pa, pb, pc) = ctx.getBean(ProjectRegistry::class.java).projects

            assertEquals(setOf("terms", "privacy", "marketing"), pa.run { catalog.types })
            assertFalse(pa.run { catalog.current("terms", clock.now)!!.meta.template })
            assertTrue(pc.run { catalog.current("terms", clock.now)!!.meta.template }, "C has no documents of its own")
            assertEquals(setOf("terms", "privacy"), pa.run { rules.required })
            assertEquals(setOf("terms"), pb.run { rules.required })

            fun shown(p: Project) = p.run { catalog.render(catalog.current("terms", clock.now)!!, "ko").markdown }
            assertTrue("ACME-A" in shown(pa) && "ACME-B" !in shown(pa))
            assertTrue("ACME-B" in shown(pb) && "ACME-A" !in shown(pb))
            assertFailsWith<NoProjectContextException> { catalog.types }
        }
    }

    @Test
    fun `a project whose documents are broken stops the start and the message names the project`() {
        runner.withPropertyValues("$a.location=classpath:legal-web/", "$a.facts.company-name=x", "$b.location=classpath:legal-bad/", "$c.location=classpath:legal-web/").run { ctx ->
            val messages = generateSequence<Throwable>(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(messages.any { "project 'b'" in it }, messages.toString())
        }
    }

    @Test
    fun `the deploy guard checks every project and says which one lacks a fact`() {
        // b 는 사실을 하나도 안 줬고, c 는 TEMPLATE 문서를 쓴다 (a 만 깨끗하다)
        runner.withPropertyValues(*docs.filterNot { it.startsWith("$b.facts.") }.toTypedArray()).run { ctx ->
            val problems = ctx.getBean(DeployGuard::class.java).problems(DeployContext(DeployEnv.PROD, emptySet()))
            assertTrue(problems.any { it.startsWith("project 'b':") && "legal.facts" in it }, problems.toString())
            assertTrue(problems.any { it.startsWith("project 'c':") && "TEMPLATE" in it }, problems.toString())
            assertFalse(problems.any { it.startsWith("project 'a':") }, problems.toString())
        }
    }

    @Test
    fun `documents and facts left in the shared settings are not borrowed by a project that gave none`() {
        runner.withPropertyValues(
            "skeleton.legal.location=classpath:legal-web/", "skeleton.legal.facts.company-name=SHARED-CO", "skeleton.legal.facts.contact-email=s@s.test",
            "$a.location=classpath:legal-web/", "$a.facts.company-name=ACME-A", "$a.facts.contact-email=a@a.test",
            "$b.required=terms", "$c.required=terms",
        ).run { ctx ->
            val catalog = ctx.getBean(LegalCatalog::class.java)
            val (pa, pb) = ctx.getBean(ProjectRegistry::class.java).projects
            assertTrue(pa.run { catalog.render(catalog.current("terms", clock.now)!!, "ko").markdown }.contains("ACME-A"))
            assertTrue(pb.run { catalog.current("terms", clock.now)!!.meta.template }, "B gave no documents: TEMPLATE, not the shared ones")
            val problems = ctx.getBean(DeployGuard::class.java).problems(DeployContext(DeployEnv.PROD, emptySet()))
            assertTrue(problems.any { it.startsWith("project 'b':") && "TEMPLATE" in it }, problems.toString())
        }
    }

    @Test
    fun `a project that gave no location does not pick up the app's own documents at the default location`() {
        // 클래스패스에 legal/manifest.json(앱의 문서)이 있다 — 위치를 적은 a 만 문서를 쓰고, 안 적은 b 는 TEMPLATE
        runner.withPropertyValues("$a.location=classpath:legal-web/", "$a.facts.company-name=x", "$b.required=terms", "$c.required=terms").run { ctx ->
            val catalog = ctx.getBean(LegalCatalog::class.java)
            val (_, pb) = ctx.getBean(ProjectRegistry::class.java).projects
            assertTrue(pb.run { catalog.current("terms", clock.now)!!.meta.template }, "b must get the TEMPLATE, not classpath:legal/")
        }
    }
}
