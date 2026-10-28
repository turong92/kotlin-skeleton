package dev.sumin.skeleton.coupon.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.coupon.CouponCodes
import dev.sumin.skeleton.coupon.CouponTab
import dev.sumin.skeleton.coupon.CouponView
import dev.sumin.skeleton.coupon.CouponWallet
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class RedeemRequest(val code: String? = null)

/**
 * 내 쿠폰함 — 호출자는 `Authentication.name`(auth 모듈에서는 계정 id)이다. 인증 없는 호출은 401.
 * 이 클래스는 [CouponWebAutoConfiguration] 이 등록한다 (`skeleton.coupon.http.enabled=false` 로 끈다).
 */
@RestController
@RequestMapping("\${skeleton.coupon.http.base-path:/api/v1/coupons}")
class CouponController(
    private val wallet: CouponWallet,
    private val codes: CouponCodes,
) {
    @GetMapping
    fun list(authentication: Authentication?, @RequestParam(required = false) status: String?): ListResponse<CouponResponse> {
        val tab = status?.takeIf { it.isNotBlank() }?.let { raw ->
            CouponTab.entries.firstOrNull { it.name == raw.uppercase() }
                ?: throw ApplicationException("Unknown status '$raw'", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
        } ?: CouponTab.AVAILABLE
        return Response.ok(wallet.list(accountId(authentication), tab).map(CouponView::toResponse))
    }

    @PostMapping("/redeem")
    fun redeem(authentication: Authentication?, @RequestBody request: RedeemRequest): ResponseEntity<DataResponse<CouponResponse>> {
        val code = request.code?.takeIf { it.isNotBlank() }
            ?: throw ApplicationException("code is required", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
        val coupon = codes.redeem(accountId(authentication), code)
        val view = wallet.view(coupon)
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.ok(view.toResponse()))
    }

    private fun accountId(authentication: Authentication?): String =
        authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name
            ?: throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
}
