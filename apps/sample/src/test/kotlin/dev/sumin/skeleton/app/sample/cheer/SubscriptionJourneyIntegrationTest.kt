package dev.sumin.skeleton.app.sample.cheer

import dev.sumin.skeleton.payment.SubscriptionRenewalGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired

/**
 * 구독 여정(진짜 PostgreSQL · 진짜 보안 체인 · 진짜 Flyway · 진짜 장부, 결제사만 가짜): 플랜 → 만들기 → 카드 등록 + 첫 결제 → 내 구독 → 해지 예약. 응원 기록은 남지 않는다(구독 주문은 응원이 아니다).
 * 갱신 · 재시도는 메모리 저장소 시험(`SubscriptionRenewalTest`)과 두 DB 의 저장소 계약 시험이 본다.
 */
class SubscriptionJourneyIntegrationTest : CheerIntegrationBase() {
    @Autowired lateinit var guard: SubscriptionRenewalGuard

    @Test
    fun `plans, start, first payment, my subscription and a reserved end - and no cheer line for the charge`() {
        get("$PAYMENTS/subscriptions/plans", user).andExpect {
            status { isOk() }
            jsonPath("$.value.plans.length()") { value(6) }   // 이 요청(KR → 가짜 결제사)의 통화 KRW 의 현재 가격: 월 셋 + 연 셋
            jsonPath("$.value.plans[0].code") { value("basic") }
            jsonPath("$.value.plans[0].productCode") { value("basic") }
            jsonPath("$.value.plans[0].name") { value("베이직") }
            jsonPath("$.value.plans[4].amount.amount") { value(9900) }
            jsonPath("$.value.plans[5].code") { value("pro-yearly") }
        }
        val started = post("$PAYMENTS/subscriptions", user, """{"planCode":"standard"}""", key = "sub-key-1").andExpect { status { isCreated() } }
        val id = read(started, "$.value.subscription.id")
        assertEquals("PENDING", read(started, "$.value.subscription.status"))
        assertTrue(read(started, "$.value.billingAuth.customerKey").startsWith("cus_"))

        post("$PAYMENTS/subscriptions/$id/activate", user, """{"providerPayload":{"authKey":"auth_1"}}""").andExpect {
            status { isOk() }
            jsonPath("$.value.status") { value("ACTIVE") }
            jsonPath("$.value.amount.amount") { value(6900) }
        }
        val me = get("$PAYMENTS/subscriptions/me", user).andExpect { status { isOk() }; jsonPath("$.value.orders.length()") { value(1) } }
        assertEquals("standard", read(me, "$.value.subscription.planCode"))
        assertEquals("1234", read(me, "$.value.subscription.card.last4"))
        assertEquals(1, gateway.chargeCalls.size)
        assertEquals(0, jdbc.sql("select count(*) from cheers").query(Long::class.java).single().toInt(), "a subscription charge is not a cheer")
        assertFalse(me.andReturn().response.contentAsString.contains("bkey_"), "the billing key is in no response")
        assertTrue(jdbc.sql("select billing_token from payment_subscriptions where id = :id").param("id", id).query(String::class.java).single() != "bkey_1", "the billing key is stored encrypted")

        post("$PAYMENTS/subscriptions/$id/cancel", user).andExpect { status { isOk() }; jsonPath("$.value.status") { value("CANCELING") } }
        post("$PAYMENTS/subscriptions/$id/cancel", user).andExpect { status { isOk() }; jsonPath("$.value.status") { value("CANCELING") } }
        // 남의 구독은 없는 구독이다
        post("$PAYMENTS/subscriptions/$id/cancel", stranger).andExpect { status { isNotFound() } }
        get("$PAYMENTS/subscriptions/me", stranger).andExpect { status { isOk() }; jsonPath("$.value.subscription") { value(null) } }
        get("$PAYMENTS/subscriptions/plans", null).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `only an account in good standing is renewed - a suspended or leaving account is not charged again`() {
        val account = accountOf("user@example.com")
        assertTrue(guard.allows(account))

        listOf("SUSPENDED", "DELETED").forEach { status ->
            jdbc.sql("update accounts set status = :s where id = :id").param("s", status).param("id", account).update()
            assertFalse(guard.allows(account), "$status must not be renewed")
        }
        jdbc.sql("update accounts set status = 'ACTIVE' where id = :id").param("id", account).update()
        assertFalse(guard.allows("acc_nobody"))
    }
}
