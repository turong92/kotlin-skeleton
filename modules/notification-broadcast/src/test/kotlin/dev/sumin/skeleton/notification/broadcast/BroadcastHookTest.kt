package dev.sumin.skeleton.notification.broadcast

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.DeliveryOutcome
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BroadcastHookTest {
    private val store = FakeBroadcastStore().also {
        it.insert(draft(channels = listOf("push")))
        it.start("bc_1", "tok", null, 3, "acc_op", T0)
        it.openChannels("bc_1", listOf("push"))
    }
    private var now = T0
    private val hook = BroadcastDeliveryHook(store, TimeProvider { now }, Duration.ofSeconds(5))

    @Test
    fun `only broadcast ids are counted, test sends are not`() {
        hook.onQueued("bc_1", "push", 3)
        hook.onOutcome("bc_1", "push", DeliveryOutcome.DELIVERED)
        hook.onOutcome("bc_1", "push", DeliveryOutcome.DROPPED)
        hook.onOpened("bc_1", "push")
        hook.onOpened("bct_9", "push")   // 테스트 발송의 클릭
        hook.onOpened("evt_1", "push")
        assertEquals(ChannelStats(queued = 3, delivered = 1, dropped = 1, opened = 1), store.channelStats("bc_1")["push"])
    }

    @Test
    fun `a cancelled broadcast halts its queued pushes, within five seconds`() {
        assertFalse(hook.halted("bc_1"))
        store.cancel("bc_1", "acc_op", T0)
        assertFalse(hook.halted("bc_1"))              // 5초 캐시
        now = T0.plusSeconds(6)
        assertTrue(hook.halted("bc_1"))
        assertFalse(hook.halted("bct_1"))
        assertFalse(hook.halted("evt_1"))
    }
}
