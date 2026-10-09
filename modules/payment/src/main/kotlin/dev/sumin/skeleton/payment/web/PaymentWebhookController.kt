package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.BasicResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PaymentWebhookInbox
import dev.sumin.skeleton.payment.WebhookDelivery
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 결제사가 웹훅을 보내는 공개 경로(스펙 §7) — `POST <base-path>/webhooks/{provider}` · `…/{provider}/{token}`. 인증이 없다([PaymentWebhookWebAutoConfiguration] 이 공개 경로로 연다):
 * 진위는 서명 없는 결제사면 주소의 비밀 조각, 서명 있는 결제사면 그 모듈의 서명 확인이다. 이 클래스는 [PaymentWebhookWebAutoConfiguration] 이 등록한다.
 *
 * 응답: `200`(받았다 · 중복 · 모르는 주문 · 다루지 않는 이벤트 · 주문 단위 한도 넘음 — 구별하지 않는다) · `404`(모르는 결제사 · 비밀 조각이 틀리거나 없음 — 구별하지 않는다) · `401`(진위 거절 · 읽을 수 없는 본문) ·
 * `413`(본문이 `webhook.max-body-bytes` 초과 — 주소가 맞은 요청만) · `429`(요청 수 한도, `Retry-After`) · `500`(저장 실패 — 결제사가 다시 보낸다).
 * 순서: 한도 → 주소 → 본문 크기 → 해석 · 저장. 본문은 주소가 맞은 뒤에야 읽는다.
 *
 * 공개 OpenAPI 문서에서 숨긴다(`@Hidden`) — 결제사가 부르는 주소(비밀 조각 포함)이지 앱 클라이언트의 계약이 아니다.
 */
@Hidden
@RestController
@RequestMapping("\${skeleton.payment.http.base-path:/api/v1/payments}/webhooks")
class PaymentWebhookController(
    private val inbox: PaymentWebhookInbox,
    private val limiter: PaymentWebhookRateLimiter,
    private val properties: PaymentProperties,
    private val clientIps: ClientIps,
) {
    @PostMapping("/{provider}", "/{provider}/{token}")
    fun receive(
        @PathVariable provider: String,
        @PathVariable(required = false) token: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): BasicResponse {
        val allowance = limiter.acquire(clientIps.of(request).limitKey)
        if (!allowance.allowed) {
            response.setHeader("Retry-After", allowance.retryAfterSeconds.toString())
            throw ApplicationException(message = "Too many requests", errorCode = PlatformErrorCode.TOO_MANY_REQUESTS, data = mapOf("retryAfterSeconds" to allowance.retryAfterSeconds))
        }
        val gateway = inbox.admit(provider, token)
        val body = BoundedBody.read(request, properties.webhook.maxBodyBytes)
        inbox.receive(gateway, WebhookDelivery(headers(request), body))
        return Response.ok()
    }

    /** 이름마다 첫 값 — [WebhookDelivery] 가 이름을 소문자로 맞춘다. */
    private fun headers(request: HttpServletRequest): Map<String, String> =
        request.headerNames.toList().associateWith { name -> request.getHeader(name).orEmpty() }
}
