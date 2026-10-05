package dev.sumin.skeleton.app.workbench.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.workbench.TestcontainersConfiguration
import kotlin.test.Test
import kotlin.test.assertTrue
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/** 모듈이 여는 업로드 엔드포인트가 진짜 S3 어댑터 · 진짜 보안 체인과 이어지는지 — presign 은 서명만 하므로 S3 에 닿지 않는다 */
@SpringBootTest(
    properties = [
        "skeleton.storage-s3.bucket=drill",
        "skeleton.storage-s3.region=us-east-1",
        "skeleton.storage-s3.endpoint-override=http://localhost:18333",
        "skeleton.storage-s3.path-style-access-enabled=true",
        "skeleton.storage-s3.credentials.access-key-id=dev",
        "skeleton.storage-s3.credentials.secret-access-key=dev-secret",
        "skeleton.storage.validation.max-size-bytes=1048576",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class StorageWebIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val body = """{"fileName":"cat.png","contentType":"image/png","sizeBytes":1024}"""

    @Test
    fun `logged-in user gets a presigned PUT under their own prefix`() {
        mockMvc.post("/api/v1/storage/presign") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.method") { value("PUT") }
            jsonPath("$.value.key") { value(startsWith("uploads/acc_user/")) }
            jsonPath("$.value.url") { value(containsString("localhost:18333/drill/uploads/acc_user/")) }
        }
    }

    @Test
    fun `anonymous callers are refused`() {
        mockMvc.post("/api/v1/storage/presign") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isUnauthorized() } }
    }

    private fun loginAccessToken(): String {
        val response = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return JsonPath.read<String>(response, "$.value.accessToken").also { assertTrue(it.isNotBlank()) }
    }
}
