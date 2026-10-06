package dev.sumin.skeleton.legal

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** 메모리 저장소 — 유니크 키(주체 · 종류 · 참조 · 순번)를 DB 처럼 지킨다. 서비스 시험 전용 (모듈에는 메모리 구현이 없다) */
class FakeConsentStore : ConsentStore {
    val rows = mutableListOf<ConsentEvent>()
    private val ids = AtomicLong()
    var appendCalls = 0

    @Synchronized
    override fun latest(subject: Subject, types: Collection<String>, referenceId: String?): Map<String, ConsentEvent> =
        rows.filter { it.subject == subject && it.type in types && it.referenceId == referenceId }.groupBy { it.type }.mapValues { (_, v) -> v.maxBy { it.seq } }

    @Synchronized
    override fun append(event: NewConsentEvent): Boolean {
        appendCalls++
        if (rows.any { it.subject == event.subject && it.type == event.type && it.referenceId == event.referenceId && it.seq == event.seq }) return false
        rows += ConsentEvent(
            ids.incrementAndGet(), event.subject, event.type, event.version, event.sha256, event.locale, event.action, event.source,
            event.referenceId, event.seq, event.ip, event.userAgent, event.at,
        )
        return true
    }

    @Synchronized
    override fun history(subject: Subject, page: Int, size: Int): ConsentPage {
        val all = rows.filter { it.subject == subject }.sortedByDescending { it.id }
        return ConsentPage(all.drop(page * size).take(size), all.size.toLong())
    }

    @Synchronized
    override fun search(search: ConsentSearch, page: Int, size: Int): ConsentPage {
        val all = rows.filter {
            (search.subjectType == null || it.subject.type == search.subjectType) && (search.subjectId == null || it.subject.id == search.subjectId) &&
                (search.type == null || it.type == search.type) && (search.action == null || it.action == search.action)
        }.sortedByDescending { it.id }
        return ConsentPage(all.drop(page * size).take(size), all.size.toLong())
    }

    @Synchronized
    override fun countSince(subject: Subject, since: Instant): Int = rows.count { it.subject == subject && !it.at.isBefore(since) }

    @Synchronized
    override fun anonymize(subject: Subject, tombstone: String): Int {
        var n = 0
        rows.replaceAll { if (it.subject == subject) { n++; it.copy(subject = Subject(subject.type, tombstone), ip = null, userAgent = null) } else it }
        return n
    }

    @Synchronized
    override fun deleteAnonymized(tombstone: String): Int {
        val before = rows.size
        rows.removeIf { it.subject.id == tombstone }
        return before - rows.size
    }

    @Synchronized
    override fun scrubPersonalData(before: Instant): Int {
        var n = 0
        rows.replaceAll { if (it.at.isBefore(before) && (it.ip != null || it.userAgent != null)) { n++; it.copy(ip = null, userAgent = null) } else it }
        return n
    }

    @Synchronized
    override fun export(subject: Subject): List<ConsentEvent> = rows.filter { it.subject == subject }.sortedBy { it.id }
}
