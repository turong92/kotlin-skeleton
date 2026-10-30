package dev.sumin.skeleton.search.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PageResponse
import dev.sumin.skeleton.common.PaginationMeta
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.search.SearchDocument
import dev.sumin.skeleton.search.SearchProperties
import dev.sumin.skeleton.search.SearchReader
import dev.sumin.skeleton.search.SearchSort
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 검색 - 호출자는 `Authentication.name`(auth 모듈에서는 계정 id)이다. 이 클래스는 [SearchWebAutoConfiguration] 이 등록한다
 * (`skeleton.search.http.enabled=false` 로 끈다). `skeleton.search.http.allow-anonymous-read=true` 면 로그인 없이도 읽고, 그때는 주인 없는 문서만 본다.
 */
@RestController
@RequestMapping("\${skeleton.search.http.base-path:/api/v1/search}")
class SearchController(
    private val reader: SearchReader,
    private val properties: SearchProperties,
) {
    @GetMapping
    fun search(
        authentication: Authentication?,
        @RequestParam(name = "q", required = false, defaultValue = "") q: String,
        @RequestParam(name = "types", required = false) types: List<String>?,
        @RequestParam(name = "scope", required = false) scope: String?,
        @RequestParam(name = "sort", required = false, defaultValue = "relevance") sort: String,
        @RequestParam(name = "page", required = false, defaultValue = "0") page: Int,
        @RequestParam(name = "size", required = false, defaultValue = "20") size: Int,
    ): PageResponse<SearchResultResponse> {
        val caller = caller(authentication)
        val typeSet = types.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.onEach {
            if (!SearchDocument.TYPE.matches(it)) throw invalid("types must be comma-separated document types")
        }.toSet()
        val scopeValue = scope?.takeIf { it.isNotBlank() }?.also { if (it.length > SearchDocument.MAX_SCOPE) throw invalid("scope is too long") }
        val sortValue = SearchSort.entries.firstOrNull { it.name.equals(sort.trim(), ignoreCase = true) } ?: throw invalid("sort is relevance or recent")

        val result = reader.search(q, typeSet, scopeValue, sortValue, page, size, caller)
        return Response.ok(result.results.map { it.toResponse() }, PaginationMeta.of(result.page, result.size, result.total))
    }

    private fun caller(authentication: Authentication?): String? {
        val name = authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name
        if (name == null && !properties.http.allowAnonymousRead) throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        return name
    }

    private fun invalid(message: String) = ApplicationException(message, PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
}
