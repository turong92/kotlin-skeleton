package dev.sumin.skeleton.inquiry.web

import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.AcceptedOperation
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.inquiry.InquiryRequest
import dev.sumin.skeleton.inquiry.InquiryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class InquiryReceipt(val status: String = "RECEIVED")

/**
 * 문의 · 의견 받기 — 로그인 없이도 된다. 로그인한 호출(유효한 액세스 토큰)이면 계정이 붙고 사람 확인을 건너뛴다.
 * 공개 경로로 풀어 주는 것은 [InquiryWebAutoConfiguration] 이다(`PublicEndpointContributor`).
 */
@RestController
@RequestMapping("/api/v1/inquiries")
@Tag(name = "Inquiries")
class InquiryController(
    private val service: InquiryService,
    private val clientIps: ClientIps,
) {
    @Operation(
        summary = "Send a question / feature idea / bug report (always 202 when accepted)",
        description = "Anonymous callers must pass the human check (captchaToken); signed-in callers are linked to their account. 400 INQUIRY.INVALID (data.violations) · 400 INQUIRY.CAPTCHA_FAILED · 429 INQUIRY.TOO_MANY (data.retryAfterSeconds).",
    )
    @PostMapping
    @AcceptedOperation
    fun submit(@RequestBody body: InquiryRequest, authentication: Authentication?, http: HttpServletRequest): ResponseEntity<DataResponse<InquiryReceipt>> {
        val account = authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name
        val client = clientIps.of(http)
        service.submit(body, account, client.limitKey, client.ip)
        // CDN 이 아니라 API 가 직접 답한다
        return Response.accepted(InquiryReceipt()).let {
            ResponseEntity.status(it.statusCode).headers(it.headers).header(HttpHeaders.CACHE_CONTROL, "no-store").body(it.body)
        }
    }
}
