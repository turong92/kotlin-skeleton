package dev.sumin.skeleton.account.social

import dev.sumin.skeleton.account.signin.IdentityView
import dev.sumin.skeleton.account.web.AccountCallers
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.CreatedOperation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LinkSocialRequest(
    @field:NotBlank @field:Size(max = 2048) val authorizationCode: String?,
    @field:Size(max = 2048) val redirectUri: String? = null,
    /** 비밀번호가 있는 계정의 다시 인증 */
    @field:Size(max = 128) val currentPassword: String? = null,
    /** 비밀번호가 없는 계정의 다시 인증 — `POST /account/reauth/confirmation` 으로 메일 받은 6자리 코드 */
    @field:Pattern(regexp = "^[0-9]{6}$") val confirmationCode: String? = null,
    /** 이메일이 없는 계정의 다시 인증 — 이미 연결된 제공자의 새 인가 코드 */
    @field:Valid val socialReauth: dev.sumin.skeleton.account.web.SocialReauthRequest? = null,
    /** 연결할 제공자의 PKCE 검증기 · 인가 요청의 nonce (`GET /auth/methods` 의 `pkce` · `nonce`) */
    @field:Size(max = 256) val codeVerifier: String? = null,
    @field:Size(max = 256) val nonce: String? = null,
) {
    override fun toString() = "LinkSocialRequest(authorizationCode=<redacted>, <credentials redacted>)"
}

/** 소셜 제공자 계정을 지금 로그인한 계정에 붙인다. [AccountSocialAutoConfiguration] 이 auth-social 이 있을 때만 등록한다 */
@RestController
@RequestMapping("/api/v1/account/identities/social")
@Tag(name = "Account")
class SocialIdentityController(private val callers: AccountCallers, private val links: SocialLinkService) {
    @Operation(summary = "Link a social provider account to the signed-in account (409 ACCOUNT.IDENTITY_TAKEN when it belongs to another account)")
    @PostMapping("/{provider}")
    @CreatedOperation
    fun link(authentication: Authentication?, @PathVariable provider: String, @Valid @RequestBody request: LinkSocialRequest): ResponseEntity<DataResponse<IdentityView>> {
        val caller = callers.require(authentication)
        val view = links.link(
            caller.accountId, provider, request.authorizationCode!!, request.redirectUri,
            dev.sumin.skeleton.account.web.reauthOf(request.currentPassword, request.confirmationCode, request.socialReauth), caller.sessionId,
            request.codeVerifier, request.nonce,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.ok(view))
    }
}
