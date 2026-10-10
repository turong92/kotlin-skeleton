package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.common.web.PublicEndpointRegistry
import dev.sumin.skeleton.common.web.WebProperties
import dev.sumin.skeleton.payment.CreateOrderCommand
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrders
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.PaymentWebhookStatus
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.common.logging.LogMasker
import org.junit.jupiter.api.AfterEach
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.util.AntPathMatcher

/**
 * 웹훅 수신 경로(스펙 §7): 비밀 조각 · 크기 제한 · 모르는 결제사 · 인증 없이 닿음. 진짜 자동설정 순서로 조립한 메모리 장부 위에서 돈다.
 * (보안 체인은 `auth` 가 세운다 — 이 모듈 시험에는 없다. 여기서는 이 모듈이 **공개 경로로 연다고 선언한 것**이 컨트롤러의 주소와 맞는지 본다.)
 */
@SpringBootTest(
    classes = [PaymentWebTestApplication::class],
    properties = ["skeleton.payment.webhook.path-token=web-test-token-0123456789abcdef", "skeleton.payment.webhook.max-body-bytes=2048"],
)
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class)
class PaymentWebhookWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: InMemoryPaymentLedgerStore
    @Autowired lateinit var tasks: InMemoryPaymentTasks
    @Autowired lateinit var gateway: ScriptedGateway
    @Autowired lateinit var orders: PaymentOrders
    @Autowired lateinit var context: ApplicationContext

    private val token = "web-test-token-0123456789abcdef"
    private val base = "/api/v1/payments/webhooks"

    @AfterEach
    fun restore() {
        gateway.webhookSigned = false
    }

    private fun order(key: String = "k-${System.nanoTime()}"): PaymentOrder {
        val created = orders.create(CreateOrderCommand("acc_web", "cheer", "cheer-3000", IdempotencyKey(key))).order
        gateway.authenticate("pay_${created.id}", created.id, created.amount)
        return created
    }

    private fun send(path: String, body: String, headers: Map<String, String> = emptyMap()): ResultActions =
        mvc.perform(post(path).contentType(MediaType.TEXT_PLAIN).content(body).apply { headers.forEach { (k, v) -> header(k, v) } })

    private fun body(order: PaymentOrder, status: String = "DONE") =
        "eventType=PAYMENT_STATUS_CHANGED\npaymentKey=pay_${order.id}\norderId=${order.id}\nstatus=$status\n"

    private fun rows() = store.listWebhookEvents(null, null, 100)

    @Test
    fun `a webhook on the address with the right token is 200 and becomes one stored row and one job`() {
        val order = order()
        val before = tasks.all(PaymentTaskTypes.WEBHOOK).size

        send("$base/scripted/$token", body(order), mapOf(ScriptedGateway.DELIVERY_HEADER to "evt-web-1")).andExpect(status().isOk).andExpect(jsonPath("$.meta").exists())

        val row = rows().single { it.eventKey == "evt-web-1" }
        assertEquals(order.id, row.orderId)
        assertEquals(PaymentWebhookStatus.RECEIVED, row.status)
        assertEquals(before + 1, tasks.all(PaymentTaskTypes.WEBHOOK).size)
    }

    @Test
    fun `a wrong or missing token, an unknown provider and a provider without webhooks answer the very same 404`() {
        val order = order()
        val answers = listOf(
            "$base/scripted/wrong-token-0123456789abcdef-xyz",
            "$base/unknown/$token",
            "$base/unknown",
            "$base/scripted",
        ).map { path -> send(path, body(order)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.WEBHOOK_NOT_FOUND")).andReturn().response.contentAsString }

        val shapes = answers.map { text -> text.replace(Regex("\"(traceId|spanId|timestamp)\":\"[^\"]*\""), "").replace(Regex(",+"), ",") }
        assertEquals(1, shapes.toSet().size, "the body says nothing about which check failed: $shapes")
        assertTrue(rows().none { it.orderId == order.id }, "a refused request stores nothing")
    }

    @Test
    fun `a provider that signs its webhooks is reached without the token, a bad signature and an unreadable body are 401 and store nothing`() {
        gateway.webhookSigned = true
        val order = order()
        val sign = mapOf(ScriptedGateway.SIGNATURE_HEADER to ScriptedGateway.VALID_SIGNATURE, ScriptedGateway.DELIVERY_HEADER to "evt-signed")

        send("$base/scripted", body(order), sign).andExpect(status().isOk)
        send("$base/scripted/$token", body(order), sign + (ScriptedGateway.DELIVERY_HEADER to "evt-signed-2")).andExpect(status().isOk)
        send("$base/scripted", body(order), mapOf(ScriptedGateway.SIGNATURE_HEADER to "forged")).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("PAYMENT.WEBHOOK_REJECTED"))

        assertEquals(setOf("evt-signed", "evt-signed-2"), rows().filter { it.orderId == order.id }.map { it.eventKey }.toSet())
        val rowsBefore = rows().size
        send("$base/scripted/$token", "this is not an event").andExpect(status().isUnauthorized)
        assertEquals(rowsBefore, rows().size)
    }

    @Test
    fun `a body over the size limit is 413 and is not looked at`() {
        val order = order()
        val rowsBefore = rows().size

        send("$base/scripted/$token", body(order) + "pad=" + "x".repeat(2048)).andExpect(status().isContentTooLarge).andExpect(jsonPath("$.code").value("PAYMENT.WEBHOOK_TOO_LARGE"))
        assertEquals(rowsBefore, rows().size)
    }

    @Test
    fun `the secret path piece never reaches a log line - right token, wrong token and a three piece address, filter lines and error lines alike`() {
        // 컨텍스트가 여럿 떠도 이 컨텍스트의 가림막이 꽂혀 있게 한다(가림막 자리는 JVM 하나에 하나다) — 끝나면 되돌려 다른 시험에 새지 않게
        val previousMasker = LogMasker.current
        LogMasker.install(context.getBean(LogMasker::class.java))
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.addAppender(appender)
        val wrong = "wrong-token-0123456789abcdef-xyz"
        val wrongWithSymbols = "wrong&token'0123456789=abcdef;xyz"
        val order = order()
        try {
            val bodies = listOf(
                send("$base/scripted/$wrongWithSymbols", body(order)).andExpect(status().isNotFound),
                send("$base/scripted/$token", body(order), mapOf(ScriptedGateway.DELIVERY_HEADER to "evt-log-1")).andExpect(status().isOk),
                send("$base/scripted/$token/extra", body(order)).andExpect(status().isNotFound),
                send("$base/scripted/$wrong/$token", body(order)).andExpect(status().isNotFound),
            ).map { it.andReturn().response.contentAsString }

            val logged = appender.list.joinToString("\n") { event -> event.formattedMessage + (event.throwableProxy?.let { " " + it.message } ?: "") }
            assertTrue(logged.contains("/webhooks/scripted/"), "the request lines are there, only the piece is hidden: $logged")
            listOf(token, wrong, "token'0123456789=abcdef;xyz", "abcdef;xyz").forEach { secret ->
                assertFalse(logged.contains(secret), "the log carries a path secret: $logged")
                bodies.forEach { assertFalse(it.contains(secret), "the error body carries a path secret: $it") }
            }
        } finally {
            root.detachAppender(appender)
            LogMasker.install(previousMasker)
        }
    }

    @Test
    fun `the two webhook POST shapes are declared public - GET, the orders, the webhook-event and the admin paths are not`() {
        val contributors = context.getBeansOfType(PublicEndpointContributor::class.java).values
        val registry = PublicEndpointRegistry.from(WebProperties(), contributors)
        val matcher = AntPathMatcher()

        fun open(method: HttpMethod, path: String) =
            registry.pathWidePatterns.any { matcher.match(it, path) } ||
                registry.methodSpecificEndpoints.any { it.method == method && matcher.match(it.pattern, path) }

        assertTrue(open(HttpMethod.POST, "$base/scripted"))
        assertTrue(open(HttpMethod.POST, "$base/scripted/$token"))
        assertFalse(open(HttpMethod.GET, "$base/scripted/$token"), "only POST is opened")
        assertFalse(open(HttpMethod.POST, "/api/v1/payments/orders"))
        assertFalse(open(HttpMethod.POST, "/api/v1/admin/payments/webhook-events/1/retry"))
        assertTrue(registry.endpoints.none { it.pattern.contains("admin") })
    }
}
