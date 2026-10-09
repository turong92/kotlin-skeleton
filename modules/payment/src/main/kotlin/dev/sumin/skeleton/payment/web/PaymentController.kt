package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentOrders
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentQueries
import dev.sumin.skeleton.payment.PaymentRefunds
import dev.sumin.skeleton.payment.web.PaymentRequests.idempotencyKey
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/**
 * 결제 사용자 경로(스펙 §7 · `docs/payment-http-contract.md`). **HTTP 변환만 한다** — 규칙은 [PaymentOrders] · [PaymentRefunds] · [PaymentQueries].
 * 이 클래스에 `@Transactional` 을 달지 않는다: 승인 · 환불은 외부 호출이라 바깥 트랜잭션 안에서 부르면 서비스가 던진다(시험으로 고정).
 * 모든 경로는 `account_id = 호출자` 로 좁힌다 — 남의 것과 없는 것은 같은 404 다.
 */
@RestController
@RequestMapping("\${skeleton.payment.http.base-path:/api/v1/payments}")
@Tag(name = "Payment")
class PaymentController(
    private val orders: PaymentOrders,
    private val refunds: PaymentRefunds,
    private val queries: PaymentQueries,
    private val router: PaymentProviderRouter,
    private val catalog: ObjectProvider<PaymentCatalog>,
    private val properties: PaymentProperties,
    private val callers: PaymentCallers,
) {
    @Operation(summary = "Can payments be taken", description = "available is false without a catalog, without a provider that opens a checkout, or when that provider cannot hand out the values for its window (a missing or mismatched public key). testMode is true while test keys are in.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = PaymentConfigResponse::class)
    @GetMapping("/config")
    fun config(authentication: Authentication?): DataResponse<PaymentConfigResponse> {
        callers.account(authentication)
        val checkout = orders.openableCheckout(null, null)   // 주문 만들기와 같은 고르기 · 같은 판정(결제창 값을 낼 수 있는가)
        val available = catalog.getIfAvailable() != null && checkout != null
        return Response.ok(PaymentConfigResponse(available, checkout != null && available && checkout.mode == PaymentMode.TEST, properties.refund.requestsEnabled))
    }

    @Operation(summary = "Confirm a payment returned from the checkout window", description = "200 PAID (also a resend with the same payment key) · 202 CONFIRMING (the provider has not answered — poll GET /orders/{id}) · 400 AMOUNT_MISMATCH before the provider is called · 402 DECLINED (data.providerCode, data.category).")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = OrderResponse::class)
    @PostMapping("/orders/{id}/confirm")
    fun confirm(authentication: Authentication?, @PathVariable id: String, @RequestBody body: JsonNode): ResponseEntity<DataResponse<OrderResponse>> {
        val account = callers.account(authentication)
        val paymentKey = PaymentRequests.text(body["providerPaymentId"], "providerPaymentId", 200)
        val amount = PaymentRequests.requiredWholeNumber(body["amount"], "amount")
        val current = orders.get(account, id)
        if (amount < 0) throw dev.sumin.skeleton.common.FieldValidationException("amount", "Min", "amount must not be negative")
        val order = orders.confirm(account, id, paymentKey, PaymentAmount(amount, current.amount.currency))
        val response = Response.ok(OrderResponse.of(refunds.get(account, order.id).let { dev.sumin.skeleton.payment.OrderView(it.order, it.refunds) }))
        return if (order.status == PaymentOrderStatus.CONFIRMING) ResponseEntity.accepted().body(response) else ResponseEntity.ok(response)
    }

    @Operation(summary = "The buyer left the checkout window", description = "PENDING becomes CANCELED. An order that is already over is returned as it is.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = OrderResponse::class)
    @PostMapping("/orders/{id}/abandon")
    fun abandon(authentication: Authentication?, @PathVariable id: String): DataResponse<OrderResponse> {
        val account = callers.account(authentication)
        val order = orders.abandon(account, id)
        return Response.ok(OrderResponse.of(view(account, order.id)))
    }

    @Operation(summary = "One of my payments with its refund lines", description = "Also an attempt that was never paid (PENDING). The result screen polls this while CONFIRMING.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = OrderDetailResponse::class)
    @GetMapping("/orders/{id}")
    fun get(authentication: Authentication?, @PathVariable id: String): DataResponse<OrderDetailResponse> =
        Response.ok(OrderDetailResponse.of(view(callers.account(authentication), id)))

    @Operation(summary = "My payments, newest first", description = "Attempts that were never paid (PENDING) are left out. Cursor paging: pass the previous cursor.nextCursor.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.CURSOR, value = OrderResponse::class)
    @GetMapping("/orders")
    fun list(authentication: Authentication?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) size: Int?): CursorResponse<OrderResponse> {
        val page = queries.myOrders(callers.account(authentication), PaymentCursors.order(cursor), PaymentRequests.size(size))
        return Response.cursor(page.values.map(OrderResponse::of), page.next?.let(PaymentCursors::order))
    }

    @Operation(summary = "Ask for a refund", description = "Only a request — the money does not move until an operator decides. 201 REQUESTED; the same Idempotency-Key again is 200 with the same line.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = RefundResponse::class)
    @PostMapping("/orders/{id}/refund-requests")
    fun requestRefund(
        authentication: Authentication?,
        @PathVariable id: String,
        @Parameter(name = "Idempotency-Key", required = true) @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        @RequestBody body: JsonNode,
    ): ResponseEntity<DataResponse<RefundResponse>> {
        val account = callers.account(authentication)
        val reason = PaymentRequests.text(body["reasonCode"], "reasonCode", 40)
        val amount = PaymentRequests.wholeNumber(body["amount"], "amount")
        val requested = refunds.request(account, id, reason, amount, idempotencyKey(key))
        val response = Response.ok(RefundResponse.of(requested.refund))
        return if (requested.replayed) ResponseEntity.ok(response) else ResponseEntity.status(201).body(response)
    }

    @Operation(summary = "Take my refund request back", description = "Only while it is REQUESTED.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = RefundResponse::class)
    @PostMapping("/refunds/{id}/withdraw")
    fun withdraw(authentication: Authentication?, @PathVariable id: String): DataResponse<RefundResponse> =
        Response.ok(RefundResponse.of(refunds.withdraw(callers.account(authentication), id)))

    private fun view(account: String, orderId: String): dev.sumin.skeleton.payment.OrderView =
        refunds.get(account, orderId).let { dev.sumin.skeleton.payment.OrderView(it.order, it.refunds) }
}
