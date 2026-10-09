package dev.sumin.skeleton.app.sample.cheer

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.test.context.TestPropertySource

/** 주문 만들기의 요청 수 한도(스펙 §7): 계정마다 창 안에 [account-capacity] 번까지 — 넘으면 429 + `Retry-After`. 다른 계정은 영향이 없다. */
@TestPropertySource(properties = ["skeleton.payment.http.order-rate-limit.account-capacity=3"])
class CheerOrderRateLimitIntegrationTest : CheerIntegrationBase() {
    @Test
    fun `the fourth order of one account in the window is 429, another account is not held back`() {
        repeat(3) { post("$PAYMENTS/orders", user, """{"itemType":"cheer","itemRef":"cheer-1000","quantity":1}""", UUID.randomUUID().toString()).andExpect { status { isCreated() } } }
        post("$PAYMENTS/orders", user, """{"itemType":"cheer","itemRef":"cheer-1000","quantity":1}""", UUID.randomUUID().toString())
            .andExpect { status { isTooManyRequests() }; header { exists("Retry-After") }; jsonPath("$.code") { value("COMMON.TOO_MANY_REQUESTS") } }
        post("$PAYMENTS/orders", stranger, """{"itemType":"cheer","itemRef":"cheer-1000","quantity":1}""", UUID.randomUUID().toString()).andExpect { status { isCreated() } }
        assertEquals(4L, jdbc.sql("select count(*) from payment_orders").query(Long::class.java).single(), "the refused request made no order")
    }
}
