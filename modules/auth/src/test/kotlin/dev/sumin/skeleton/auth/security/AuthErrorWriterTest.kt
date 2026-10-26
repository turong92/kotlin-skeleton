package dev.sumin.skeleton.auth.security

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.common.GlobalExceptionHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper

class AuthErrorWriterTest {
    @Test
    fun `access denied by the security chain logs one Forbidden line without the query`() {
        val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            val request = MockHttpServletRequest("GET", "/api/admin/x").apply { queryString = "token=secret" }
            val response = MockHttpServletResponse()

            AuthErrorWriter(JsonMapper.builder().build()).writeForbidden(request, response)

            assertEquals(403, response.status)
            assertEquals(
                "Forbidden: GET /api/admin/x principal=anonymous code=COMMON.FORBIDDEN message=Access denied",
                appender.list.single().formattedMessage,
            )
        } finally {
            logger.detachAppender(appender)
        }
    }
}
