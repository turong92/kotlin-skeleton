package dev.sumin.skeleton.payment.kakaopay.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 가짜 카카오페이 HTTP 서버 (JDK `HttpServer`) - 개발자센터 문서 모양의 JSON 으로 `ready` · `approve` · `cancel` · `order` 에 답한다. 시험 소스에만 있다(`main` 에 두지 않는다).
 *
 * **엄격하다** - 진짜 카카오페이가 거절할 일을 이 서버도 거절해, 모듈이 그 거절을 만나도 올바르게 구는지 본다:
 * - `Authorization: SECRET_KEY <키>` 가 틀리면 `401 -401`. 가맹점 코드(`cid`)가 다르면 `400 -400`. 필수 칸이 빠지면 `400 -400`.
 * - `approve`: 구매자가 인증을 마친 결제([userApproves])의 `pg_token` 이 아니면, 주문 번호 · 회원 id 가 `ready` 와 다르면, **총액이 `ready` 와 다르면**, 이미 승인된 결제면 `400 -780`.
 * - `cancel`: **남은 취소 가능 금액을 넘으면** `400 -781`. 승인되지 않은 결제도 `-781`.
 * - 실패 지시([failNext]): [Fail.DECLINE] 승인 · 취소를 적용하지 않고 `-780`/`-781` · [Fail.DELAY] 응답을 붙잡는다(적용 안 함) · [Fail.LOSE] **적용한 뒤** 연결을 끊는다(응답 유실) · [Fail.ERROR] 적용 안 하고 500.
 * - **루프백(127.0.0.1)에만 묶는다** - 와일드카드 바인딩은 다른 프로세스에 연결이 가로채인다(`TestServerRulesTest`).
 * 받은 요청은 전부 [requests] 에 남는다(헤더 이름은 소문자). 응답에는 이 모듈이 읽지 않는 칸에 개인정보 모양의 값을 심는다([PLANTED]).
 */
