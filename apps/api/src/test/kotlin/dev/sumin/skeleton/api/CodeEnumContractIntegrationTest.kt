package dev.sumin.skeleton.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CodeEnumContractIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `code enum can be read from query and returned as code plus descriptor`() {
        val token = loginAccessToken()

        mockMvc.get("/api/v1/skeleton/enums/status") {
            header("Authorization", "Bearer $token")
            param("status", "20")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.status") { value(20) }
            jsonPath("$.value.statusInfo.code") { value(20) }
            jsonPath("$.value.statusInfo.name") { value("PAID") }
            jsonPath("$.value.statusInfo.label") { value("Paid") }
            jsonPath("$.value.statusInfo.description") { doesNotExist() }
            jsonPath("$.value.allStatuses[0].code") { value(10) }
            jsonPath("$.value.allStatuses[0].name") { value("CREATED") }
            jsonPath("$.value.allStatuses[0].label") { value("Created") }
        }
    }

    @Test
    fun `code enum can be read from json body without per enum json creator`() {
        val token = loginAccessToken()

        mockMvc.post("/api/v1/skeleton/enums/status") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"status":90}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.status") { value(90) }
            jsonPath("$.value.statusInfo.name") { value("CANCELED") }
            jsonPath("$.value.statusInfo.label") { value("Canceled") }
        }
    }

    @Test
    fun `OpenAPI describes code enum fields with code name and label`() {
        val docs = mockMvc.get("/api/v1/docs") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        val description = JsonPath.read<String>(
            docs,
            "$.components.schemas.SkeletonCodeEnumRequest.properties.status.description",
        )

        assertTrue(description.contains("{code: 10, name: CREATED, label: Created, description: Created but unpaid.}"))
        assertTrue(description.contains("{code: 20, name: PAID, label: Paid}"))
        assertTrue(description.contains("{code: 90, name: CANCELED, label: Canceled}"))
        assertEquals(
            "integer",
            JsonPath.read(docs, "$.components.schemas.SkeletonCodeEnumRequest.properties.status.type"),
        )
        assertEquals(
            "integer",
            JsonPath.read(docs, "$.paths['/api/v1/skeleton/enums/status'].get.parameters[0].schema.type"),
        )
        assertEquals(
            listOf(10, 20, 90),
            JsonPath.read(docs, "$.paths['/api/v1/skeleton/enums/status'].get.parameters[0].schema.enum"),
        )
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
