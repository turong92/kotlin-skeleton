package dev.sumin.skeleton.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OperationContractIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `POST example item returns created response with location and value envelope`() {
        mockMvc.post("/api/v1/examples/items") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"name":"sample"}"""
        }.andExpect {
            status { isCreated() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            header { string("Location", "http://localhost/api/v1/examples/items/item-1") }
            jsonPath("$.value.id") { value("item-1") }
            jsonPath("$.value.name") { value("sample") }
            jsonPath("$.meta.traceId") { isNotEmpty() }
            jsonPath("$.meta.spanId") { isNotEmpty() }
        }
    }

    @Test
    fun `DELETE example item returns no content response`() {
        val result = mockMvc.delete("/api/v1/examples/items/item-1") {
            header("Authorization", "Bearer ${loginAccessToken()}")
        }.andExpect {
            status { isNoContent() }
        }.andReturn()

        assertEquals("", result.response.contentAsString)
    }

    @Test
    fun `POST example job returns accepted response with value envelope`() {
        mockMvc.post("/api/v1/examples/jobs") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isAccepted() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.value.jobId") { value("job-1") }
            jsonPath("$.value.status") { value("QUEUED") }
            jsonPath("$.meta.traceId") { isNotEmpty() }
            jsonPath("$.meta.spanId") { isNotEmpty() }
        }
    }

    @Test
    fun `GET example items returns paged response envelope`() {
        mockMvc.get("/api/v1/examples/items") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            param("page", "1")
            param("size", "2")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.values.length()") { value(2) }
            jsonPath("$.values[0].id") { value("item-3") }
            jsonPath("$.pagination.page") { value(1) }
            jsonPath("$.pagination.size") { value(2) }
            jsonPath("$.pagination.totalElements") { value(5) }
            jsonPath("$.pagination.totalPages") { value(3) }
            jsonPath("$.pagination.hasNext") { value(true) }
            jsonPath("$.pagination.hasPrevious") { value(true) }
            jsonPath("$.meta.traceId") { isNotEmpty() }
            jsonPath("$.meta.spanId") { isNotEmpty() }
        }
    }

    @Test
    fun `GET example items with invalid page query returns validation errors`() {
        mockMvc.get("/api/v1/examples/items") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            param("page", "-1")
            param("size", "101")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(400) }
            jsonPath("$.title") { value("Validation failed") }
            jsonPath("$.errors[?(@.field == 'page')].code") { value(hasItem("Min")) }
            jsonPath("$.errors[?(@.field == 'size')].code") { value(hasItem("Max")) }
        }
    }

    private fun loginAccessToken(): String {
        val response = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        return JsonPath.read(response, "$.value.accessToken")
    }
}
