package dev.sumin.skeleton.app.sample.apikey

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.cheer.CheerIntegrationBase
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 견본 앱의 API 키(스펙 §6 ⑤ ⑥의 백엔드 쪽) - 사용자가 `notes.read` 키를 만든다(다시 인증) → 키로 노트 읽기 200 · 쓰기 403 · 계정 403 → 회수 뒤 401,
 * 운영자가 다른 키를 사유와 함께 회수 → 사용자 목록에 `revokedBy: OPERATOR`. 진짜 PostgreSQL · 보안 체인 · Flyway.
 */
class ApiKeyJourneyIntegrationTest : CheerIntegrationBase() {
    @BeforeEach
    @AfterEach
    fun wipeKeys() {
        jdbc.sql("delete from api_keys").update()
    }

    private fun issue(scopes: String = """["notes.read"]""", expiresAt: Instant? = Instant.now().plus(30, ChronoUnit.DAYS)) =
        post("/api/v1/api-keys", user, """{"name":"ci","scopes":$scopes,"expiresAt":${expiresAt?.let { "\"$it\"" }},"currentPassword":"password"}""")

    private fun withKey(token: String, path: String, method: String = "GET") = when (method) {
        "POST" -> mvc.post(path) { header("Authorization", "Bearer $token"); contentType = MediaType.APPLICATION_JSON; content = """{"title":"t","body":"","status":"ACTIVE","pinned":false}""" }
        else -> mvc.get(path) { header("Authorization", "Bearer $token") }
    }

    private fun created(): Pair<String, String> {
        val body = issue().andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return JsonPath.read<String>(body, "$.value.key.id") to JsonPath.read<String>(body, "$.value.token")
    }

    @Test
    fun `a notes read key reads notes and nothing else, and a revoked key is 401`() {
        val (id, token) = created()
        assertTrue(token.startsWith("notes_"), "the sample's token prefix")

        withKey(token, "/api/v1/notes").andExpect { status { isOk() } }
        withKey(token, "/api/v1/notes", "POST").andExpect { status { isForbidden() }; jsonPath("$.code") { value("API_KEY.SCOPE_MISSING") } }
        withKey(token, "/api/v1/account/me").andExpect { status { isForbidden() }; jsonPath("$.code") { value("API_KEY.NOT_ALLOWED") } }

        mvc.delete("/api/v1/api-keys/$id") { header("Authorization", "Bearer $user") }.andExpect { status { isNoContent() } }
        withKey(token, "/api/v1/notes").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("API_KEY.REVOKED") } }
    }

    @Test
    fun `the sample caps the lifetime - a key without an expiry date is refused`() {
        issue(expiresAt = null).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("API_KEY.EXPIRES_INVALID") } }
    }

    @Test
    fun `an operator revokes someone else's key with a reason and the owner sees OPERATOR`() {
        val (id, token) = created()

        post("/api/v1/admin/api-keys/$id/revoke", user, """{"reason":"leaked"}""").andExpect { status { isForbidden() } }
        get("/api/v1/admin/api-keys?status=ACTIVE", operator).andExpect { status { isOk() }; jsonPath("$.values[0].id") { value(id) }; jsonPath("$.values[0].ownerName") { value("user@example.com") } }
        post("/api/v1/admin/api-keys/$id/revoke", operator, """{"reason":"found in a public repo"}""").andExpect { status { isNoContent() } }

        get("/api/v1/api-keys", user).andExpect { status { isOk() }; jsonPath("$.values[0].status") { value("REVOKED") }; jsonPath("$.values[0].revokedBy") { value("OPERATOR") } }
        withKey(token, "/api/v1/notes").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("API_KEY.REVOKED") } }
        assertEquals(accountOf("admin@example.com"), jdbc.sql("select revoked_by from api_keys where id = :i").param("i", id).query(String::class.java).single())
    }
}
