package dev.sumin.skeleton.reward

import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardLabel
import dev.sumin.skeleton.common.reward.RewardPermanentFailure
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublishResult
import dev.sumin.skeleton.reward.notification.NotificationRewardNotifier
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RewardWorkerTest {
    private val store = FakeGrantStore()
    private val tasks = RecordingTasks()
    private val time = TimeProvider.fixed(Instant.parse("2026-10-28T00:00:00Z"))
    private val service = RewardGrantService(store, tasks, time)
    private val notified = mutableListOf<Pair<RewardGrantView, RewardLabel>>()

    private fun worker(issuer: ScriptedIssuer?, notifier: RewardNotifier = RewardNotifier { g, l -> notified += g to l }) =
        RewardWorker(store, { listOfNotNull(issuer) }, { notifier }, time, Locale.ENGLISH)

    private fun grant(): String = service.record("acc_1", "point", mapOf("amount" to "100"), "attendance", "c1:acc_1:2026-10-28:daily:0")!!

    @Test
    fun `an issuer that throws twice and then succeeds issues exactly once`() {
        val issuer = ScriptedIssuer(failures = 2)
        val id = grant()
        val w = worker(issuer)

        repeat(2) { assertFailsWith<IllegalStateException> { w.process(id) } }
        assertEquals(RewardGrantStatus.PENDING, store.find(id)!!.status)
        w.process(id)

        assertEquals(1, issuer.issued.get())
        assertEquals(RewardGrantStatus.ISSUED, store.find(id)!!.status)
        assertEquals("res_$id", store.find(id)!!.resultRef)
    }

    @Test
    fun `an already issued grant does not reach the issuer`() {
        val issuer = ScriptedIssuer()
        val id = grant()
        val w = worker(issuer)
        w.process(id)
        w.process(id)
        assertEquals(1, issuer.issued.get())
        assertEquals(1, notified.size)
    }

    @Test
    fun `no issuer for the type is a permanent failure that leaves the grant failed`() {
        val id = grant()
        assertFailsWith<RewardPermanentFailure> { worker(null).process(id) }
        val row = store.find(id)!!
        assertEquals(RewardGrantStatus.FAILED, row.status)
        assertEquals("no issuer for reward type 'point'", row.lastError)
    }

    @Test
    fun `a permanent failure from the issuer marks the grant failed`() {
        val id = grant()
        assertFailsWith<RewardPermanentFailure> { worker(ScriptedIssuer(failures = 1, permanent = true)).process(id) }
        assertEquals(RewardGrantStatus.FAILED, store.find(id)!!.status)
    }

    @Test
    fun `a notifier that throws leaves the grant issued`() {
        val id = grant()
        worker(ScriptedIssuer(), RewardNotifier { _, _ -> error("inbox down") }).process(id)
        assertEquals(RewardGrantStatus.ISSUED, store.find(id)!!.status)
    }

    @Test
    fun `the inbox notification id is rwd_ plus the grant id and carries the label`() {
        val id = grant()
        val published = mutableListOf<NotificationEvent>()
        val notifier = NotificationRewardNotifier({ published += it; NotificationPublishResult(it.id, 0) }, "rewards")
        worker(ScriptedIssuer(), notifier).process(id)

        val event = published.single()
        assertEquals("rwd_$id", event.id)
        assertEquals(setOf("acc_1"), event.recipientIds)
        assertEquals("reward.granted", event.type)
        assertEquals("You got 100 points", event.message)
        assertEquals("/rewards/points", event.payload["link"])
        assertEquals("/rewards/points", event.link)
    }

    @Test
    fun `a label link that is not an in-app path stays out of the link field and does not lose the notification`() {
        val published = mutableListOf<NotificationEvent>()
        val notifier = NotificationRewardNotifier({ published += it; NotificationPublishResult(it.id, 0) }, "rewards")
        val grant = RewardGrantView("g1", "acc_1", "point", emptyMap(), "s", "k")

        notifier.issued(grant, RewardLabel("t", "m", link = "https://elsewhere.example/x"))
        notifier.issued(grant, RewardLabel("t", "m", link = "//elsewhere.example"))
        notifier.issued(grant, RewardLabel("t", "m", link = null))

        assertEquals(listOf(null, null, null), published.map { it.link })
        assertEquals("https://elsewhere.example/x", published.first().payload["link"])
    }

    @Test
    fun `recording the same source twice makes one grant and one job`() {
        val first = grant()
        val second = service.record("acc_1", "point", mapOf("amount" to "100"), "attendance", "c1:acc_1:2026-10-28:daily:0")
        assertNull(second)
        assertEquals(listOf(first), tasks.enqueued)
    }

    @Test
    fun `record outside a transaction is refused`() {
        store.open = false
        assertFailsWith<IllegalStateException> { grant() }
        assertEquals(0, store.rows.size)
    }

    @Test
    fun `retry reopens a failed grant and enqueues it again, cancel only takes a pending one`() {
        val id = grant()
        assertFailsWith<RewardPermanentFailure> { worker(null).process(id) }
        service.retry(id)
        assertEquals(RewardGrantStatus.PENDING, store.find(id)!!.status)
        assertEquals(listOf(id, id), tasks.enqueued)
        service.cancel(id)
        assertEquals(RewardGrantStatus.CANCELED, store.find(id)!!.status)
    }
}
