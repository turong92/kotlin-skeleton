package dev.sumin.skeleton.idempotency

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class IdempotencyCachingFilterTest {
    @Test
    fun `non cached methods pass through original response so streaming endpoints are not buffered`() {
        val filter = IdempotencyCachingFilter(
            properties = IdempotencyProperties(cachedMethods = setOf("POST")),
            store = InMemoryIdempotencyStore(),
        )
        val request = MockHttpServletRequest("GET", "/api/v1/notifications/sse")
        val response = MockHttpServletResponse()
        var responseSeenByChain: ServletResponse? = null

        filter.doFilter(
            request,
            response,
            FilterChain { _: ServletRequest, servletResponse: ServletResponse ->
                responseSeenByChain = servletResponse
                servletResponse.contentType = "text/event-stream"
                servletResponse.writer.write("event:connected\n\n")
            },
        )

        assertSame(response, responseSeenByChain)
        assertEquals("event:connected\n\n", response.contentAsString)
    }
}
