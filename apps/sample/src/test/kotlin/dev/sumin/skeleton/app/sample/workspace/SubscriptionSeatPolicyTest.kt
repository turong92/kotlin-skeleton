package dev.sumin.skeleton.app.sample.workspace

import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionSeatPolicyTest {
    private val owners = mapOf("ws_1" to "acc_free", "ws_2" to "acc_paid")
    private val policy = SubscriptionSeatPolicy(ownerOf = owners::get, hasActiveSubscription = { it == "acc_paid" })

    @Test
    fun `an owner without a subscription gets the free limit and a subscriber gets the paid limit`() {
        assertEquals(3, policy.limitOf("ws_1"))
        assertEquals(10, policy.limitOf("ws_2"))
    }

    @Test
    fun `a workspace with no owner row falls back to the free limit`() {
        assertEquals(3, policy.limitOf("ws_gone"))
    }
}
