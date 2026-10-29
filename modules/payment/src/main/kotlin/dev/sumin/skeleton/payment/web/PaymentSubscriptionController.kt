package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.payment.PaymentAmount
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
import org.springframework.web.bind.annotation.DeleteMapping
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
    private val routing: PaymentCountryRouting,
) {
    @Operation(summary = "The subscription plans", description = "code, name, amount, period (ISO-8601, P1M or PT3M), level (higher is a higher tier), productCode (plans of one product share it) and regularAmount (the list price when amount is a sale price, else null). Only the plans in the currency of this request (GET /config currency); all of them when there is no plan in that currency.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = SubscriptionPlansResponse::class)
    @GetMapping("/subscriptions/plans")
    fun plans(authentication: Authentication?, request: HttpServletRequest): DataResponse<SubscriptionPlansResponse> {
        callers.account(authentication)
        // 이 요청의 통화(라우팅이 정한다)의 플랜만 - 브라우저가 통화를 고르는 길은 없다
        return Response.ok(SubscriptionPlansResponse(subscriptions.plansFor(routing.currencyOf(routing.of(request))).map { SubscriptionPlanResponse.of(it, languages()) }))
    }

    /** 요청 언어(`Accept-Language`, 우선순위 순)의 언어 태그 - 헤더가 없으면 비어 있어 플랜의 기본 이름이 나간다 */
    private fun languages(): List<String> {
        val request = (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() as? org.springframework.web.context.request.ServletRequestAttributes)?.request ?: return emptyList()
        return if (request.getHeader("Accept-Language") == null) emptyList() else request.locales.toList().flatMap { listOf(it.toLanguageTag(), it.language) }.distinct()
    }

    /** 플랜 이름 - 요청 언어의 이름, 없으면 기본 이름(플랜 목록과 같은 규칙) */
    private fun nameOf(plan: dev.sumin.skeleton.payment.SubscriptionPlan?): String? = plan?.nameFor(languages())

    @Operation(
        summary = "Start a subscription and get the values for the card registration window",
        description = "201 with { subscription (PENDING), billingAuth }; the same Idempotency-Key again is 200 with the same subscription (billingAuth is null once the card was handed in). " +
            "Body { planCode, provider?, returnOrigin? } - the buyer's country (and so the cancel / refund policy) is decided by the server (see GET /config); a body country is ignored and a body provider is taken only when it is one of GET /config subscriptionChoices. returnOrigin is the origin of the screen that starts it (used only when it is in the provider's allowed list). A resend of the same key answers with the row as it was made. " +
            "400 without the header · 409 ALREADY_SUBSCRIBED · 409 IDEMPOTENCY_CONFLICT for another plan · 422 ITEM_NOT_PURCHASABLE for an unknown plan · 429 over the per-account / per-IP limit · 503 UNAVAILABLE.",
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
        val route = routing.of(request)
        // 같은 나라에 빌링 결제사가 둘 이상 열려 있으면 구매자가 고른 것(`provider`)을 받는다 - 후보 안에서만, 나머지는 서버 값
        val provider = routing.chosenForSubscription(route, body["provider"]?.takeIf { it.isString }?.stringValue())
        val started = subscriptions.start(account, planCode, idempotencyKey(key), route.country, provider, route.providerClosed, body["returnOrigin"]?.takeIf { it.isString }?.stringValue(), providerChosen = provider != null && provider != route.provider, discounts = PaymentRequests.discounts(body["discounts"]))
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
        val orders = current?.let { subscriptions.recentOrderViews(it.id) }.orEmpty().map(OrderResponse::of)
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

    @Operation(summary = "Take back a scheduled plan change", description = "Removes the change set for the next renewal (a lower tier or a shorter period). Without a scheduled change it answers the subscription as it is.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = SubscriptionResponse::class)
    @DeleteMapping("/subscriptions/{id}/scheduled-change")
    fun cancelScheduledChange(authentication: Authentication?, @PathVariable id: String): DataResponse<SubscriptionResponse> =
        Response.ok(view(planChanges.cancelScheduled(callers.account(authentication), id)))

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
        return Response.ok(subscriptions.plan(result.subscription.planCode).let { plan -> CancelNowResponse.of(result, nameOf(plan), plan) })
    }

    @Operation(
        summary = "What a plan change would charge, and when it happens",
        description = "timing NOW (a higher tier of the same period, or a longer period: charge is what is paid at once, 0 = changed for free, restartsPeriod tells that a new period starts today) or NEXT_RENEWAL (a lower tier or a shorter period: charge is 0, effectiveAt is the end of this period). amount is the new plan's full price. " +
            "For a subscription the provider renews (Paddle) only a higher tier or a longer period is offered: timing is always NOW, charge/credit/amount are what the provider calculated in the buyer's currency; " +
            "a lower tier or a shorter period is 409 PLAN_CHANGE_DOWNGRADE_UNSUPPORTED. " +
            "409 PLAN_CHANGE_NOT_ALLOWED when the plan is the same / another currency / the subscription is not in use / a charge is in flight / plan change is off / the provider cannot change it now · 422 ITEM_NOT_PURCHASABLE for an unknown plan.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = PlanChangePreviewResponse::class)
    @GetMapping("/subscriptions/{id}/change-preview")
    fun changePreview(
        authentication: Authentication?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @PathVariable id: String,
        @RequestParam planCode: String,
    ): DataResponse<PlanChangePreviewResponse> {
        val account = callers.account(authentication)
        // 결제사가 갱신하는 구독의 미리보기는 결제사 API 를 부른다 - 키의 호출 한도를 지키려고 호출 한도를 건다 - 별도 통에 변경보다 큰 한도(5배)라 미리보기를 여러 번 불러도 바꾸기가 막히지 않는다
        if (planChanges.callsProvider(account, id)) {
            limiter.acquire(account, clientIps.of(request).limitKey, bucket = "payment-plan-preview", scale = 5)?.let { retryAfter ->
                response.setHeader("Retry-After", retryAfter.toString())
                throw ApplicationException(message = "Too many requests", errorCode = PlatformErrorCode.TOO_MANY_REQUESTS, data = mapOf("retryAfterSeconds" to retryAfter))
            }
        }
        return Response.ok(planChanges.preview(account, id, planCode).let { PlanChangePreviewResponse.of(it, nameOf(it.plan) ?: it.plan.name) })
    }

    @Operation(
        summary = "Change plan - at once, or scheduled for the next renewal",
        description = "Body { planCode, expectedCharge } + Idempotency-Key. 200 changed (or scheduled: subscription.scheduledChange is set and nothing is charged, expectedCharge 0) · 202 the charge is unconfirmed (subscription.pendingPlanCode is set — poll GET /subscriptions/me) · " +
            "402 DECLINED (the plan stays) · 409 PLAN_CHANGE_CHARGE_CHANGED when the charge is now higher than expectedCharge (preview again) · 409 PLAN_CHANGE_NOT_ALLOWED · 409 PLAN_CHANGE_DOWNGRADE_UNSUPPORTED. " +
            "A subscription the provider renews (Paddle) is changed by the provider at once: there is no 202 and no scheduled change, and when the answer is lost the call fails while the provider's webhook brings the plan in line later. · 429.",
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
        subscriptions.plan(subscription.planCode).let { plan -> SubscriptionResponse.of(subscription, nameOf(plan), subscriptions.discountOf(subscription), plan, scheduledOf(subscription)) }

    private fun scheduledOf(subscription: PaymentSubscription): ScheduledChangeResponse? {
        val code = subscription.scheduledPlanCode ?: return null
        val amount = subscription.scheduledAmount ?: return null
        val plan = subscriptions.plan(code)
        return ScheduledChangeResponse.of(code, nameOf(plan) ?: code, PaymentAmount(amount, subscription.amount.currency), subscription.scheduledPeriod ?: subscription.period, subscription.currentPeriodEnd)
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
        const val PAYLOAD_FIELDS_MAX = 8
        const val PAYLOAD_VALUE_MAX = 512
    }
}
