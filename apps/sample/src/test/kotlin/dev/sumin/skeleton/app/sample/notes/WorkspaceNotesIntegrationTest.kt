package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.app.sample.workspace.WorkspaceSeed
import dev.sumin.skeleton.workspace.WorkspaceMember
import dev.sumin.skeleton.workspace.WorkspaceRole
import dev.sumin.skeleton.workspace.WorkspaceService
import dev.sumin.skeleton.workspace.WorkspaceStore
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/** 워크스페이스 노트(헤더 `X-Workspace-Id`) - 남의 팀 노트가 안 보이고, 추방 직후 닫히고, 헤더가 없으면 예전 그대로인지 */
class WorkspaceNotesIntegrationTest : SampleIntegrationTest() {
    @Autowired lateinit var store: WorkspaceStore
    @Autowired lateinit var service: WorkspaceService

    @Test
    fun `members share the team's notes, a stranger sees nothing, and the team's notes are not in anyone's own list`() {
        val ws = createWorkspace(user, "Notes Team")
        val teamNote = createdTeamNote(user, ws, "팀 노트")
        val own = createdId(user, title = "내 노트")

        // 멤버가 아니면 존재도 모른다
        listNotes(other, ws).andExpect { status { isNotFound() }; jsonPath("$.code") { value("WORKSPACE.NOT_FOUND") } }
        getNote(other, teamNote, ws).andExpect { status { isNotFound() } }

        // 멤버는 같이 읽고 쓴다, 지우기는 쓴 사람 · ADMIN 이상
        join(ws, "acc_admin", WorkspaceRole.MEMBER)
        getNote(other, teamNote, ws).andExpect { status { isOk() }; jsonPath("$.value.title") { value("팀 노트") } }
        putNote(other, teamNote, """{"title":"고침","body":"","status":"DRAFT","pinned":false}""", ws).andExpect { status { isOk() } }
        deleteNote(other, teamNote, ws).andExpect { status { isForbidden() }; jsonPath("$.code") { value("WORKSPACE.FORBIDDEN") } }
        val othersNote = createdTeamNote(other, ws, "동료 노트")
        deleteNote(user, othersNote, ws).andExpect { status { isNoContent() } }   // 소유자(ADMIN 이상)는 남이 쓴 것도 지운다
        deleteNote(user, teamNote, ws).andExpect { status { isNoContent() } }

        // 내 노트와 팀 노트는 섞이지 않는다
        val again = createdTeamNote(user, ws, "다시")
        listNotes(user, ws).andExpect { jsonPath("$.values.length()") { value(1) }; jsonPath("$.values[0].id") { value(again) } }
        listNotes(user, null).andExpect { jsonPath("$.values.length()") { value(1) }; jsonPath("$.values[0].id") { value(own) } }
        getNote(user, again, null).andExpect { status { isNotFound() } }
        getNote(user, own, ws).andExpect { status { isNotFound() } }

        // 좌석 상한은 소유자의 구독이 정한다 - 구독이 없으면 무료 3
        mockMvc.get("/api/v1/workspaces/$ws") { header("Authorization", "Bearer $user") }.andExpect { jsonPath("$.value.seatLimit") { value(3) } }
    }

    @Test
    fun `an expelled member gets 404 on the very next request, even holding the old header`() {
        val ws = createWorkspace(user, "Expel Team")
        join(ws, "acc_admin", WorkspaceRole.MEMBER)
        val note = createdTeamNote(other, ws, "곧 쫓겨날 사람의 노트")
        getNote(other, note, ws).andExpect { status { isOk() } }

        mockMvc.delete("/api/v1/workspaces/$ws/members/acc_admin") { header("Authorization", "Bearer $user") }.andExpect { status { isNoContent() } }

        getNote(other, note, ws).andExpect { status { isNotFound() } }
        listNotes(other, ws).andExpect { status { isNotFound() } }
        createNoteIn(other, ws, "몰래").andExpect { status { isNotFound() } }
        getNote(other, note, null).andExpect { status { isNotFound() } }   // 쓴 사람이어도 헤더 없이는 팀 노트가 아니라 내 노트만이다
        listNotes(other, null).andExpect { jsonPath("$.values.length()") { value(0) } }
    }

    @Test
    fun `without the header notes work as before`() {
        val id = createdId(user, title = "예전 그대로")
        listNotes(user, null).andExpect { status { isOk() }; jsonPath("$.values[0].title") { value("예전 그대로") } }
        putNote(user, id, """{"title":"바뀜","body":"","status":"DRAFT","pinned":false}""", null).andExpect { status { isOk() } }
        mockMvc.get("/api/v1/notes/summary") { header("Authorization", "Bearer $user") }.andExpect { jsonPath("$.value.total") { value(1) } }
        listNotes(other, null).andExpect { jsonPath("$.values.length()") { value(0) } }
    }

