package dev.sumin.skeleton.legal.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.PageQuery
import dev.sumin.skeleton.common.PageResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.consent.ConsentClaim
import dev.sumin.skeleton.common.consent.ConsentContext
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.legal.ConsentService
import dev.sumin.skeleton.legal.ConsentStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 로그인한 호출자의 동의 — 상태 · 동의 · 철회 · 기록. 주체는 항상 호출자 자신이다 (본문에서 받지 않는다). [LegalWebAutoConfiguration] 이 등록한다 */
@RestController
@RequestMapping("\${skeleton.legal.http.base-path:/api/v1/legal}/consents")
@Tag(name = "Legal")
class ConsentController(
    private val service: ConsentService,
    private val store: ConsentStore,
    private val callers: LegalCallers,
    private val clientIps: ClientIps,
) {
    @Operation(summary = "What the caller agreed to, what is current, what is missing or outdated")
    @GetMapping("/me")
    fun me(authentication: Authentication?): DataResponse<ConsentStatusResponse> =
        Response.ok(service.status(callers.require(authentication)).toResponse())

    @Operation(summary = "Agree to the listed (type, version) pairs; rejects stale versions, idempotent")
    @PostMapping
    fun agree(authentication: Authentication?, @Valid @RequestBody request: ConsentRequest, http: HttpServletRequest): DataResponse<ConsentStatusResponse> {
        val subject = callers.require(authentication)
        val claims = request.consents!!.map { ConsentClaim(it.type!!, it.version!!, it.locale) }
        if (claims.map { it.type }.distinct().size != claims.size) throw ApplicationException("a document type may appear once", PlatformErrorCode.VALIDATION_FAILED)
        return Response.ok(service.record(subject, claims, context(http), request.source ?: "consent").toResponse())
    }

    @Operation(summary = "Withdraw an optional agreement (recorded as its own event); idempotent")
    @PostMapping("/{type}/withdraw")
    fun withdraw(authentication: Authentication?, @PathVariable type: String, http: HttpServletRequest): DataResponse<ConsentStatusResponse> =
        Response.ok(service.withdraw(callers.require(authentication), type, context(http)).toResponse())

    @Operation(summary = "The caller's own consent events, newest first (no ip)")
    @GetMapping("/me/history")
    fun history(authentication: Authentication?, @Valid @ParameterObject @ModelAttribute pageQuery: PageQuery): PageResponse<HistoryEventResponse> {
        val page = store.history(callers.require(authentication), pageQuery.page, pageQuery.size)
        return Response.ok(page.items.map { it.toHistory() }, pageQuery.toPagination(page.total))
    }

    private fun context(http: HttpServletRequest) = ConsentContext(clientIps.of(http).ip, http.getHeader("User-Agent"))
}
