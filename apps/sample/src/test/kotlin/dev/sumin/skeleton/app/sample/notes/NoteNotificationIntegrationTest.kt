package dev.sumin.skeleton.app.sample.notes

import com.jayway.jsonpath.JsonPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
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
    fun `created and updated notifications link to the note, a deleted one links nowhere`() {
        val id = createdId(user, title = "링크 노트")
        putNote(user, id, """{"title":"링크 노트 2","body":"","status":"ACTIVE","pinned":false}""").andReturn()
        deleteNote(user, id).andReturn()

        val mine = items(user).filter { (it["payload"] as Map<*, *>)["noteId"] == id }.associateBy { it["type"] }
        assertEquals("/notes/$id", mine.getValue("NOTE_CREATED")["link"])
        assertEquals("/notes/$id", mine.getValue("NOTE_UPDATED")["link"])
        assertEquals(null, mine.getValue("NOTE_DELETED")["link"])
    }

    @Test
    fun `editing the same note again before it is read folds into one NOTE_UPDATED row with a count, and unread-count counts it once`() {
        val id = createdId(user, title = "접히는 노트")
        repeat(2) { n -> putNote(user, id, """{"title":"접히는 노트 ${n + 1}","body":"","status":"ACTIVE","pinned":false}""").andReturn() }

        val updated = items(user).filter { it["type"] == "NOTE_UPDATED" && (it["payload"] as Map<*, *>)["noteId"] == id }
        assertEquals(1, updated.size)
        assertEquals(2, updated.single()["collapsedCount"])
        assertTrue((updated.single()["message"] as String).contains("접히는 노트 2"), "the newest content wins")
        val unread = mockMvc.get("/api/v1/notifications/unread-count") { header("Authorization", "Bearer $user") }.andReturn().response.contentAsString
        assertEquals(2, JsonPath.read<Int>(unread, "$.value.count"), "created + the folded update")
    }

    @Test
    fun `muting NOTE_UPDATED stops its notifications but not the others, and unmuting brings them back`() {
        fun mute(muted: Boolean) = mockMvc.put("/api/v1/notifications/preferences/NOTE_UPDATED") {
            header("Authorization", "Bearer $user")
            contentType = MediaType.APPLICATION_JSON
            content = """{"muted":$muted}"""
        }
        val id = createdId(user, title = "끄는 노트")
        mute(true).andExpect { status { isOk() } }
        putNote(user, id, """{"title":"끈 뒤 수정","body":"","status":"ACTIVE","pinned":false}""").andReturn()
        assertTrue(items(user).none { it["type"] == "NOTE_UPDATED" })
        assertTrue(items(user).any { it["type"] == "NOTE_CREATED" })

        mute(false).andExpect { status { isOk() } }
        putNote(user, id, """{"title":"켠 뒤 수정","body":"","status":"ACTIVE","pinned":false}""").andReturn()
        assertEquals(1, items(user).count { it["type"] == "NOTE_UPDATED" })
    }

    @Test
    fun `only the types the app lists can be muted`() {
        mockMvc.put("/api/v1/notifications/preferences/NOTE_CREATED") {
            header("Authorization", "Bearer $user")
            contentType = MediaType.APPLICATION_JSON
            content = """{"muted":true}"""
        }.andExpect { status { isNotFound() } }
        val list = mockMvc.get("/api/v1/notifications/preferences") { header("Authorization", "Bearer $user") }.andReturn().response.contentAsString
        assertEquals(listOf("NOTE_UPDATED", "comment.created", "comment.replied", "reward.granted", "badge.awarded"), JsonPath.read<List<String>>(list, "$.value.items[*].type"))
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
