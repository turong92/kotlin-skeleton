package dev.sumin.skeleton.workspace.web

import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.workspace.FakeWorkspaceStore
import dev.sumin.skeleton.workspace.WorkspaceMember
import dev.sumin.skeleton.workspace.WorkspaceRole
import dev.sumin.skeleton.workspace.WorkspaceService
import dev.sumin.skeleton.workspacetest.WorkspaceWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration(proxyBeanMethods = false)
class WorkspaceTestBeans {
    @Bean fun fake(): FakeWorkspaceStore = FakeWorkspaceStore()
    @Bean fun labels(): AccountLabels = AccountLabels { ids -> ids.associateWith { "$it@example.com" } }
}

/** 사용자 경로와 경계(`CurrentWorkspace`) - 헤더 한 줄로 거는 경계가 추방 직후 닫히고, 없는 · 남의 · 닫힌 워크스페이스가 같은 바이트로 답한다 */
@SpringBootTest(classes = [WorkspaceWebTestApplication::class])
@AutoConfigureMockMvc
@Import(WorkspaceTestBeans::class)
class WorkspaceWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeWorkspaceStore
    @Autowired lateinit var service: WorkspaceService

    private fun who(id: String) = TestingAuthenticationToken(id, "n/a", "ROLE_USER")
    private fun MockHttpServletRequestBuilder.by(id: String) = principal(who(id))
    private fun MockHttpServletRequestBuilder.inWs(id: String?) = apply { if (id != null) header("X-Workspace-Id", id) }
    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

    private lateinit var ws: String

    @BeforeEach
    fun seed() {
        store.clear()
        ws = service.create("acc_o", "Example Team").workspace.id
        store.insertMember(WorkspaceMember(ws, "acc_a", WorkspaceRole.ADMIN, java.time.Instant.now().plusSeconds(1)))
        store.insertMember(WorkspaceMember(ws, "acc_m", WorkspaceRole.MEMBER, java.time.Instant.now().plusSeconds(2)))
    }

    private fun body(r: MvcResult) = r.response.contentAsString.replace(Regex("\"(traceId|spanId|timestamp)\"\\s*:\\s*\"[^\"]*\""), "\"\$1\":\"-\"")

    // ---- 경계

    @Test
    fun `no header means no context - optional is none, require is 400 WORKSPACE_REQUIRED`() {
        mvc.perform(get("/probe/optional").by("acc_o")).andExpect(status().isOk).andExpect { assertEquals("none", it.response.contentAsString) }
        mvc.perform(get("/probe/member").by("acc_o")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORKSPACE.REQUIRED"))
        mvc.perform(get("/probe/member").by("acc_o").header("X-Workspace-Id", "  ")).andExpect(status().isBadRequest)
    }

    @Test
    fun `a member passes with the role read from the database, and the answer varies by the header`() {
        mvc.perform(get("/probe/member").by("acc_m").inWs(ws)).andExpect(status().isOk).andExpect { assertEquals("$ws:MEMBER", it.response.contentAsString) }
            .andExpect(header().stringValues("Vary", org.hamcrest.Matchers.hasItem("X-Workspace-Id")))
        mvc.perform(get("/probe/admin").by("acc_m").inWs(ws)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("WORKSPACE.FORBIDDEN"))
        mvc.perform(get("/probe/admin").by("acc_a").inWs(ws)).andExpect(status().isOk)
    }

    @Test
    fun `unknown, foreign and closed workspaces answer with the very same bytes`() {
        val closed = service.create("acc_c", "Closed Team").workspace.id
        store.closeWorkspace(closed, java.time.Instant.now())
        val foreign = mvc.perform(get("/probe/member").by("acc_x").inWs(ws)).andExpect(status().isNotFound).andReturn()
        val unknown = mvc.perform(get("/probe/member").by("acc_x").inWs("ws_nope")).andExpect(status().isNotFound).andReturn()
        val gone = mvc.perform(get("/probe/member").by("acc_c").inWs(closed)).andExpect(status().isNotFound).andReturn()
        val optionalForeign = mvc.perform(get("/probe/optional").by("acc_x").inWs(ws)).andExpect(status().isNotFound).andReturn()
        assertEquals(body(foreign), body(unknown))
        assertEquals(body(foreign), body(gone))
        assertEquals(body(foreign), body(optionalForeign))
        assertTrue(body(foreign).contains("WORKSPACE.NOT_FOUND"))
        assertTrue(!body(foreign).contains(ws), "the answer never repeats the id")
    }

    @Test
    fun `a removed member is refused on the very next request, a changed role applies at once`() {
        mvc.perform(get("/probe/member").by("acc_m").inWs(ws)).andExpect(status().isOk)
        mvc.perform(delete("/api/v1/workspaces/$ws/members/acc_m").by("acc_o")).andExpect(status().isNoContent)
        mvc.perform(get("/probe/member").by("acc_m").inWs(ws)).andExpect(status().isNotFound)

        mvc.perform(get("/probe/admin").by("acc_a").inWs(ws)).andExpect(status().isOk)
        mvc.perform(patch("/api/v1/workspaces/$ws/members/acc_a").by("acc_o").json("""{"role":"MEMBER"}""")).andExpect(status().isNoContent)
        mvc.perform(get("/probe/admin").by("acc_a").inWs(ws)).andExpect(status().isForbidden)
    }

    @Test
    fun `the operator routes do not exist unless the app turns them on`() {
        mvc.perform(get("/api/v1/admin/workspaces").by("acc_o")).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/admin/workspaces/$ws").by("acc_o")).andExpect(status().isNotFound)
    }

    @Test
    fun `without a caller the answer is 401`() {
        mvc.perform(get("/probe/member").inWs(ws)).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/workspaces")).andExpect(status().isUnauthorized)
    }

    // ---- 사용자 경로

    @Test
    fun `create answers 201 with the team I own, list and detail show the shapes the frontend reads`() {
        mvc.perform(post("/api/v1/workspaces").by("acc_n").json("""{"name":"Fresh Team"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.name").value("Fresh Team"))
            .andExpect(jsonPath("$.value.slug").value("fresh-team"))
            .andExpect(jsonPath("$.value.role").value("OWNER"))
            .andExpect(header().exists("Location"))
        mvc.perform(post("/api/v1/workspaces").by("acc_n").json("""{"name":" "}""")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORKSPACE.INVALID"))
        mvc.perform(post("/api/v1/workspaces").by("acc_n").json("""{"name":"Other","slug":"fresh-team"}""")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORKSPACE.SLUG_TAKEN"))
        mvc.perform(get("/api/v1/workspaces").by("acc_n")).andExpect(status().isOk).andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].slug").value("fresh-team"))

        mvc.perform(get("/api/v1/workspaces/$ws").by("acc_m"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.id").value(ws))
            .andExpect(jsonPath("$.value.role").value("MEMBER"))
            .andExpect(jsonPath("$.value.memberCount").value(3))
            .andExpect(jsonPath("$.value.pendingInvitations").value(0))
            .andExpect(jsonPath("$.value.seatLimit").doesNotExist())
            .andExpect(jsonPath("$.value.createdAt").exists())
    }

    @Test
    fun `patch renames and re-addresses, by-slug resolves the old address to the current one`() {
        mvc.perform(patch("/api/v1/workspaces/$ws").by("acc_m").json("""{"name":"Nope"}""")).andExpect(status().isForbidden)
        mvc.perform(patch("/api/v1/workspaces/$ws").by("acc_a").json("""{"name":"Renamed","slug":"renamed-team"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.name").value("Renamed")).andExpect(jsonPath("$.value.slug").value("renamed-team"))
        mvc.perform(get("/api/v1/workspaces/by-slug/example-team").by("acc_m")).andExpect(status().isOk).andExpect(jsonPath("$.value.slug").value("renamed-team")).andExpect(jsonPath("$.value.id").value(ws))
        mvc.perform(get("/api/v1/workspaces/by-slug/renamed-team").by("acc_x")).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/workspaces/by-slug/never-was").by("acc_m")).andExpect(status().isNotFound)
    }

    @Test
    fun `members, role, leave and transfer end to end over http`() {
        mvc.perform(get("/api/v1/workspaces/$ws/members").by("acc_m")).andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(3)).andExpect(jsonPath("$.values[0].accountId").value("acc_o")).andExpect(jsonPath("$.values[0].label").value("acc_o@example.com"))
            .andExpect(jsonPath("$.values[0].role").value("OWNER")).andExpect(jsonPath("$.values[0].joinedAt").exists())
        mvc.perform(patch("/api/v1/workspaces/$ws/members/acc_m").by("acc_o").json("""{"role":"OWNER"}""")).andExpect(status().isBadRequest)
        mvc.perform(patch("/api/v1/workspaces/$ws/members/acc_m").by("acc_o").json("""{"role":"nonsense"}""")).andExpect(status().isBadRequest)
        mvc.perform(post("/api/v1/workspaces/$ws/leave").by("acc_o")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORKSPACE.OWNER_CANNOT_LEAVE"))
        mvc.perform(post("/api/v1/workspaces/$ws/transfer").by("acc_o").json("""{"accountId":"acc_a"}""")).andExpect(status().isNoContent)
        mvc.perform(get("/api/v1/workspaces/$ws").by("acc_a")).andExpect(jsonPath("$.value.role").value("OWNER"))
        mvc.perform(post("/api/v1/workspaces/$ws/leave").by("acc_o")).andExpect(status().isNoContent)
        mvc.perform(get("/api/v1/workspaces/$ws").by("acc_o")).andExpect(status().isNotFound)
    }
}
