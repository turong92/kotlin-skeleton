package dev.sumin.skeleton.notification.push.legal

import dev.sumin.skeleton.common.consent.ConsentClaim
import dev.sumin.skeleton.common.consent.ConsentContext
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.legal.ConsentAction
import dev.sumin.skeleton.legal.ConsentChangeListener
import dev.sumin.skeleton.legal.ConsentEvent
import dev.sumin.skeleton.legal.ConsentOptions
import dev.sumin.skeleton.legal.ConsentPage
import dev.sumin.skeleton.legal.ConsentSearch
import dev.sumin.skeleton.legal.ConsentService
import dev.sumin.skeleton.legal.ConsentStore
import dev.sumin.skeleton.legal.DocumentStatus
import dev.sumin.skeleton.legal.LedgerEntry
import dev.sumin.skeleton.legal.LegalCatalog
import dev.sumin.skeleton.legal.LegalLedger
import dev.sumin.skeleton.legal.LegalRules
import dev.sumin.skeleton.legal.LegalText
import dev.sumin.skeleton.legal.ManifestVersion
import dev.sumin.skeleton.legal.NewConsentEvent
import dev.sumin.skeleton.legal.Subject
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublishResult
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.push.PushAdvertisingNoticeStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class MutableClock(@Volatile var now: Instant) : TimeProvider {
    override fun now(): Instant = now
}

/** 시험용 메모리 저장소 - 이 모듈이 쓰는 읽기 · 쓰기만 진짜고 나머지는 비어 있다 */
class MemoryConsentStore : ConsentStore {
    val rows = CopyOnWriteArrayList<ConsentEvent>()

    @Synchronized
    override fun latest(subject: Subject, types: Collection<String>, referenceId: String?): Map<String, ConsentEvent> =
        rows.filter { it.subject == subject && it.type in types && it.referenceId == referenceId }.groupBy { it.type }.mapValues { (_, v) -> v.maxBy { it.seq } }

    @Synchronized
    override fun append(event: NewConsentEvent): Boolean {
        if (rows.any { it.subject == event.subject && it.type == event.type && it.referenceId == event.referenceId && it.seq == event.seq }) return false
        rows += ConsentEvent(
            rows.size + 1L, event.subject, event.type, event.version, event.sha256, event.locale, event.action, event.source,
            event.referenceId, event.seq, event.ip, event.userAgent, event.at,
        )
        return true
    }

    @Synchronized
    override fun latestAgreementsBefore(type: String, before: Instant, afterSubjectId: String?, limit: Int): List<ConsentEvent> =
        rows.filter { it.type == type && it.subject.type == Subject.ACCOUNT && it.referenceId == null }
            .groupBy { it.subject.id }.map { (_, v) -> v.maxBy { it.seq } }
            .filter { it.action == ConsentAction.AGREED && it.at.isBefore(before) && (afterSubjectId == null || it.subject.id > afterSubjectId) }
            .sortedBy { it.subject.id }.take(limit)

    override fun history(subject: Subject, page: Int, size: Int) = ConsentPage(emptyList(), 0)
    override fun search(search: ConsentSearch, page: Int, size: Int) = ConsentPage(emptyList(), 0)
    override fun countSince(subject: Subject, since: Instant) = 0
    override fun anonymize(subject: Subject, tombstone: String) = 0
    override fun deleteAnonymized(tombstone: String) = 0
    override fun scrubPersonalData(before: Instant) = 0
    override fun export(subject: Subject) = rows.filter { it.subject == subject }
}

class MemoryLedger : LegalLedger {
    private val entries = CopyOnWriteArrayList<LedgerEntry>()
    override fun find(type: String, version: String, locale: String) = entries.firstOrNull { it.type == type && it.version == version && it.locale == locale }
    override fun all(): List<LedgerEntry> = entries.toList()
    override fun append(entry: LedgerEntry): Boolean = entries.add(entry)
}

class RecordingPublisher : NotificationPublisher {
    val events = CopyOnWriteArrayList<NotificationEvent>()
    @Volatile var failure: RuntimeException? = null

    override fun publish(event: NotificationEvent): NotificationPublishResult {
        failure?.let { throw it }
        events += event
        return NotificationPublishResult(event.id, 0)
    }
}

class MemoryNoticeStore : PushAdvertisingNoticeStore {
    val marks = CopyOnWriteArrayList<Triple<String, String, Instant>>()
    override fun isNoticed(accountId: String, type: String, agreedAt: Instant) = Triple(accountId, type, agreedAt) in marks
    override fun markNoticed(accountId: String, type: String, agreedAt: Instant, at: Instant) {
        if (!isNoticed(accountId, type, agreedAt)) marks += Triple(accountId, type, agreedAt)
    }
}

/** marketing v1 (2026-01-01) → v2 (2035-01-01), marketing-night v1. 시계는 2026-10-07 에서 시작한다. 유예 없음 */
class LegalKit(val store: ConsentStore = MemoryConsentStore()) {
    val clock = MutableClock(Instant.parse("2026-10-07T00:00:00Z"))
    val listeners = CopyOnWriteArrayList<ConsentChangeListener>()
    private val sources = mutableMapOf<String, String>()
    private val ctx = ConsentContext("203.0.113.9", "ua")

    private fun v(type: String, version: String, from: String): ManifestVersion {
        listOf("ko", "en").forEach { sources["$type/$version.$it"] = "# $type $version $it\n\nbody" }
        return ManifestVersion(type, version, DocumentStatus.REVIEWED, Instant.parse(from), false, listOf("ko", "en"), listOf("ko", "en").associateWith { LegalText.sha256(sources["$type/$version.$it"]!!) })
    }

    private val rules = LegalRules(emptySet(), emptySet(), "ko", Duration.ZERO, emptyMap())
    val catalog = LegalCatalog(
        listOf(v("marketing", "v1", "2026-01-01T00:00:00Z"), v("marketing", "v2", "2035-01-01T00:00:00Z"), v("marketing-night", "v1", "2026-01-01T00:00:00Z")),
        { t, ver, l -> sources["$t/$ver.$l"] }, rules,
    )
    val service = ConsentService(catalog, rules, store, clock, ConsentOptions(maxEventsPerDay = 0), listeners = { listeners.toList() })

    fun agree(account: String, type: String = "marketing", version: String = "v1", source: String = "push-settings") {
        service.record(Subject.account(account), listOf(ConsentClaim(type, version, "ko")), ctx, source)
    }

    fun withdraw(account: String, type: String = "marketing") {
        service.withdraw(Subject.account(account), type, ctx)
    }
}
