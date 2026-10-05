package dev.sumin.skeleton.alert

import java.time.Duration
import java.time.Instant

/**
 * (종류 · 키)마다 한 건씩 기록하며 접기를 판정하는 곳. 기본은 메모리([InMemoryAlertStore] — 이 인스턴스 안에서만 접는다),
 * `alert-jdbc` 가 DB 로 바꾸면 여러 인스턴스 · 재시작을 가로질러 접고 목록을 남긴다.
 */
interface AlertStore {
    fun record(
        kind: AlertKind,
        key: String,
        severity: AlertSeverity,
        title: String,
        detail: String,
        now: Instant,
        minInterval: Duration,
    ): AlertRecorded
}

class InMemoryAlertStore : AlertStore {
    private class Entry(var sentAt: Instant, var suppressed: Int, var occurrences: Long)

    private val entries = HashMap<String, Entry>()

    @Synchronized
    override fun record(kind: AlertKind, key: String, severity: AlertSeverity, title: String, detail: String, now: Instant, minInterval: Duration): AlertRecorded {
        val id = "${kind.name}\u0000$key"
        val entry = entries[id]
        if (entry == null) {
            entries[id] = Entry(now, 0, 1)
            return AlertRecorded(send = true, suppressedFolded = 0, occurrences = 1)
        }
        entry.occurrences++
        if (!entry.sentAt.isAfter(now.minus(minInterval))) {
            val folded = entry.suppressed
            entry.sentAt = now
            entry.suppressed = 0
            return AlertRecorded(send = true, suppressedFolded = folded, occurrences = entry.occurrences)
        }
        entry.suppressed++
        return AlertRecorded(send = false, suppressedFolded = 0, occurrences = entry.occurrences)
    }
}
