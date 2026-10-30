package dev.sumin.skeleton.app.sample.reviews

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.cheer.CheerIntegrationBase
import dev.sumin.skeleton.app.sample.orders.SampleOrderListener
import dev.sumin.skeleton.badge.BadgeDefinitions
import dev.sumin.skeleton.campaign.Campaigns
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderListener
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import dev.sumin.skeleton.review.ReviewPurchases
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put

/**
 * 결제 → 구매 신호 → 산 사람만 쓰는 리뷰 → 보상이 견본 앱 안에서 맞물리는지(진짜 PostgreSQL · 보안 체인 · 큐 일꾼). 결제 장부의 훅은 이 앱의 [SampleOrderListener] 를 직접 부른다.
 * 비로그인 읽기와 응답에 계정 id 가 없음도 이 앱의 보안 체인에서 본다. 씨앗은 [ReviewsSeed] 를 직접 돌린다.
 */
class ReviewsFlowIntegrationTest : CheerIntegrationBase() {
    @Autowired lateinit var listener: PaymentOrderListener
    @Autowired lateinit var purchases: ReviewPurchases
    @Autowired lateinit var definitions: BadgeDefinitions
    @Autowired lateinit var campaigns: Campaigns

    private lateinit var userId: String
    private lateinit var strangerId: String
    private val reviewsPath = "/api/v1/reviews/targets/product/basic/reviews"

    @BeforeEach
    fun clean() {
        userId = accountOf("user@example.com")
        strangerId = accountOf("moderator@example.com")
        wipe()
    }

    @AfterEach
    fun stopAchievement() {
        campaigns.list().filter { it.name == ReviewsSeed.REVIEWS_ACHIEVEMENT }.forEach { campaigns.setEnabled(it.id, false) }   // 뒤의 시험에서 지급 건이 생기지 않게
        wipe()
    }

    private fun wipe() {
        listOf("review_helpful", "review_reports", "review_replies", "review_photos", "reviews", "review_target_stats", "review_purchases").forEach { jdbc.sql("delete from $it").update() }
        mapOf("campaign_progress" to "account_id", "reward_grants" to "account_id", "badge_award_events" to "account_id", "badge_awards" to "account_id", "point_entries" to "account_id", "point_balances" to "account_id", "notification_inbox" to "recipient_id")
            .forEach { (table, column) -> jdbc.sql("delete from $table where $column in (:a, :b)").param("a", userId).param("b", strangerId).update() }
    }

    private fun order(account: String, itemType: String = "subscription", itemRef: String = "basic", status: PaymentOrderStatus = PaymentOrderStatus.PAID): PaymentOrder =
        PaymentTestOrders.order(id = "ord_rv" + UUID.randomUUID().toString().replace("-", "").take(30), accountId = account, status = status, amount = PaymentAmount(3900, "KRW"))
            .copy(itemType = itemType, itemRef = itemRef, paidAt = Instant.now(), paidAmount = 3900)

    private fun write(token: String, body: String = """{"rating":5,"body":"works great for me, thanks"}""") =
        post(reviewsPath, token, body, UUID.randomUUID().toString())

    private fun purchaseRows(): Long = jdbc.sql("select count(*) from review_purchases where revoked_at is null").query(Long::class.java).single()

