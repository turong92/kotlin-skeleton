package dev.sumin.skeleton.payment.naverpay.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import tools.jackson.databind.json.JsonMapper

/**
 * 가짜 네이버페이 HTTP 서버 (JDK `HttpServer`) - 개발 문서(docs.pay.naver.com) 모양으로 결제 예약(`v2/reserve`) · 승인(`apply/payment`) · 취소(`cancel`) · 결제 내역 조회(`list/history/{paymentId}`)에 답한다. 시험 소스에만 있다.
 * 결제창은 구매자 브라우저가 `/payments/{reserveId}` 로 가는 것이라 서버 호출이 아니다 - [buyerPays] 가 그 결과(예약한 값 그대로의 결제 + 돌려받은 `paymentId`)를 흉내 낸다. 금액 · 주문 번호는 서버가 예약한 값이라 구매자가 바꿀 수 없다.
 *
 * **엄격하다** - 진짜 네이버페이가 거절할 일을 이 서버도 거절해, 모듈이 그 거절을 만나도 올바르게 구는지 본다:
 * - 인증 헤더 셋(`X-Naver-Client-Id` · `X-Naver-Client-Secret` · `X-NaverPay-Chain-Id`)이 하나라도 틀리면 `401`(본문 없음).
 * - 승인 · 취소는 폼(`application/x-www-form-urlencoded`)이고 **멱등 키 헤더가 없으면 `400`**. 같은 멱등 키의 재요청은 적용하지 않고 `409` + 처음 응답을 그대로 돌려준다(문서의 약속).
 * - 예약: 필수 칸이 빠지거나 과세 + 면세가 총액과 다르거나 총액이 10원 미만이거나 상품 목록이 비면 `InvalidParameter`.
 * - 승인: 모르는 결제 번호 `InvalidMerchant` · 이미 승인된 결제 `AlreadyComplete`. 업무 오류는 HTTP 200 + `code`(문서의 결과 코드 표를 그렇게 읽었다 - 진짜 상태는 못 잰 가정).
 * - 취소: 남은 금액을 넘으면 `OverRemainAmount` · 과세 + 면세가 취소 금액과 다르면 `TaxScopeAmountError` · 승인되지 않은 결제는 `InvalidPaymentId`.
 * - 실패 지시([failNext]): [Fail.DECLINE] 적용하지 않고 `Fail` · [Fail.DELAY] 응답을 붙잡는다(적용 안 함) · [Fail.LOSE] **적용한 뒤** 연결을 끊는다(응답 유실) · [Fail.ERROR] 적용 안 하고 500.
 * - **루프백(127.0.0.1)에만 묶는다** - 와일드카드 바인딩은 다른 프로세스에 연결이 가로채인다.
 * 받은 요청은 전부 [requests] 에 남는다(헤더 이름은 소문자). 응답에는 이 모듈이 읽지 않는 칸에 개인정보 모양의 값을 심는다([PLANTED]).
 */
