package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asUser
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.keys
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.read
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.strings
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import dev.sumin.skeleton.paymenttest.SubscriptionBeans
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 구독 경로: 상태 코드 · 오류 코드, 남의 구독 = 없는 구독, 어떤 응답에도 빌링키가 없다(카드 등록 창 값의 고객 키만 예외). */
@SpringBootTest(
    classes = [PaymentWebTestApplication::class],
    properties = [
        "skeleton.payment.subscription.enabled=true",
        "skeleton.payment.http.order-rate-limit.account-capacity=100000",
        "skeleton.payment.http.order-rate-limit.ip-capacity=100000",
    ],
)
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class, SubscriptionBeans::class)
class PaymentSubscriptionHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var gateway: ScriptedGateway

    private val base = "/api/v1/payments"
    private var sequence = 0

    private fun account() = "acc_sub_${System.nanoTime()}_${sequence++}"

    private fun start(account: String, key: String = "k-${System.nanoTime()}", plan: String = "basic"): ResultActions =
        mvc.perform(post("$base/subscriptions").asUser(account).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("""{"planCode":"$plan"}"""))

    private fun activate(account: String, id: String, body: String = """{"providerPayload":{"authKey":"auth_1"}}"""): ResultActions =
        mvc.perform(post("$base/subscriptions/$id/activate").asUser(account).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun started(account: String): String = read(start(account).andExpect(status().isCreated).andReturn().response.contentAsString)["value"]["subscription"]["id"].asString()

    @Test
    fun `start is 201 with the card registration values, the same key again is 200 with the same ones until the card is handed in, and the header is required`() {
        val account = account()
        val first = start(account, "same").andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.subscription.status").value("PENDING"))
            .andExpect(jsonPath("$.value.billingAuth.kind").value("SCRIPTED_BILLING_AUTH"))
            .andExpect(jsonPath("$.value.billingAuth.customerKey").exists())
            .andReturn().response.contentAsString
        start(account, "same").andExpect(status().isOk)
            .andExpect(jsonPath("$.value.subscription.id").value(read(first)["value"]["subscription"]["id"].asString()))
            .andExpect(jsonPath("$.value.billingAuth.customerKey").value(read(first)["value"]["billingAuth"]["customerKey"].asString()))
        // 카드를 낸 뒤의 재전송에는 등록 창 값이 없다
        activate(account, read(first)["value"]["subscription"]["id"].asString()).andExpect(status().isOk)
        start(account, "same").andExpect(status().isOk).andExpect(jsonPath("$.value.billingAuth").value(null as Any?))
        mvc.perform(post("$base/subscriptions").asUser(account).contentType(MediaType.APPLICATION_JSON).content("""{"planCode":"basic"}""")).andExpect(status().isBadRequest)
        start(account, "other", plan = "gold").andExpect(status().isUnprocessableContent)
        start(account, "same", plan = "plus").andExpect(status().isConflict)
    }

    @Test
    fun `activate is 200 ACTIVE, me shows it with the payment, and no response carries the billing key`() {
        val account = account()
        val id = started(account)

        val activated = activate(account, id).andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("ACTIVE")).andReturn().response.contentAsString
        val me = mvc.perform(get("$base/subscriptions/me").asUser(account)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.subscription.status").value("ACTIVE"))
            .andExpect(jsonPath("$.value.subscription.card.last4").value("1234"))
            .andExpect(jsonPath("$.value.orders[0].kind").value("SUBSCRIPTION_INITIAL"))
            .andReturn().response.contentAsString

        listOf(activated, me).forEach { body ->
            assertFalse(body.contains("bkey_"), "the billing key is in a response: $body")
            assertFalse(keys(read(body)).any { it.contains("billing", ignoreCase = true) || it.equals("customerKey", true) }, "billing columns are in a response")
            assertTrue(strings(read(body)).none { it.startsWith("cus_") }, "the customer key is in a response")
        }
    }

    @Test
    fun `somebody else's subscription is 404 on every route, the same as a missing one`() {
        val owner = account()
        val id = started(owner)
        val stranger = account()

        activate(stranger, id).andExpect(status().isNotFound)
        mvc.perform(post("$base/subscriptions/$id/cancel").asUser(stranger)).andExpect(status().isNotFound)
        activate(stranger, "sub_missing").andExpect(status().isNotFound)
        mvc.perform(post("$base/subscriptions/sub_missing/cancel").asUser(stranger)).andExpect(status().isNotFound)
        mvc.perform(get("$base/subscriptions/me").asUser(stranger)).andExpect(status().isOk).andExpect(jsonPath("$.value.subscription").value(null as Any?))
    }

    @Test
    fun `the body of activate must carry a providerPayload of strings`() {
        val account = account()
        val id = started(account)

        activate(account, id, """{}""").andExpect(status().isBadRequest)
        activate(account, id, """{"providerPayload":{"authKey":5}}""").andExpect(status().isBadRequest)
    }
}
