package dev.sumin.skeleton.app.sample.notes

import java.util.UUID
import kotlin.test.Test
import org.hamcrest.Matchers.endsWith
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.nullValue
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

class NoteCrudIntegrationTest : SampleIntegrationTest() {
    @Test
    fun `creating a note answers 201 with Location and the note with defaults`() {
        createNote(user, title = "첫 노트").andExpect {
            status { isCreated() }
            header { string("Location", matchesPattern(".*/api/v1/notes/[0-9a-f-]{36}")) }
            jsonPath("$.value.id") { isNotEmpty() }
            jsonPath("$.value.title") { value("첫 노트") }
            jsonPath("$.value.body") { value("") }
            jsonPath("$.value.status") { value("DRAFT") }
            jsonPath("$.value.pinned") { value(false) }
            jsonPath("$.value.attachmentKey") { value(nullValue()) }
            jsonPath("$.value.attachmentName") { value(nullValue()) }
            jsonPath("$.value.createdAt") { value(endsWith("Z")) }
            jsonPath("$.value.updatedAt") { value(endsWith("Z")) }
            jsonPath("$.meta.traceId") { exists() }
        }
    }

    @Test
    fun `repeating a create with the same Idempotency-Key replays the first answer and stores one note`() {
        val key = UUID.randomUUID().toString()
        val first = createNote(user, title = "한 번만", key = key).andExpect { status { isCreated() } }.andReturn().readString("$.value.id")
        val second = createNote(user, title = "한 번만", key = key).andExpect { status { isCreated() } }.andReturn().readString("$.value.id")

        kotlin.test.assertEquals(first, second)
        mockMvc.get("/api/v1/notes") { header("Authorization", "Bearer $user") }.andExpect {
            jsonPath("$.pagination.totalElements") { value(1) }
        }
    }

    @Test
    fun `the same Idempotency-Key with a different body is a 409`() {
        val key = UUID.randomUUID().toString()
        createNote(user, title = "처음", key = key).andExpect { status { isCreated() } }
        createNote(user, title = "다른 내용", key = key).andExpect { status { isConflict() } }
    }

    @Test
    fun `a create without Idempotency-Key is refused`() {
        mockMvc.post("/api/v1/notes") {
            header("Authorization", "Bearer $user")
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"키 없음"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("COMMON.IDEMPOTENCY_ERROR") }
        }
    }

    @Test
    fun `a blank title is a 400 with the field named in errors`() {
        createNote(user, title = "   ").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("COMMON.VALIDATION_FAILED") }
            jsonPath("$.errors", hasSize<Any>(1))
            jsonPath("$.errors[0].field") { value("title") }
            jsonPath("$.errors[0].code") { value("NotBlank") }
            jsonPath("$.errors[0].message") { isNotEmpty() }
        }
    }

    @Test
    fun `too long title and body are reported per field`() {
        createNote(user, title = "가".repeat(81), body = "나".repeat(5001)).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[?(@.field=='title')].code") { value("Size") }
            jsonPath("$.errors[?(@.field=='body')].code") { value("Size") }
        }
    }

    @Test
    fun `an unknown status is a malformed request`() {
        createNote(user, title = "상태", status = "FINISHED").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("COMMON.MALFORMED_REQUEST") }
        }
    }

    @Test
    fun `anonymous callers are refused`() {
        mockMvc.get("/api/v1/notes").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a note can be read back, replaced and deleted`() {
        val id = createdId(user, title = "원본", body = "본문")

        getNote(user, id).andExpect {
            status { isOk() }
            jsonPath("$.value.title") { value("원본") }
            jsonPath("$.value.body") { value("본문") }
        }

        putNote(user, id, """{"title":"수정본","body":"새 본문","status":"ACTIVE","pinned":true,"attachmentKey":null,"attachmentName":null}""").andExpect {
            status { isOk() }
            jsonPath("$.value.id") { value(id) }
            jsonPath("$.value.title") { value("수정본") }
            jsonPath("$.value.status") { value("ACTIVE") }
            jsonPath("$.value.pinned") { value(true) }
        }

        deleteNote(user, id).andExpect { status { isNoContent() } }
        getNote(user, id).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOTES.NOT_FOUND") }
        }
    }

    @Test
    fun `update validates fields the same way`() {
        val id = createdId(user)
        putNote(user, id, """{"title":"","body":"","status":"DRAFT","pinned":false}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("title") }
        }
    }

    @Test
    fun `an attachment key must live under the caller's own storage prefix`() {
        val id = createdId(user)
        val own = "uploads/acc_user/${UUID.randomUUID()}/cat.png"

        putNote(user, id, """{"title":"사진","body":"","status":"ACTIVE","pinned":false,"attachmentKey":"$own","attachmentName":"cat.png"}""").andExpect {
            status { isOk() }
            jsonPath("$.value.attachmentKey") { value(own) }
            jsonPath("$.value.attachmentName") { value("cat.png") }
        }

        putNote(user, id, """{"title":"사진","body":"","status":"ACTIVE","pinned":false,"attachmentKey":"uploads/acc_admin/x/cat.png","attachmentName":"cat.png"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("attachmentKey") }
        }
        putNote(user, id, """{"title":"사진","body":"","status":"ACTIVE","pinned":false,"attachmentKey":"../etc/passwd","attachmentName":"x"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("attachmentKey") }
        }
    }

    @Test
    fun `removing or replacing the attachment deletes the stored object, deleting the note too`() {
        val id = createdId(user)
        val first = "uploads/acc_user/${UUID.randomUUID()}/a.txt"
        val second = "uploads/acc_user/${UUID.randomUUID()}/b.txt"
        storage.objects[first] = "a".toByteArray()
        storage.objects[second] = "b".toByteArray()

        putNote(user, id, """{"title":"t","body":"","status":"DRAFT","pinned":false,"attachmentKey":"$first","attachmentName":"a.txt"}""").andExpect { status { isOk() } }
        putNote(user, id, """{"title":"t","body":"","status":"DRAFT","pinned":false,"attachmentKey":"$second","attachmentName":"b.txt"}""").andExpect { status { isOk() } }
        kotlin.test.assertFalse(storage.objects.containsKey(first), "replaced attachment is removed")
        kotlin.test.assertTrue(storage.objects.containsKey(second))

        deleteNote(user, id).andExpect { status { isNoContent() } }
        kotlin.test.assertFalse(storage.objects.containsKey(second), "deleting the note removes its attachment")
    }
}
