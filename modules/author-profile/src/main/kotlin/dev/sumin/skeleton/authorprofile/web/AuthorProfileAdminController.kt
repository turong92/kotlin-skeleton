package dev.sumin.skeleton.authorprofile.web

import dev.sumin.skeleton.account.web.AccountCallers
import dev.sumin.skeleton.authorprofile.AuthorLinkLookup
import dev.sumin.skeleton.authorprofile.AuthorLinks
import dev.sumin.skeleton.authorprofile.AuthorProfile
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Instant
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 관리자 응답의 프로필 한 줄 — 상태 · 시각 포함(지워진 것은 `name` · `tag` 가 null, `deletedAt` 이 있다). 계정 이메일은 싣지 않는다 */
data class AdminAuthorProfileResponse(
    val id: String,
    val name: String?,
    val tag: String?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)

data class AdminAuthorLinksResponse(val accountId: String, val profiles: List<AdminAuthorProfileResponse>) {
    /** 계정 id 와 프로필 id 의 쌍이 로그 · 예외 메시지에 실리지 않게 — 개수만 */
    override fun toString(): String = "AdminAuthorLinksResponse(profiles=${profiles.size})"
}

private fun AuthorProfile.toAdmin() = AdminAuthorProfileResponse(id, name, visibleTag, status.name, createdAt, updatedAt, deletedAt)

private fun AuthorLinks.toResponse() = AdminAuthorLinksResponse(accountId, profiles.map { it.toAdmin() })

/**
 * 프로필 ↔ 계정 연결 조회 — **`ADMIN` 만**(게시판 운영자 `MODERATOR` 는 403), 호출마다 `AUTHOR_LINK_VIEWED` 감사 이벤트. `skeleton.author-profile.admin.enabled=true` 일 때만
 * [AuthorProfileAdminWebAutoConfiguration] 이 등록한다. 권한은 매 호출 저장소의 역할을 다시 읽는다([AccountCallers.requireAdmin]).
 */
@RestController
@RequestMapping("/api/v1/admin/author-profiles")
@Tag(name = "Author profile admin")
class AuthorProfileAdminController(private val callers: AccountCallers, private val lookup: AuthorLinkLookup) {
    @Operation(
        summary = "The profiles of the account behind a profile (deleted ones and times included)",
        description = "403 for anyone but an administrator (a MODERATOR too), 404 AUTHOR_PROFILE.NOT_FOUND for an unknown profile id. Every successful call is audited as AUTHOR_LINK_VIEWED.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AdminAuthorLinksResponse::class)
    @GetMapping("/{id}")
    fun byProfile(authentication: Authentication?, @PathVariable id: String): DataResponse<AdminAuthorLinksResponse> =
        Response.ok(lookup.byProfile(callers.requireAdmin(authentication).accountId, id).toResponse())

    @Operation(
        summary = "The profiles of an account (deleted ones and times included)",
        description = "404 AUTHOR_PROFILE.NOT_FOUND for an unknown account id (not audited). Every successful call is audited as AUTHOR_LINK_VIEWED.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AdminAuthorLinksResponse::class)
    @GetMapping
    fun byAccount(authentication: Authentication?, @RequestParam accountId: String): DataResponse<AdminAuthorLinksResponse> =
        Response.ok(lookup.byAccount(callers.requireAdmin(authentication).accountId, accountId).toResponse())
}