internal class FakeNaverPayServer(
    val clientId: String = "FAKECLIENTID0123456789",
    val clientSecret: String = "FAKE_CLIENT_SECRET_0123456789abcdef",
    val chainId: String = "FAKECHAINID0123",
) : AutoCloseable {
    data class Recorded(val method: String, val path: String, val headers: Map<String, List<String>>, val body: String) {
        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

        /** 폼 본문을 이름 → 값으로. */
        val form: Map<String, String>
            get() = body.split('&').filter { it.isNotEmpty() }.associate {
                val (name, value) = it.split('=', limit = 2).let { parts -> parts[0] to parts.getOrElse(1) { "" } }
                URLDecoder.decode(name, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
    }

    /** [BALANCE] 잔액 부족(`NotEnoughAccountBalance`) - 결제가 일어나지 않은 게 분명한 거절 / [NOT_COMPLETE] 취소 응답 `CancelNotComplete`(적용하지 않는다) */
    enum class Fail { DECLINE, DELAY, LOSE, ERROR, BALANCE, NOT_COMPLETE }

    /** 응답에 심는 값 - 이 모듈이 읽지 않는 칸에 들어 있다. 결과 · 예외 · 로그 어디에도 나오면 안 된다. */
    object PLANTED {
        const val USER_KEY = "buyer-planted-key"
        const val CARD_NO = "5365-****-****-planted"
        const val USER_IDENTIFIER = "t1GLYqqJ05planted="
        const val PRODUCT = "초코파이-planted"
        val ALL = listOf(USER_KEY, CARD_NO, USER_IDENTIFIER, PRODUCT)
    }

    /** 서버가 예약한 결제. */
    class Reservation(val reserveId: String, val merchantPayKey: String, val total: Long, val returnUrl: String)

    /** 구매자가 예약한 결제창에서 낸 결제. */
    class Payment(
        val paymentId: String,
        val merchantPayKey: String,
        val total: Long,
        var applied: Boolean = false,
        var appliedAt: String? = null,
        val cancels: MutableList<Pair<String, Long>> = mutableListOf(),
        var failed: Boolean = false,
    ) {
        val canceledTotal: Long get() = cancels.sumOf { it.second }
        val available: Long get() = if (applied) total - canceledTotal else 0
    }

    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val release = CountDownLatch(1)
    private val recorded = Collections.synchronizedList(mutableListOf<Recorded>())
    private val payments = Collections.synchronizedMap(linkedMapOf<String, Payment>())
    private val failures = Collections.synchronizedMap(mutableMapOf<String, ArrayDeque<Fail>>())
    private val replies = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val reservations = Collections.synchronizedMap(linkedMapOf<String, Reservation>())
    private val sequence = AtomicInteger()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
    val requests: List<Recorded> get() = synchronized(recorded) { recorded.toList() }

    fun paymentOf(paymentId: String): Payment? = payments[paymentId]

    fun reservations(): List<Reservation> = synchronized(reservations) { reservations.values.toList() }

    fun payments(): List<Payment> = synchronized(payments) { payments.values.toList() }

    /** 이 경로(`apply` · `cancel` · `history`)의 다음 호출 하나를 실패시킨다. */
    fun failNext(operation: String, fail: Fail) {
        failures.getOrPut(operation) { ArrayDeque() }.addLast(fail)
    }

    /** 구매자가 예약한 결제창(`/payments/{reserveId}`)에서 결제를 마쳤다 - 예약한 주문 번호 · 총액 그대로 결제가 만들어지고 결제 번호(`paymentId`)가 돌아온다(`returnUrl` 로 붙어 온다). */
    fun buyerPays(reserveId: String): String {
        val reservation = checkNotNull(reservations[reserveId]) { "no such reservation" }
        val paymentId = "2026" + "1017NP" + "%08d".format(sequence.incrementAndGet())
        payments[paymentId] = Payment(paymentId, reservation.merchantPayKey, reservation.total)
        return paymentId
    }

    fun clearRequests() = recorded.clear()

    fun release() = release.countDown()

    override fun close() {
        release.countDown()
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val headers = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.toList() }
        val request = Recorded(exchange.requestMethod, exchange.requestURI.path, headers, body)
        recorded += request

        val operation = operationOf(request.path)
        val reply: Reply = when {
            request.header("x-naver-client-id") != clientId || request.header("x-naver-client-secret") != clientSecret || request.header("x-naverpay-chain-id") != chainId -> Reply.Empty(401)
            operation == null || request.method != "POST" -> Reply.Empty(404)
            else -> failureOr(operation) { route(operation, request) }
        }
        send(exchange, reply)
    }

    private fun operationOf(path: String): String? =
        when {
            path.endsWith("/v2/reserve") -> "reserve"
            path.endsWith("/apply/payment") -> "apply"
            path.endsWith("/v1/cancel") -> "cancel"
            "/list/history/" in path -> "history"
            else -> null
        }

    private fun failureOr(operation: String, normal: () -> Reply): Reply {
        val fail = failures[operation]?.removeFirstOrNull() ?: return normal()
        return when (fail) {
            Fail.DECLINE -> envelope("Fail", "PG failure ${PLANTED.USER_KEY}", null)
            Fail.DELAY -> Reply.Hang
            Fail.LOSE -> normal().also { return Reply.Drop }
            Fail.ERROR -> Reply.Empty(500)
            Fail.BALANCE -> envelope("NotEnoughAccountBalance", "balance", null)
            Fail.NOT_COMPLETE -> envelope("CancelNotComplete", "retry later", null)
        }
    }

    private fun route(operation: String, request: Recorded): Reply {
        if (operation == "history") return history(request.path.substringAfterLast('/'))
        // 승인 · 취소는 폼 + 멱등 키 - 같은 키의 재요청은 적용하지 않고 처음 응답을 409 로 돌려준다
        val key = request.header("x-naverpay-idempotency-key")
        if (key.isNullOrBlank() || key.length > 64 || !Regex("[A-Za-z0-9_-]+").matches(key)) return Reply.Empty(400)
        replies["$operation/$key"]?.let { return Reply.Json(409, it) }
        val reply = when (operation) {
            "reserve" -> reserve(request.body)
            "apply" -> apply(request.form)
            else -> cancel(request.form)
        }
        if (reply is Reply.Json && reply.status == 200) replies["$operation/$key"] = reply.body
        return reply
    }

    private fun reserve(body: String): Reply {
        val node = runCatching { mapper.readTree(body) }.getOrNull()
        val merchantPayKey = node?.get("merchantPayKey")?.takeIf { it.isString }?.asString()
        val total = node?.get("totalPayAmount")?.takeIf { it.isIntegralNumber }?.longValue()
        val taxed = node?.get("taxScopeAmount")?.takeIf { it.isIntegralNumber }?.longValue()
        val exempt = node?.get("taxExScopeAmount")?.takeIf { it.isIntegralNumber }?.longValue()
        val returnUrl = node?.get("returnUrl")?.takeIf { it.isString }?.asString()
        val items = node?.get("productItems")
        if (merchantPayKey.isNullOrBlank() || merchantPayKey.length > 64 || total == null || total < 10 || taxed == null || exempt == null || taxed + exempt != total ||
            returnUrl.isNullOrBlank() || node.get("productName")?.isString != true || node.get("productCount")?.isIntegralNumber != true || items == null || !items.isArray || items.size() == 0
        ) {
            return envelope("InvalidParameter", "reserve", null)
        }
        val reserveId = "RSV%08d".format(sequence.incrementAndGet())
        reservations[reserveId] = Reservation(reserveId, merchantPayKey, total, returnUrl)
        return envelope("Success", "", linkedMapOf("reserveId" to reserveId))
    }

    private fun apply(form: Map<String, String>): Reply {
        val payment = payments[form["paymentId"]] ?: return envelope("InvalidMerchant", "unknown payment", null)
        synchronized(payment) {
            if (payment.applied) return envelope("AlreadyComplete", "already", null)
            payment.applied = true
            payment.appliedAt = "20261017151722"
        }
        val detail = linkedMapOf<String, Any?>(
            "paymentId" to payment.paymentId,
            "payHistId" to "H${payment.paymentId.takeLast(12)}",
            "merchantName" to "가맹점",
            "merchantId" to "loginId",
            "merchantPayKey" to payment.merchantPayKey,
            "merchantUserKey" to PLANTED.USER_KEY,
            "admissionTypeCode" to "01",
            "admissionYmdt" to payment.appliedAt,
            "tradeConfirmYmdt" to payment.appliedAt,
            "admissionState" to "SUCCESS",
            "totalPayAmount" to payment.total,
            "applyPayAmount" to payment.total,
            "primaryPayAmount" to payment.total,
            "npointPayAmount" to 0,
            "giftCardAmount" to 0,
            "discountPayAmount" to 0,
            "taxScopeAmount" to payment.total,
            "taxExScopeAmount" to 0,
            "environmentDepositAmount" to 0,
            "primaryPayMeans" to "CARD",
            "cardCorpCode" to "C0",
            "cardNo" to PLANTED.CARD_NO,
            "cardAuthNo" to "17545616",
            "cardInstCount" to 0,
            "usedCardPoint" to false,
            "bankCorpCode" to "",
            "bankAccountNo" to "",
            "productName" to PLANTED.PRODUCT,
            "settleExpected" to false,
            "settleExpectAmount" to 0,
            "payCommissionAmount" to 0,
            "userIdentifier" to PLANTED.USER_IDENTIFIER,
            "extraDeduction" to false,
            "useCfmYmdt" to "20261017",
        )
        return envelope("Success", "", linkedMapOf("paymentId" to payment.paymentId, "detail" to detail))
    }

    private fun cancel(form: Map<String, String>): Reply {
        val payment = payments[form["paymentId"]]?.takeIf { it.applied } ?: return envelope("InvalidPaymentId", "not applied", null)
        val asked = form["cancelAmount"]?.toLongOrNull() ?: return envelope("RequireParameter", "cancelAmount", null)
        val taxed = form["taxScopeAmount"]?.toLongOrNull()
        val exempt = form["taxExScopeAmount"]?.toLongOrNull()
        if (form["cancelReason"].isNullOrBlank() || form["cancelRequester"] !in setOf("1", "2")) return envelope("RequireParameter", "cancelReason", null)
        if (taxed == null || exempt == null || taxed + exempt != asked) return envelope("TaxScopeAmountError", "tax", null)
        synchronized(payment) {
            if (asked <= 0 || asked > payment.available) return envelope(if (payment.available == 0L) "AlreadyCanceled" else "OverRemainAmount", "rest", null)
            payment.cancels += "C${sequence.incrementAndGet()}".padEnd(14, '0') to asked
        }
        val rest = payment.available
        return envelope(
            "Success", "",
            linkedMapOf(
                "paymentId" to payment.paymentId,
                "payHistId" to payment.cancels.last().first,
                "primaryPayMeans" to "CARD",
                "primaryPayCancelAmount" to asked,
                "primaryPayRestAmount" to rest,
                "npointCancelAmount" to 0,
                "npointRestAmount" to 0,
                "giftCardCancelAmount" to 0,
                "giftCardRestAmount" to 0,
                "discountCancelAmount" to 0,
                "discountRestAmount" to 0,
                "taxScopeAmount" to asked,
                "taxExScopeAmount" to 0,
                "taxScopeRestAmount" to rest,
                "taxExScopeRestAmount" to 0,
                "environmentDepositAmount" to 0,
                "environmentDepositRestAmount" to 0,
                "cancelYmdt" to "20261017152500",
                "totalRestAmount" to rest,
                "applyRestAmount" to rest,
            ),
        )
    }

    private fun history(paymentId: String): Reply {
        val payment = payments[paymentId] ?: return envelope("Success", "", linkedMapOf("list" to emptyList<Any>(), "totalCount" to 0, "responseCount" to 0, "totalPageCount" to 0, "currentPageNumber" to 1))
        val entries = buildList<Map<String, Any?>> {
            if (payment.applied) add(historyEntry(payment, "H${payment.paymentId.takeLast(12)}", "01", "SUCCESS", payment.total, payment.appliedAt!!))
            payment.cancels.forEach { (id, value) -> add(historyEntry(payment, id, if (value == payment.total) "03" else "04", "SUCCESS", value, "20261017152500")) }
            if (payment.failed) add(historyEntry(payment, "HF${payment.paymentId.takeLast(11)}", "01", "FAIL", payment.total, "20261017151800"))
        }
        return envelope("Success", "", linkedMapOf("list" to entries, "totalCount" to entries.size, "responseCount" to entries.size, "totalPageCount" to 1, "currentPageNumber" to 1))
    }

    /** 결제 승인이 진짜 카드사에서 거절된 일을 흉내 낸다 - 조회에만 실패 이력이 남는다. */
    fun declinedAtIssuer(paymentId: String) {
        checkNotNull(payments[paymentId]).failed = true
    }

    private fun historyEntry(payment: Payment, histId: String, typeCode: String, state: String, amount: Long, at: String): Map<String, Any?> =
        linkedMapOf(
            "paymentId" to payment.paymentId,
            "payHistId" to histId,
            "admissionState" to state,
            "admissionTypeCode" to typeCode,
            "admissionYmdt" to at,
            "tradeConfirmYmdt" to at,
            "totalPayAmount" to amount,
            "applyPayAmount" to amount,
            "primaryPayAmount" to amount,
            "merchantPayKey" to payment.merchantPayKey,
            "merchantUserKey" to PLANTED.USER_KEY,
            "primaryPayMeans" to "CARD",
            "cardNo" to PLANTED.CARD_NO,
            "productName" to PLANTED.PRODUCT,
            "settleInfo" to mapOf("settleCreated" to false),
        )

    private sealed interface Reply {
        data class Json(val status: Int, val body: String) : Reply

        data class Empty(val status: Int) : Reply

        data object Drop : Reply

        data object Hang : Reply
    }

    private fun envelope(code: String, message: String, body: Any?): Reply.Json = Reply.Json(200, mapper.writeValueAsString(linkedMapOf("code" to code, "message" to message, "body" to body)))

    private fun send(exchange: HttpExchange, reply: Reply) {
        when (reply) {
            is Reply.Json -> {
                val bytes = reply.body.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json;charset=UTF-8")
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            is Reply.Empty -> exchange.sendResponseHeaders(reply.status, -1)
            Reply.Drop -> exchange.close()
            Reply.Hang -> {
                release.await(HANG_LIMIT_SECONDS, TimeUnit.SECONDS)
                runCatching { exchange.close() }
            }
        }
    }

    private companion object {
        const val HANG_LIMIT_SECONDS = 30L
    }
}
