package dev.sumin.skeleton.badge.web

import dev.sumin.skeleton.badge.BadgeCatalog
import dev.sumin.skeleton.badge.BadgeProperties
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 내 배지와 프로필 배지 — 호출자는 `Authentication.name`(auth 모듈에서는 계정 id)이다. 이 클래스는 [BadgeWebAutoConfiguration] 이 등록한다
 * (`skeleton.badge.http.enabled=false` 로 끈다).
 *
 * **`GET …/accounts/{accountId}` 는 로그인 없이 읽는다**(`skeleton.badge.http.allow-anonymous-read`, 기본 켜짐). 계정이 있는지 알려 주지 않는다 - 없는 계정도 `{badges: []}` 다.
 * 이 경로는 주소에 계정 id 를 담으므로 `author-profile`(필명 프로필) 화면에 붙이지 않는다 - 필명 여러 개가 같은 계정으로 이어진다.
 */
@RestController
@RequestMapping("\${skeleton.badge.http.base-path:/api/v1/badges}")
class BadgeController(
    private val catalog: BadgeCatalog,
    private val properties: BadgeProperties,
) {
    @GetMapping("/me")
    fun mine(authentication: Authentication?): DataResponse<MyBadgesResponse> =
        Response.ok(catalog.mine(accountId(authentication)).toResponse())

    @PatchMapping("/me/{awardId}")
    fun setVisible(authentication: Authentication?, @PathVariable awardId: String, @RequestBody request: VisibleRequest): DataResponse<VisibleResponse> {
        val visible = request.visible ?: throw ApplicationException("visible is required", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
        catalog.setVisible(accountId(authentication), awardId, visible)
        return Response.ok(VisibleResponse(awardId, visible))
    }

    @GetMapping("/accounts/{accountId}")
    fun profile(authentication: Authentication?, @PathVariable accountId: String): DataResponse<PublicBadgesResponse> {
        if (!properties.http.allowAnonymousRead) accountId(authentication)
        return Response.ok(PublicBadgesResponse(catalog.publicOf(accountId).map { it.toResponse() }))
    }

    private fun accountId(authentication: Authentication?): String =
        authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name
            ?: throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
}
