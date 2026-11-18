package dev.sumin.skeleton.notification.scheduled

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.JobWake
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.transaction.support.TransactionSynchronizationManager

/** 예약을 만들면 그 시각에 깨우기 주소가 불리게 한다 - 인스턴스가 0대여도 때가 되면 훑기가 돈다 */
class ScheduledPushWakeTest {
    private val clock = TimeProvider { T0 }
    private val store = FakeScheduledPushStore()
    private val requests = mutableListOf<Instant>()
    private val properties = ScheduledPushProperties(enabled = true, type = "reminder")
    private val at = T0.plusSeconds(3600)

    private fun push(key: String = "k1", runAt: Instant = at) = ScheduledPush(key, "acc_1", runAt, "다음 화가 기다려요")

    /** 호출자의 트랜잭션을 흉내 낸다 - 끝에서 커밋하면 등록된 afterCommit 을 부른다 */
    private fun <T> inTransaction(commit: Boolean, block: () -> T): T {
        TransactionSynchronizationManager.initSynchronization()
        try {
            val result = block()
            if (commit) TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            return result
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `schedule requests wake at runAt after commit`() {
        val pushes = ScheduledPushes(store, properties, clock, JobWake { requests += it })

        inTransaction(commit = true) {
            assertEquals(ScheduleResult.CREATED, pushes.schedule(push()))
            assertTrue(requests.isEmpty(), "커밋 전에는 깨우지 않는다")
        }
        assertEquals(listOf(at), requests)

        // 같은 키를 바꿔도(REPLACED) 새 시각으로 깨운다. 트랜잭션 밖이면 바로
        val later = at.plusSeconds(60)
        assertEquals(ScheduleResult.REPLACED, pushes.schedule(push(runAt = later)))
        assertEquals(listOf(at, later), requests)
    }

    @Test
    fun `ignored schedule requests no wake`() {
        val pushes = ScheduledPushes(store, properties, clock, JobWake { requests += it })
        pushes.schedule(push())
        store.rows.getValue("k1").also { it.status = ScheduledPushStatus.SENT; it.finishedAt = T0 }
        requests.clear()

        assertEquals(ScheduleResult.IGNORED, pushes.schedule(push()))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `no JobWake bean keeps behaviour`() {
        val pushes = ScheduledPushes(store, properties, clock)
        assertEquals(ScheduleResult.CREATED, pushes.schedule(push()))
        assertEquals(ScheduleResult.REPLACED, inTransaction(commit = true) { pushes.schedule(push()) })
    }
}
