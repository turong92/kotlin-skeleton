package dev.sumin.skeleton.payment.toss.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import tools.jackson.databind.json.JsonMapper

/**
 * 가짜 토스 HTTP 서버 (JDK `HttpServer`) — 토스 문서 모양의 JSON 으로 승인 · 취소 · 조회에 답한다. 시험 소스에만 있다(`main` 에 두지 않는다).
 *
 * - **루프백(127.0.0.1)에만 묶는다** — 와일드카드 바인딩은 다른 프로세스에 연결이 가로채인다(`TestServerRulesTest`).
 * - Basic 인증(`<시크릿 키>:`)이 틀리면 진짜 토스처럼 `401 UNAUTHORIZED_KEY`. [requireIdempotencyKey] 가 true(기본)이면 POST 에 `Idempotency-Key` 가 없을 때 이 서버가
 *   `400 INVALID_REQUEST` 로 거절한다 — 진짜 토스는 헤더 없이도 받고(받으면 같은 요청을 다시 보내도 **새 요청**으로 처리한다), 이 서버가 일부러 엄격한 것은
 *   "키를 싣는 호출은 늘 싣는다"를 시험이 잡으려는 것이다. 키가 없을 때 헤더 없이 나가는 동작 자체를 보려면 false 로 둔다.
 * - 받은 요청은 전부 [requests] 에 남는다(헤더 이름은 소문자).
 * - 응답은 경로마다 [Reply] 로 정한다: 정상 JSON · 오류 · 깨진 JSON · 연결 끊기 · 응답 붙잡기(제한 시간 초과).
 * - 뒤 작업(샘플 통합 등)이 같은 서버를 다시 쓴다 — 응답 모양은 [TossFixtures] 가 만든다.
 */
internal class FakeTossServer(
    val secretKey: String = "test_sk_fake_server_key",
    private val requireIdempotencyKey: Boolean = true,
) : AutoCloseable {
    data class Recorded(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    }

    sealed interface Reply {
        data class Json(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) : Reply

        /** 200 인데 JSON 이 깨져 있다. */
        data class Broken(val status: Int = 200, val body: String = "{\"paymentKey\": \"x\", \"status\": ") : Reply

        /** 응답 헤더도 보내지 않고 연결을 닫는다. */
        data object Drop : Reply

        /** [FakeTossServer.release] (또는 close) 전까지 응답하지 않는다 — 제한 시간 초과를 부하와 무관하게 만든다. */
        data object Hang : Reply
    }

    private val release = CountDownLatch(1)
    private val routes = Collections.synchronizedMap(linkedMapOf<String, (Recorded) -> Reply>())
    private val recorded = Collections.synchronizedList(mutableListOf<Recorded>())
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
    val requests: List<Recorded> get() = synchronized(recorded) { recorded.toList() }

    /** `method path` 에 답한다. [path] 는 쿼리를 뺀 요청 경로 그대로(`/v1/payments/pay_1/cancel`). */
    fun on(method: String, path: String, reply: (Recorded) -> Reply) {
        routes["$method $path"] = reply
    }

    fun on(method: String, path: String, reply: Reply) = on(method, path) { reply }

    fun release() = release.countDown()

    fun clearRequests() = recorded.clear()

    override fun close() {
        release.countDown()
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val headers = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.toList() }
        val request = Recorded(exchange.requestMethod, exchange.requestURI.path, headers, body)
        recorded += request

        val reply: Reply = when {
            request.header("authorization") != expectedAuthorization() -> TossFixtures.error(401, "UNAUTHORIZED_KEY")
            requireIdempotencyKey && request.method == "POST" && request.header("idempotency-key").isNullOrBlank() ->
                TossFixtures.error(400, "INVALID_REQUEST")
            else -> routes["${request.method} ${request.path}"]?.invoke(request) ?: TossFixtures.error(404, "NOT_FOUND_PAYMENT")
        }
        send(exchange, reply)
    }

    private fun send(exchange: HttpExchange, reply: Reply) {
        when (reply) {
            is Reply.Json -> write(exchange, reply.status, reply.body, reply.headers)
            is Reply.Broken -> write(exchange, reply.status, reply.body, emptyMap())
            Reply.Drop -> exchange.close()
            Reply.Hang -> {
                release.await(HANG_LIMIT_SECONDS, TimeUnit.SECONDS)
                runCatching { exchange.close() }
            }
        }
    }

    private fun write(exchange: HttpExchange, status: Int, body: String, headers: Map<String, String>) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json;charset=UTF-8")
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun expectedAuthorization(): String =
        "Basic " + Base64.getEncoder().encodeToString("$secretKey:".toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val HANG_LIMIT_SECONDS = 30L
    }
}

