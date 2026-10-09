package dev.sumin.skeleton.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.common.logging.LogMasker
import dev.sumin.skeleton.common.logging.SkeletonLoggers
import jakarta.servlet.FilterChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestLoggingFilterTest {
    private companion object {
        const val BUDGET_MS = 50L
    }


    @Test
    fun `request logs use stable request debug category`() {
        captureLogger(SkeletonLoggers.REQUEST).use { captured ->
            val request = MockHttpServletRequest("GET", "/api/v1/hello")
            val response = MockHttpServletResponse()
            val chain = FilterChain { _, servletResponse ->
                (servletResponse as MockHttpServletResponse).status = 204
            }

            RequestLoggingFilter().doFilter(request, response, chain)

            val messages = captured.events.map { it.formattedMessage }
            assertTrue(messages.any { it == "-> GET /api/v1/hello" || it == "\u2192 GET /api/v1/hello" })
            assertTrue(messages.any { it.startsWith("<- GET /api/v1/hello 204") || it.startsWith("\u2190 GET /api/v1/hello 204") })
        }
    }


    @Test
    fun `an error the request recorded shows up on the end line as code and reason`() {
        captureLogger(SkeletonLoggers.REQUEST).use { captured ->
            val request = MockHttpServletRequest("POST", "/api/v1/notes")
            val response = MockHttpServletResponse()
            val chain = FilterChain { servletRequest, servletResponse ->
                (servletResponse as MockHttpServletResponse).status = 400
                RequestErrorNote.record(servletRequest as MockHttpServletRequest, "COMMON.IDEMPOTENCY_ERROR", "Missing Idempotency-Key")
            }

            RequestLoggingFilter().doFilter(request, response, chain)

            val end = captured.events.map { it.formattedMessage }.single { it.contains("POST /api/v1/notes 400") }
            assertTrue(end.contains("code=COMMON.IDEMPOTENCY_ERROR"), end)
            assertTrue(end.contains("reason=Missing Idempotency-Key"), end)
        }
    }

    @Test
    fun `a reason is one short line - control characters are dropped and the length is capped`() {
        val request = MockHttpServletRequest("POST", "/x")
        RequestErrorNote.record(request, "X.Y", "line one\nline two " + "z".repeat(500))
        val reason = RequestErrorNote.of(request)!!.reason
        assertTrue(!reason.contains('\n') && reason.length <= 160, reason)
    }

    @Test
    fun `a request without an error has the plain end line`() {
        captureLogger(SkeletonLoggers.REQUEST).use { captured ->
            RequestLoggingFilter().doFilter(MockHttpServletRequest("GET", "/ok"), MockHttpServletResponse(), FilterChain { _, _ -> })
            val end = captured.events.map { it.formattedMessage }.single { it.contains("GET /ok 200") }
            assertTrue(!end.contains("code="), end)
        }
    }

    @Test
    fun `the logged path goes through the contributor rules so a secret path piece never reaches the line`() {
        val previous = LogMasker.current
        LogMasker.install(contributorMasker("""/hooks/[a-z]+/(?<value>[^\s?]+)"""))
        try {
            captureLogger(SkeletonLoggers.REQUEST).use { captured ->
                RequestLoggingFilter().doFilter(MockHttpServletRequest("POST", "/hooks/toss/SECRETPIECE"), MockHttpServletResponse(), FilterChain { _, _ -> })

                val messages = captured.events.map { it.formattedMessage }
                assertEquals(2, messages.size)
                messages.forEach { assertTrue(!it.contains("SECRETPIECE") && it.contains("/hooks/toss/[REDACTED]"), it) }
            }
        } finally {
            LogMasker.install(previous)
        }
    }

    @Test
    fun `without a contributor the request lines carry the path exactly as it came - default rules, app rules and a long path included`() {
        val paths = listOf("/api/v1/hello", "/a/token=abc123/b", "/a/eyJhbGciOi.eyJzdWIiOi.c2ln/b", "/a/APPSECRET/me@example.com", "/" + "x".repeat(3000))
        val previous = LogMasker.current
        LogMasker.install(LogMasker(patterns = listOf("(?<value>APPSECRET)"), maskEmails = true))
        try {
            paths.forEach { path ->
                captureLogger(SkeletonLoggers.REQUEST).use { captured ->
                    RequestLoggingFilter().doFilter(MockHttpServletRequest("GET", path), MockHttpServletResponse(), FilterChain { _, _ -> })
                    val messages = captured.events.map { it.formattedMessage }
                    assertEquals("\u2192 GET $path", messages[0])
                    assertTrue(messages[1].startsWith("\u2190 GET $path 200 ("), messages[1])
                }
            }
        } finally {
            LogMasker.install(previous)
        }
    }

    @Test
    fun `with a contributor only its rule runs on the path - the default rules do not`() {
        withContributorMasker {
            captureLogger(SkeletonLoggers.REQUEST).use { captured ->
                val path = "/api/v1/payments/webhooks/toss/SECRETPIECE"
                RequestLoggingFilter().doFilter(MockHttpServletRequest("GET", path), MockHttpServletResponse(), FilterChain { _, _ -> })
                RequestLoggingFilter().doFilter(MockHttpServletRequest("GET", "/a/token=abc123/eyJhbGciOi.eyJzdWIiOi.c2ln"), MockHttpServletResponse(), FilterChain { _, _ -> })

                val messages = captured.events.map { it.formattedMessage }
                assertTrue(messages[0] == "\u2192 GET /api/v1/payments/webhooks/toss/[REDACTED]", messages[0])
                assertTrue(messages[2] == "\u2192 GET /a/token=abc123/eyJhbGciOi.eyJzdWIiOi.c2ln", messages[2])
            }
        }
    }

    @Test
    fun `with a contributor a path longer than 512 characters is cut before masking and marked`() {
        withContributorMasker {
            captureLogger(SkeletonLoggers.REQUEST).use { captured ->
                val path = "/api/v1/payments/webhooks/toss/SECRETPIECE" + "z".repeat(2000)
                RequestLoggingFilter().doFilter(MockHttpServletRequest("GET", path), MockHttpServletResponse(), FilterChain { _, _ -> })
                val long = "/" + "q".repeat(1000)
                RequestLoggingFilter().doFilter(MockHttpServletRequest("GET", long), MockHttpServletResponse(), FilterChain { _, _ -> })

                val messages = captured.events.map { it.formattedMessage }
                assertEquals("\u2192 GET /api/v1/payments/webhooks/toss/[REDACTED]${LogMasker.TRUNCATED_MARK}", messages[0], "the hidden piece swallows the cut tail, and the mark follows")
                assertEquals("\u2192 GET ${long.take(LogMasker.MAX_PATH_CHARS)}${LogMasker.TRUNCATED_MARK}", messages[2])
                assertTrue(!messages[2].contains(long), "the full path is not logged")
            }
        }
    }

    /** 요청 로그의 가림은 인증 · 한도보다 앞에서 도므로, 악성 경로 한 번이 CPU 를 묶지 못해야 한다 (Tomcat 기본 헤더 8KB 안의 7,800자 경로) */
    private fun filterMillis(path: String): Long {
        val started = System.nanoTime()
        RequestLoggingFilter().doFilter(MockHttpServletRequest("GET", path), MockHttpServletResponse(), FilterChain { _, _ -> })
        return (System.nanoTime() - started) / 1_000_000
    }

    /** 여러 번 돌려 최솟값 — CI 의 우연한 지연은 거르고, 0.4초짜리는 확실히 잡는다 */
    private fun bestOf(runs: Int, path: String): Long = (1..runs).minOf { filterMillis(path) }

    private fun withContributorMasker(block: () -> Unit) {
        val previous = LogMasker.current
        LogMasker.install(contributorMasker("""/api/v1/payments/webhooks/[^/\s]+/(?<value>\S+)"""))
        try {
            block()
        } finally {
            LogMasker.install(previous)
        }
    }

    @Test
    fun `a 7800 character hostile path is logged within the time budget whatever the masker has - sensitive-pair shaped`() {
        val path = "/" + "a-".repeat(3900)
        withContributorMasker {
            captureLogger(SkeletonLoggers.REQUEST).use { assertTrue(bestOf(5, path) < BUDGET_MS, "took ${bestOf(1, path)}ms") }
        }
    }

    @Test
    fun `a 7800 character hostile path is logged within the time budget whatever the masker has - jwt shaped`() {
        val path = "/" + "eyJ-".repeat(1950)
        withContributorMasker {
            captureLogger(SkeletonLoggers.REQUEST).use { assertTrue(bestOf(5, path) < BUDGET_MS, "took ${bestOf(1, path)}ms") }
        }
    }

    @Test
    fun `a 7800 character hostile path aimed at a contributor rule is logged within the time budget`() {
        val paths = listOf(
            "/api/v1/payments/webhooks/x/" + "a/".repeat(3880),
            "/api/v1/payments/webhooks/" + "a".repeat(7700),
            "/api/v1/payments/webhooks/x".repeat(290),
        )
        withContributorMasker {
            captureLogger(SkeletonLoggers.REQUEST).use {
                paths.forEach { path -> assertTrue(bestOf(5, path) < BUDGET_MS, "took ${bestOf(1, path)}ms for ${path.take(40)}") }
            }
        }
    }

    private fun captureLogger(loggerName: String): CapturedLogger {
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val previousLevel = logger.level
        val previousAdditive = logger.isAdditive
        logger.level = Level.INFO
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        return CapturedLogger(logger, appender, previousLevel, previousAdditive)
    }

    private class CapturedLogger(
        private val logger: Logger,
        private val appender: ListAppender<ILoggingEvent>,
        private val previousLevel: Level?,
        private val previousAdditive: Boolean,
    ) : AutoCloseable {
        val events: List<ILoggingEvent>
            get() = appender.list

        override fun close() {
            logger.detachAppender(appender)
            logger.level = previousLevel
            logger.isAdditive = previousAdditive
            appender.stop()
        }
    }

    private fun contributorMasker(vararg patterns: String) = LogMasker(patterns = patterns.toList(), pathPatterns = patterns.toList())
}