    @Test
    fun `a paid subscription is the purchase signal - a stranger cannot write, the buyer writes a verified review that a logged-out reader sees without any account id`() {
        write(stranger).andExpect { status { isForbidden() }; jsonPath("$.code") { value("REVIEW.PURCHASE_REQUIRED") } }
        get("/api/v1/reviews/targets/product/basic/summary", stranger).andExpect { jsonPath("$.value.writeBlockedReason") { value("PURCHASE_REQUIRED") } }

        listener.onPaid(order(userId))
        write(user).andExpect { status { isCreated() }; jsonPath("$.value.verified") { value(true) }; jsonPath("$.value.mine") { value(true) } }

        val summary = get("/api/v1/reviews/targets/product/basic/summary", null).andExpect {
            status { isOk() }
            jsonPath("$.value.count") { value(1) }
            jsonPath("$.value.average") { value(5.0) }
            jsonPath("$.value.writeBlockedReason") { value("SIGN_IN") }
        }.andReturn().response.contentAsString
        val list = get(reviewsPath, null).andExpect { status { isOk() }; jsonPath("$.values[0].verified") { value(true) } }.andReturn().response.contentAsString
        listOf(summary, list).forEach { assertFalse(it.contains(userId) || it.contains("acc_"), "an account id leaked: $it") }
        get("/api/v1/reviews/config", null).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `yearly prices map to their product, and cheer orders or unknown prices send no signal`() {
        listener.onPaid(order(strangerId, itemRef = "basic-yearly"))
        assertEquals(1L, purchaseRows())
        write(stranger).andExpect { status { isCreated() } }
        listener.onPaid(order(userId, itemType = "cheer", itemRef = "cheer-3000"))
        listener.onPaid(order(userId, itemRef = "no-such-price"))
        assertEquals(1L, purchaseRows())
        val same = order(userId)
        listener.onPaid(same)
        listener.onPaid(same)   // 같은 주문은 한 줄
        assertEquals(2L, purchaseRows())
    }

    @Test
    fun `a full refund takes the verified badge down, a partial one does not`() {
        val paid = order(userId)
        listener.onPaid(paid)
        write(user).andExpect { status { isCreated() } }
        val refund = PaymentTestOrders.refund(orderId = paid.id, status = PaymentRefundStatus.SUCCEEDED, amount = PaymentAmount(1000, "KRW"))
        listener.onRefunded(paid.copy(status = PaymentOrderStatus.PARTIALLY_REFUNDED, refundedAmount = 1000), refund)
        get(reviewsPath, null).andExpect { jsonPath("$.values[0].verified") { value(true) } }
        listener.onRefunded(paid.copy(status = PaymentOrderStatus.REFUNDED, refundedAmount = 3900), refund)
        get(reviewsPath, null).andExpect { jsonPath("$.values[0].verified") { value(false) } }
    }

    @Test
    fun `the first review earns the first-review badge and 100 points, and a rewritten one is not rewarded twice`() {
        ReviewsSeed(purchases, definitions, campaigns).run()
        campaigns.list().filter { it.name == ReviewsSeed.REVIEWS_ACHIEVEMENT }.forEach { campaigns.setEnabled(it.id, true) }
        jdbc.sql("delete from review_purchases").update()
        listener.onPaid(order(userId))
        write(user).andExpect { status { isCreated() } }
        await {
            JsonPath.read<List<String>>(get("/api/v1/badges/me", user).andReturn().response.contentAsString, "$.value.earned[*].code").takeIf { it == listOf(ReviewsSeed.FIRST_REVIEW) }
        }
        await { JsonPath.read<Number>(get("/api/v1/points", user).andReturn().response.contentAsString, "$.value.balance").toLong().takeIf { it == 100L } }
        val id = JsonPath.read<String>(get("/api/v1/reviews/me", user).andReturn().response.contentAsString, "$.values[0].id")
        mvc.delete("/api/v1/reviews/$id") { header("Authorization", "Bearer $user") }.andExpect { status { isNoContent() } }
        write(user).andExpect { status { isCreated() } }
        Thread.sleep(1500)
        assertEquals(1L, jdbc.sql("select count(*) from badge_award_events where account_id = :a and action = 'AWARDED'").param("a", userId).query(Long::class.java).single())
    }

    @Test
    fun `the seed gives acc_user a basic purchase so a local user can review without a payment key`() {
        ReviewsSeed(purchases, definitions, campaigns).run()
        ReviewsSeed(purchases, definitions, campaigns).run()   // 다시 떠도 늘지 않는다
        assertEquals(1L, jdbc.sql("select count(*) from review_purchases where account_id = 'acc_user'").query(Long::class.java).single())
        assertEquals(1, campaigns.list().count { it.name == ReviewsSeed.REVIEWS_ACHIEVEMENT })
    }

    @Test
    fun `photo keys must be the caller's own uploads, and the operator path is open for a moderator`() {
        listener.onPaid(order(userId))
        write(user, """{"rating":4,"body":"photo of someone else's file","photos":["uploads/someone-else.jpg"]}""").andExpect { status { isForbidden() }; jsonPath("$.code") { value("REVIEW.FORBIDDEN") } }
        write(user).andExpect { status { isCreated() } }
        val id = JsonPath.read<String>(get("/api/v1/reviews/me", user).andReturn().response.contentAsString, "$.values[0].id")
        get("/api/v1/admin/reviews?queue=all", user).andExpect { status { isForbidden() } }
        get("/api/v1/admin/reviews?queue=all", operator).andExpect { status { isOk() }; jsonPath("$.values[0].id") { value(id) } }
        mvc.put("/api/v1/admin/reviews/$id/moderation") {
            header("Authorization", "Bearer $operator"); contentType = org.springframework.http.MediaType.APPLICATION_JSON; content = """{"status":"HIDDEN","reason":"test"}"""
        }.andExpect { status { isNoContent() } }
        get(reviewsPath, null).andExpect { jsonPath("$.values.length()") { value(0) } }
        assertTrue(get("/api/v1/reviews/me", user).andReturn().response.contentAsString.contains("test"), "the author sees the reason")
    }
}