/** 토스 문서 모양의 응답 JSON. 개인정보는 [PLANTED] 로 일부러 심는다 — 결과 · 예외 · 로그 어디에도 나오면 안 된다. */
internal object TossFixtures {
    private val mapper: JsonMapper = JsonMapper.builder().build()

    /** 응답에 심는 값. 이 모듈이 읽지 않는 칸에 들어 있다. */
    object PLANTED {
        const val NAME = "홍길동"
        const val EMAIL = "gildong.hong@example.test"
        const val PHONE = "01012345678"
        const val FULL_CARD_NUMBER = "4330123412341234"
        val ALL = listOf(NAME, EMAIL, PHONE, FULL_CARD_NUMBER)
    }

    fun json(value: Any?): String = mapper.writeValueAsString(value)

    fun error(status: Int, code: String, message: String = "${PLANTED.NAME} ${PLANTED.EMAIL} ${PLANTED.PHONE} ${PLANTED.FULL_CARD_NUMBER}"): FakeTossServer.Reply.Json =
        FakeTossServer.Reply.Json(status, json(mapOf("code" to code, "message" to message)))

    fun cancel(
        transactionKey: String,
        amount: Long,
        status: String = "DONE",
        canceledAt: String = "2026-10-08T10:15:30+09:00",
    ): Map<String, Any?> =
        mapOf(
            "cancelReason" to "고객 요청",
            "canceledAt" to canceledAt,
            "cancelAmount" to amount,
            "taxFreeAmount" to 0,
            "refundableAmount" to 0,
            "transactionKey" to transactionKey,
            "cancelStatus" to status,
            "cardNumber" to PLANTED.FULL_CARD_NUMBER,
            "receiptKey" to null,
        )

    /**
     * 결제 객체(승인 · 조회 · 취소 응답이 모두 이 모양이다). 이름 · 이메일 · 전화 · 전체 카드 번호가 [PLANTED] 로 들어 있다.
     * [cardNumber] 는 토스가 가려서 주는 값(`43301234****123*`) — 시험이 일부러 가려지지 않은 값을 넣기도 한다.
     */
    fun payment(
        paymentKey: String = "pay_key_1",
        orderId: String = "ord_abcdef123456",
        status: String = "DONE",
        total: Long = 3_000,
        balance: Long? = total,
        cancels: List<Map<String, Any?>>? = null,
        method: String? = "카드",
        approvedAt: String? = "2026-10-08T10:00:00+09:00",
        card: Map<String, Any?>? = card(),
        lastTransactionKey: String? = null,
        extra: Map<String, Any?> = emptyMap(),
    ): String {
        val body = linkedMapOf<String, Any?>(
            "mId" to "tosstest",
            "version" to "2022-11-16",
            "paymentKey" to paymentKey,
            "orderId" to orderId,
            "orderName" to "응원하기",
            "status" to status,
            "requestedAt" to "2026-10-08T09:59:00+09:00",
            "approvedAt" to approvedAt,
            "currency" to "KRW",
            "totalAmount" to total,
            "balanceAmount" to balance,
            "suppliedAmount" to total,
            "vat" to 0,
            "method" to method,
            "lastTransactionKey" to lastTransactionKey,
            "cancels" to cancels,
            "card" to card,
            "customerName" to PLANTED.NAME,
            "customerEmail" to PLANTED.EMAIL,
            "customerMobilePhone" to PLANTED.PHONE,
            "metadata" to mapOf("note" to PLANTED.NAME),
            "unknownFutureField" to mapOf("nested" to listOf(1, 2, 3)),
        )
        body.putAll(extra)
        return json(body)
    }

    fun card(issuerCode: String? = "61", number: String? = "43301234****123*"): Map<String, Any?> =
        mapOf(
            "issuerCode" to issuerCode,
            "acquirerCode" to "31",
            "number" to number,
            "installmentPlanMonths" to 0,
            "approveNo" to "00000000",
            "cardType" to "신용",
            "ownerType" to "개인",
            "amount" to 3_000,
        )

    fun webhook(
        eventType: String = "PAYMENT_STATUS_CHANGED",
        paymentKey: String? = "pay_key_1",
        orderId: String? = "ord_abcdef123456",
        status: String = "DONE",
    ): String =
        json(
            mapOf(
                "eventType" to eventType,
                "createdAt" to "2026-10-08T10:00:01.000000",
                "data" to linkedMapOf<String, Any?>(
                    "mId" to "tosstest",
                    "paymentKey" to paymentKey,
                    "orderId" to orderId,
                    "status" to status,
                    "totalAmount" to 9_999_999, // 본문의 금액 · 상태는 쓰지 않는다 — 위조돼도 아무것도 바뀌지 않는다
                    "customerName" to PLANTED.NAME,
                    "customerEmail" to PLANTED.EMAIL,
                    "card" to card(number = PLANTED.FULL_CARD_NUMBER),
                ),
            ),
        )
}
