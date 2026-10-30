package dev.sumin.skeleton.app.sample.rewards

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.cheer.ADMIN
import dev.sumin.skeleton.app.sample.cheer.CheerIntegrationBase
import dev.sumin.skeleton.app.sample.cheer.PAYMENTS
import dev.sumin.skeleton.campaign.Campaigns
import dev.sumin.skeleton.campaign.Referrals
import dev.sumin.skeleton.coupon.CouponCodes
import dev.sumin.skeleton.coupon.CouponDefinitions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired

/**
 * 이벤트 · 보상 · 쿠폰 · 결제 면제가 견본 앱 안에서 서로 맞물리는지(진짜 PostgreSQL · 보안 체인 · 큐 일꾼, 결제사만 가짜) - 스펙 §10 의 1 · 2 · 3 · 4 · 5.
 * 씨앗은 [RewardsSeed] 를 직접 돌린다(로컬 프로필의 같은 코드).
 */
class RewardsFlowIntegrationTest : CheerIntegrationBase() {
    @Autowired lateinit var definitions: CouponDefinitions
    @Autowired lateinit var codes: CouponCodes
    @Autowired lateinit var campaigns: Campaigns
    @Autowired lateinit var referrals: Referrals

    private lateinit var userId: String

    @BeforeEach
    fun seedWorld() {
        listOf(
            "reward_grants", "coupons", "coupon_code_redemptions", "coupon_codes", "coupon_definitions", "point_entries", "point_balances",
            "campaign_attendance", "campaign_referral_codes", "campaign_referrals", "campaigns", "notification_inbox",
        ).forEach { jdbc.sql("delete from $it").update() }
        jdbc.sql("delete from account_roles where role = 'WAIVED'").update()
        jdbc.sql("delete from authz_roles where name = 'WAIVED'").update()
        RewardsSeed(definitions, codes, campaigns).run()
        userId = accountOf("user@example.com")
    }

