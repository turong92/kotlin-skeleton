package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 로그인 · 새로고침이 하루에 한 번만 활동을 남기고(같은 날은 저장소를 부르지 않는다), 저장소가 실패해도 로그인은 된다 */
class AccountActivityRecordingTest {
    private class MutableTime(var at: Instant) : TimeProvider {
        override fun now() = at
    }

    private class RecordingStore(val fail: Boolean = false) : AccountActivityStore {
        val written = CopyOnWriteArrayList<Pair<String, LocalDate>>()
        override fun recordActive(accountId: String, day: LocalDate) {
            if (fail) error("database is down")
            written += accountId to day
        }
        override fun eraseAccount(accountId: String) = Unit
        override fun purgeBefore(day: LocalDate) = 0
    }

    private val zone = ZoneId.of("Asia/Seoul")
    private val time = MutableTime(Instant.parse("2026-10-26T14:00:00Z"))   // 서울 23:00 (10-26)
    private val client = SessionClient(ip = "203.0.113.7", userAgent = "JUnit/1", deviceName = "Laptop")

    private fun service(store: AccountActivityStore) =
        SessionService(InMemorySessionStore(), AuthSessionProperties(), time, activity = AccountActivityRecorder(store, { zone }, time))

    @Test
    fun `login and refreshes on one day write one line, the next day writes another - the day is the activity zone's`() {
        val store = RecordingStore()
        val service = service(store)
        val opened = service.open("acc_1", client)
        val refreshed = service.refresh(requireNotNull(opened.refreshToken), client)
        val third = service.refresh(requireNotNull(refreshed.session.refreshToken), client)
        assertEquals(listOf("acc_1" to LocalDate.parse("2026-10-26")), store.written)

        time.at = time.at.plus(Duration.ofHours(2))   // 서울 01:00 (10-27) — UTC 로는 같은 날이지만 활동 시간대로는 다음 날
        service.refresh(requireNotNull(third.session.refreshToken), client)
        assertEquals(listOf("acc_1" to LocalDate.parse("2026-10-26"), "acc_1" to LocalDate.parse("2026-10-27")), store.written)
    }

    @Test
    fun `another account is recorded on its own`() {
        val store = RecordingStore()
        val service = service(store)
        service.open("acc_1", client)
        service.open("acc_2", client)
        service.open("acc_1", client)

        assertEquals(listOf("acc_1", "acc_2"), store.written.map { it.first })
    }

    @Test
    fun `after a failure the store is left alone for a while, then tried again`() {
        class Flaky : AccountActivityStore {
            var calls = 0
            var failing = true
            override fun recordActive(accountId: String, day: LocalDate) { calls++; if (failing) error("down") }
            override fun eraseAccount(accountId: String) = Unit
            override fun purgeBefore(day: LocalDate) = 0
        }
        val store = Flaky()
        val service = service(store)
        repeat(5) { service.open("acc_$it", client) }
        assertEquals(1, store.calls, "one failure pauses the writes - not a call and a WARN per login")

        store.failing = false
        time.at = time.at.plus(Duration.ofSeconds(31))
        service.open("acc_9", client)
        assertEquals(2, store.calls)
    }

    @Test
    fun `a failure in one project does not pause the other project's records`() {
        val failing = RecordingStore(fail = true)
        val working = RecordingStore()
        fun project(id: String) = dev.sumin.skeleton.common.project.Project(id, listOf("$id.test"), id, dev.sumin.skeleton.common.project.ProjectDatasource(), emptyMap())
        // 한 기록기를 두 프로젝트가 나눠 쓴다 — 저장소는 프로젝트마다 다른 스키마를 본다
        var current: AccountActivityStore = failing
        val shared = AccountActivityRecorder(object : AccountActivityStore by failing { override fun recordActive(accountId: String, day: LocalDate) = current.recordActive(accountId, day) }, { zone }, time)

        dev.sumin.skeleton.common.project.ProjectContext.runIn(project("a")) { shared.record("acc_1") }   // a 의 표가 없다 → a 만 멈춘다
        current = working
        dev.sumin.skeleton.common.project.ProjectContext.runIn(project("b")) { shared.record("acc_2") }

        assertEquals(listOf("acc_2" to LocalDate.parse("2026-10-26")), working.written)
    }

    @Test
    fun `a failing activity store never blocks the login or the refresh`() {
        val service = service(RecordingStore(fail = true))
        val opened = service.open("acc_1", client)
        assertTrue(requireNotNull(opened.refreshToken).startsWith("r1."))
        service.refresh(opened.refreshToken!!, client)
    }
}
