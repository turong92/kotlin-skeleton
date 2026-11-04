package dev.sumin.skeleton.app.sample.cheer

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.payment.appstore.AppStoreSignatureVerifier
import dev.sumin.skeleton.payment.appstore.FakeAppStoreServer
import dev.sumin.skeleton.payment.appstore.TestApiKey
import dev.sumin.skeleton.payment.appstore.TestChain
import dev.sumin.skeleton.payment.appstore.notificationBody
import dev.sumin.skeleton.payment.appstore.transactionJson
import dev.sumin.skeleton.payment.googleplay.FakePlayServer
import dev.sumin.skeleton.payment.googleplay.TestOidc
import dev.sumin.skeleton.payment.googleplay.TestServiceAccount
import dev.sumin.skeleton.payment.googleplay.orderJson
import dev.sumin.skeleton.payment.googleplay.productJson
import dev.sumin.skeleton.payment.googleplay.pushBody
import dev.sumin.skeleton.payment.googleplay.voidedNotification
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 앱 인앱결제 여정(진짜 PostgreSQL · 진짜 보안 체인 · 진짜 Flyway · 진짜 장부, 스토어만 가짜 서버 둘): 설정 → 스토어에 직접 물어 확인 → 응원 기록 → 스토어의 환불 알림 → 회수.
 * 애플은 시험용 인증서 체인으로 서명한 거래·알림(검증기 빈만 시험 루트로 바꿔 끼운다), 구글은 가짜 Play API + 시험용 OIDC 키로 서명한 Pub/Sub push 다.
 * 스토어 모듈의 세부(위조·재생·경합)는 각 모듈 시험이 본다 - 여기는 **견본 앱의 조립**(상품 지도 `cheer-3000` → 응원 기록, 환불 훅)이 끝까지 이어지는지만 본다.
 */
@TestPropertySource(properties = [
    "skeleton.payment-iap.enabled=true",
    "skeleton.payment-appstore.enabled=true",
    "skeleton.payment-appstore.bundle-id=com.example.sample",
    "skeleton.payment-appstore.app-apple-id=1234567890",
    "skeleton.payment-appstore.issuer-id=11111111-2222-3333-4444-555555555555",
    "skeleton.payment-appstore.key-id=ABC123DEFG",
    "skeleton.payment-googleplay.enabled=true",
    "skeleton.payment-googleplay.package-name=com.example.sample",
    "skeleton.payment-googleplay.pubsub.audience=https://api.example.com/api/v1/payments/webhooks/googleplay",
    "skeleton.payment-googleplay.pubsub.service-account-email=pubsub-push@sample-project.iam.gserviceaccount.com",
])
@Import(IapJourneyIntegrationTest.Verifier::class)
class IapJourneyIntegrationTest : CheerIntegrationBase() {
    /** 앱스토어 서명 검증기는 `@ConditionalOnMissingBean` 이다 - 시험만 시험 루트로 바꿔 끼운다(신뢰 루트를 바꾸는 설정은 없다). */
    @TestConfiguration(proxyBeanMethods = false)
    class Verifier {
        @Bean
        fun appStoreSignatureVerifier(): AppStoreSignatureVerifier = AppStoreSignatureVerifier(setOf(chain.root), TimeProvider.systemUtc())
    }

    companion object {
        private val now = Instant.now()
        private val chain = TestChain.create(notBefore = now.minus(Duration.ofDays(2)), notAfter = now.plus(Duration.ofDays(2)))
        private val apple = FakeAppStoreServer(chain, "Production")
        private val sandbox = FakeAppStoreServer(chain, "Sandbox")
        private val account = TestServiceAccount()
        private val oidc = TestOidc()
        private val play = FakePlayServer(account, oidc)
        private val apiKey = TestApiKey()

        @JvmStatic
        @DynamicPropertySource
        fun stores(registry: DynamicPropertyRegistry) {
            registry.add("skeleton.payment-appstore.base-url") { apple.baseUrl }
            registry.add("skeleton.payment-appstore.sandbox-base-url") { sandbox.baseUrl }
            registry.add("skeleton.payment-appstore.private-key") { apiKey.pem }
            registry.add("skeleton.payment-googleplay.service-account") { account.base64 }
            registry.add("skeleton.payment-googleplay.base-url") { play.baseUrl }
            registry.add("skeleton.payment-googleplay.token-url") { "${play.baseUrl}/token" }
            registry.add("skeleton.payment-googleplay.certs-url") { "${play.baseUrl}/certs" }
        }

        @JvmStatic
        @AfterAll
        fun stopFakes() {
            apple.close(); sandbox.close(); play.close()
        }
    }

    private val iap = "$PAYMENTS/iap"

    private fun cheerRows(accountId: String): List<Map<String, Any?>> =
        jdbc.sql("select status, amount, refunded_amount from cheers where account_id = :a").param("a", accountId).query().listOfRows()

    private fun config(platform: String): ResultActionsDsl = get("$iap/config?platform=$platform&storefront=KR", user)