internal class FakeKakaoPayServer(
    val secretKey: String = "DEV_FAKE_SECRET_KEY_0123456789abcdef",
    val cid: String = "TC0ONETIME",
    /** 정기 결제 가맹점 코드 - 이 코드로 `ready` · `approve` 한 결제는 1회차 승인에서 SID 를 주고, `subscription`(SID 과금) · `inactive` · `status` 가 열린다. */
    val subscriptionCid: String = "TCSUBSCRIP",
) : AutoCloseable {
    data class Recorded(val method: String, val path: String, val headers: Map<String, List<String>>, val body: String) {
        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    }

    enum class Fail { DECLINE, DELAY, LOSE, ERROR, REJECT_REQUEST }

    /** 응답에 심는 값 - 이 모듈이 읽지 않는 칸에 들어 있다. 결과 · 예외 · 로그 어디에도 나오면 안 된다. */
    object PLANTED {
        const val NAME = "홍길동"
        const val CARD_BIN = "621640"
        const val ISSUER = "수협은행"
        const val ITEM = "초코파이-planted"
        val ALL = listOf(NAME, CARD_BIN, ISSUER, ITEM)
    }

    class Payment(
        val tid: String,
        val partnerOrderId: String,
        val partnerUserId: String,
        val total: Long,
        val itemName: String,
        val approvalUrl: String,
        val cid: String,
        var status: String = "READY",
        var pgToken: String? = null,
        var approvedAt: String? = null,
        val cancels: MutableList<Pair<String, Long>> = mutableListOf(),
    ) {
        val canceledTotal: Long get() = cancels.sumOf { it.second }
        val available: Long get() = if (status == "SUCCESS_PAYMENT" || status == "PART_CANCEL_PAYMENT") total - canceledTotal else 0
    }

    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val release = CountDownLatch(1)
    private val recorded = Collections.synchronizedList(mutableListOf<Recorded>())
    private val payments = Collections.synchronizedMap(linkedMapOf<String, Payment>())
    private val failures = Collections.synchronizedMap(mutableMapOf<String, ArrayDeque<Fail>>())
    private val sequence = AtomicInteger()

    /** 발급한 SID → 활성 여부. */
    private val sids = Collections.synchronizedMap(linkedMapOf<String, Boolean>())
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
    val requests: List<Recorded> get() = synchronized(recorded) { recorded.toList() }

    fun paymentOf(tid: String): Payment? = payments[tid]

    /** 발급된 SID 들(활성 여부 포함) - 시험이 이어서 과금 · 비활성을 본다. */
    fun sidsIssued(): Map<String, Boolean> = synchronized(sids) { LinkedHashMap(sids) }

    fun payments(): List<Payment> = synchronized(payments) { payments.values.toList() }

    /** 이 경로(`approve` · `cancel` …)의 다음 호출 하나를 실패시킨다. */
    fun failNext(operation: String, fail: Fail) {
        failures.getOrPut(operation) { ArrayDeque() }.addLast(fail)
    }

    /** 구매자가 카카오톡에서 인증을 마쳤다 - 이 결제의 `pg_token` 을 돌려준다(진짜는 `approval_url` 에 붙어 돌아온다). */
    fun userApproves(tid: String): String {
        val payment = checkNotNull(payments[tid]) { "no such payment" }
        payment.pgToken = "pgtok_${payment.tid.takeLast(8)}_${sequence.incrementAndGet()}"
        if (payment.status == "READY") payment.status = "AUTH_PASSWORD"
        return checkNotNull(payment.pgToken)
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

        val node: JsonNode? = runCatching { mapper.readTree(body) }.getOrNull()
        val operation = request.path.substringAfterLast('/')
        val reply: Reply = when {
            request.header("authorization") != "SECRET_KEY $secretKey" -> error(401, -401, "unauthorized")
            request.method != "POST" || node == null || !node.isObject -> error(400, -400, "bad request")
            node.text("cid") != cid && node.text("cid") != subscriptionCid -> error(400, -400, "bad cid")
            else -> failureOr(operation) { route(operation, node) }
        }
        send(exchange, reply)
    }

    private fun failureOr(operation: String, normal: () -> Reply): Reply {
        val fail = failures[operation]?.removeFirstOrNull() ?: return normal()
        return when (fail) {
            Fail.DECLINE -> error(400, when (operation) { "cancel" -> -781; "subscription" -> -782; else -> -780 }, "failure!", "USER_LOCKED")
            Fail.DELAY -> Reply.Hang
            Fail.LOSE -> normal().also { return Reply.Drop }
            Fail.ERROR -> error(500, -500, "internal")
            // 진짜 카카오페이가 도메인 미등록으로 `ready` 를 거절할 때의 모양 - 글에 비밀 · 이메일 · 긴 숫자가 섞여 와도 로그에서 가려지는지 본다
            Fail.REJECT_REQUEST -> json(400, mapOf("error_code" to -400, "error_message" to REJECT_MESSAGE))
        }
    }

    private fun route(operation: String, node: JsonNode): Reply =
        when (operation) {
            "ready" -> ready(node)
            "approve" -> approve(node)
            "cancel" -> cancel(node)
            "order" -> order(node)
            "subscription" -> subscriptionCharge(node)
            "inactive" -> sidState(node, deactivate = true)
            "status" -> sidState(node, deactivate = false)
            else -> error(404, -404, "no such function")
        }

    private fun ready(node: JsonNode): Reply {
        val required = listOf("partner_order_id", "partner_user_id", "item_name", "approval_url", "cancel_url", "fail_url")
        if (required.any { node.text(it).isNullOrBlank() } || node.long("total_amount") == null || node.long("quantity") == null || node.long("tax_free_amount") == null) return error(400, -400, "missing field")
        if (node.long("total_amount")!! <= 0) return error(400, -400, "bad amount")
        val tid = "T" + "%019d".format(sequence.incrementAndGet())
        payments[tid] = Payment(tid, node.text("partner_order_id")!!, node.text("partner_user_id")!!, node.long("total_amount")!!, node.text("item_name")!!, node.text("approval_url")!!, node.text("cid")!!)
        return json(
            200,
            mapOf(
                "tid" to tid,
                "next_redirect_app_url" to "$baseUrl/pay/$tid/aInfo",
                "next_redirect_mobile_url" to "$baseUrl/pay/$tid/mInfo",
                "next_redirect_pc_url" to "$baseUrl/pay/$tid/info",
                "created_at" to "2026-10-16T21:18:22",
            ),
        )
    }

    private fun approve(node: JsonNode): Reply {
        val payment = payments[node.text("tid")]?.takeIf { it.cid == node.text("cid") } ?: return error(400, -780, "approval failure!", "NOT_FOUND")
        val matches = node.text("partner_order_id") == payment.partnerOrderId && node.text("partner_user_id") == payment.partnerUserId
        val token = node.text("pg_token")
        val amountOk = node.long("total_amount").let { it == null || it == payment.total }
        synchronized(payment) {
            if (!matches || token.isNullOrBlank() || token != payment.pgToken || !amountOk || payment.status == "SUCCESS_PAYMENT" || payment.status == "PART_CANCEL_PAYMENT" || payment.status == "CANCEL_PAYMENT") {
                return error(400, -780, "approval failure!", if (payment.status == "SUCCESS_PAYMENT") "ALREADY_APPROVED" else "USER_LOCKED")
            }
            payment.status = "SUCCESS_PAYMENT"
            payment.approvedAt = "2026-10-16T21:19:30"
        }
        return json(
            200,
            linkedMapOf<String, Any?>(
                "aid" to "A${payment.tid.takeLast(19)}",
                "tid" to payment.tid,
                "cid" to payment.cid,
                "sid" to if (payment.cid == subscriptionCid) newSid() else null,
                "partner_order_id" to payment.partnerOrderId,
                "partner_user_id" to payment.partnerUserId,
                "payment_method_type" to "CARD",
                "item_name" to payment.itemName + PLANTED.ITEM,
                "quantity" to 1,
                "amount" to amount(payment.total),
                "card_info" to mapOf("bin" to PLANTED.CARD_BIN, "kakaopay_issuer_corp" to PLANTED.ISSUER, "kakaopay_issuer_corp_code" to ISSUER_CODE, "card_type" to "체크", "approved_id" to "12345678"),
                "created_at" to "2026-10-16T21:18:22",
                "approved_at" to payment.approvedAt,
                "buyer_name" to PLANTED.NAME,
            ),
        )
    }

    private fun cancel(node: JsonNode): Reply {
        val payment = payments[node.text("tid")]?.takeIf { it.cid == node.text("cid") } ?: return error(400, -781, "cancel failure!", "6666")
        val asked = node.long("cancel_amount") ?: return error(400, -400, "missing field")
        synchronized(payment) {
            if (asked <= 0 || asked > payment.available) return error(400, -781, "cancel failure!", "6666")
            payment.cancels += "C${sequence.incrementAndGet()}".padEnd(20, '0') to asked
            payment.status = if (payment.available == 0L) "CANCEL_PAYMENT" else "PART_CANCEL_PAYMENT"
        }
        return json(
            200,
            linkedMapOf<String, Any?>(
                "aid" to payment.cancels.last().first,
                "tid" to payment.tid,
                "cid" to payment.cid,
                "status" to payment.status,
                "partner_order_id" to payment.partnerOrderId,
                "partner_user_id" to payment.partnerUserId,
                "payment_method_type" to "CARD",
                "amount" to amount(payment.total),
                "approved_cancel_amount" to amount(asked),
                "canceled_amount" to amount(payment.canceledTotal),
                "cancel_available_amount" to amount(payment.available),
                "created_at" to "2026-10-16T21:18:22",
                "approved_at" to payment.approvedAt,
                "canceled_at" to "2026-10-16T21:25:00",
                "buyer_name" to PLANTED.NAME,
            ),
        )
    }

    private fun order(node: JsonNode): Reply {
        val payment = payments[node.text("tid")]?.takeIf { it.cid == node.text("cid") } ?: return error(400, -780, "not found", "NOT_FOUND")
        val actions = buildList<Map<String, Any?>> {
            if (payment.approvedAt != null) add(linkedMapOf("aid" to "A${payment.tid.takeLast(19)}", "payment_action_type" to "PAYMENT", "amount" to payment.total, "approved_at" to payment.approvedAt))
            payment.cancels.forEach { (aid, value) -> add(linkedMapOf("aid" to aid, "payment_action_type" to "CANCEL", "amount" to value, "approved_at" to "2026-10-16T21:25:00")) }
        }
        return json(
            200,
            linkedMapOf<String, Any?>(
                "tid" to payment.tid,
                "cid" to payment.cid,
                "status" to payment.status,
                "partner_order_id" to payment.partnerOrderId,
                "partner_user_id" to payment.partnerUserId,
                "payment_method_type" to "CARD",
                "item_name" to payment.itemName + PLANTED.ITEM,
                "quantity" to 1,
                "amount" to amount(payment.total),
                "canceled_amount" to amount(payment.canceledTotal),
                "cancel_available_amount" to amount(payment.available),
                "created_at" to "2026-10-16T21:18:22",
                "approved_at" to payment.approvedAt,
                "selected_card_info" to mapOf("card_bin" to PLANTED.CARD_BIN, "card_corp_name" to PLANTED.ISSUER),
                "payment_action_details" to actions,
            ),
        )
    }

    private fun newSid(): String = "SID${"%08d".format(sequence.incrementAndGet())}".also { sids[it] = true }

    /** `subscription` - 활성 SID 로 새 결제를 만든다(인증 창 없음). 같은 주문 번호가 이미 있으면 -782(중복 과금 방어). */
    private fun subscriptionCharge(node: JsonNode): Reply {
        val sid = node.text("sid")
        val required = listOf("partner_order_id", "partner_user_id", "item_name")
        if (node.text("cid") != subscriptionCid || required.any { node.text(it).isNullOrBlank() } || node.long("total_amount") == null || node.long("quantity") == null || node.long("tax_free_amount") == null) return error(400, -400, "missing field")
        if (sid == null || sids[sid] != true) return error(400, -782, "subscription failure!", "INACTIVE_SID")
        val orderId = node.text("partner_order_id")!!
        if (payments.values.any { it.partnerOrderId == orderId }) return error(400, -782, "subscription failure!", "DUPLICATE_ORDER")
        val tid = "T" + "%019d".format(sequence.incrementAndGet())
        val payment = Payment(tid, orderId, node.text("partner_user_id")!!, node.long("total_amount")!!, node.text("item_name")!!, "", subscriptionCid, "SUCCESS_PAYMENT", approvedAt = "2026-11-16T21:19:30")
        payments[tid] = payment
        return json(
            200,
            linkedMapOf<String, Any?>(
                "aid" to "A${tid.takeLast(19)}",
                "tid" to tid,
                "cid" to subscriptionCid,
                "sid" to sid,
                "partner_order_id" to orderId,
                "partner_user_id" to payment.partnerUserId,
                "payment_method_type" to "CARD",
                "item_name" to payment.itemName + PLANTED.ITEM,
                "quantity" to 1,
                "amount" to amount(payment.total),
                "created_at" to "2026-11-16T21:19:29",
                "approved_at" to payment.approvedAt,
            ),
        )
    }

    /** `inactive`(비활성) · `status`(조회). 이미 비활성인 SID 의 `inactive` 는 오류(진짜도 그렇다) - 제공자가 `status` 로 확인해 멱등으로 읽는지 본다. */
    private fun sidState(node: JsonNode, deactivate: Boolean): Reply {
        val sid = node.text("sid")
        if (node.text("cid") != subscriptionCid) return error(400, -400, "bad cid")
        val active = sid?.let { sids[it] } ?: return error(400, -400, "unknown sid")
        if (deactivate) {
            if (!active) return error(400, -780, "already inactive")
            sids[sid] = false
        }
        return json(200, linkedMapOf<String, Any?>("available" to (sids[sid] == true), "status" to if (sids[sid] == true) "ACTIVE" else "INACTIVE", "created_at" to "2026-10-16T21:18:22"))
    }

    private fun amount(total: Long): Map<String, Any?> = linkedMapOf("total" to total, "tax_free" to 0, "vat" to total / 11, "point" to 0, "discount" to 0, "green_deposit" to 0)

    private sealed interface Reply {
        data class Json(val status: Int, val body: String) : Reply

        data object Drop : Reply

        data object Hang : Reply
    }

    private fun json(status: Int, value: Any?): Reply = Reply.Json(status, mapper.writeValueAsString(value))

    private fun error(status: Int, code: Int, message: String, methodResult: String? = null): Reply =
        json(status, linkedMapOf<String, Any?>("error_code" to code, "error_message" to "$message ${PLANTED.NAME}", "extras" to methodResult?.let { mapOf("method_result_code" to it, "method_result_message" to "${PLANTED.NAME} 님 $it") }))

    private fun send(exchange: HttpExchange, reply: Reply) {
        when (reply) {
            is Reply.Json -> {
                val bytes = reply.body.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json;charset=UTF-8")
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            Reply.Drop -> exchange.close()
            Reply.Hang -> {
                release.await(HANG_LIMIT_SECONDS, TimeUnit.SECONDS)
                runCatching { exchange.close() }
            }
        }
    }

    private fun JsonNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.asString()

    private fun JsonNode.long(name: String): Long? = get(name)?.takeIf { it.isIntegralNumber }?.longValue()

    private companion object {
        const val HANG_LIMIT_SECONDS = 30L
        const val ISSUER_CODE = "SHINHAN"
        const val REJECT_MESSAGE = "등록된 도메인(플랫폼 > Web > 사이트도메인)이 존재하지 않아 요청을 처리할 수 없습니다. secret_key=LEAKME-SECRET owner@example.com 01012345678"
    }
}
