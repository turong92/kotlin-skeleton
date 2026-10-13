package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.payment.PaymentSubscription
import dev.sumin.skeleton.payment.PaymentSubscriptions
import dev.sumin.skeleton.payment.SubscriptionCancellations
import dev.sumin.skeleton.payment.SubscriptionPlanChanges
import dev.sumin.skeleton.payment.SubscriptionStatus
import dev.sumin.skeleton.payment.web.PaymentRequests.idempotencyKey
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
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
 * 구독 사용자 경로(스펙 `2026-10-09-subscription-design.md` §7) — **구독을 켠 앱에서만** 열린다(`skeleton.payment.subscription.enabled=true`). HTTP 변환만 하고 규칙은 [PaymentSubscriptions] 에 있다.
 * `@Transactional` 없음: 카드 등록 · 첫 결제는 외부 호출이라 바깥 트랜잭션 안에서 부르면 서비스가 던진다. 남의 구독과 없는 구독은 같은 404. 본문에 금액은 없다(금액의 출처는 앱의 플랜).
 */
@RestController
@RequestMapping("\${skeleton.payment.http.base-path:/api/v1/payments}")
@Tag(name = "Payment")
class PaymentSubscriptionController(
    private val subscriptions: PaymentSubscriptions,
    private val planChanges: SubscriptionPlanChanges,
    private val cancellations: SubscriptionCancellations,
    private val callers: PaymentCallers,
    private val limiter: PaymentOrderRateLimiter,
    private val clientIps: ClientIps,
) {
    @Operation(summary = "The subscription plans", description = "code, name, amount, period (ISO-8601, P1M or PT3M) and level (higher is a higher tier).")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = SubscriptionPlansResponse::class)
    @GetMapping("/subscriptions/plans")
    fun plans(authentication: Authentication?): DataResponse<SubscriptionPlansResponse> {
        callers.account(authentication)
        return Response.ok(SubscriptionPlansResponse(subscriptions.plans().map(SubscriptionPlanResponse::of)))
    }

    @Operation(
        summary = "Start a subscription and get the values for the card registration window",
        description = "201 with { subscription (PENDING), billingAuth }; the same Idempotency-Key again is 200 with the same subscription (billingAuth is null once the card was handed in). " +
            "Body { planCode, country? } - country is the buyer's ISO-3166 alpha-2 code (the cancel / refund policy follows it; the configured default when missing). " +
            "400 without the header or for a bad country · 409 ALREADY_SUBSCRIBED · 409 IDEMPOTENCY_CONFLICT for another plan · 422 ITEM_NOT_PURCHASABLE for an unknown plan · 429 over the per-account / per-IP limit · 503 UNAVAILABLE.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StartSubscriptionResponse::class)
    @PostMapping("/subscriptions")
    fun start(
        authentication: Authentication?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @Parameter(name = "Idempotency-Key", required = true) @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        @RequestBody body: JsonNode,
    ): ResponseEntity<DataResponse<StartSubscriptionResponse>> {
        val account = callers.account(authentication)
        limiter.acquire(account, clientIps.of(request).limitKey)?.let { retryAfter ->
            response.setHeader("Retry-After", retryAfter.toString())
            throw ApplicationException(message = "Too many requests", errorCode = PlatformErrorCode.TOO_MANY_REQUESTS, data = mapOf("retryAfterSeconds" to retryAfter))
        }
        val planCode = PaymentRequests.text(body["planCode"], "planCode", 64)
        val started = subscriptions.start(account, planCode, idempotencyKey(key), country(body["country"]), PaymentRequests.provider(body["provider"]))
        val payload = Response.ok(StartSubscriptionResponse(view(started.subscription), started.billing?.let(BillingAuthResponse::of)))
        return if (started.replayed) ResponseEntity.ok(payload) else ResponseEntity.status(201).body(payload)
    }

    @Operation(
        summary = "Hand in what the card registration window returned and take the first payment",
        description = "200 ACTIVE (also a resend) · 202 PENDING (the provider has not answered the first charge — poll GET /subscriptions/me) · 402 DECLINED (data.providerCode, data.category) · 404 for somebody else's · 409 ALREADY_SUBSCRIBED.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = SubscriptionResponse::class)
    @PostMapping("/subscriptions/{id}/activate")
    fun activate(authentication: Authentication?, @PathVariable id: String, @RequestBody body: JsonNode): ResponseEntity<DataResponse<SubscriptionResponse>> {
        val account = callers.account(authentication)
        val activated = subscriptions.activate(account, id, providerPayload(body["providerPayload"]))
        val payload = Response.ok(view(activated))
        return if (activated.status == SubscriptionStatus.PENDING) ResponseEntity.accepted().body(payload) else ResponseEntity.ok(payload)
    }

    @Operation(summary = "My subscription and my last five payments for it", description = "subscription is null when there is none. An ended subscription stays here with its endReason — the only notice of a failed renewal.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = MySubscriptionResponse::class)
    @GetMapping("/subscriptions/me")
    fun me(authentication: Authentication?): DataResponse<MySubscriptionResponse> {
        val account = callers.account(authentication)
        val current = subscriptions.current(account)
        val orders = current?.let { subscriptions.recentOrders(it.id) }.orEmpty().map { OrderResponse.of(dev.sumin.skeleton.payment.OrderView(it, emptyList())) }
        return Response.ok(MySubscriptionResponse(current?.let(::view), orders))
    }

    @Operation(summary = "Cancel at the end of the period", description = "ACTIVE becomes CANCELING (usable until the period ends), PAST_DUE ends at once. Pressing again answers the same. 409 for a subscription that was never started.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = SubscriptionResponse::class)
    @PostMapping("/subscriptions/{id}/cancel")
    fun cancel(authentication: Authentication?, @PathVariable id: String): DataResponse<SubscriptionResponse> =
        Response.ok(view(subscriptions.cancel(callers.account(authentication), id)))

    @Operation(
        summary = "What cancelling now would refund",
        description = "open=false means the policy of the subscription's country keeps the mid-term cancel closed (only 'cancel at the end of the period'). Otherwise refund is what comes back, lines the calculation " +
            "(PAID +, USED -, PENALTY -) and withdrawal tells it is a full refund inside the withdrawal window. 409 SUBSCRIPTION_CANCEL_NOT_ALLOWED when the subscription is not in use.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = CancelPreviewResponse::class)
    @GetMapping("/subscriptions/{id}/cancel-preview")
    fun cancelPreview(authentication: Authentication?, @PathVariable id: String): DataResponse<CancelPreviewResponse> =
        Response.ok(CancelPreviewResponse.of(cancellations.preview(callers.account(authentication), id)))

    @Operation(
        summary = "Cancel now and refund the unused rest of this period",
        description = "Ends the subscription at once (ENDED, endReason USER_CANCELED_NOW) and refunds through the refund ledger, order by order; the card is removed. Pressing again answers the same and refunds once. " +
            "409 SUBSCRIPTION_CANCEL_NOT_ALLOWED when the country's policy keeps it closed or the subscription is not in use · 409 SUBSCRIPTION_CHARGE_IN_PROGRESS while a renewal / plan-change charge is unanswered. " +
            "refunds[].status NOT_SENT is sent again by the server; FAILED was declined by the provider.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = CancelNowResponse::class)
    @PostMapping("/subscriptions/{id}/cancel-now")
    fun cancelNow(authentication: Authentication?, @PathVariable id: String): DataResponse<CancelNowResponse> {
        val result = cancellations.cancelNow(callers.account(authentication), id)
        return Response.ok(CancelNowResponse.of(result, subscriptions.plans().firstOrNull { it.code == result.subscription.planCode }?.name))
    }

    @Operation(
        summary = "What a change to a higher plan would charge now",
        description = "charge is what is paid at once (0 = changed for free), amount is the new plan's full price, restartsPeriod tells that a new period starts today. " +
            "409 PLAN_CHANGE_NOT_ALLOWED when the plan is not higher / the subscription is not in use / a charge is in flight / plan change is off · 422 ITEM_NOT_PURCHASABLE for an unknown plan.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = PlanChangePreviewResponse::class)
    @GetMapping("/subscriptions/{id}/change-preview")
    fun changePreview(authentication: Authentication?, @PathVariable id: String, @RequestParam planCode: String): DataResponse<PlanChangePreviewResponse> =
        Response.ok(PlanChangePreviewResponse.of(planChanges.preview(callers.account(authentication), id, planCode)))

    @Operation(
        summary = "Change to a higher plan at once",
        description = "Body { planCode, expectedCharge } + Idempotency-Key. 200 changed · 202 the charge is unconfirmed (subscription.pendingPlanCode is set — poll GET /subscriptions/me) · " +
            "402 DECLINED (the plan stays) · 409 PLAN_CHANGE_CHARGE_CHANGED when the charge is now higher than expectedCharge (preview again) · 409 PLAN_CHANGE_NOT_ALLOWED · 429.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = SubscriptionResponse::class)
    @PostMapping("/subscriptions/{id}/change")
    fun change(
        authentication: Authentication?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @PathVariable id: String,
        @Parameter(name = "Idempotency-Key", required = true) @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        @RequestBody body: JsonNode,
    ): ResponseEntity<DataResponse<SubscriptionResponse>> {
        val account = callers.account(authentication)
        limiter.acquire(account, clientIps.of(request).limitKey)?.let { retryAfter ->
            response.setHeader("Retry-After", retryAfter.toString())
            throw ApplicationException(message = "Too many requests", errorCode = PlatformErrorCode.TOO_MANY_REQUESTS, data = mapOf("retryAfterSeconds" to retryAfter))
        }
        val planCode = PaymentRequests.text(body["planCode"], "planCode", 64)
        val expected = PaymentRequests.requiredWholeNumber(body["expectedCharge"], "expectedCharge")
        val changed = planChanges.change(account, id, planCode, expected, idempotencyKey(key))
        val payload = Response.ok(view(changed))
        return if (changed.pendingPlanCode != null) ResponseEntity.accepted().body(payload) else ResponseEntity.ok(payload)
    }

    private fun view(subscription: PaymentSubscription): SubscriptionResponse =
        SubscriptionResponse.of(subscription, subscriptions.plans().firstOrNull { it.code == subscription.planCode }?.name)

    /** 구매자 나라 - 없으면 null(설정의 기본 나라), 있으면 두 글자 영문(소문자도 받아 대문자로). */
    private fun country(node: JsonNode?): String? {
        if (node == null || node.isNull) return null
        val text = if (node.isString) node.stringValue().trim() else ""
        if (!COUNTRY.matches(text)) throw FieldValidationException("country", "Pattern", "country must be an ISO-3166 alpha-2 code (two letters)")
        return text.uppercase()
    }

    /** 카드 등록 창이 돌려준 값 — 문자열 칸만, 여덟 개까지, 칸마다 512자까지. 모양은 결제사가 확인한다. */
    private fun providerPayload(node: JsonNode?): Map<String, Any?> {
        if (node == null || node.isNull || !node.isObject) throw FieldValidationException("providerPayload", "Required", "providerPayload is required")
        val fields = node.properties().toList()
        if (fields.size > PAYLOAD_FIELDS_MAX) throw FieldValidationException("providerPayload", "Size", "providerPayload has too many fields")
        return fields.associate { (name, value) ->
            if (!value.isString || value.stringValue().length > PAYLOAD_VALUE_MAX) throw FieldValidationException("providerPayload.$name", "Pattern", "$name must be a string of at most $PAYLOAD_VALUE_MAX characters")
            name to value.stringValue()
        }
    }

    private companion object {
        val COUNTRY = Regex("[A-Za-z]{2}")
        const val PAYLOAD_FIELDS_MAX = 8
        const val PAYLOAD_VALUE_MAX = 512
    }
}
