package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.payment.OperatorOrderQuery
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentQueries
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentRefunds
import dev.sumin.skeleton.payment.PaymentWebhookInbox
import dev.sumin.skeleton.payment.PaymentWebhookStatus
import dev.sumin.skeleton.payment.RefundOutcome
import dev.sumin.skeleton.payment.web.PaymentOperatorInterceptor.Companion.OPERATOR_ATTRIBUTE
import dev.sumin.skeleton.payment.web.PaymentRequests.idempotencyKey
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/**
 * 결제 운영자 경로(`skeleton.payment.admin.enabled=true` 일 때만 — 꺼져 있으면 경로가 없어 404). 접근 규칙(역할 없음 403 · 익명 401)은 핸들러가 아니라 [PaymentOperatorInterceptor] 가 걸고, 인터셉터가 안 걸린 조립에서는 [requireOperator] 가 모든 핸들러를 닫는다 — 핸들러는 운영자 id 가 필요할 때 인터셉터가 실은 값을 `@RequestAttribute` 로 받는다.
 * **`@Transactional` 없음**: 환불 · 승인 · 맞추기 · 웹훅 다시 처리는 결제사를 부르거나 작업을 넣는 입구라 바깥 트랜잭션 안에서 부르면 서비스가 던진다.
 *
 * 환불 · 승인 경로의 `PAYMENT.*` 오류는 **이 호출이 `PENDING` 줄을 남기지 않았을 때만** 낸다 — 선점 전에 결제사를 못 찾는 실패는 서비스의 환불 전 확인 구간이 `503 UNAVAILABLE`("보내지 않았다")로 옮긴다.
 */
