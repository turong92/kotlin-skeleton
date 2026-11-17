package dev.sumin.skeleton.ads.webrewarded

import dev.sumin.skeleton.ads.webrewarded.web.OpenSessionRequest
import dev.sumin.skeleton.ads.webrewarded.web.WebRewardGrantResponse
import dev.sumin.skeleton.ads.webrewarded.web.WebRewardSessionResponse
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.web.ClientIps
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 웹 보상형 광고 세션 — 로그인 호출자만(익명 401). 기본 꺼짐: `skeleton.ads-web-rewarded.enabled=true` */
@RestController
@RequestMapping("/api/v1/ads/web-rewarded/sessions")
@Tag(name = "Ads web rewarded")
class WebRewardedController(private val service: WebRewardService, private val clientIps: ClientIps) {
    @Operation(summary = "Open an ad session before showing the rewarded ad (409 DAILY_LIMIT when today's limit is already reached)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = WebRewardSessionResponse::class)
    @PostMapping
    fun open(authentication: Authentication?, @Valid @RequestBody body: OpenSessionRequest, request: HttpServletRequest): DataResponse<WebRewardSessionResponse> =
        Response.ok(WebRewardSessionResponse.of(service.open(caller(authentication), body.purpose!!, clientIps.of(request).limitKey)))

    @Operation(summary = "Claim the reward once the ad was granted in the browser - once per session")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = WebRewardGrantResponse::class)
    @PostMapping("/{sessionId}/grant")
    fun grant(authentication: Authentication?, @PathVariable sessionId: String): DataResponse<WebRewardGrantResponse> =
        Response.ok(WebRewardGrantResponse.of(service.grant(caller(authentication), sessionId)))

    private fun caller(authentication: Authentication?): String {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        return authentication.name
    }
}
