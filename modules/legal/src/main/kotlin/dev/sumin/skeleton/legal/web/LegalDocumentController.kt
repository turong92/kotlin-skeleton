package dev.sumin.skeleton.legal.web

import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.legal.LegalCatalog
import dev.sumin.skeleton.legal.LegalErrorCode
import dev.sumin.skeleton.legal.LegalException
import dev.sumin.skeleton.legal.LegalRules
import dev.sumin.skeleton.legal.LegalText
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Duration
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.WebRequest

/** 로그인 없이 읽는 문서 — 현재 판 목록과 본문. 캐시할 수 있다 (`Cache-Control: public`, `ETag`). [LegalWebAutoConfiguration] 이 등록한다 */
@RestController
@RequestMapping("\${skeleton.legal.http.base-path:/api/v1/legal}")
@Tag(name = "Legal")
class LegalDocumentController(
    private val catalog: LegalCatalog,
    private val rules: LegalRules,
    private val time: TimeProvider,
    private val cacheMaxAge: Duration,
) {
    @Operation(summary = "Current version of every document, per locale (public, cacheable)")
    @GetMapping("/documents")
    fun list(request: WebRequest): ResponseEntity<ListResponse<DocumentSummaryResponse>>? {
        val now = time.now()
        val values = catalog.types.mapNotNull { catalog.current(it, now) }.flatMap { version ->
            val next = catalog.next(version.type, now)
            version.locales.map { locale ->
                val shown = catalog.render(version, locale)
                DocumentSummaryResponse(
                    version.type, locale, version.version, version.effectiveFrom!!, shown.title, shown.sha256,
                    version.type in rules.required, version.type in rules.requiredAtSignUp, version.meta.template,
                    next?.let { NextVersionResponse(it.version, it.effectiveFrom!!) },
                )
            }
        }
        val etag = LegalText.sha256(values.joinToString("|") { "${it.type}/${it.locale}/${it.version}/${it.sha256}/${it.title}/${it.next?.version}" })
        if (request.checkNotModified(etag)) return null
        return ResponseEntity.ok().cacheControl(cache()).eTag(etag).body(Response.ok(values))
    }

    @Operation(summary = "One document: the current version or any version already in force, as markdown (public, cacheable)")
    @GetMapping("/documents/{type}")
    fun get(
        @PathVariable type: String,
        @RequestParam(required = false) version: String?,
        @RequestParam(required = false) locale: String?,
        request: WebRequest,
    ): ResponseEntity<DataResponse<DocumentDetailResponse>>? {
        val now = time.now()
        val current = if (LegalCatalog.TYPE.matches(type)) catalog.current(type, now) else null
        val document = if (version == null) current else catalog.find(type, version, now)
        if (current == null || document == null) throw LegalException(LegalErrorCode.DOCUMENT_NOT_FOUND, "document not found")
        val shown = catalog.render(document, locale ?: rules.defaultLocale)
        val body = DocumentDetailResponse(
            type, document.version, shown.locale, shown.requestedLocale, document.effectiveFrom!!, document.version == current.version, document.meta.template,
            shown.title, shown.sha256, type in rules.required, type in rules.requiredAtSignUp, shown.markdown,
        )
        val etag = LegalText.sha256("${body.type}/${body.version}/${body.locale}/${body.markdown}/${body.current}")
        if (request.checkNotModified(etag)) return null
        return ResponseEntity.ok().cacheControl(cache()).eTag(etag).body(Response.ok(body))
    }

    private fun cache() = CacheControl.maxAge(cacheMaxAge).cachePublic()
}
