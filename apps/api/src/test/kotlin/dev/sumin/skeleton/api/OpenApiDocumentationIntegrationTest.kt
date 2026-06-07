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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OpenApiDocumentationIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `OpenAPI docs are composed from platform and auth modules`() {
        val docs = mockMvc.get("/api/v1/docs") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }.andReturn().response.contentAsString

        assertEquals("kotlin-skeleton API", JsonPath.read(docs, "$.info.title"))
        assertEquals("0.1.0", JsonPath.read(docs, "$.info.version"))

        assertEquals("object", JsonPath.read(docs, "$.components.schemas.ApiError.type"))
        assertEquals("object", JsonPath.read(docs, "$.components.schemas.ResponseMeta.type"))
        assertEquals("object", JsonPath.read(docs, "$.components.schemas.PaginationMeta.type"))

        assertEquals("http", JsonPath.read(docs, "$.components.securitySchemes.bearerAuth.type"))
        assertEquals("bearer", JsonPath.read(docs, "$.components.securitySchemes.bearerAuth.scheme"))
        assertEquals("JWT", JsonPath.read(docs, "$.components.securitySchemes.bearerAuth.bearerFormat"))

        assertEquals("email", JsonPath.read(docs, "$.components.schemas.PasswordLoginRequest.properties.email.format"))
        assertEquals(254, JsonPath.read(docs, "$.components.schemas.PasswordLoginRequest.properties.email.maxLength"))
        assertEquals(64, JsonPath.read(docs, "$.components.schemas.PasswordLoginRequest.properties.accountId.maxLength"))
        assertEquals(128, JsonPath.read(docs, "$.components.schemas.PasswordLoginRequest.properties.password.maxLength"))
        val loginRequired = JsonPath.read<List<String>>(docs, "$.components.schemas.PasswordLoginRequest.required")
        assertTrue(loginRequired.contains("password"))

        val helloParameters = JsonPath.read<List<Map<String, Any?>>>(docs, "$.paths['/api/v1/hello'].get.parameters")
        assertTrue(helloParameters.any { it["name"] == "traceparent" && it["in"] == "header" })
        assertTrue(helloParameters.any { it["name"] == "X-Trace-Id" && it["in"] == "header" })

        val helloSchemaRef = JsonPath.read<String>(
            docs,
            "$.paths['/api/v1/hello'].get.responses['200'].content['application/json'].schema['\$ref']",
        )
        assertTrue(helloSchemaRef.contains("ApiValueResponse"))

        assertEquals(
            "#/components/schemas/ApiError",
            JsonPath.read(
                docs,
                "$.paths['/api/v1/hello'].get.responses['500'].content['application/json'].schema['\$ref']",
            ),
        )

        val meSecurity = JsonPath.read<List<Map<String, Any?>>>(docs, "$.paths['/api/v1/auth/me'].get.security")
        assertTrue(meSecurity.any { it.containsKey("bearerAuth") })

        val socialLoginSchemaRef = JsonPath.read<String>(
            docs,
            "$.paths['/api/v1/auth/social/{provider}/login'].post.responses['200'].content['application/json'].schema['\$ref']",
        )
        assertTrue(socialLoginSchemaRef.contains("ApiValueResponse"))
    }
}
