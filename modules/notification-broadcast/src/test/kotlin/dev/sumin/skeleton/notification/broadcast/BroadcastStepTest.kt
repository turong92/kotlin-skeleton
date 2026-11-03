package dev.sumin.skeleton.notification.broadcast

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 단계 작업의 불변식(스펙 §9): 중복 · 취소 · 광고 동의 · 빈 받는 사람. 저장소는 대역이지만 전이 규칙은 진짜와 같다 - 진짜 DB 는 jdbc 모듈 dbTest 가 센다 */
class BroadcastStepTest {
    private val everyone = (1..5).map { "acc_$it" }
    private val store = FakeBroadcastStore()
    private val tasks = RecordingTasks()
    private val publisher = RecordingPublisher()
    private val properties = BroadcastProperties(enabled = true, types = listOf("notice.service", "promo.x"), batchSize = 2, recipientsPerMinute = 60)

    private fun runner(gate: AdvertisingGate = plainGate()) =
        BroadcastStepRunner(store, tasks, publisher, { listOf(StaticAudience("everyone", everyone)) }, gate, properties, TimeProvider.fixed(T0), BroadcastEvents(gate, properties.topic))

    /** 보내기와 같은 상태: SENDING + 토큰 */
    private fun started(type: String = "notice.service", channels: List<String> = emptyList()): BroadcastStep {
        store.insert(draft(type = type, channels = channels))
        store.start("bc_1", "tok", null, 5, "acc_op", T0)
        return BroadcastStep("bc_1", "tok", 0)
    }

    @Test
    fun `the same step run twice and a step with a stale token publish only once`() {
        val step = started()
        val runner = runner()
        runner.run(step)
        runner.run(step)                       // 재시도 · 중복 작업
        runner.run(step.copy(seq = 1, token = "old"))   // 예약을 풀었다 다시 건 뒤의 옛 작업
        assertEquals(1, publisher.events.size)
        assertEquals(setOf("acc_1", "acc_2"), publisher.events.single().recipientIds)
        assertEquals(2, store.find("bc_1")!!.recipients)
        assertEquals(1, tasks.steps.size)       // 다음 작업은 한 번만
    }

    @Test
    fun `batches walk the audience with an interval and end sent`() {
        var step = started()
        val runner = runner()
        repeat(4) {
            runner.run(step)
            tasks.steps.lastOrNull()?.first?.let { next -> step = next }
        }
        assertEquals(listOf(setOf("acc_1", "acc_2"), setOf("acc_3", "acc_4"), setOf("acc_5")), publisher.events.map { it.recipientIds })
        assertEquals(BroadcastStatus.SENT, store.find("bc_1")!!.status)
        assertEquals(5, store.find("bc_1")!!.recipients)
        assertEquals(Duration.ofSeconds(2), Duration.between(T0, tasks.steps.first().second))   // 2명 / 분당 60명 = 2초
        assertTrue(publisher.events.all { it.id == "bc_1" })   // 묶음마다 같은 id - 알림함 pk 가 재저장을 막는다
    }

    @Test
    fun `a cancelled broadcast sends no further batch`() {
        val step = started()
        val runner = runner()
        runner.run(step)
        store.cancel("bc_1", "acc_op", T0)
        runner.run(tasks.steps.last().first)
        assertEquals(1, publisher.events.size)
        assertEquals(1, tasks.steps.size)
    }

    @Test
    fun `advertising leaves out people without consent, brands the title once, and never publishes an empty recipient set`() {
        val gate = advertisingGate(consenting = setOf("acc_2", "acc_3"))
        val step = started(type = "promo.x")
        val runner = runner(gate)
        runner.run(step)                          // acc_1 (동의 없음) acc_2 (동의)
        runner.run(tasks.steps.last().first)      // acc_3 (동의) acc_4 (없음)
        runner.run(tasks.steps.last().first)      // acc_5 (동의 없음) - 남는 사람이 없다
        assertEquals(listOf(setOf("acc_2"), setOf("acc_3")), publisher.events.map { it.recipientIds })
        assertTrue(publisher.events.all { it.title == "(광고) Maintenance" })
        assertEquals(5, store.find("bc_1")!!.recipients)   // 처리한 사람은 센다 - 진행률이 예상 수에 닿게
    }

    @Test
    fun `an informational broadcast reaches everyone whatever their advertising consent`() {
        val gate = advertisingGate(consenting = emptySet())
        val step = started(type = "notice.service")
        runner(gate).run(step)
        assertEquals(setOf("acc_1", "acc_2"), publisher.events.single().recipientIds)
        assertEquals("Maintenance", publisher.events.single().title)
    }
}
