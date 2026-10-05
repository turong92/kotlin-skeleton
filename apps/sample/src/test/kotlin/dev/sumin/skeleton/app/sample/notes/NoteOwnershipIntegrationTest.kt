package dev.sumin.skeleton.app.sample.notes

import kotlin.test.Test
import org.springframework.test.web.servlet.post

class NoteOwnershipIntegrationTest : SampleIntegrationTest() {
    @Test
    fun `someone else's note is a 404 for read, replace, delete and export`() {
        val id = createdId(user, title = "내 비밀")

        getNote(other, id).andExpect { status { isNotFound() }; jsonPath("$.code") { value("NOTES.NOT_FOUND") } }
        putNote(other, id, """{"title":"탈취","body":"","status":"DRAFT","pinned":false}""").andExpect { status { isNotFound() } }
        deleteNote(other, id).andExpect { status { isNotFound() } }
        mockMvc.post("/api/v1/notes/$id/export") { header("Authorization", "Bearer $other") }.andExpect { status { isNotFound() } }

        getNote(user, id).andExpect { jsonPath("$.value.title") { value("내 비밀") } }
    }

    @Test
    fun `a malformed id is a 404 too`() {
        getNote(user, "not-a-uuid").andExpect { status { isNotFound() } }
    }
}
