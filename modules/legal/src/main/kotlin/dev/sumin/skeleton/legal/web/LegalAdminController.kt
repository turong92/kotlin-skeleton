package dev.sumin.skeleton.legal.web

import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PageQuery
import dev.sumin.skeleton.common.PageResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.legal.ConsentAction
import dev.sumin.skeleton.legal.ConsentSearch
import dev.sumin.skeleton.legal.ConsentStore
import dev.sumin.skeleton.legal.LegalCatalog
import dev.sumin.skeleton.legal.LegalLedger
import dev.sumin.skeleton.legal.LegalRules
import dev.sumin.skeleton.legal.LegalText
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 운영자 읽기 — 모든 주체의 동의 사건과 매니페스트 상태. 역할은 `skeleton.legal.admin-role` */
@RestController
@RequestMapping("\${skeleton.legal.http.base-path:/api/v1/legal}/admin")
@Tag(name = "Legal")
class LegalAdminController(
    private val store: ConsentStore,
    private val catalog: LegalCatalog,
    private val ledger: LegalLedger,
    private val rules: LegalRules,
    private val time: TimeProvider,
    private val callers: LegalCallers,
) {
    @Operation(summary = "Consent events of every subject, newest first (admin)")
    @GetMapping("/consents")
    fun consents(
        authentication: Authentication?,
        @RequestParam(required = false) subjectType: String?,
        @RequestParam(required = false) subjectId: String?,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) action: ConsentAction?,
        @Valid @ParameterObject @ModelAttribute pageQuery: PageQuery,
    ): PageResponse<AdminConsentResponse> {
        callers.requireAdmin(authentication)
        val page = store.search(ConsentSearch(subjectType, subjectId, type, action), pageQuery.page, pageQuery.size)
        return Response.ok(page.items.map { it.toAdmin() }, pageQuery.toPagination(page.total))
    }

    @Operation(summary = "Every version of every document with status, pin state and missing facts (admin)")
    @GetMapping("/documents")
    fun documents(authentication: Authentication?): ListResponse<AdminDocumentResponse> {
        callers.requireAdmin(authentication)
        val now = time.now()
        return Response.ok(
            catalog.all().map { v ->
                AdminDocumentResponse(
                    v.type, v.version, v.meta.status.name, v.effectiveFrom, v.locales, v.meta.template,
                    catalog.current(v.type, now)?.version == v.version,
                    v.sources.mapValues { LegalText.sha256(it.value) },
                    v.meta.locales.all { ledger.find(v.type, v.version, it) != null },
                    v.sources.values.flatMap { LegalText.placeholders(it) }.filter { it !in rules.facts }.distinct(),
                )
            },
        )
    }
}
