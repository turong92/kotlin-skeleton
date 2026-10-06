package dev.sumin.skeleton.legalnoopt

import dev.sumin.skeleton.legal.*

import dev.sumin.skeleton.common.consent.SignUpConsentGate
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

@SpringBootApplication
class NoSecurityApp

@TestConfiguration(proxyBeanMethods = false)
class Ports {
    @Bean fun time(): TimeProvider = TimeProvider.fixed(Instant.parse("2026-10-07T00:00:00Z"))

    @Bean fun store(): ConsentStore = object : ConsentStore {
        override fun latest(subject: Subject, types: Collection<String>, referenceId: String?) = emptyMap<String, ConsentEvent>()
        override fun append(event: NewConsentEvent) = true
        override fun history(subject: Subject, page: Int, size: Int) = ConsentPage(emptyList(), 0)
        override fun search(search: ConsentSearch, page: Int, size: Int) = ConsentPage(emptyList(), 0)
        override fun countSince(subject: Subject, since: Instant) = 0
        override fun anonymize(subject: Subject, tombstone: String) = 0
        override fun deleteAnonymized(tombstone: String) = 0
        override fun scrubPersonalData(before: Instant) = 0
        override fun export(subject: Subject) = emptyList<ConsentEvent>()
    }

    @Bean fun ledger(): LegalLedger = object : LegalLedger {
        override fun find(type: String, version: String, locale: String): LedgerEntry? = null
        override fun all() = emptyList<LedgerEntry>()
        override fun append(entry: LedgerEntry) = true
    }
}

/** spring-security-core 가 클래스패스에 없는 앱 — 서비스 · 고리는 서고, HTTP 엔드포인트 · 재동의 필터는 등록되지 않는다 */
@SpringBootTest(classes = [NoSecurityApp::class], properties = ["skeleton.legal.reconsent.enabled=true", "skeleton.legal.location=classpath:nothing/"])
@Import(Ports::class)
class LegalWithoutSecurityTest {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `security is really absent from this class path`() {
        assertTrue(runCatching { Class.forName("org.springframework.security.core.Authentication") }.isFailure)
    }

    @Test
    fun `the services and the sign-up hook work without it`() {
        assertEquals(setOf("terms", "privacy", "marketing"), context.getBean(LegalCatalog::class.java).types)
        context.getBean(SignUpConsentGate::class.java).check(
            listOf(
                dev.sumin.skeleton.common.consent.ConsentClaim("terms", "template-1"),
                dev.sumin.skeleton.common.consent.ConsentClaim("privacy", "template-1"),
            ),
        )
    }

    @Test
    fun `no endpoint and no filter is registered`() {
        assertEquals(emptyList(), context.getBeanNamesForType(dev.sumin.skeleton.legal.web.LegalDocumentController::class.java).toList())
        assertEquals(emptyList(), context.getBeanNamesForType(dev.sumin.skeleton.legal.web.ConsentController::class.java).toList())
        assertEquals(emptyList(), context.getBeanNamesForType(org.springframework.web.servlet.config.annotation.WebMvcConfigurer::class.java).filter { it.contains("legal") })
    }
}