    private fun balance(token: String): Long = JsonPath.read<Number>(get("/api/v1/points", token).andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.value.balance").toLong()

    private fun coupons(token: String, tab: String): String =
        get("/api/v1/coupons?status=$tab", token).andExpect { status { isOk() } }.andReturn().response.contentAsString

    private fun couponId(token: String, tab: String, name: String): String? =
        JsonPath.read<List<String>>(coupons(token, tab), "$.values[?(@.name=='$name')].id").firstOrNull()

    private fun giveCoupon(name: String, accountId: String): String {
        val definitionId = JsonPath.read<List<String>>(
            get("/api/v1/admin/coupon-definitions", operator).andExpect { status { isOk() } }.andReturn().response.contentAsString,
            "$.values[?(@.name=='$name')].id",
        ).single()
        post("/api/v1/admin/coupon-definitions/$definitionId/issue", operator, """{"accountIds":["$accountId"]}""")
            .andExpect { status { isOk() }; jsonPath("$.value.issued.length()") { value(1) } }
        return couponId(if (accountId == userId) user else stranger, "AVAILABLE", name)!!
    }

    private fun orderWith(token: String, couponId: String): Pair<String, org.springframework.test.web.servlet.ResultActionsDsl> {
        val result = post(
            "$PAYMENTS/orders", token,
            """{"itemType":"cheer","itemRef":"cheer-3000","quantity":1,"discounts":[{"type":"coupon","ref":"$couponId"}]}""",
            UUID.randomUUID().toString(),
        ).andExpect { status { isCreated() } }
        return read(result, "$.value.order.id") to result
    }

    @Test
    fun `an attendance check-in becomes a grant, ten points and one inbox notice through the worker`() {
        val campaignId = JsonPath.read<List<String>>(get("/api/v1/campaigns", user).andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.values[?(@.kind=='attendance')].id").single()
        post("/api/v1/campaigns/$campaignId/attendance", user).andExpect { status { isOk() }; jsonPath("$.value.alreadyChecked") { value(false) } }
        post("/api/v1/campaigns/$campaignId/attendance", user).andExpect { status { isOk() }; jsonPath("$.value.alreadyChecked") { value(true) } }

        await { balance(user).takeIf { it == 10L } }
        val inbox = await { get("/api/v1/notifications", user).andReturn().response.contentAsString.takeIf { JsonPath.read<Int>(it, "$.pagination.totalElements") >= 1 } }
        assertEquals(1, JsonPath.read<Int>(inbox, "$.pagination.totalElements"), "a second check-in the same day pays and tells nothing more")
        assertEquals(1L, jdbc.sql("select count(*) from reward_grants where account_id = :a and status = 'ISSUED'").param("a", userId).query(Long::class.java).single())
    }

    /** 초대 관계 한 벌 - user 가 초대하고 stranger 가 코드를 쓴다. MockMvc 는 둘 다 같은 주소라 네트워크 표시를 지워 「다른 네트워크」로 둔다(같은 네트워크 판정은 모듈 시험과 e2e 가 맡는다). */
    private fun invitedStranger(): String {
        val code = JsonPath.read<String>(get("/api/v1/referrals/me", user).andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.value.code")
        post("/api/v1/referrals/claim", stranger, """{"code":"$code"}""").andExpect { status { isNoContent() } }
        jdbc.sql("update campaign_referral_codes set last_network = null").update()
        jdbc.sql("update campaign_referrals set claim_network = null, flags = null").update()
        await { couponId(stranger, "AVAILABLE", RewardsSeed.CHEER_30) }
        return accountOf("moderator@example.com")
    }

    private fun referralStatus(inviteeId: String): String = jdbc.sql("select status from campaign_referrals where invitee_id = :i").param("i", inviteeId).query(String::class.java).single()

    /** 시계를 옮긴다 - 보류 기한을 하루 전으로 되돌려 「7일이 지난」 상태로 만든 뒤 해제 작업을 한 번 부른다 */
    private fun passHoldPeriod() {
        jdbc.sql("update campaign_referrals set release_at = release_at - interval '8 days' where status = 'HELD'").update()
        referrals.releaseDue()
    }

    @Test
    fun `a claimed invite gives the invitee a coupon, and the inviter's 500 points wait for the 7 day hold after the invitee's first paid cheer - once`() {
        val invitee = invitedStranger()
        assertEquals(0L, balance(user), "the inviter is paid on the first purchase, not on the claim")

        buy(stranger)
        assertEquals("HELD", referralStatus(invitee))
        Thread.sleep(1500)
        assertEquals(0L, balance(user), "the withdrawal period holds the reward")

        passHoldPeriod()
        await { balance(user).takeIf { it == 500L } }
        assertEquals("QUALIFIED", referralStatus(invitee))
        buy(stranger)
        passHoldPeriod()
        Thread.sleep(1500)
        assertEquals(500L, balance(user), "the second purchase pays the inviter nothing more")
    }

    @Test
    fun `a full refund during the hold cancels the inviter's reward for good`() {
        val invitee = invitedStranger()
        val orderId = buy(stranger)
        assertEquals("HELD", referralStatus(invitee))

        val refundId = read(post("$PAYMENTS/orders/$orderId/refund-requests", stranger, """{"reasonCode":"CUSTOMER_REQUEST"}""", UUID.randomUUID().toString()).andExpect { status { isCreated() } }, "$.value.id")
        post("$ADMIN/refunds/$refundId/approve", operator).andExpect { status { isOk() } }
        await { referralStatus(invitee).takeIf { it == "CANCELED" } }

        jdbc.sql("update campaign_referrals set release_at = release_at - interval '8 days'").update()
        referrals.releaseDue()
        Thread.sleep(1500)
        assertEquals(0L, balance(user), "a canceled relation is never paid, not even after the hold date")
    }

    @Test
    fun `a refund of an order from before the signal does not end a relation that is still waiting for its first payment`() {
        val invitee = invitedStranger()
        val orderId = buy(stranger)
        // 그 주문이 신호를 내기 전이었던 것처럼 되돌린다 - 신호 전(CLAIMED) 관계에 환불이 오는 경우
        jdbc.sql("update campaign_referrals set status = 'CLAIMED', release_at = null, qualify_event = null").update()

        val refundId = read(post("$PAYMENTS/orders/$orderId/refund-requests", stranger, """{"reasonCode":"CUSTOMER_REQUEST"}""", UUID.randomUUID().toString()).andExpect { status { isCreated() } }, "$.value.id")
        post("$ADMIN/refunds/$refundId/approve", operator).andExpect { status { isOk() } }
        await { myCheers(stranger).takeIf { it.contains("REFUNDED") } }

        assertEquals("CLAIMED", referralStatus(invitee), "a refund only ends a reward that is on hold or under review")
        buy(stranger)
        assertEquals("HELD", referralStatus(invitee), "the real first payment still opens the inviter's reward")
    }

    @Test
    fun `an inviter at the cap of 10 gets nothing for the 11th friend - the hold counts toward the cap`() {
        val campaignId = jdbc.sql("select id from campaigns where name = :n").param("n", RewardsSeed.REFERRAL).query(String::class.java).single()
        repeat(10) {
            jdbc.sql("insert into campaign_referrals (invitee_id, inviter_id, campaign_id, status, claimed_at, release_at) values (:i, :u, :c, 'HELD', now(), now() + interval '100 days')")
                .param("i", "held-$it").param("u", userId).param("c", campaignId).update()
        }
        val invitee = invitedStranger()
        buy(stranger)
        assertEquals("CAPPED", referralStatus(invitee))

        passHoldPeriod()
        Thread.sleep(1500)
        assertEquals(0L, balance(user))
        assertEquals("CAPPED", referralStatus(invitee))
    }

    @Test
    fun `a coupon cheer is cheaper by the coupon, uses it up on paid, and a full refund brings it back`() {
        val coupon = giveCoupon(RewardsSeed.CHEER_30, userId)
        val (orderId, created) = orderWith(user, coupon)
        assertEquals(2100, JsonPath.read<Int>(created.andReturn().response.contentAsString, "$.value.order.amount.amount"))
        assertEquals(900, JsonPath.read<Int>(created.andReturn().response.contentAsString, "$.value.order.discountAmount"))
        assertEquals("RESERVED", JsonPath.read<String>(coupons(user, "AVAILABLE"), "$.values[0].status"), "an order in flight holds the coupon")

        confirm(user, orderId, 2100).andExpect { status { isOk() }; jsonPath("$.value.status") { value("PAID") } }
        assertEquals(null, couponId(user, "AVAILABLE", RewardsSeed.CHEER_30))
        assertEquals("USED", JsonPath.read<String>(coupons(user, "PAST"), "$.values[0].status"))

        // 운영 현황: 쓴 쿠폰 한 장과 그 할인액(결제 장부의 확정 줄) - 환불로 복원되면 사라진다
        val definitionId = JsonPath.read<List<String>>(get("/api/v1/admin/coupon-definitions", operator).andReturn().response.contentAsString, "$.values[?(@.name=='${RewardsSeed.CHEER_30}')].id").single()
        get("/api/v1/admin/coupon-definitions/$definitionId/stats", operator).andExpect {
            status { isOk() }
            jsonPath("$.value.used") { value(1) }
            jsonPath("$.value.discountTotals[0].currency") { value("KRW") }
            jsonPath("$.value.discountTotals[0].amount") { value(900) }
        }

        val refundId = read(post("$PAYMENTS/orders/$orderId/refund-requests", user, """{"reasonCode":"CUSTOMER_REQUEST"}""", UUID.randomUUID().toString()).andExpect { status { isCreated() } }, "$.value.id")
        post("$ADMIN/refunds/$refundId/approve", operator).andExpect { status { isOk() } }
        await { couponId(user, "AVAILABLE", RewardsSeed.CHEER_30) }
        get("/api/v1/admin/coupon-definitions/$definitionId/stats", operator).andExpect {
            jsonPath("$.value.used") { value(0) }
            jsonPath("$.value.discountTotals.length()") { value(0) }
        }
    }

    @Test
    fun `a coupon held by an order in flight cannot be spent on a second order`() {
        val coupon = giveCoupon(RewardsSeed.CHEER_30, userId)
        orderWith(user, coupon)
        post(
            "$PAYMENTS/orders", user,
            """{"itemType":"cheer","itemRef":"cheer-3000","quantity":1,"discounts":[{"type":"coupon","ref":"$coupon"}]}""", UUID.randomUUID().toString(),
        ).andExpect { status { isConflict() }; jsonPath("$.code") { value("PAYMENT.DISCOUNT_UNAVAILABLE") } }
    }

    @Test
    fun `a free voucher finishes the cheer at zero without touching the provider`() {
        val voucher = giveCoupon(RewardsSeed.FREE_PASS, userId)
        val (orderId, created) = orderWith(user, voucher)
        val body = created.andReturn().response.contentAsString
        assertEquals(0, JsonPath.read<Int>(body, "$.value.order.amount.amount"))
        assertEquals("FREE", JsonPath.read<String>(body, "$.value.order.settlement"))
        assertEquals("PAID", JsonPath.read<String>(body, "$.value.order.status"))
        assertTrue(gateway.checkoutCalls.isEmpty() && gateway.confirmCalls.isEmpty())
        await { myCheers(user).takeIf { it.contains(orderId) } }
        assertEquals("USED", JsonPath.read<String>(coupons(user, "PAST"), "$.values[0].status"))
    }

    @Test
    fun `an account holding payment exempt gets the cheer without a provider, and a free or exempt purchase does not open the invite reward`() {
        post("/api/v1/admin/roles", operator, """{"name":"WAIVED","description":"x","permissions":["payment.exempt"]}""").andExpect { status { isCreated() } }
        jdbc.sql("insert into account_roles (account_id, role) values (:a, 'WAIVED')").param("a", userId).update()

        val code = JsonPath.read<String>(get("/api/v1/referrals/me", stranger).andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.value.code")
        post("/api/v1/referrals/claim", user, """{"code":"$code"}""").andExpect { status { isNoContent() } }

        val result = post("$PAYMENTS/orders", user, """{"itemType":"cheer","itemRef":"cheer-3000","quantity":1}""", UUID.randomUUID().toString()).andExpect { status { isCreated() } }
        val body = result.andReturn().response.contentAsString
        assertEquals("EXEMPT", JsonPath.read<String>(body, "$.value.order.settlement"))
        assertEquals("PAID", JsonPath.read<String>(body, "$.value.order.status"))
        assertTrue(gateway.checkoutCalls.isEmpty())
        await { myCheers(user).takeIf { it.contains(JsonPath.read<String>(body, "$.value.order.id")) } }
        assertEquals("exempt", jdbc.sql("select provider from payment_orders").query(String::class.java).single())

        Thread.sleep(1500)
        assertEquals(0L, balance(stranger), "an exempt order is not a purchase - the inviter gets nothing")

        jdbc.sql("delete from account_roles where role = 'WAIVED'").update()
        val next = post("$PAYMENTS/orders", user, """{"itemType":"cheer","itemRef":"cheer-3000","quantity":1}""", UUID.randomUUID().toString()).andExpect { status { isCreated() } }
        assertEquals("PROVIDER", JsonPath.read<String>(next.andReturn().response.contentAsString, "$.value.order.settlement"), "taking the role back brings the payment window again")
    }

    @Test
    fun `a first-month-free subscription coupon is only looked at on start and taken on the first payment - no charge, then half price`() {
        val coupon = giveCoupon(RewardsSeed.FIRST_MONTH_FREE, userId)
        val started = post("$PAYMENTS/subscriptions", user, """{"planCode":"standard","discounts":[{"type":"coupon","ref":"$coupon"}]}""", UUID.randomUUID().toString())
            .andExpect { status { isCreated() } }
        val id = read(started, "$.value.subscription.id")
        assertEquals("AVAILABLE", JsonPath.read<String>(coupons(user, "AVAILABLE"), "$.values[0].status"), "start only looks - the coupon is held at the first payment")

        post("$PAYMENTS/subscriptions/$id/activate", user, """{"providerPayload":{"authKey":"auth_1"}}""")
            .andExpect { status { isOk() }; jsonPath("$.value.status") { value("ACTIVE") } }
        assertTrue(gateway.chargeCalls.isEmpty(), "a free first period does not charge the card")
        assertEquals("USED", JsonPath.read<String>(coupons(user, "PAST"), "$.values[0].status"))
        assertEquals("free", jdbc.sql("select provider from payment_orders where kind = 'SUBSCRIPTION_INITIAL'").query(String::class.java).single())
        val me = get("$PAYMENTS/subscriptions/me", user).andExpect { status { isOk() } }
        assertEquals(3450, JsonPath.read<Int>(me.andReturn().response.contentAsString, "$.value.subscription.discount.nextAmount"))
        assertEquals(3, JsonPath.read<Int>(me.andReturn().response.contentAsString, "$.value.subscription.discount.remainingPeriods"))
    }

    @Test
    fun `a coupon that is not for subscriptions is refused at start`() {
        post("/api/v1/admin/coupon-definitions", operator, """{"name":"one-time only","stages":[{"type":"FREE","periods":1}],"scope":{"purposes":["ONE_TIME"]}}""").andExpect { status { isCreated() } }
        val coupon = giveCoupon("one-time only", userId)
        post("$PAYMENTS/subscriptions", user, """{"planCode":"standard","discounts":[{"type":"coupon","ref":"$coupon"}]}""", UUID.randomUUID().toString())
            .andExpect { status { isBadRequest() }; jsonPath("$.code") { value("PAYMENT.DISCOUNT_NOT_APPLICABLE") } }
        assertEquals("AVAILABLE", JsonPath.read<String>(coupons(user, "AVAILABLE"), "$.values[0].status"))
    }

    @Test
    fun `the seed is idempotent and the shared WELCOME code lands a coupon once per account`() {
        RewardsSeed(definitions, codes, campaigns).run()
        assertEquals(4, definitions.list().size)
        assertEquals(2, campaigns.list().size)

        post("/api/v1/coupons/redeem", user, """{"code":"welcome"}""").andExpect { status { isCreated() }; jsonPath("$.value.name") { value(RewardsSeed.CHEER_30) } }
        post("/api/v1/coupons/redeem", user, """{"code":"WELCOME"}""").andExpect { status { isConflict() }; jsonPath("$.code") { value("COUPON.CODE_USED") } }
        post("/api/v1/coupons/redeem", user, """{"code":"NOPE"}""").andExpect { status { isBadRequest() }; jsonPath("$.code") { value("COUPON.CODE_INVALID") } }
    }
}
