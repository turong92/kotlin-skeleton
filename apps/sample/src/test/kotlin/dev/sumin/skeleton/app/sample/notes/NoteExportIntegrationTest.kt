package dev.sumin.skeleton.app.sample.notes

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.post

class NoteExportIntegrationTest : SampleIntegrationTest() {
    @Autowired private lateinit var jobs: JobQueue

    @Test
    fun `export answers 202 with a job id, writes a markdown file and tells the owner when it is done`() {
        val id = createdId(user, title = "내보낼 노트", body = "본문 한 줄")

        val jobId = mockMvc.post("/api/v1/notes/$id/export") { header("Authorization", "Bearer $user") }
            .andExpect {
                status { isAccepted() }
                jsonPath("$.value.jobId") { isNotEmpty() }
            }.andReturn().readString("$.value.jobId")

        val done = await {
            JsonPath.read<List<Map<String, Any?>>>(inbox(user), "$.values").firstOrNull {
                it["type"] == "NOTE_EXPORTED" && (it["payload"] as Map<*, *>)["noteId"] == id
            }
        }
        assertEquals("노트 내보내기 완료", done["title"])
        assertEquals("SUCCESS", done["severity"])
        val payload = done["payload"] as Map<*, *>
        assertEquals(jobId, payload["jobId"])
        val key = payload["exportKey"] as String
        assertTrue(key.startsWith("uploads/acc_user/exports/"), key)
        assertTrue(key.endsWith(".md"), key)
        val file = String(requireNotNull(storage.objects[key]) { "export file was not stored under $key" })
        assertTrue(file.contains("# 내보낼 노트") && file.contains("본문 한 줄"), file)
    }

    @Test
    fun `the exported file can be downloaded through the storage endpoint by its owner only`() {
        val id = createdId(user, title = "다운로드")
        mockMvc.post("/api/v1/notes/$id/export") { header("Authorization", "Bearer $user") }.andExpect { status { isAccepted() } }
        val key = await {
            JsonPath.read<List<Map<String, Any?>>>(inbox(user), "$.values").firstOrNull { it["type"] == "NOTE_EXPORTED" }
                ?.let { (it["payload"] as Map<*, *>)["exportKey"] as String }
        }

        mockMvc.post("/api/v1/storage/presign-download") {
            header("Authorization", "Bearer $user")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"key":"$key"}"""
        }.andExpect { status { isOk() }; jsonPath("$.value.method") { value("GET") } }
        mockMvc.post("/api/v1/storage/presign-download") {
            header("Authorization", "Bearer $other")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"key":"$key"}"""
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `an export job whose note no longer exists goes straight to DEAD without a notification`() {
        val gone = UUID.randomUUID()
        val jobId = jobs.enqueue("note-export", """{"noteId":"$gone","ownerId":"acc_user"}""")

        val status = await {
            jdbc.sql("select status from skeleton_jobs where id = :id").param("id", jobId).query(String::class.java).single()
                .takeIf { it == "DONE" || it == "DEAD" }
        }
        assertEquals("DEAD", status)
        assertFalse(inbox(user).contains("NOTE_EXPORTED"))
    }
}
