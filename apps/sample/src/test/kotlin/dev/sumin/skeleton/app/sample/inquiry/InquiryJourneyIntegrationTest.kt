package dev.sumin.skeleton.app.sample.inquiry

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.cheer.CheerIntegrationBase
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/**
 * 견본 앱의 문의 폼 - 로그인 없이 보내면 202(보안 체인을 지나 공개 경로로 열려 있다) · 로그인해서 보내면 계정이 붙는다 ·
 * 값이 틀리면 400 `INQUIRY.INVALID` · 운영자만 목록을 보고 처리함으로 표시한다(`inquiry.read` · `inquiry.handle` 은 ADMIN 전권). 진짜 PostgreSQL · 보안 체인 · Flyway.
 */
class InquiryJourneyIntegrationTest : CheerIntegrationBase() {
    @BeforeEach
    @AfterEach
    fun wipe() {
        jdbc.sql("delete from inquiries").update()
    }

    private val body = """{"kind":"QUESTION","body":"견본 앱에서 노트를 내려받는 방법이 궁금해요","replyEmail":"asker@example.com","context":"screen:notes","lang":"ko"}"""

    private fun send(token: String? = null, content: String = body) = mvc.post("/api/v1/inquiries") {
        token?.let { header("Authorization", "Bearer $it") }
        contentType = MediaType.APPLICATION_JSON
        this.content = content
    }

    @Test
    fun `anyone can send a question and a signed-in sender is linked to the account`() {
        send().andExpect { status { isAccepted() }; header { string("Cache-Control", "no-store") }; jsonPath("$.value.status") { value("RECEIVED") } }
        send(user).andExpect { status { isAccepted() } }

        val rows = jdbc.sql("select account_id from inquiries order by id").query(String::class.java).list()
        assertEquals(listOf(null, accountOf("user@example.com")), rows)
    }

    @Test
    fun `a wrong value is a 400 with the violated fields`() {
        send(content = """{"kind":"NOPE","body":"짧아요"}""").andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INQUIRY.INVALID") } }
    }

    @Test
    fun `only an operator lists inquiries and marks one done, and the list never shows the account id`() {
        send(user).andExpect { status { isAccepted() } }

        get("/api/v1/admin/inquiries", null).andExpect { status { isUnauthorized() } }
        get("/api/v1/admin/inquiries", user).andExpect { status { isForbidden() } }
        val list = get("/api/v1/admin/inquiries", operator).andExpect {
            status { isOk() }
            jsonPath("$.values[0].signedIn") { value(true) }
            jsonPath("$.values[0].replyEmail") { value("asker@example.com") }
            jsonPath("$.values[0].accountId") { doesNotExist() }
        }.andReturn().response.contentAsString
        val id = JsonPath.read<Number>(list, "$.values[0].id").toLong()

        mvc.put("/api/v1/admin/inquiries/$id/status") { header("Authorization", "Bearer $user"); contentType = MediaType.APPLICATION_JSON; content = """{"status":"DONE"}""" }
            .andExpect { status { isForbidden() } }
        mvc.put("/api/v1/admin/inquiries/$id/status") { header("Authorization", "Bearer $operator"); contentType = MediaType.APPLICATION_JSON; content = """{"status":"DONE"}""" }
            .andExpect { status { isOk() }; jsonPath("$.value.status") { value("DONE") } }
        get("/api/v1/admin/inquiries?status=NEW", operator).andExpect { jsonPath("$.values") { isEmpty() } }
    }
}
