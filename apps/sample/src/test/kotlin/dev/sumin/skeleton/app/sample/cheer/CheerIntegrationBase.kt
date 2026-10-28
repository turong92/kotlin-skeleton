package dev.sumin.skeleton.app.sample.cheer

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.TestcontainersConfiguration
import dev.sumin.skeleton.app.sample.notes.SampleIntegrationTest
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCard
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import java.util.UUID
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 「응원하기」 통합 시험의 바탕 — 진짜 PostgreSQL · 진짜 보안 체인 · 진짜 Flyway · 진짜 결제 장부(`payment-jdbc`)이고, 결제사만 시험 소스의 [ScriptedGateway] 빈이다(스펙 §13.1 · §13.2).
 * 장부의 "외부 호출은 열린 트랜잭션 밖" 감시를 켜 두려고 가짜 결제사에 `openTransaction = store::hasOpenTransaction` 를 연결한다.
 * 같은 설정이라 이 바탕을 쓰는 시험 클래스끼리 컨텍스트가 하나다(작업 큐 폴링 200ms · 운영자 경로 켬 · 웹훅 비밀 조각 · 조회 작업 1초).
 */
@SpringBootTest(properties = [
    "skeleton.job-queue.poll-interval=200ms",
    "spring.config.import=classpath:test-seeds.yml",
    "skeleton.payment.admin.enabled=true",
    "skeleton.payment.webhook.path-token=$WEBHOOK_TOKEN",
    "skeleton.payment.reconcile.first-delay=1s",
    "skeleton.payment.http.order-rate-limit.account-capacity=1000",
    "skeleton.payment.http.order-rate-limit.ip-capacity=1000",
    "skeleton.payment.subscription.enabled=true",
    "skeleton.payment.subscription.renewal.retry-interval=2s",
])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, SampleIntegrationTest.FakeStorageConfiguration::class, CheerIntegrationBase.Provider::class)
abstract class CheerIntegrationBase {
    /** 결제사는 가짜다 — 이 빈 하나가 결제창 · 승인 · 조회 · 환불 · 웹훅 해석 능력을 모두 가진다. */
    @TestConfiguration(proxyBeanMethods = false)
    class Provider {
        @Bean
        @org.springframework.context.annotation.Primary   // 다른 가짜 결제사를 더하는 시험(CatalogJourneyIntegrationTest)에서도 `gateway` 필드는 이 빈이다
        fun scriptedGateway(store: PaymentLedgerStore): ScriptedGateway =
            ScriptedGateway(now = { java.time.Instant.now() }, openTransaction = store::hasOpenTransaction).also { it.confirmCarriesDetails = true }
    }

    @Autowired protected lateinit var mvc: MockMvc
    @Autowired protected lateinit var jdbc: JdbcClient
    @Autowired protected lateinit var gateway: ScriptedGateway

    protected lateinit var user: String
    protected lateinit var operator: String
    protected lateinit var stranger: String

    @BeforeEach
    fun cleanLedger() {
        // 자식부터 — 장부의 표 넷 · 응원 기록 · 작업
        listOf("payment_events", "payment_refunds", "payment_webhook_events", "payment_order_discounts", "payment_orders", "payment_subscription_discount_stages", "payment_subscription_discounts", "payment_subscriptions", "cheers", "jobs").forEach { jdbc.sql("delete from $it").update() }
        gateway.confirmCarriesDetails = true
        // 가짜 결제사는 컨텍스트 하나를 시험 여럿이 나눠 쓴다 — 호출 기록은 시험마다 비운다
        listOf(gateway.confirmCalls, gateway.lookupCalls, gateway.checkoutCalls, gateway.refundCalls, gateway.issueCalls, gateway.chargeCalls, gateway.revokeCalls).forEach { it.clear() }
        // 한 시험이 남긴 대본이 다음 시험의 첫 호출을 가로채지 않게 비운다(실패한 시험 뒤에도)
        gateway.clearScripts()
        user = login("user@example.com")
        operator = login("admin@example.com")
        stranger = login("moderator@example.com")
    }

    protected fun login(email: String): String {
        val body = mvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return JsonPath.read<String>(body, "$.value.accessToken").also { assertTrue(it.isNotBlank()) }
    }

    protected fun accountOf(email: String): String =
        jdbc.sql("select id from accounts where email = :e").param("e", email).query(String::class.java).single()

    protected fun get(path: String, token: String?): ResultActionsDsl = mvc.get(path) { token?.let { header("Authorization", "Bearer $it") } }

    protected fun post(path: String, token: String?, body: String = "{}", key: String? = null): ResultActionsDsl =
        mvc.post(path) {
            token?.let { header("Authorization", "Bearer $it") }
            key?.let { header("Idempotency-Key", it) }
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    protected fun read(result: ResultActionsDsl, path: String): String = JsonPath.read<Any>(result.andReturn().response.contentAsString, path).toString()

    /** 주문을 만든다 — 응답의 주문 id. */
    protected fun createOrder(token: String, itemRef: String = "cheer-3000", key: String = UUID.randomUUID().toString()): String =
        read(post("$PAYMENTS/orders", token, """{"itemType":"cheer","itemRef":"$itemRef","quantity":1}""", key).andExpect { status { isCreated() } }, "$.value.order.id")

    /** 결제창에서 인증을 마친 것으로 두고 승인을 부른다. 승인 요청의 응답. */
    protected fun confirm(token: String, orderId: String, amount: Long, paymentKey: String = "pay_" + UUID.randomUUID().toString().take(12), card: PaymentCard? = PaymentCard("HYUNDAI", "1234")): ResultActionsDsl {
        gateway.authenticate(paymentKey, orderId, PaymentAmount(amount, "KRW"), card)
        return post("$PAYMENTS/orders/$orderId/confirm", token, """{"providerPaymentId":"$paymentKey","amount":$amount}""")
    }

    /** 응원 하나를 끝까지 산다 — 주문 id. */
    protected fun buy(token: String, itemRef: String = "cheer-3000", amount: Long = 3000): String {
        val orderId = createOrder(token, itemRef)
        confirm(token, orderId, amount).andExpect { status { isOk() }; jsonPath("$.value.status") { value("PAID") } }
        return orderId
    }

    /** 비동기로 도착하는 것(작업)을 기다린다 — 최대 [seconds] 초. */
    protected fun <T> await(seconds: Long = 20, probe: () -> T?): T {
        val deadline = System.nanoTime() + seconds * 1_000_000_000
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(100)
        }
        error("조건이 ${seconds}초 안에 충족되지 않았다")
    }

    protected fun myCheers(token: String): String = get("/api/v1/cheers/me", token).andExpect { status { isOk() } }.andReturn().response.contentAsString

}

const val PAYMENTS = "/api/v1/payments"
const val ADMIN = "/api/v1/admin/payments"
const val WEBHOOK_TOKEN = "cheer-webhook-token-0123456789-abcdef"
