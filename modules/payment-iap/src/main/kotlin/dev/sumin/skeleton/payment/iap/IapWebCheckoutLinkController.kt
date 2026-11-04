package dev.sumin.skeleton.payment.iap

import dev.sumin.skeleton.common.account.SessionBoundPrincipal
import dev.sumin.skeleton.common.account.WebHandoffIssuer
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.payment.web.PaymentCallers
import dev.sumin.skeleton.payment.web.PaymentCountryRouting
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class IapWebCheckoutLinkResponse(val url: String) {
    // 주소에 1회용 코드가 실려 있다 - 로그에 찍히지 않게
    override fun toString() = "IapWebCheckoutLinkResponse(url=<redacted>)"
}

/**
 * 앱 -> 웹 결제로 넘어갈 때 쓰는 한 번 쓰는 자동 로그인 주소(스펙 「결정됨」 3). `auth-web-handoff` 가 켜져 있을 때만 선다.
 * 코드는 주소의 `#` 조각에 싣는다 - 조각은 서버·프록시 접근 로그와 Referer 에 가지 않는다. 웹은 조각을 읽어 `POST /api/v1/auth/web-handoff/redeem` 로 바꾸고 주소에서 지운다.
 */
@RestController
@RequestMapping("\${skeleton.payment.http.base-path:/api/v1/payments}/iap")
@Tag(name = "Payment IAP")
class IapWebCheckoutLinkController(
    private val callers: PaymentCallers,
    private val channels: IapChannelPolicy,
    private val routing: PaymentCountryRouting,
    private val handoff: WebHandoffIssuer,
    private val properties: IapProperties,
) {
    @Operation(
        summary = "A one-time sign-in address for the web checkout (open it in the outside browser)",
        description = "platform and storefront are read like GET /config; 404 when the channel has no web (IAP_ONLY). The code is in the # fragment, lives a couple of minutes and works once; " +
            "a new link, logging out everywhere, a password reset and a deletion close the one still open. The response is not cached.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = IapWebCheckoutLinkResponse::class)
    @PostMapping("/web-checkout-link")
    fun link(
        authentication: Authentication?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam(required = false) platform: String?,
        @RequestParam(required = false) storefront: String?,
    ): DataResponse<IapWebCheckoutLinkResponse> {
        val account = callers.account(authentication)
        val target = IapPlatform.of(platform) ?: throw FieldValidationException("platform", "Pattern", "platform must be ios or android")
        val country = StorefrontCountries.alpha2(storefront) ?: routing.of(request).country
        if (!channels.channelOf(country, target).allowsWeb) throw ApplicationException("No web checkout in this channel", PlatformErrorCode.NOT_FOUND)
        response.setHeader("Cache-Control", "no-store")
        return Response.ok(IapWebCheckoutLinkResponse("${properties.webCheckoutUrl.substringBefore('#')}#handoff=${handoff.issue(account, (authentication?.principal as? SessionBoundPrincipal)?.sessionId)}"))
    }
}
