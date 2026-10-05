package dev.sumin.skeleton.common

import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.Test
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

/** 클라이언트가 잘못 부른 요청이 500 · ERROR 로그가 아니라 알맞은 4xx 가 되는지 — 실제 MVC 디스패치로 본다 */
class GlobalExceptionHandlerHttpStatusTest {
    @RestController
    class Probe {
        @GetMapping("/only-get", produces = ["application/json"])
        fun onlyGet() = mapOf("ok" to true)

        @PostMapping("/json-only", consumes = ["application/json"])
        fun jsonOnly(@RequestBody body: Map<String, String>) = body

        @GetMapping("/needs-param")
        fun needsParam(@RequestParam q: String) = mapOf("q" to q)
    }

    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(Probe()).setControllerAdvice(GlobalExceptionHandler()).build()

    @Test
    fun `a method the route does not allow is 405 with an Allow header`() {
        mvc.perform(post("/only-get"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().string("Allow", "GET"))
            .andExpect(jsonPath("$.code").value("COMMON.METHOD_NOT_ALLOWED"))
    }

    @Test
    fun `a body type the route does not consume is 415`() {
        mvc.perform(post("/json-only").contentType(MediaType.TEXT_PLAIN).content("x"))
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.code").value("COMMON.UNSUPPORTED_MEDIA_TYPE"))
    }

    @Test
    fun `an Accept the route cannot produce is 406 without a body and without an ERROR log`() {
        val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            mvc.perform(get("/only-get").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable)
            assertTrue(appender.list.none { it.level == Level.ERROR }, "a client Accept mistake must not log ERROR: ${appender.list.map { it.formattedMessage }}")
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `a missing required query parameter is 400 naming it`() {
        mvc.perform(get("/needs-param"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("COMMON.PARAMETER_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[0].field").value("q"))
    }
}