    @Test
    fun `a team note's notifications go to whoever acted, never to an expelled author`() {
        val ws = createWorkspace(user, "Notify Team")
        join(ws, "acc_admin", WorkspaceRole.MEMBER)
        val note = createdTeamNote(other, ws, "쫓겨날 사람의 노트")
        mockMvc.delete("/api/v1/workspaces/$ws/members/acc_admin") { header("Authorization", "Bearer $user") }.andExpect { status { isNoContent() } }

        putNote(user, note, """{"title":"새 제목 비밀","body":"","status":"DRAFT","pinned":false}""", ws).andExpect { status { isOk() } }
        deleteNote(user, note, ws).andExpect { status { isNoContent() } }

        await { inbox(user).takeIf { it.contains("NOTE_DELETED") } }   // 고치고 지운 사람(소유자)이 받았다
        val rejected = inbox(other)
        assertTrue(!rejected.contains("새 제목 비밀") && !rejected.contains("NOTE_DELETED") && !rejected.contains("NOTE_UPDATED"), rejected)
    }

    @Test
    fun `exporting someone else's team note finishes for the requester`() {
        val ws = createWorkspace(user, "Export Team")
        join(ws, "acc_admin", WorkspaceRole.MEMBER)
        val note = createdTeamNote(user, ws, "팀 내보내기")

        mockMvc.post("/api/v1/notes/$note/export") { header("Authorization", "Bearer $other"); header("X-Workspace-Id", ws) }.andExpect { status { isAccepted() } }

        val done = await { inbox(other).takeIf { it.contains("NOTE_EXPORTED") } }
        assertTrue(done.contains(note), done)
    }

    @Test
    fun `team notes take no attachment and a member's edit never deletes the author's file`() {
        val ws = createWorkspace(user, "Attach Team")
        join(ws, "acc_admin", WorkspaceRole.MEMBER)
        val note = createdTeamNote(user, ws, "첨부 시도")

        putNote(other, note, """{"title":"x","attachmentKey":"uploads/acc_admin/a.png"}""", ws)
            .andExpect { status { isBadRequest() }; jsonPath("$.code") { value("NOTES.TEAM_ATTACHMENT_UNSUPPORTED") } }

        jdbc.sql("update notes set attachment_key = 'uploads/acc_user/keep.png', attachment_name = 'keep.png' where id = :id::uuid").param("id", note).update()
        putNote(other, note, """{"title":"고침","body":"","status":"DRAFT","pinned":false}""", ws).andExpect { status { isOk() } }
        assertEquals("uploads/acc_user/keep.png", jdbc.sql("select attachment_key from notes where id = :id::uuid").param("id", note).query(String::class.java).single())
    }

    @Test
    fun `the local seed makes Example Team once, owned by the sample user`() {
        val seed = WorkspaceSeed(service)
        seed.run()
        seed.run()
        val mine = service.list(WorkspaceSeed.OWNER).filter { it.workspace.slug == WorkspaceSeed.SLUG }
        assertEquals(1, mine.size)
        assertEquals("Example Team", mine.single().workspace.name)
        assertEquals(WorkspaceRole.OWNER, mine.single().role)
    }

    @Test
    fun `the invitation preview is public in the real security chain`() {
        mockMvc.post("/api/v1/workspace-invitations/preview") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"token":"nope"}"""
        }.andExpect { status { isBadRequest() }; jsonPath("$.code") { value("WORKSPACE.INVITATION_INVALID") } }
    }

    private fun createWorkspace(token: String, name: String): String =
        mockMvc.post("/api/v1/workspaces") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name-${UUID.randomUUID().toString().take(6)}"}"""
        }.andExpect { status { isCreated() } }.andReturn().readString("$.value.id")

    private fun join(workspaceId: String, accountId: String, role: WorkspaceRole) {
        check(store.insertMember(WorkspaceMember(workspaceId, accountId, role, Instant.now())))
    }

    private fun createNoteIn(token: String, ws: String?, title: String): ResultActionsDsl =
        mockMvc.post("/api/v1/notes") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", UUID.randomUUID().toString())
            ws?.let { header("X-Workspace-Id", it) }
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":${json(title)}}"""
        }

    private fun createdTeamNote(token: String, ws: String, title: String): String =
        createNoteIn(token, ws, title).andExpect { status { isCreated() } }.andReturn().readString("$.value.id")

    private fun listNotes(token: String, ws: String?): ResultActionsDsl =
        mockMvc.get("/api/v1/notes?size=100") { header("Authorization", "Bearer $token"); ws?.let { header("X-Workspace-Id", it) } }

    private fun getNote(token: String, id: String, ws: String?): ResultActionsDsl =
        mockMvc.get("/api/v1/notes/$id") { header("Authorization", "Bearer $token"); ws?.let { header("X-Workspace-Id", it) } }

    private fun putNote(token: String, id: String, json: String, ws: String?): ResultActionsDsl =
        mockMvc.put("/api/v1/notes/$id") {
            header("Authorization", "Bearer $token")
            ws?.let { header("X-Workspace-Id", it) }
            contentType = MediaType.APPLICATION_JSON
            content = json
        }

    private fun deleteNote(token: String, id: String, ws: String?): ResultActionsDsl =
        mockMvc.delete("/api/v1/notes/$id") { header("Authorization", "Bearer $token"); ws?.let { header("X-Workspace-Id", it) } }
}
