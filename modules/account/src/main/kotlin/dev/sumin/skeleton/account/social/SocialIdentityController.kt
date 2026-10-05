package dev.sumin.skeleton.account.social

import dev.sumin.skeleton.account.signin.IdentityView
import dev.sumin.skeleton.account.web.AccountCallers
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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
) {
    override fun toString() = "LinkSocialRequest(authorizationCode=<redacted>)"
}

/** 소셜 제공자 계정을 지금 로그인한 계정에 붙인다. [AccountSocialAutoConfiguration] 이 auth-social 이 있을 때만 등록한다 */
@RestController
@RequestMapping("/api/v1/account/identities/social")
@Tag(name = "Account")
class SocialIdentityController(private val callers: AccountCallers, private val links: SocialLinkService) {
    @Operation(summary = "Link a social provider account to the signed-in account (409 ACCOUNT.IDENTITY_TAKEN when it belongs to another account)")
    @PostMapping("/{provider}")
    fun link(authentication: Authentication?, @PathVariable provider: String, @Valid @RequestBody request: LinkSocialRequest): ResponseEntity<DataResponse<IdentityView>> {
        val view = links.link(callers.require(authentication).accountId, provider, request.authorizationCode!!, request.redirectUri)
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.ok(view))
    }
}
