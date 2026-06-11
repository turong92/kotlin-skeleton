package dev.sumin.skeleton.idempotency

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper

class IdempotencyCachingFilter(
    private val properties: IdempotencyProperties,
    private val store: IdempotencyStore,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestToUse = if (shouldCacheRequest(request)) {
            CachedBodyHttpServletRequest(request)
        } else {
            request
        }
        val responseToUse = ContentCachingResponseWrapper(response)

        try {
            filterChain.doFilter(requestToUse, responseToUse)
            completeReservedRequest(requestToUse, responseToUse)
        } finally {
            responseToUse.copyBodyToResponse()
        }
    }

    private fun shouldCacheRequest(request: HttpServletRequest): Boolean =
        request.method.uppercase() in properties.cachedMethods.map { it.uppercase() }

    private fun completeReservedRequest(
        request: HttpServletRequest,
        response: ContentCachingResponseWrapper,
    ) {
        val context = request.getAttribute(IdempotencyAttributes.CONTEXT) as? IdempotencyContext ?: return

        store.complete(
            scopedKey = context.scopedKey,
            response = StoredIdempotencyResponse(
                status = response.status,
                contentType = response.contentType,
                headers = replayableHeaders(response),
                body = response.contentAsByteArray,
            ),
        )
    }

    private fun replayableHeaders(response: ContentCachingResponseWrapper): Map<String, List<String>> =
        response.headerNames
            .filter { it.equals("Location", ignoreCase = true) }
            .associateWith { response.getHeaders(it).toList() }
}
