package dev.sumin.skeleton.payment.paddle

import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import dev.sumin.skeleton.common.time.TimeProvider
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.Collections
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.web.reactive.function.client.WebClient

internal const val SECRET = "pdl_ntfset_test_secret"
internal val NOW: Instant = Instant.parse("2026-10-13T10:00:00Z")

internal fun sign(body: String, ts: Long = NOW.epochSecond, secret: String = SECRET): String {
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
    return "ts=$ts;h1=" + mac.doFinal("$ts:$body".toByteArray()).joinToString("") { "%02x".format(it) }
}

internal fun properties(baseUrl: String = "", prices: Map<String, String> = mapOf("basic" to "pri_basic", "cheer" to "pri_cheer")) =
    PaddleProperties(enabled = true, apiKey = "pdl_sdbx_apikey_x", clientToken = "test_clienttoken", webhookSecret = SECRET, baseUrl = baseUrl, prices = prices)

internal fun gateway(baseUrl: String = "", props: PaddleProperties = properties(baseUrl)) = PaddlePaymentProvider(
    httpClient = DefaultExternalHttpClient(
        webClientBuilder = WebClient.builder(),
        properties = OutboundHttpProperties(defaultConnectTimeout = Duration.ofSeconds(30), defaultResponseTimeout = Duration.ofSeconds(30)),
        defaultErrorMapper = DefaultExternalHttpErrorMapper(),
        customizers = emptyList(),
    ),
    properties = props,
    time = TimeProvider { NOW },
)

/** 루프백에만 묶는 가짜 Paddle API. 받은 요청을 남긴다. */
internal class FakePaddleApi : AutoCloseable {
    data class Req(val method: String, val path: String, val auth: String?, val body: String)

    val requests: MutableList<Req> = Collections.synchronizedList(mutableListOf())
    var status = 200
    var responseBody = "{\"data\":{}}"

    /** `메서드 경로` 별 답 - 없으면 [status] · [responseBody]. */
    private val routes = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, String>>()

    fun on(method: String, path: String, status: Int, body: String) {
        routes["$method $path"] = status to body
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            requests += Req(ex.requestMethod, ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), ex.requestBody.readBytes().decodeToString())
            val route = routes["${ex.requestMethod} ${ex.requestURI.path}"]
            val out = (route?.second ?: responseBody).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(route?.first ?: status, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    override fun close() = server.stop(0)
}

internal fun transactionCompleted(custom: String = """{"orderId":"ord_1","accountId":"acc_1"}""", subscriptionId: String? = null, total: String = "990", currency: String = "USD", eventId: String = "evt_1", priceId: String = "pri_cheer") = """
{"event_id":"$eventId","event_type":"transaction.completed","occurred_at":"2026-10-13T09:59:59Z","notification_id":"ntf_1",
 "data":{"id":"txn_1","status":"completed","currency_code":"$currency","origin":"web","customer_id":"ctm_1","address_id":"add_1",${subscriptionId?.let { "\"subscription_id\":\"$it\"," } ?: ""}
  "custom_data":$custom,"billed_at":"2026-10-13T09:59:00Z","billing_period":{"starts_at":"2026-10-13T09:59:00Z","ends_at":"2026-11-13T09:59:00Z"},
  "items":[{"price":{"id":"$priceId","billing_cycle":{"interval":"month","frequency":1}},"quantity":1}],
  "details":{"totals":{"subtotal":"900","tax":"90","total":"$total"}},
  "payments":[{"status":"captured","method_details":{"type":"card","card":{"type":"visa","last4":"4242"}}}]}}
""".trim()

internal fun subscriptionEvent(type: String = "subscription.updated", status: String = "active", scheduled: String = "null", at: String = "2026-10-13T09:59:30Z", eventId: String = "evt_s1", transactionId: String? = null) = """
{"event_id":"$eventId","event_type":"$type","occurred_at":"$at",
 "data":{"id":"sub_1","status":"$status",${transactionId?.let { "\"transaction_id\":\"$it\"," } ?: ""}"custom_data":{"subscriptionId":"sub_ours","accountId":"acc_1"},"scheduled_change":$scheduled,"billing_cycle":{"interval":"month","frequency":1},
  "current_billing_period":{"starts_at":"2026-10-13T09:59:00Z","ends_at":"2026-11-13T09:59:00Z"}}}
""".trim()

internal fun paymentFailed(custom: String = """{"orderId":"ord_1","accountId":"acc_1"}""", errorCode: String = "declined") = """
{"event_id":"evt_f1","event_type":"transaction.payment_failed","occurred_at":"2026-10-13T09:59:59Z",
 "data":{"id":"txn_1","status":"ready","currency_code":"USD","custom_data":$custom,
  "payments":[{"status":"error","error_code":"$errorCode"}]}}
""".trim()

/** Paddle 의 거래 조회(`include=adjustments`) 답 - 금액은 최소 단위의 문자열이다. [adjustments] 는 `(id, action, status, total)`. */
internal fun transactionBody(
    status: String = "completed", total: String = "499", currency: String = "USD", items: Int = 1,
    adjustments: List<List<String>> = emptyList(),
) = """
{"data":{"id":"txn_1","status":"$status","currency_code":"$currency","billed_at":"2026-10-13T09:59:00Z",
 "details":{"totals":{"total":"$total","currency_code":"$currency"},
  "line_items":[${(1..items).joinToString(",") { """{"id":"txnitm_$it","price_id":"pri_cheer","quantity":1}""" }}]},
 "adjustments":[${adjustments.joinToString(",") { (id, action, st, amount) -> """{"id":"$id","action":"$action","status":"$st","transaction_id":"txn_1","created_at":"2026-10-14T01:00:00Z","totals":{"total":"$amount"}}""" }}]}}
""".trim()

internal fun adjustmentEvent(
    type: String = "adjustment.created", id: String = "adj_1", action: String = "refund", status: String = "approved", total: String = "499", eventId: String = "evt_a1",
) = """
{"event_id":"$eventId","event_type":"$type","occurred_at":"2026-10-14T01:00:00Z",
 "data":{"id":"$id","action":"$action","status":"$status","transaction_id":"txn_1","subscription_id":"sub_1","currency_code":"USD","totals":{"total":"$total"}}}
""".trim()
