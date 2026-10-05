package dev.sumin.skeleton.common.web

import dev.sumin.skeleton.common.ApiError
import dev.sumin.skeleton.common.PlatformErrorCode
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

/**
 * 단일 인스턴스용 고정 창 카운터. 카운터마다 **자기 창의 끝**을 들고 있어, 다른 창 길이로 부른 호출이 남의 카운터를 일찍 지우지 않는다.
 * 만료된 창은 호출 중에 초당 한 번 걸러 내고, 조용한 서버에서도 쌓이지 않게 [sweep] 을 스케줄러에서 불러도 된다.
 */
class InMemoryFixedWindowRateLimitStore(
    private val clock: Clock = Clock.systemUTC(),
) : RateLimitStore {
    private val counters = ConcurrentHashMap<String, WindowCounter>()
    private var lastSweepMillis = Long.MIN_VALUE

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
        val windowEnd = windowStart + effectiveWindowMillis
        val counterKey = "$key:$windowStart"
        val counter = counters.compute(counterKey) { _, current ->
            current?.copy(count = current.count + 1) ?: WindowCounter(windowEnd = windowEnd, count = 1)
        }!!
        sweepIfDue(currentMillis)
        return RateLimitDecision(
            allowed = counter.count <= capacity,
            limit = capacity,
            remaining = (capacity - counter.count).coerceAtLeast(0),
            resetAt = Instant.ofEpochMilli(windowEnd),
        )
    }

    /** 끝난 창의 카운터를 지우고 지운 수를 돌려준다. [now] 를 생략하면 생성자의 시계 */
    @Synchronized
    fun sweep(now: Instant = clock.instant()): Int {
        val before = counters.size
        val nowMillis = now.toEpochMilli()
        counters.entries.removeIf { (_, value) -> value.windowEnd <= nowMillis }
        lastSweepMillis = nowMillis
        return before - counters.size
    }

    /** 지금 들고 있는 카운터 수 (테스트 · 점검용) */
    fun size(): Int = counters.size

    private fun sweepIfDue(nowMillis: Long) {
        if (nowMillis - lastSweepMillis >= SWEEP_INTERVAL_MILLIS) sweep(Instant.ofEpochMilli(nowMillis))
    }

    private data class WindowCounter(
        val windowEnd: Long,
        val count: Int,
    )

    private companion object {
        const val SWEEP_INTERVAL_MILLIS = 1_000L
    }
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
                code = PlatformErrorCode.TOO_MANY_REQUESTS.code,
                title = PlatformErrorCode.TOO_MANY_REQUESTS.title,
                status = PlatformErrorCode.TOO_MANY_REQUESTS.status.value(),
                detail = PlatformErrorCode.TOO_MANY_REQUESTS.defaultDetail,
                traceId = MDC.get(TraceIdFilter.MDC_KEY),
                spanId = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
            ),
        )
    }

    private fun retryAfterSeconds(decision: RateLimitDecision): Long =
        (decision.resetAt.epochSecond - clock.instant().epochSecond).coerceAtLeast(1)
}
