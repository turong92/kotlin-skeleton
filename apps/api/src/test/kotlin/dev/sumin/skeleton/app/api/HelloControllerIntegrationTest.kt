package dev.sumin.skeleton.app.api

import dev.sumin.skeleton.common.TraceIdFilter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HelloControllerIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `GET hello returns the standard envelope with a trace id`() {
        val response = mockMvc.get("/api/v1/hello") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.message") { value("Hello from Kotlin backend!") }
            jsonPath("$.value.timestamp") { isNotEmpty() }
            jsonPath("$.meta.traceId") { isNotEmpty() }
            jsonPath("$.meta.spanId") { isNotEmpty() }
            header { exists(TraceIdFilter.HEADER_TRACEPARENT) }
        }.andReturn().response

        val traceId = response.getHeader(TraceIdFilter.HEADER_TRACE_ID).orEmpty()
        assertTrue(traceId.matches(Regex("[0-9a-f]{32}")))
        assertEquals(traceId, com.jayway.jsonpath.JsonPath.read<String>(response.contentAsString, "$.meta.traceId"))
    }
}
