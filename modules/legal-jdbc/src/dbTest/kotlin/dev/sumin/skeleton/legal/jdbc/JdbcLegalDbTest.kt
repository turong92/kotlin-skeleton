package dev.sumin.skeleton.legal.jdbc

import dev.sumin.skeleton.common.consent.ConsentClaim
import dev.sumin.skeleton.common.consent.ConsentContext
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.legal.ConsentAction
import dev.sumin.skeleton.legal.ConsentErasureListener
import dev.sumin.skeleton.legal.ConsentSearch
import dev.sumin.skeleton.legal.ConsentService
import dev.sumin.skeleton.legal.ConsentState
import dev.sumin.skeleton.legal.ConsentStore
import dev.sumin.skeleton.legal.DocumentStatus
import dev.sumin.skeleton.legal.ErasureMode
import dev.sumin.skeleton.legal.LedgerEntry
import dev.sumin.skeleton.legal.LegalCatalog
import dev.sumin.skeleton.legal.LegalLedger
import dev.sumin.skeleton.legal.LegalRules
import dev.sumin.skeleton.legal.LegalText
import dev.sumin.skeleton.legal.LedgerPin
import dev.sumin.skeleton.legal.ManifestVersion
import dev.sumin.skeleton.legal.NewConsentEvent
import dev.sumin.skeleton.legal.Subject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient

