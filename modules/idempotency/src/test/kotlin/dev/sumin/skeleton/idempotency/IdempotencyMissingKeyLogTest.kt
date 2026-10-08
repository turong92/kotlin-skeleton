package dev.sumin.skeleton.idempotency

import dev.sumin.skeleton.common.RequestErrorNote
import java.time.Clock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.method.HandlerMethod
import tools.jackson.databind.json.JsonMapper

class IdempotencyMissingKeyLogTest {
    class Probe {
        @IdempotentOperation
        fun create() = Unit
    }

    private val interceptor = IdempotencyHandlerInterceptor(
        IdempotencyProperties(), InMemoryIdempotencyStore(Clock.systemUTC()), PrincipalIdempotencyScopeResolver(), JsonMapper.builder().build(),
    )
    private val handler = HandlerMethod(Probe(), Probe::class.java.getMethod("create"))

    @Test
    fun `a missing key is 400 with its own code and the request log can say why`() {
        val request = MockHttpServletRequest("POST", "/api/v1/notes")
        val response = MockHttpServletResponse()

        assertThat(interceptor.preHandle(request, response, handler)).isFalse()

        assertThat(response.status).isEqualTo(400)
        assertThat(response.contentAsString).contains("COMMON.IDEMPOTENCY_ERROR")
        val note = RequestErrorNote.of(request)
        assertThat(note?.code).isEqualTo("COMMON.IDEMPOTENCY_ERROR")
        assertThat(note?.reason).contains("Idempotency-Key")
    }

    @Test
    fun `a conflicting reuse is recorded too and never carries the key itself`() {
        val first = MockHttpServletRequest("POST", "/api/v1/notes").apply { addHeader("Idempotency-Key", "secret-key-123"); setContent("""{"a":1}""".toByteArray()) }
        interceptor.preHandle(first, MockHttpServletResponse(), handler)
        val second = MockHttpServletRequest("POST", "/api/v1/notes").apply { addHeader("Idempotency-Key", "secret-key-123"); setContent("""{"a":2}""".toByteArray()) }

        interceptor.preHandle(second, MockHttpServletResponse(), handler)

        val note = RequestErrorNote.of(second)
        assertThat(note?.code).isEqualTo("COMMON.IDEMPOTENCY_ERROR")
        assertThat(note?.reason).doesNotContain("secret-key-123")
    }
}
