package dev.sumin.skeleton.app.sample.notes

import kotlin.test.Test
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasSize
import org.springframework.test.web.servlet.get

class NoteListIntegrationTest : SampleIntegrationTest() {
    private fun list(token: String, query: String = "", q: String? = null) =
        mockMvc.get("/api/v1/notes$query") {
            header("Authorization", "Bearer $token")
            q?.let { param("q", it) }
        }

    @Test
    fun `an empty workspace lists nothing and says so in pagination`() {
        list(user).andExpect {
            status { isOk() }
            jsonPath("$.values", hasSize<Any>(0))
            jsonPath("$.pagination.totalElements") { value(0) }
            jsonPath("$.pagination.totalPages") { value(0) }
        }
    }

    @Test
    fun `pinned notes come first then the most recently updated`() {
        createdId(user, title = "오래된 것")
        Thread.sleep(5)
        val pinned = createdId(user, title = "고정", pinned = true)
        Thread.sleep(5)
        createdId(user, title = "최근 것")

        list(user).andExpect {
            jsonPath("$.values[*].title") { value(contains("고정", "최근 것", "오래된 것")) }
            jsonPath("$.values[0].id") { value(pinned) }
        }
    }

    @Test
    fun `page and size slice the list and report the totals`() {
        repeat(5) { createdId(user, title = "노트 $it") }

        list(user, "?page=1&size=2").andExpect {
            jsonPath("$.values", hasSize<Any>(2))
            jsonPath("$.pagination.page") { value(1) }
            jsonPath("$.pagination.size") { value(2) }
            jsonPath("$.pagination.totalElements") { value(5) }
            jsonPath("$.pagination.totalPages") { value(3) }
            jsonPath("$.pagination.hasNext") { value(true) }
            jsonPath("$.pagination.hasPrevious") { value(true) }
        }
        list(user, "?size=101").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `q searches title and body case-insensitively and treats percent and underscore literally`() {
        createdId(user, title = "Weekly Plan", body = "")
        createdId(user, title = "장보기", body = "우유와 Eggs")
        createdId(user, title = "100% 완료", body = "")
        createdId(user, title = "a_b", body = "")

        list(user, q = "weekly").andExpect { jsonPath("$.values[*].title") { value(contains("Weekly Plan")) } }
        list(user, q = "EGGS").andExpect { jsonPath("$.values[*].title") { value(contains("장보기")) } }
        list(user, q = "%").andExpect { jsonPath("$.values[*].title") { value(contains("100% 완료")) } }
        list(user, q = "a_b").andExpect { jsonPath("$.values", hasSize<Any>(1)) }
        list(user, q = "_").andExpect { jsonPath("$.values[*].title") { value(contains("a_b")) } }
    }

    @Test
    fun `status and pinned filters combine with the search`() {
        createdId(user, title = "초안 하나", status = "DRAFT")
        createdId(user, title = "진행 하나", status = "ACTIVE", pinned = true)
        createdId(user, title = "진행 둘", status = "ACTIVE")
        createdId(user, title = "보관 하나", status = "ARCHIVED")

        list(user, "?status=ACTIVE").andExpect { jsonPath("$.pagination.totalElements") { value(2) } }
        list(user, "?pinned=true").andExpect { jsonPath("$.values[*].title") { value(contains("진행 하나")) } }
        list(user, "?status=ACTIVE&q=둘").andExpect { jsonPath("$.values[*].title") { value(contains("진행 둘")) } }
        list(user, "?status=NOPE").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `each person sees only their own notes`() {
        createdId(user, title = "내 것")
        createdId(other, title = "남의 것")

        list(user).andExpect {
            jsonPath("$.values[*].title") { value(contains("내 것")) }
            jsonPath("$.pagination.totalElements") { value(1) }
        }
        list(other).andExpect { jsonPath("$.values[*].title") { value(contains("남의 것")) } }
    }

    @Test
    fun `summary counts the caller's notes by state`() {
        createdId(user, status = "DRAFT")
        createdId(user, status = "ACTIVE", pinned = true)
        createdId(user, status = "ACTIVE")
        val withFile = createdId(user, status = "ARCHIVED")
        putNote(user, withFile, """{"title":"파일","body":"","status":"ARCHIVED","pinned":false,"attachmentKey":"uploads/acc_user/u/f.pdf","attachmentName":"f.pdf"}""")
        createdId(other, status = "ACTIVE")

        mockMvc.get("/api/v1/notes/summary") { header("Authorization", "Bearer $user") }.andExpect {
            status { isOk() }
            jsonPath("$.value.total") { value(4) }
            jsonPath("$.value.pinned") { value(1) }
            jsonPath("$.value.withAttachment") { value(1) }
            jsonPath("$.value.draft") { value(1) }
            jsonPath("$.value.active") { value(2) }
            jsonPath("$.value.archived") { value(1) }
        }
    }

    @Test
    fun `summary of an empty workspace is all zeros`() {
        mockMvc.get("/api/v1/notes/summary") { header("Authorization", "Bearer $user") }.andExpect {
            jsonPath("$.value.total") { value(0) }
            jsonPath("$.value.withAttachment") { value(0) }
        }
    }
}