/** 같은 소스가 PostgreSQL · MySQL 두 벌에서 돈다 — 트리거가 실제로 막는지, 동시 기록이 한 줄인지, 장부가 못 박는지 */
@Import(DbTestcontainers::class, JdbcLegalDbTest.Fixtures::class)
@SpringBootTest(classes = [LegalJdbcTestApplication::class], properties = ["skeleton.legal.location=classpath:none/"])
class JdbcLegalDbTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun time() = MutableTime()
    }

    class MutableTime(var now: Instant = Instant.parse("2026-10-07T00:00:00Z")) : TimeProvider { override fun now(): Instant = now }

    @Autowired lateinit var store: ConsentStore
    @Autowired lateinit var ledger: LegalLedger
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var catalog: LegalCatalog
    @Autowired lateinit var rules: LegalRules
    @Autowired lateinit var time: MutableTime

    private val t0 = Instant.parse("2026-10-07T01:02:03.456789Z")
    private val me = Subject.account("acc_1")

    @BeforeEach
    fun clean() {
        // 트리거가 지우기를 막으므로 시험 사이의 정리도 같은 규칙을 따른다: 익명화한 뒤 지운다
        jdbc.sql("select distinct subject_type, subject_id from legal_consents where subject_id not like 'deleted:%'")
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
            .forEach { (type, id) -> store.anonymize(Subject(type, id), "deleted:clean-$id") }
        jdbc.sql("delete from legal_consents").update()
        time.now = Instant.parse("2026-10-07T00:00:00Z")
    }

    private fun event(
        seq: Int, type: String = "terms", action: ConsentAction = ConsentAction.AGREED, subject: Subject = me, reference: String? = null,
        version: String = "template-1", at: Instant = t0, ip: String? = "203.0.113.9", ua: String? = "ua",
    ) = NewConsentEvent(subject, type, version, "a".repeat(64), "ko", action, "sign-up", reference, seq, ip, ua, at)

    @Test
    fun `the JDBC stores replace nothing the app supplies and are the ones wired by the module`() {
        assertTrue(store is JdbcConsentStore)
        assertTrue(ledger is JdbcLegalLedger)
    }

    @Test
    fun `an appended event reads back exactly, with microsecond time, and latest returns the highest sequence per type`() {
        assertTrue(store.append(event(1)))
        assertTrue(store.append(event(2, action = ConsentAction.WITHDRAWN)))
        assertTrue(store.append(event(1, type = "privacy")))

        val latest = store.latest(me, listOf("terms", "privacy", "marketing"), null)

        assertEquals(setOf("terms", "privacy"), latest.keys)
        val terms = latest.getValue("terms")
        assertEquals(2, terms.seq)
        assertEquals(ConsentAction.WITHDRAWN, terms.action)
        assertEquals(t0, terms.at)
        assertEquals("a".repeat(64), terms.sha256)
        assertEquals("ko", terms.locale)
        assertEquals("203.0.113.9", terms.ip)
        assertEquals("ua", terms.userAgent)
        assertNull(terms.referenceId)
        assertEquals(me, terms.subject)
    }

    @Test
    fun `the same sequence cannot be written twice, so a lost race says false instead of making a second row`() {
        assertTrue(store.append(event(1)))
        assertEquals(false, store.append(event(1)))
        assertEquals(1, store.export(me).size)
    }

    @Test
    fun `references and subjects are separate slots`() {
        store.append(event(1, reference = "order-7"))
        store.append(event(1))
        store.append(event(1, subject = Subject("device", "acc_1")))

        assertEquals("order-7", store.latest(me, listOf("terms"), "order-7").getValue("terms").referenceId)
        assertNull(store.latest(me, listOf("terms"), null).getValue("terms").referenceId)
        assertEquals(1, store.latest(Subject("device", "acc_1"), listOf("terms"), null).size)
        assertEquals(emptyMap(), store.latest(me, listOf("terms"), "order-8"))
    }

    @Test
    fun `sixteen threads recording the same agreement through the service leave exactly one row`() {
        val service = ConsentService(catalog, rules, store, time)
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val results = (1..16).map {
            pool.submit { go.await(); service.record(me, listOf(ConsentClaim("terms", "template-1")), ConsentContext("1.1.1.1", "ua"), "consent") }
        }
        go.countDown()
        results.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, store.export(me).size)
        assertEquals(ConsentState.CURRENT, service.status(me).items.single { it.type == "terms" }.state)
    }

    @Test
    fun `agree and withdraw racing from many threads never break the sequence`() {
        val service = ConsentService(catalog, rules, store, time)
        service.record(me, listOf(ConsentClaim("marketing", "template-1")), ConsentContext(null, null), "consent")
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val results = (1..24).map { i ->
            pool.submit {
                go.await()
                if (i % 2 == 0) service.withdraw(me, "marketing", ConsentContext(null, null))
                else service.record(me, listOf(ConsentClaim("marketing", "template-1")), ConsentContext(null, null), "consent")
            }
        }
        go.countDown()
        results.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        val seqs = store.export(me).map { it.seq }
        assertEquals((1..seqs.size).toList(), seqs.sorted(), "contiguous, no duplicates")
        val actions = store.export(me).sortedBy { it.seq }.map { it.action }
        assertTrue(actions.zipWithNext().all { (a, b) -> a != b }, "events alternate: a no-op is never recorded: $actions")
    }

    // ---- append-only is enforced by the database ----

    private fun refused(sql: String) { assertFailsWith<DataAccessException>(sql) { jdbc.sql(sql).update() } }

    @Test
    fun `the database refuses to rewrite or delete an agreement`() {
        store.append(event(1))
        refused("update legal_consents set version = 'x'")
        refused("update legal_consents set content_sha256 = '${"b".repeat(64)}'")
        refused("update legal_consents set action = 'WITHDRAWN'")
        refused("update legal_consents set seq = seq + 1")
        refused("update legal_consents set subject_id = 'acc_2'")
        refused("update legal_consents set ip = '198.51.100.1'")
        refused("delete from legal_consents")
        assertEquals(1, store.export(me).size)
        assertEquals("203.0.113.9", store.export(me).single().ip)
    }

    @Test
    fun `the database allows only what erasure and retention need - tombstone the subject and clear the client data`() {
        store.append(event(1))
        assertEquals(1, jdbc.sql("update legal_consents set ip = null, user_agent = null").update())
        assertEquals(1, jdbc.sql("update legal_consents set subject_id = 'deleted:abc'").update())
        assertEquals(1, jdbc.sql("delete from legal_consents where subject_id = 'deleted:abc'").update())
    }

    @Test
    fun `the version ledger cannot be changed or deleted at all`() {
        assertTrue(ledger.append(LedgerEntry("immutable", "v1", "ko", DocumentStatus.REVIEWED, "c".repeat(64), t0, t0)))
        refused("update legal_document_versions set content_sha256 = '${"d".repeat(64)}'")
        refused("update legal_document_versions set status = 'DRAFT'")
        refused("delete from legal_document_versions")
        assertEquals("c".repeat(64), ledger.find("immutable", "v1", "ko")!!.sha256)
    }

    // ---- ledger ----

    @Test
    fun `the ledger roundtrips status, hash, effective date and recorded time, with a missing effective date`() {
        assertTrue(ledger.append(LedgerEntry("privacy", "v7", "en", DocumentStatus.DRAFT, "e".repeat(64), null, t0)))
        assertTrue(ledger.append(LedgerEntry("privacy", "v7", "ko", DocumentStatus.REVIEWED, "f".repeat(64), Instant.parse("2026-11-01T00:00:00Z"), t0)))
        assertEquals(false, ledger.append(LedgerEntry("privacy", "v7", "ko", DocumentStatus.REVIEWED, "f".repeat(64), Instant.parse("2026-11-01T00:00:00Z"), t0)))

        val draft = assertNotNull(ledger.find("privacy", "v7", "en"))
        assertEquals(DocumentStatus.DRAFT, draft.status)
        assertNull(draft.effectiveFrom)
        assertEquals(t0, draft.recordedAt)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), ledger.find("privacy", "v7", "ko")!!.effectiveFrom)
        assertNull(ledger.find("privacy", "v7", "fr"))
        assertEquals(2, ledger.all().count { it.type == "privacy" && it.version == "v7" })
    }

    @Test
    fun `a published text that changes after the first start is caught by the pin against the real ledger`() {
        val sources = mutableMapOf("pintest/v1.ko" to "You may cancel within 30 days.")
        fun pin(): List<String> {
            val m = ManifestVersion("pintest", "v1", DocumentStatus.REVIEWED, Instant.parse("2026-01-01T00:00:00Z"), false, listOf("ko"), mapOf("ko" to LegalText.sha256(sources["pintest/v1.ko"]!!)))
            val c = LegalCatalog(listOf(m), { t, v, l -> sources["$t/$v.$l"] }, LegalRules(emptySet(), emptySet(), "ko", Duration.ZERO, emptyMap()))
            return LedgerPin(ledger) { t0 }.sync(c)
        }
        val first = pin()
        sources["pintest/v1.ko"] = "You may cancel within 3 days."
        val second = pin()

        assertEquals(emptyList(), first)
        assertTrue(second.single().contains("the text changed after it was published"), second.toString())
    }

    // ---- queries and erasure ----

    @Test
    fun `history and search page newest first and filter`() {
        (1..5).forEach { store.append(event(it, type = "marketing", action = if (it % 2 == 1) ConsentAction.AGREED else ConsentAction.WITHDRAWN, at = t0.plusSeconds(it.toLong()))) }
        store.append(event(1, subject = Subject.account("acc_2")))

        val page = store.history(me, 0, 2)
        assertEquals(5, page.total)
        assertEquals(listOf(5, 4), page.items.map { it.seq })
        assertEquals(listOf(3, 2), store.history(me, 1, 2).items.map { it.seq })

        assertEquals(6, store.search(ConsentSearch(), 0, 50).total)
        assertEquals(1, store.search(ConsentSearch(subjectId = "acc_2"), 0, 50).total)
        assertEquals(2, store.search(ConsentSearch(type = "marketing", action = ConsentAction.WITHDRAWN), 0, 50).total)
        assertEquals(5, store.search(ConsentSearch(subjectType = "account", subjectId = "acc_1", type = "marketing"), 0, 50).total)
    }

    @Test
    fun `countSince counts a subject's recent events only`() {
        store.append(event(1, at = t0.minus(Duration.ofDays(2))))
        store.append(event(2, at = t0))
        store.append(event(1, type = "privacy", at = t0))
        assertEquals(2, store.countSince(me, t0.minus(Duration.ofHours(1))))
        assertEquals(3, store.countSince(me, t0.minus(Duration.ofDays(3))))
    }

    @Test
    fun `erasure anonymizes, keeping the proof and dropping the person, and delete mode removes the rows`() {
        store.append(event(1))
        store.append(event(1, type = "privacy"))
        store.append(event(1, subject = Subject.account("acc_2")))
        val tomb = AccountTombstone.of("acc_1")

        ConsentErasureListener(store, ErasureMode.ANONYMIZE).erase(ErasureRequest("acc_1", tomb))

        assertEquals(0, store.export(me).size)
        val kept = store.export(Subject.account(tomb))
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.ip == null && it.userAgent == null && it.sha256 == "a".repeat(64) && it.at == t0 })
        assertEquals("203.0.113.9", store.export(Subject.account("acc_2")).single().ip)

        ConsentErasureListener(store, ErasureMode.DELETE).erase(ErasureRequest("acc_1", tomb))   // second run: nothing left to anonymize, then deletes
        assertEquals(0, store.export(Subject.account(tomb)).size)
        assertEquals(1, store.export(Subject.account("acc_2")).size)
    }

    @Test
    fun `scrubbing clears client data older than the cut-off and keeps the rows`() {
        store.append(event(1, at = t0.minus(Duration.ofDays(400))))
        store.append(event(2, at = t0))

        assertEquals(1, store.scrubPersonalData(t0.minus(Duration.ofDays(365))))
        assertEquals(0, store.scrubPersonalData(t0.minus(Duration.ofDays(365))), "nothing left to scrub")

        val rows = store.export(me).sortedBy { it.seq }
        assertEquals(listOf(null, "203.0.113.9"), rows.map { it.ip })
        assertEquals(2, rows.size)
    }
}
