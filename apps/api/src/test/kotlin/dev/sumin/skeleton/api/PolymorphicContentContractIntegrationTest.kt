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
class PolymorphicContentContractIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `content create request accepts video spec and returns video response spec`() {
        val token = loginAccessToken()

        mockMvc.post("/api/v1/skeleton/polymorphic/contents") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "launch-video.mp4",
                  "spec": {
                    "type": "VIDEO",
                    "durationMs": 120000,
                    "width": 1920,
                    "height": 1080,
                    "codec": "h264"
                  }
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.value.id") { value("sample-video") }
            jsonPath("$.value.type") { value("VIDEO") }
            jsonPath("$.value.name") { value("launch-video.mp4") }
            jsonPath("$.value.spec.type") { value("VIDEO") }
            jsonPath("$.value.spec.durationMs") { value(120000) }
            jsonPath("$.value.spec.width") { value(1920) }
            jsonPath("$.value.spec.height") { value(1080) }
            jsonPath("$.value.spec.streamUrl") { value("https://cdn.example.test/sample-video.mp4") }
        }
    }

    @Test
    fun `content create request accepts image spec and returns image response spec`() {
        val token = loginAccessToken()

        mockMvc.post("/api/v1/skeleton/polymorphic/contents") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "cover-image.png",
                  "spec": {
                    "type": "IMAGE",
                    "width": 1200,
                    "height": 630,
                    "format": "png"
                  }
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.value.id") { value("sample-image") }
            jsonPath("$.value.type") { value("IMAGE") }
            jsonPath("$.value.name") { value("cover-image.png") }
            jsonPath("$.value.spec.type") { value("IMAGE") }
            jsonPath("$.value.spec.width") { value(1200) }
            jsonPath("$.value.spec.height") { value(630) }
            jsonPath("$.value.spec.imageUrl") { value("https://cdn.example.test/sample-image.png") }
        }
    }

    @Test
    fun `content create request accepts tts spec and returns tts response spec`() {
        val token = loginAccessToken()

        mockMvc.post("/api/v1/skeleton/polymorphic/contents") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "intro-voice",
                  "spec": {
                    "type": "TTS",
                    "text": "Welcome aboard.",
                    "voice": "alloy",
                    "speed": 1.1
                  }
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.value.id") { value("sample-tts") }
            jsonPath("$.value.type") { value("TTS") }
            jsonPath("$.value.name") { value("intro-voice") }
            jsonPath("$.value.spec.type") { value("TTS") }
            jsonPath("$.value.spec.voice") { value("alloy") }
            jsonPath("$.value.spec.durationMs") { value(1600) }
            jsonPath("$.value.spec.audioUrl") { value("https://cdn.example.test/sample-tts.mp3") }
        }
    }

    @Test
    fun `OpenAPI documents polymorphic request and response specs with discriminator`() {
        val docs = mockMvc.get("/api/v1/docs") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        assertEquals(
            "#/components/schemas/SkeletonContentCreateSpec",
            JsonPath.read(docs, "$.components.schemas.SkeletonContentCreateRequest.properties.spec['\$ref']"),
        )
        assertEquals(
            "type",
            JsonPath.read(docs, "$.components.schemas.SkeletonContentCreateSpec.discriminator.propertyName"),
        )
        assertTrue(
            JsonPath.read<List<Map<String, String>>>(
                docs,
                "$.components.schemas.SkeletonContentCreateSpec.oneOf",
            ).any { it["\$ref"] == "#/components/schemas/SkeletonVideoContentCreateSpec" },
        )
        assertTrue(
            JsonPath.read<List<Map<String, String>>>(
                docs,
                "$.components.schemas.SkeletonContentCreateSpec.oneOf",
            ).any { it["\$ref"] == "#/components/schemas/SkeletonTtsContentCreateSpec" },
        )
        assertEquals(
            "#/components/schemas/SkeletonContentResponseSpec",
            JsonPath.read(docs, "$.components.schemas.SkeletonContentResponse.properties.spec['\$ref']"),
        )
        assertEquals(
            "type",
            JsonPath.read(docs, "$.components.schemas.SkeletonContentResponseSpec.discriminator.propertyName"),
        )
        assertTrue(
            JsonPath.read<List<Map<String, String>>>(
                docs,
                "$.components.schemas.SkeletonContentResponseSpec.oneOf",
            ).any { it["\$ref"] == "#/components/schemas/SkeletonImageContentResponseSpec" },
        )
        assertEquals(
            "#/components/schemas/DataResponseSkeletonContentResponse",
            JsonPath.read(docs, "$.paths['/api/v1/skeleton/polymorphic/contents'].post.responses['201'].content['application/json'].schema['\$ref']"),
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
