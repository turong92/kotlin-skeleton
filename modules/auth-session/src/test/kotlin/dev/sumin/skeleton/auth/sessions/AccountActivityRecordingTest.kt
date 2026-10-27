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
    fun `a failing activity store never blocks the login or the refresh`() {
        val service = service(RecordingStore(fail = true))
        val opened = service.open("acc_1", client)
        assertTrue(requireNotNull(opened.refreshToken).startsWith("r1."))
        service.refresh(opened.refreshToken!!, client)
    }
}
