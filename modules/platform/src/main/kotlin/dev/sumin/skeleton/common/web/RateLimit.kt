package dev.sumin.skeleton.common.web

import dev.sumin.skeleton.common.ApiError
import dev.sumin.skeleton.common.TraceIdFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

fun interface RateLimitKeyResolver {
    fun resolve(request: HttpServletRequest): String
}

data class RateLimitDecision(
    val allowed: Boolean,
    val limit: Int,
    val remaining: Int,
    val resetAt: Instant,
)

interface RateLimitStore {
    fun consume(
        key: String,
        capacity: Int,
        windowMillis: Long,
        now: Instant,
    ): RateLimitDecision
}

class InMemoryFixedWindowRateLimitStore(
    private val clock: Clock = Clock.systemUTC(),
) : RateLimitStore {
    private val counters = ConcurrentHashMap<String, WindowCounter>()

    @Synchronized
    override fun consume(
        key: String,
        capacity: Int,
        windowMillis: Long,
        now: Instant,
    ): RateLimitDecision {
        val effectiveWindowMillis = windowMillis.coerceAtLeast(1)
        val currentMillis = now.toEpochMilli()
        val windowStart = (currentMillis / effectiveWindowMillis) * effectiveWindowMillis
        val counterKey = "$key:$windowStart"
        val counter = counters.compute(counterKey) { _, current ->
            when {
                current == null -> WindowCounter(windowStart = windowStart, count = 1)
                else -> current.copy(count = current.count + 1)
            }
        } ?: WindowCounter(windowStart = windowStart, count = 1)
        val resetAt = Instant.ofEpochMilli(windowStart + effectiveWindowMillis)
        val remaining = (capacity - counter.count).coerceAtLeast(0)

        counters.entries.removeIf { (_, value) ->
            value.windowStart + effectiveWindowMillis < clock.millis()
        }

        return RateLimitDecision(
            allowed = counter.count <= capacity,
            limit = capacity,
            remaining = remaining,
            resetAt = resetAt,
        )
    }

    private data class WindowCounter(
        val windowStart: Long,
        val count: Int,
    )
}

class ClientIpRateLimitKeyResolver : RateLimitKeyResolver {
    override fun resolve(request: HttpServletRequest): String =
        request.remoteAddr ?: "unknown"
}

class RateLimitFilter(
    private val properties: WebProperties.RateLimit,
    private val keyResolver: RateLimitKeyResolver,
    private val store: RateLimitStore,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) : OncePerRequestFilter() {
    private val pathMatcher = AntPathMatcher()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!pathMatcher.match(properties.pathPattern, request.requestURI)) {
            filterChain.doFilter(request, response)
            return
        }

        val decision = store.consume(
            key = keyResolver.resolve(request),
            capacity = properties.capacity,
            windowMillis = properties.window.toMillis(),
            now = clock.instant(),
        )
        response.setHeader("X-RateLimit-Limit", decision.limit.toString())
        response.setHeader("X-RateLimit-Remaining", decision.remaining.toString())
        response.setHeader("X-RateLimit-Reset", decision.resetAt.epochSecond.toString())

        if (decision.allowed) {
            filterChain.doFilter(request, response)
            return
        }

        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.setHeader("Retry-After", retryAfterSeconds(decision).toString())
        objectMapper.writeValue(
            response.outputStream,
            ApiError(
                title = "Too many requests",
                status = HttpStatus.TOO_MANY_REQUESTS.value(),
                detail = "Rate limit exceeded",
                traceId = MDC.get(TraceIdFilter.MDC_KEY),
                spanId = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
            ),
        )
    }

    private fun retryAfterSeconds(decision: RateLimitDecision): Long =
        (decision.resetAt.epochSecond - clock.instant().epochSecond).coerceAtLeast(1)
}
