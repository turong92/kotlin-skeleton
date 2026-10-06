package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.consent.SignUpConsentGate
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.erasure.AccountDataExporter
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class LegalAutoConfigurationTest {
    private val clock = MutableClock(Instant.parse("2026-10-07T00:00:00Z"))

    @Configuration(proxyBeanMethods = false)
    class Ports(private val ledger: FakeLedger = FakeLedger(), private val store: FakeConsentStore = FakeConsentStore()) {
        @Bean fun ledger(): LegalLedger = ledger
        @Bean fun store(): ConsentStore = store
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LegalAutoConfiguration::class.java))
        .withBean(TimeProvider::class.java, { clock })
        .withPropertyValues("skeleton.legal.location=classpath:legal-web/", "skeleton.legal.facts.company-name=ACME", "skeleton.legal.facts.contact-email=a@b.c")

    private fun ApplicationContextRunner.withPorts() = withUserConfiguration(Ports::class.java)

    @Test
    fun `the module registers its services and hooks, each replaceable by a bean of the same type`() {
        runner.withPorts().run { ctx ->
            assertNotNull(ctx.getBean(LegalCatalog::class.java))
            assertNotNull(ctx.getBean(ConsentService::class.java))
            assertTrue(ctx.getBean(SignUpConsentGate::class.java) is LegalSignUpGate)
            assertEquals("legal", ctx.getBean(AccountErasureListener::class.java).name)
            assertEquals("legal", ctx.getBean(AccountDataExporter::class.java).section)
            assertEquals("legal", ctx.getBean(DeployGuard::class.java).name)
            assertEquals(setOf("terms", "privacy", "marketing"), ctx.getBean(LegalCatalog::class.java).types)
        }
        val mine = object : SignUpConsentGate {
            override fun check(claims: List<dev.sumin.skeleton.common.consent.ConsentClaim>) = Unit
            override fun record(accountId: String, claims: List<dev.sumin.skeleton.common.consent.ConsentClaim>, context: dev.sumin.skeleton.common.consent.ConsentContext) = Unit
        }
        runner.withPorts().withBean(SignUpConsentGate::class.java, { mine }).run { ctx -> assertTrue(ctx.getBean(SignUpConsentGate::class.java) === mine) }
    }

    @Test
    fun `the configured values reach the services - required types, grace and facts`() {
        runner.withPorts().withPropertyValues("skeleton.legal.required=terms", "skeleton.legal.previous-version-grace=3d").run { ctx ->
            val service = ctx.getBean(ConsentService::class.java)
            assertEquals(listOf("terms"), service.status(Subject.account("a")).missing.map { it.type })
            assertEquals(Instant.parse("2026-10-04T00:00:00Z"), ctx.getBean(LegalCatalog::class.java).graceUntil("terms", clock.now))
            assertEquals(emptySet(), ctx.getBean(LegalCatalog::class.java).missingFacts(clock.now))
        }
    }

    @Test
    fun `every version of the manifest is pinned in the ledger when the app starts`() {
        val ledger = FakeLedger()
        runner.withBean(ConsentStore::class.java, { FakeConsentStore() }).withBean(LegalLedger::class.java, { ledger }).run { ctx ->
            assertEquals(null, ctx.startupFailure, ctx.startupFailure?.toString())
        }
        assertEquals(
            setOf(
                Triple("terms", "v1", "ko"), Triple("terms", "v1", "en"), Triple("terms", "v2", "ko"), Triple("terms", "v2", "en"),
                Triple("privacy", "v1", "ko"), Triple("privacy", "v1", "en"), Triple("marketing", "v1", "ko"),
            ),
            ledger.rows.keys,
        )
    }

    @Test
    fun `a directory without a manifest falls back to the module's TEMPLATE documents, and the guard says so`() {
        runner.withPorts().withPropertyValues("skeleton.legal.location=classpath:nothing-here/").run { ctx ->
            val catalog = ctx.getBean(LegalCatalog::class.java)
            assertEquals(setOf("terms", "privacy", "marketing"), catalog.types)
            assertTrue(catalog.all().all { it.meta.template })
            val guard = ctx.getBean(DeployGuard::class.java)
            assertEquals(1, guard.problems(DeployContext(DeployEnv.PROD, emptySet())).count { it.contains("TEMPLATE") })
            assertEquals(emptyList(), guard.problems(DeployContext(DeployEnv.LOCAL, emptySet())))
        }
    }

    @Test
    fun `an app that acknowledges the template may run it in prod`() {
        runner.withPorts().withPropertyValues("skeleton.legal.location=classpath:nothing-here/", "skeleton.legal.acknowledge-template=true", "skeleton.legal.facts.company-name=A", "skeleton.legal.facts.contact-email=a@b.c").run { ctx ->
            assertEquals(emptyList(), ctx.getBean(DeployGuard::class.java).problems(DeployContext(DeployEnv.PROD, emptySet())))
        }
    }

    @Test
    fun `without a consent store the app does not start and the missing bean is named`() {
        runner.withBean(LegalLedger::class.java, { FakeLedger() }).run { ctx ->
            assertTrue(ctx.startupFailure != null)
            assertTrue(generateSequence<Throwable>(ctx.startupFailure) { it.cause }.any { it.message.orEmpty().contains("ConsentStore") }, ctx.startupFailure.toString())
        }
    }

    @Test
    fun `a broken document fails the start with a message that names the file's problem`() {
        runner.withPorts().withPropertyValues("skeleton.legal.location=classpath:legal-bad/", "skeleton.legal.required=terms").run { ctx ->
            val text = generateSequence<Throwable>(ctx.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
            assertTrue(text.contains("skeleton.legal: terms v1 ko: line 3: raw HTML"), text)
        }
    }

    @Test
    fun `a published text that changed since the ledger pinned it fails the start`() {
        val ledger = FakeLedger()
        ledger.append(LedgerEntry("terms", "v1", "ko", DocumentStatus.REVIEWED, "0".repeat(64), Instant.parse("2026-01-01T00:00:00Z"), clock.now))
        runner.withBean(ConsentStore::class.java, { FakeConsentStore() }).withBean(LegalLedger::class.java, { ledger }).run { ctx ->
            val text = generateSequence<Throwable>(ctx.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
            assertTrue(text.contains("terms v1 ko: the text changed after it was published"), text)
        }
    }

    @Test
    fun `a required type that the manifest does not have fails the start`() {
        runner.withPorts().withPropertyValues("skeleton.legal.required=terms,age").run { ctx ->
            val text = generateSequence<Throwable>(ctx.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
            assertTrue(text.contains("required document type 'age' has no version"), text)
        }
    }
}
