package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.payment.CreateOrderCommand
import dev.sumin.skeleton.payment.OrderView
import dev.sumin.skeleton.payment.PaymentOrders
import dev.sumin.skeleton.payment.web.PaymentRequests.idempotencyKey
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/**
 * 주문 만들기 — **앱이 [dev.sumin.skeleton.payment.PaymentCatalog] 빈을 둘 때만** 열린다(금액의 출처가 없으면 경로가 없다, 스펙 §4.2). 계정 · IP 별 요청 수 한도가 걸린다.
 * `@Transactional` 없음 — [PaymentController] 와 같다.
 */
@RestController
@RequestMapping("\${skeleton.payment.http.base-path:/api/v1/payments}")
@Tag(name = "Payment")
class PaymentOrderController(
    private val orders: PaymentOrders,
    private val callers: PaymentCallers,
    private val limiter: PaymentOrderRateLimiter,
    private val clientIps: ClientIps,
    private val routing: PaymentCountryRouting,
) {
    @Operation(
        summary = "Create an order and get the values for the checkout window",
        description = "201 with { order, checkout }; the same Idempotency-Key again is 200 with the same order (checkout is null once the order is no longer PENDING or its payment window has passed). " +
            "The buyer's country is decided by the server (see GET /config) and a body country is ignored; a body provider is taken only when it is one of the providers GET /config lists in choices for this country (more than one provider open for the same country), otherwise the server's own pick is used. 503 UNAVAILABLE when the provider of the country cannot open its window (no other provider is tried; a resend of an existing key still answers). " +
            "400 without the header · 409 IDEMPOTENCY_CONFLICT for another body · 422 ITEM_NOT_PURCHASABLE · 429 over the per-account / per-IP limit · 503 UNAVAILABLE.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = CreateOrderResponse::class)
    @PostMapping("/orders")
    fun create(
        authentication: Authentication?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @Parameter(name = "Idempotency-Key", required = true) @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        @RequestBody body: JsonNode,
    ): ResponseEntity<DataResponse<CreateOrderResponse>> {
        val account = callers.account(authentication)
        limiter.acquire(account, clientIps.of(request).limitKey)?.let { retryAfter ->
            response.setHeader("Retry-After", retryAfter.toString())
            throw ApplicationException(message = "Too many requests", errorCode = PlatformErrorCode.TOO_MANY_REQUESTS, data = mapOf("retryAfterSeconds" to retryAfter))
        }
        val itemType = PaymentRequests.text(body["itemType"], "itemType", 40)
        val itemRef = PaymentRequests.text(body["itemRef"], "itemRef", 128)
        val quantity = PaymentRequests.wholeNumber(body["quantity"], "quantity") ?: 1
        val route = routing.of(request)
        // 같은 나라의 결제사가 둘 이상 열려 있으면 구매자가 고른 것(`provider`)을 받는다 - 나라 · 후보는 서버가 정한 것 안에서만(`PaymentCountryRouting.chosen`)
        val provider = routing.chosen(route, body["provider"]?.takeIf { it.isString }?.stringValue())
        val created = orders.create(CreateOrderCommand(account, itemType, itemRef, idempotencyKey(key), quantity.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), country = route.country, provider = provider, providerClosed = route.providerClosed, providerChosen = provider != null && provider != route.provider, returnOrigin = body["returnOrigin"]?.takeIf { it.isString }?.stringValue()))
        val response = Response.ok(CreateOrderResponse(OrderResponse.of(OrderView(created.order, created.refunds)), created.checkout?.let(CheckoutResponse::of)))
        return if (created.replayed) ResponseEntity.ok(response) else ResponseEntity.status(201).body(response)
    }
}
