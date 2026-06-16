package dev.sumin.skeleton.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val apiErrorProperties = JsonPath.read<Map<String, Any?>>(docs, "$.components.schemas.ApiError.properties")
        assertFalse(apiErrorProperties.containsKey("type"))
        assertEquals("string", JsonPath.read(docs, "$.components.schemas.ApiError.properties.code.type"))
        assertEquals("object", JsonPath.read(docs, "$.components.schemas.ApiError.properties.data.type"))
        val apiErrorRequired = JsonPath.read<List<String>>(docs, "$.components.schemas.ApiError.required")
        assertTrue(apiErrorRequired.contains("code"))
        assertEquals("object", JsonPath.read(docs, "$.components.schemas.ResponseMeta.type"))
        assertEquals("object", JsonPath.read(docs, "$.components.schemas.PaginationMeta.type"))
        assertEquals("object", JsonPath.read(docs, "$.components.schemas.CursorMeta.type"))
        assertEquals("object", JsonPath.read(docs, "$.components.schemas.JsonDocument.type"))
        assertEquals("object", JsonPath.read(docs, "$.components.schemas.VersionedJsonDocument.type"))
        assertEquals(
            "#/components/schemas/JsonDocument",
            JsonPath.read(docs, "$.components.schemas.VersionedJsonDocument.properties.payload['\$ref']"),
        )

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

        val helloEnvelope = responseSchema(docs, "/api/v1/hello", "get", "200")
        val helloEnvelopeProperties = properties(helloEnvelope)
        assertTrue(helloEnvelopeProperties.containsKey("value"))
        assertTrue(helloEnvelopeProperties.containsKey("meta"))
        assertEquals(
            "#/components/schemas/HelloResponse",
            (helloEnvelopeProperties["value"] as Map<*, *>)["\$ref"],
        )

        assertEquals(
            "#/components/schemas/ApiError",
            JsonPath.read(
                docs,
                "$.paths['/api/v1/hello'].get.responses['500'].content['application/json'].schema['\$ref']",
            ),
        )

        val meSecurity = JsonPath.read<List<Map<String, Any?>>>(docs, "$.paths['/api/v1/auth/me'].get.security")
        assertTrue(meSecurity.any { it.containsKey("bearerAuth") })

        val socialLoginEnvelope = responseSchema(docs, "/api/v1/auth/social/{provider}/login", "post", "200")
        val socialLoginProperties = properties(socialLoginEnvelope)
        assertTrue(socialLoginProperties.containsKey("value"))
        assertTrue(socialLoginProperties.containsKey("meta"))
        assertEquals(
            "#/components/schemas/AuthTokenResponse",
            (socialLoginProperties["value"] as Map<*, *>)["\$ref"],
        )

        val createdItemEnvelope = responseSchema(docs, "/api/v1/examples/items", "post", "201")
        val createdItemProperties = properties(createdItemEnvelope)
        assertTrue(createdItemProperties.containsKey("value"))
        assertEquals(
            "#/components/schemas/ExampleItemResponse",
            (createdItemProperties["value"] as Map<*, *>)["\$ref"],
        )
        val createItemParameters = JsonPath.read<List<Map<String, Any?>>>(docs, "$.paths['/api/v1/examples/items'].post.parameters")
        val idempotencyKey = createItemParameters.first { it["name"] == "Idempotency-Key" && it["in"] == "header" }
        assertEquals(true, idempotencyKey["required"])
        assertEquals(
            "Created resource URI",
            JsonPath.read(docs, "$.paths['/api/v1/examples/items'].post.responses['201'].headers.Location.description"),
        )

        val acceptedJobEnvelope = responseSchema(docs, "/api/v1/examples/jobs", "post", "202")
        val acceptedJobProperties = properties(acceptedJobEnvelope)
        assertTrue(acceptedJobProperties.containsKey("value"))
        assertEquals(
            "#/components/schemas/ExampleJobResponse",
            (acceptedJobProperties["value"] as Map<*, *>)["\$ref"],
        )

        assertEquals(
            "No content",
            JsonPath.read(docs, "$.paths['/api/v1/examples/items/{id}'].delete.responses['204'].description"),
        )
        val deleteItemResponse = JsonPath.read<Map<String, Any?>>(
            docs,
            "$.paths['/api/v1/examples/items/{id}'].delete.responses['204']",
        )
        assertFalse(deleteItemResponse.containsKey("content"))

        val jsonEchoEnvelope = responseSchema(docs, "/api/v1/skeleton/json/echo", "post", "200")
        val jsonEchoProperties = properties(jsonEchoEnvelope)
        assertTrue(jsonEchoProperties.containsKey("value"))
        assertEquals(
            "#/components/schemas/JsonDocument",
            (jsonEchoProperties["value"] as Map<*, *>)["\$ref"],
        )
        assertEquals(
            "Echo arbitrary JSON document",
            JsonPath.read(docs, "$.paths['/api/v1/skeleton/json/echo'].post.summary"),
        )
        assertEquals(
            "Return sample versioned JSON document",
            JsonPath.read(docs, "$.paths['/api/v1/skeleton/json/versioned'].get.summary"),
        )

        val itemListParameters = JsonPath.read<List<Map<String, Any?>>>(docs, "$.paths['/api/v1/examples/items'].get.parameters")
        assertTrue(itemListParameters.any { it["name"] == "page" && it["in"] == "query" })
        assertTrue(itemListParameters.any { it["name"] == "size" && it["in"] == "query" })
        val pageSchema = itemListParameters.first { it["name"] == "page" }["schema"] as Map<*, *>
        val sizeSchema = itemListParameters.first { it["name"] == "size" }["schema"] as Map<*, *>
        assertEquals(0, pageSchema["minimum"])
        assertEquals(100, sizeSchema["maximum"])

        val itemPageEnvelope = responseSchema(docs, "/api/v1/examples/items", "get", "200")
        val itemPageProperties = properties(itemPageEnvelope)
        assertTrue(itemPageProperties.containsKey("values"))
        assertTrue(itemPageProperties.containsKey("pagination"))
        assertTrue(itemPageProperties.containsKey("meta"))
        assertEquals(
            "#/components/schemas/ExampleItemResponse",
            ((itemPageProperties["values"] as Map<*, *>)["items"] as Map<*, *>)["\$ref"],
        )
        assertEquals(
            "#/components/schemas/PaginationMeta",
            (itemPageProperties["pagination"] as Map<*, *>)["\$ref"],
        )

        val cursorEnvelope = responseSchema(docs, "/api/v1/examples/items/cursor", "get", "200")
        val cursorProperties = properties(cursorEnvelope)
        assertTrue(cursorProperties.containsKey("values"))
        assertTrue(cursorProperties.containsKey("cursor"))
        assertTrue(cursorProperties.containsKey("meta"))
        assertEquals(
            "#/components/schemas/CursorMeta",
            (cursorProperties["cursor"] as Map<*, *>)["\$ref"],
        )

        val annotatedEnvelope = responseSchema(docs, "/api/v1/examples/items/annotated", "get", "200")
        val annotatedProperties = properties(annotatedEnvelope)
        assertTrue(annotatedProperties.containsKey("values"))
        assertEquals(
            "#/components/schemas/ExampleItemResponse",
            ((annotatedProperties["values"] as Map<*, *>)["items"] as Map<*, *>)["\$ref"],
        )
    }

    private fun responseSchema(
        docs: String,
        path: String,
        method: String,
        code: String,
    ): Map<String, Any?> {
        val schema = JsonPath.read<Map<String, Any?>>(
            docs,
            "$.paths['$path'].$method.responses['$code'].content['application/json'].schema",
        )
        val ref = schema["\$ref"] as? String
        return if (ref == null) {
            schema
        } else {
            val schemaName = ref.substringAfterLast("/")
            JsonPath.read(docs, "$.components.schemas.$schemaName")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun properties(schema: Map<String, Any?>): Map<String, Any?> =
        schema["properties"] as? Map<String, Any?>
            ?: error("Schema has no properties: $schema")
}
