package dev.sumin.skeleton.authorprofile.web

import dev.sumin.skeleton.account.DisplayNameRules
import dev.sumin.skeleton.authorprofile.AuthorProfileProperties
import dev.sumin.skeleton.authorprofile.AuthorProfileService
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.openapi.CreatedOperation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * 내 작성자 프로필 HTTP — 변환만 한다. [AuthorProfileWebAutoConfiguration] 이 등록한다 (`skeleton.author-profile.http.enabled=false` 로 끈다).
 * 경로는 `skeleton.author-profile.http.base-path`(기본 `/api/v1/author-profiles`). 로그인이 필요하고 호출자는 `Authentication.name` 이다.
 * `Idempotency-Key` 는 이 모듈이 따로 다루지 않는다 — 앱이 `idempotency` 모듈을 끼우면 그 필터가 처리한다.
 */
@RestController
@RequestMapping("\${skeleton.author-profile.http.base-path:/api/v1/author-profiles}")
@Tag(name = "Author profile")
class AuthorProfileController(
    private val service: AuthorProfileService,
    private val callers: AuthorProfileCallers,
    private val properties: AuthorProfileProperties,
) {
    @Operation(summary = "Profile limits", description = "maxPerAccount (living profiles per account, first profile included) and nameMaxLength.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AuthorProfileConfigResponse::class)
    @GetMapping("/config")
    fun config(authentication: Authentication?): DataResponse<AuthorProfileConfigResponse> {
        callers.accountId(authentication)
        return Response.ok(AuthorProfileConfigResponse(properties.maxPerAccount, DisplayNameRules.MAX))
    }

    @Operation(
        summary = "My living profiles, oldest first",
        description = "Guarantees the first profile first (and adopts the account's old posts into it). name is null for a nameless first profile; tag is null when the display-name mode has no visible tag.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.LIST, value = AuthorProfileResponse::class)
    @GetMapping
    fun list(authentication: Authentication?): ListResponse<AuthorProfileResponse> {
        val accountId = callers.accountId(authentication)
        service.ensureFirstProfile(accountId)
        return Response.ok(service.list(accountId).map { it.toResponse() })
    }

    @Operation(
        summary = "Create a profile",
        description = "400 with errors[].field=name (Required, Size, Pattern, Reserved); 409 AUTHOR_PROFILE.NAME_TAKEN or AUTHOR_PROFILE.LIMIT_REACHED; 429 AUTHOR_PROFILE.RATE_LIMITED (Retry-After).",
    )
    @CreatedOperation
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AuthorProfileResponse::class)
    @PostMapping
    fun create(authentication: Authentication?, @RequestBody request: AuthorProfileNameRequest): ResponseEntity<DataResponse<AuthorProfileResponse>> {
        val profile = service.create(callers.accountId(authentication), request.name).toResponse()
        return Response.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(profile.id).toUri(), profile)
    }

    @Operation(
        summary = "Rename a profile (past posts show the new name)",
        description = "Same errors as create. A profile that is someone else's, missing or deleted is the same 404 AUTHOR_PROFILE.NOT_FOUND.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AuthorProfileResponse::class)
    @PatchMapping("/{id}")
    fun rename(authentication: Authentication?, @PathVariable id: String, @RequestBody request: AuthorProfileNameRequest): DataResponse<AuthorProfileResponse> =
        Response.ok(service.rename(callers.accountId(authentication), id, request.name).toResponse())

    @Operation(
        summary = "Delete a profile (immediately, for good; its posts stay as a deleted user)",
        description = "A profile that is someone else's, missing or deleted is the same 404 AUTHOR_PROFILE.NOT_FOUND.",
    )
    @DeleteMapping("/{id}")
    fun delete(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        service.delete(callers.accountId(authentication), id)
        return Response.noContent()
    }
}