    @Test
    fun `an App Store cheer is confirmed with Apple, recorded as a cheer, and taken back by Apple's REFUND notification`() {
        val me = accountOf("user@example.com")
        val token = read(config("ios").andExpect { status { isOk() }; jsonPath("$.value.store") { value("appstore") }; jsonPath("$.value.channel") { value("IAP_ONLY") } }, "$.value.appAccountToken")
        apple.knows("2000000000000101", transactionJson(transactionId = "2000000000000101", productId = "com.example.sample.cheer3000", price = 3_000_000, currency = "KRW", appAccountToken = token, purchaseDate = Instant.now().minusSeconds(60)))

        post("$iap/appstore/purchases", user, """{"transactionId":"2000000000000101"}""").andExpect {
            status { isOk() }
            jsonPath("$.value.result") { value("GRANTED") }
            jsonPath("$.value.kind") { value("ONE_TIME") }
        }
        assertEquals(listOf(mapOf("status" to "PAID", "amount" to 3000L, "refunded_amount" to 0L)), cheerRows(me).map { it.toMap() }, "the cheer is recorded from the App Store's own answer, not from the app")
        assertEquals("PAID", jdbc.sql("select status from payment_orders where account_id = :a").param("a", me).query(String::class.java).single())

        // 같은 거래를 다시 보내도 응원은 한 줄 (앱이 다시 시작해 복원할 때)
        post("$iap/appstore/purchases", user, """{"transactionId":"2000000000000101"}""").andExpect { status { isOk() } }
        assertEquals(1, cheerRows(me).size)

        // 애플의 REFUND 알림 - 시험용 체인으로 서명한 본문만 믿는다. 서명이 다른 루트면 거절하고 응원은 그대로다
        val refunded = transactionJson(transactionId = "2000000000000101", productId = "com.example.sample.cheer3000", price = 3_000_000, currency = "KRW", appAccountToken = token,
            purchaseDate = Instant.now().minusSeconds(60), revocationDate = Instant.now(), revocationType = "REFUND_FULL", revocationPercentage = 100_000)
        mvc.post("$PAYMENTS/webhooks/appstore") { contentType = org.springframework.http.MediaType.APPLICATION_JSON; content = notificationBody(TestChain.create(), "REFUND", transaction = refunded) }.andExpect { status { isUnauthorized() } }
        assertEquals("PAID", cheerRows(me).single()["status"])

        mvc.post("$PAYMENTS/webhooks/appstore") { contentType = org.springframework.http.MediaType.APPLICATION_JSON; content = notificationBody(chain, "REFUND", uuid = "aaaaaaaa-0000-4000-8000-000000000101", transaction = refunded) }.andExpect { status { isOk() } }
        val row = await { cheerRows(me).singleOrNull()?.takeIf { it["status"] == "REFUNDED" } }
        assertEquals(3000L, row["refunded_amount"], "the whole cheer is taken back")

        // 환불된 거래를 다시 확인하면 지급하지 않는다
        apple.knows("2000000000000101", refunded)   // 애플은 환불된 거래를 revocationDate 와 함께 돌려준다
        post("$iap/appstore/purchases", user, """{"transactionId":"2000000000000101"}""").andExpect { status { isEqualTo(422) }; jsonPath("$.code") { value("PAYMENT.IAP_PURCHASE_REVOKED") } }
        assertTrue(cheerRows(me).all { it["status"] == "REFUNDED" })
    }

    @Test
    fun `a Google Play cheer follows the same road - confirmed with Google, recorded, and taken back by the voided-purchase push`() {
        val me = accountOf("user@example.com")
        val accountToken = read(config("android").andExpect { status { isOk() }; jsonPath("$.value.store") { value("googleplay") } }, "$.value.appAccountToken")
        play.product("tok-cheer-1", productJson(productId = "cheer_3000", orderId = "GPA.3300-0000-0000-00101", account = accountToken))
        play.order("GPA.3300-0000-0000-00101", orderJson("GPA.3300-0000-0000-00101", "KRW", 3000, createTime = Instant.now().minusSeconds(60)))

        post("$iap/googleplay/purchases", user, """{"productId":"cheer_3000","purchaseToken":"tok-cheer-1"}""").andExpect {
            status { isOk() }
            jsonPath("$.value.result") { value("GRANTED") }
        }
        assertEquals(listOf(mapOf("status" to "PAID", "amount" to 3000L, "refunded_amount" to 0L)), cheerRows(me).map { it.toMap() })
        assertTrue(play.pathsCalled().any { it.endsWith(":acknowledge") }, "Google is told the purchase was granted (acknowledge), or it refunds in three days")

        val body = pushBody(voidedNotification("tok-cheer-1", "GPA.3300-0000-0000-00101"), messageId = "7000000000101")
        fun push(bearer: String?) = mvc.post("$PAYMENTS/webhooks/googleplay") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = body
            if (bearer != null) header("Authorization", "Bearer $bearer")
        }
        push(null).andExpect { status { isUnauthorized() } }
        push(oidc.token(audience = "https://other.example.com/hook", expires = Instant.now().plusSeconds(1800))).andExpect { status { isUnauthorized() } }
        assertEquals("PAID", cheerRows(me).single()["status"], "a push without Google's token changes nothing")

        push(oidc.token(expires = Instant.now().plusSeconds(1800))).andExpect { status { isOk() } }
        val row = await { cheerRows(me).singleOrNull()?.takeIf { it["status"] == "REFUNDED" } }
        assertEquals(3000L, row["refunded_amount"])
    }
}