@RestController
@RequestMapping("\${skeleton.payment.admin.base-path:/api/v1/admin/payments}")
@Tag(name = "Payment admin")
class PaymentAdminController(
    private val queries: PaymentQueries,
    private val refunds: PaymentRefunds,
    private val inbox: PaymentWebhookInbox,
) {
    /**
     * 두 번째 잠금 — 인터셉터가 실은 운영자 속성이 없으면(인터셉터가 안 걸린 조립) **모든 핸들러**가 `403` 으로 닫힌다. `@ModelAttribute` 메서드는 핸들러의 인자(본문 · 쿼리)를 읽기 전에 돈다.
     * 속성을 `@RequestAttribute` 로 받으면 없을 때 바인딩 오류(400)가 나므로 직접 보고 같은 `FORBIDDEN` 으로 던진다. 값은 핸들러 인자와 같은 기준(`String`)으로 읽는다.
     * **`final`** — kotlin-spring(allopen)이 `@RestController` 의 멤버를 열어 두므로, 앱이 이 컨트롤러를 상속해 이 메서드를 재정의하면 둘째 잠금이 꺼질 수 있다.
     */
    @ModelAttribute
    final fun requireOperator(request: HttpServletRequest) {
        if (request.getAttribute(OPERATOR_ATTRIBUTE) as? String == null) throw ApplicationException("Operator role required", PlatformErrorCode.FORBIDDEN)
    }

    @Operation(
        summary = "Payments, newest first (operator)",
        description = "status · accountId filter; q is the exact order id or provider payment number; review=true lists what needs a person: rows with a review reason plus derived ones " +
            "(STALE_CONFIRMING, STALE_REFUND, unfulfilled paid, unapplied refund). Erased accounts' rows are hidden unless includeErased=true, a q lookup or review=true.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.CURSOR, value = AdminOrderResponse::class)
    @GetMapping("/orders")
    fun orders(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) review: Boolean?,
        @RequestParam(required = false) includeErased: Boolean?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): CursorResponse<AdminOrderResponse> {
        val page = queries.operatorOrders(
            OperatorOrderQuery(
                status = enumParam<PaymentOrderStatus>("status", status), accountId = accountId?.takeIf { it.isNotBlank() }, q = q, review = review == true,
                includeErased = includeErased == true, before = PaymentCursors.order(cursor), size = PaymentRequests.size(size),
            ),
        )
        return Response.cursor(page.values.map(AdminOrderResponse::of), page.next?.let(PaymentCursors::order))
    }

    @Operation(summary = "One payment with refunds and history, oldest first (operator)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AdminOrderDetailResponse::class)
    @GetMapping("/orders/{id}")
    fun order(@PathVariable id: String): DataResponse<AdminOrderDetailResponse> = Response.ok(AdminOrderDetailResponse.of(queries.operatorOrder(id)))

    @Operation(
        summary = "Refund now (operator)",
        description = "200 SUCCEEDED · 202 PENDING (the provider has not answered — a job settles it). The same Idempotency-Key again returns that line as it is now (200, even FAILED) without asking the provider. " +
            "402 REFUND_DECLINED (data.category, data.providerCode) · 409 REFUND_IN_PROGRESS / REFUND_NOT_ALLOWED / MODE_MISMATCH / IDEMPOTENCY_CONFLICT · 400 REFUND_AMOUNT_EXCEEDED, or VALIDATION_FAILED for an amount of 0 or less · 503 UNAVAILABLE when nothing was sent.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AdminRefundResponse::class)
    @PostMapping("/orders/{id}/refunds")
    fun refund(
        @RequestAttribute(OPERATOR_ATTRIBUTE) operator: String,
        @PathVariable id: String,
        @Parameter(name = "Idempotency-Key", required = true) @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        @RequestBody body: JsonNode,
    ): ResponseEntity<DataResponse<AdminRefundResponse>> {
        val reason = PaymentRequests.text(body["reasonCode"], "reasonCode", 40)
        val amount = PaymentRequests.wholeNumber(body["amount"], "amount")
        val endSubscription = PaymentRequests.flag(body["endSubscription"], "endSubscription", default = true)
        val idempotency = idempotencyKey(key)
        return outcome(refunds.refund(operator, id, reason, amount, idempotency, endSubscription))
    }

    @Operation(summary = "Refund requests, newest first (operator)", description = "status defaults to REQUESTED.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.CURSOR, value = AdminRefundResponse::class)
    @GetMapping("/refunds")
    fun refundList(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): CursorResponse<AdminRefundResponse> {
        val page = queries.operatorRefunds(enumParam<PaymentRefundStatus>("status", status) ?: PaymentRefundStatus.REQUESTED, PaymentCursors.refund(cursor), PaymentRequests.size(size))
        return Response.cursor(page.values.map { AdminRefundResponse.of(it, page.orders[it.orderId]) }, page.next?.let(PaymentCursors::refund))
    }

    @Operation(
        summary = "Approve a refund request, optionally with a corrected amount (operator)",
        description = "Optional body { amount, endSubscription } (amount in the smallest unit; endSubscription defaults to true): without amount the requested amount is refunded; with it 0 < amount <= min(requested, refundable) or 400 REFUND_AMOUNT_EXCEEDED before the provider is called. " +
            "endSubscription=false refunds without ending the subscription when a subscription charge is refunded in full. " +
            "The provider gets the corrected amount; the line shows it as amount and keeps the first one as requestedAmount. Same answers as refunding directly. No Idempotency-Key: the line's state is the guard — a second approval is 409 REFUND_NOT_ALLOWED.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AdminRefundResponse::class)
    @PostMapping("/refunds/{id}/approve")
    fun approve(@RequestAttribute(OPERATOR_ATTRIBUTE) operator: String, @PathVariable id: String, @RequestBody(required = false) body: JsonNode?): ResponseEntity<DataResponse<AdminRefundResponse>> =
        outcome(refunds.approve(operator, id, PaymentRequests.wholeNumber(body?.get("amount"), "amount"), PaymentRequests.flag(body?.get("endSubscription"), "endSubscription", default = true)))

    @Operation(summary = "Reject a refund request (operator)", description = "Only a REQUESTED line; an already decided one is 409 REFUND_NOT_ALLOWED.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AdminRefundResponse::class)
    @PostMapping("/refunds/{id}/reject")
    fun reject(@RequestAttribute(OPERATOR_ATTRIBUTE) operator: String, @PathVariable id: String): DataResponse<AdminRefundResponse> =
        Response.ok(refundResponse(refunds.reject(operator, id)))

    @Operation(summary = "Match the ledger with the provider (operator)", description = "Asks the provider and converges; returns the updated order. 404 PROVIDER_PAYMENT_NOT_FOUND when the provider does not know the payment (the ledger is left as it was) · 400 CAPABILITY_UNSUPPORTED · 409 MODE_MISMATCH · 502 PROVIDER_ERROR · 503 UNAVAILABLE.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AdminOrderResponse::class)
    @PostMapping("/orders/{id}/sync")
    fun sync(@RequestAttribute(OPERATOR_ATTRIBUTE) operator: String, @PathVariable id: String): DataResponse<AdminOrderResponse> {
        val order = refunds.sync(operator, id)
        return Response.ok(AdminOrderResponse.of(queries.operatorView(order)))
    }

    @Operation(
        summary = "Close a stuck subscription charge as not paid (operator)",
        description = "For a subscription charge the provider never answered (CONFIRMING). It asks the provider once more first: a payment that is found paid is settled as PAID and not closed; " +
            "otherwise the order becomes FAILED (OPERATOR_CLOSED, with the operator on the history) and the subscription's seat is freed. A provider that cannot be asked by order number (KakaoPay) cannot say a keyless order has no payment: " +
            "optional body { confirmedAtProvider: true } says the operator checked in the provider's own console - without it that case is 409 ORDER_CLOSE_NEEDS_CONFIRMATION and nothing is closed. 409 ORDER_NOT_CLOSABLE for any other order or while the provider is still processing · " +
            "404 · 409 MODE_MISMATCH · 502 PROVIDER_ERROR · 503 UNAVAILABLE (nothing is closed when the provider cannot be asked).",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AdminOrderResponse::class)
    @PostMapping("/orders/{id}/close")
    fun closeStuckCharge(@RequestAttribute(OPERATOR_ATTRIBUTE) operator: String, @PathVariable id: String, @RequestBody(required = false) body: JsonNode?): DataResponse<AdminOrderResponse> =
        Response.ok(
            AdminOrderResponse.of(
                queries.operatorView(refunds.closeStuckSubscriptionCharge(operator, id, PaymentRequests.flag(body?.get("confirmedAtProvider"), "confirmedAtProvider", default = false))),
            ),
        )

    @Operation(
        summary = "Webhook inbox, newest first (operator)",
        description = "No body is shown. A row with resourceType LIMIT_MARKER / eventType LIMIT_REACHED means this order's webhooks hit the per-order window limit and were deferred to one lookup at the end of the window. " +
            "Without status the list is by id; with a status it is by received time.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.CURSOR, value = WebhookEventResponse::class)
    @GetMapping("/webhook-events")
    fun webhookEvents(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): CursorResponse<WebhookEventResponse> {
        val page = inbox.list(enumParam<PaymentWebhookStatus>("status", status), PaymentCursors.webhook(cursor), PaymentRequests.size(size))
        return Response.cursor(page.values.map(WebhookEventResponse::of), page.next?.let(PaymentCursors::webhook))
    }

    @Operation(summary = "Process a webhook row again (operator)", description = "A row that is already PROCESSED comes back as it is (200, nothing happens). 404 WEBHOOK_EVENT_NOT_FOUND.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = WebhookEventResponse::class)
    @PostMapping("/webhook-events/{id}/retry")
    fun retry(@PathVariable id: Long): DataResponse<WebhookEventResponse> = Response.ok(WebhookEventResponse.of(inbox.retry(id)))

    /** 200 = 끝났거나 닫힌 줄(재전송의 FAILED · REJECTED 포함), 202 = 결제사의 답을 모른다(`PENDING`). */
    private fun outcome(outcome: RefundOutcome): ResponseEntity<DataResponse<AdminRefundResponse>> {
        val response = Response.ok(AdminRefundResponse.of(outcome.refund, queries.refundInfo(outcome.order)))
        return if (outcome.refund.status == PaymentRefundStatus.PENDING) ResponseEntity.accepted().body(response) else ResponseEntity.ok(response)
    }

    private fun refundResponse(refund: dev.sumin.skeleton.payment.PaymentRefund): AdminRefundResponse =
        AdminRefundResponse.of(refund, queries.refundInfo(queries.operatorOrder(refund.orderId).view.order))

    private inline fun <reified E : Enum<E>> enumParam(name: String, value: String?): E? {
        if (value.isNullOrBlank()) return null
        return enumValues<E>().firstOrNull { it.name == value } ?: throw FieldValidationException(name, "Pattern", "$name is not one of ${enumValues<E>().joinToString { it.name }}")
    }
}
