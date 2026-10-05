package dev.sumin.skeleton.app.sample.notes

import com.jayway.jsonpath.JsonPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request

class NoteNotificationIntegrationTest : SampleIntegrationTest() {
    private fun items(token: String): List<Map<String, Any?>> = JsonPath.read(inbox(token), "$.values")

    @Test
    fun `creating, updating and deleting a note each put a notification in the owner's inbox`() {
        val id = createdId(user, title = "알림 노트")
        putNote(user, id, """{"title":"알림 노트 2","body":"","status":"ACTIVE","pinned":false}""").andReturn()
        deleteNote(user, id).andReturn()

        val mine = items(user).filter { (it["payload"] as Map<*, *>)["noteId"] == id }
        assertEquals(setOf("NOTE_CREATED", "NOTE_UPDATED", "NOTE_DELETED"), mine.map { it["type"] }.toSet())
        val created = mine.single { it["type"] == "NOTE_CREATED" }
        assertEquals("notes", created["topic"])
        assertEquals("SUCCESS", created["severity"])
        assertEquals("노트를 만들었어요", created["title"])
        assertTrue((created["message"] as String).contains("알림 노트"))
        assertEquals("알림 노트", (created["payload"] as Map<*, *>)["noteTitle"])
        assertEquals(null, created["readAt"])
    }

    @Test
    fun `the other person's inbox does not get them`() {
        val id = createdId(user, title = "혼자 보는 노트")
        assertTrue(items(other).none { (it["payload"] as Map<*, *>)["noteId"] == id })
    }

    @Test
    fun `a failed create leaves no notification behind`() {
        createNote(user, title = "").andReturn()
        assertTrue(items(user).isEmpty())
    }

    @Test
    fun `the owner receives the notification over server-sent events and nobody else does`() {
        val mine = mockMvc.perform(
            get("/api/v1/notifications/sse")
                .header("Authorization", "Bearer $user").accept(MediaType.TEXT_EVENT_STREAM),
        ).andExpect(request().asyncStarted()).andReturn()
        val theirs = mockMvc.perform(
            get("/api/v1/notifications/sse")
                .header("Authorization", "Bearer $other").accept(MediaType.TEXT_EVENT_STREAM),
        ).andExpect(request().asyncStarted()).andReturn()

        val id = createdId(user, title = "실시간 노트")

        val stream = await { mine.response.getContentAsString(Charsets.UTF_8).takeIf { it.contains(id) } }
        assertTrue(stream.contains("event:NOTE_CREATED"), stream)
        assertTrue(stream.contains("노트를 만들었어요"))
        assertFalse(theirs.response.getContentAsString(Charsets.UTF_8).contains(id), "other accounts must not see someone else's events")
    }
}
