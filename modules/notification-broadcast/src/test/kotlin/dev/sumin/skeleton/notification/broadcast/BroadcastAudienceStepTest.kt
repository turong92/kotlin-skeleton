package dev.sumin.skeleton.notification.broadcast

import dev.sumin.skeleton.common.audience.AudienceParam
import dev.sumin.skeleton.common.audience.AudienceSource
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 앱이 낸 대상 제공자(TEXT 인자 · 사람이 바뀜 · 읽다 실패)로 단계 작업이 도는 모습 */
class BroadcastAudienceStepTest {
    /** 시리즈별 구독자 - `param` 이 시리즈 id. 보내는 동안 [followers] 가 바뀔 수 있고, [failOnce] 면 다음 쪽 읽기가 한 번 터진다 */
    private class Followers(val followers: MutableMap<String, MutableList<String>>) : AudienceSource {
        override val kind = "followers"
        override val param = AudienceParam.TEXT
        var failOnce = false
        override fun validate(param: String?) = if (param == null) "seriesId is required" else null
        override fun count(param: String?) = followers[param].orEmpty().size.toLong()
        override fun page(param: String?, after: String?, limit: Int): List<String> {
            if (failOnce) { failOnce = false; error("provider down") }
            return followers[param].orEmpty().sorted().filter { after == null || it > after }.take(limit)
        }
    }

    private val followers = Followers(mutableMapOf("s1" to mutableListOf("acc_1", "acc_2", "acc_3"), "s2" to mutableListOf("acc_9")))
    private val store = FakeBroadcastStore()
    private val tasks = RecordingTasks()
    private val publisher = RecordingPublisher()
    private val properties = BroadcastProperties(enabled = true, types = listOf("notice.service", "promo.x"), batchSize = 2, recipientsPerMinute = 60)

    private fun runner(gate: AdvertisingGate = plainGate(), sources: List<AudienceSource> = listOf(followers)) =
        BroadcastStepRunner(store, tasks, publisher, { sources }, gate, properties, TimeProvider.fixed(T0), BroadcastEvents(gate, properties.topic))

    private fun started(type: String = "notice.service", kind: String = "followers", param: String? = "s1"): BroadcastStep {
        store.insert(draft(type = type).let { it.copy(content = it.content.copy(audience = BroadcastAudience(kind, param))) })
        store.start("bc_1", "tok", null, 3, "acc_op", T0)
        return BroadcastStep("bc_1", "tok", 0)
    }

    @Test
    fun `only the people the provider returns for the param receive it`() {
        val runner = runner()
        var step = started(param = "s1")
        repeat(3) { runner.run(step); tasks.steps.last().first.let { step = it } }
        assertEquals(listOf(setOf("acc_1", "acc_2"), setOf("acc_3")), publisher.events.map { it.recipientIds })
        assertEquals(BroadcastStatus.SENT, store.find("bc_1")!!.status)
    }

    @Test
    fun `the audience is read again when the step runs, so people who left before a scheduled time do not get it`() {
        val step = started(param = "s1")      // 예약 때는 acc_1~3
        followers.followers["s1"] = mutableListOf("acc_2", "acc_3", "acc_4")   // 그 사이 acc_1 이 알림을 끄고 acc_4 가 켰다
        val runner = runner()
        runner.run(step)
        runner.run(tasks.steps.last().first)
        assertEquals(listOf(setOf("acc_2", "acc_3"), setOf("acc_4")), publisher.events.map { it.recipientIds })
    }

    @Test
    fun `advertising consent still filters the people the provider returned`() {
        val gate = advertisingGate(consenting = setOf("acc_2", "acc_3"))
        val runner = runner(gate)
        val step = started(type = "promo.x", param = "s1")
        runner.run(step)
        runner.run(tasks.steps.last().first)
        assertEquals(listOf(setOf("acc_2"), setOf("acc_3")), publisher.events.map { it.recipientIds })
        assertTrue(publisher.events.all { it.title == "(광고) Maintenance" })
    }

    @Test
    fun `a provider that throws leaves the cursor where it was, and the retry continues without repeating anyone`() {
        val runner = runner()
        val first = started(param = "s1")
        runner.run(first)
        val second = tasks.steps.last().first
        followers.failOnce = true
        assertFailsWith<IllegalStateException> { runner.run(second) }   // 큐가 재시도한다
        runner.run(second)
        assertEquals(listOf(setOf("acc_1", "acc_2"), setOf("acc_3")), publisher.events.map { it.recipientIds })
    }

    @Test
    fun `a kind that no longer exists is a permanent failure, not a retry`() {
        val step = started(kind = "gone")
        assertFailsWith<BroadcastPermanentFailure> { runner().run(step) }
    }
}
