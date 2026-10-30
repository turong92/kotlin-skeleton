package dev.sumin.skeleton.workspace.web

import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.workspace.FakeWorkspaceStore
import dev.sumin.skeleton.workspace.WorkspaceMember
import dev.sumin.skeleton.workspace.WorkspaceRole
import dev.sumin.skeleton.workspace.WorkspaceService
import dev.sumin.skeleton.workspacetest.WorkspaceWebTestApplication
import java.time.Instant
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
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration(proxyBeanMethods = false)
class AdminTestBeans {
    @Bean fun adminStore(): FakeWorkspaceStore = FakeWorkspaceStore()
    @Bean fun adminLabels(): AccountLabels = AccountLabels { ids -> ids.associateWith { "$it@example.com" } }

    /** 시험용 판정: 토큰의 역할 이름이 곧 권한 키다 */
    @Bean fun adminAccess(): OperatorAccess = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = ref.tokenRoles
    }
}

/** 운영자 읽기 경로 - 기본 꺼짐, `workspace.read`, 계정 이름은 `account.read` 도 있을 때만 */
@SpringBootTest(classes = [WorkspaceWebTestApplication::class], properties = ["skeleton.workspace.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(AdminTestBeans::class)
class WorkspaceAdminWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeWorkspaceStore
    @Autowired lateinit var service: WorkspaceService
    @Autowired lateinit var declarations: List<PermissionDeclaration>

    private fun staff(vararg permissions: String) = TestingAuthenticationToken("staff", "n/a", *permissions.map { "ROLE_$it" }.toTypedArray())
    private fun MockHttpServletRequestBuilder.asStaff(vararg permissions: String) = principal(staff(*permissions))

    private lateinit var alpha: String
    private lateinit var beta: String

    @BeforeEach
    fun seed() {
        store.clear()
        alpha = service.create("acc_o", "Alpha Team").workspace.id
        store.insertMember(WorkspaceMember(alpha, "acc_m", WorkspaceRole.MEMBER, Instant.now().plusSeconds(1)))
        beta = service.create("acc_p", "Beta Crew").workspace.id
    }

    @Test
    fun `the module declares workspace-read and nothing else for operators`() {
        val declared = declarations.single { it.module == "workspace" }
        assertEquals(listOf("workspace.read"), declared.permissions.map { it.key })
        assertTrue(declared.permissions.single().defaultRoles.isEmpty(), "only the all-permissions role gets it by default")
    }

    @Test
    fun `anonymous is 401 and an operator without workspace-read is 403, on both routes`() {
        mvc.perform(get("/api/v1/admin/workspaces")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/admin/workspaces").asStaff("account.read")).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/workspaces/$alpha").asStaff("badge.manage")).andExpect(status().isForbidden)
    }

    @Test
    fun `the list shows name, owner, member count, status and date, and the owner's name only with account-read`() {
        mvc.perform(get("/api/v1/admin/workspaces").asStaff("workspace.read"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(2))
            .andExpect(jsonPath("$.values[0].id").value(beta))   // 새 것부터
            .andExpect(jsonPath("$.values[0].name").value("Beta Crew"))
            .andExpect(jsonPath("$.values[0].slug").value("beta-crew"))
            .andExpect(jsonPath("$.values[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.values[0].ownerId").value("acc_p"))
            .andExpect(jsonPath("$.values[0].ownerLabel").value(null as String?))
            .andExpect(jsonPath("$.values[1].memberCount").value(2))
            .andExpect(jsonPath("$.values[1].createdAt").exists())
        mvc.perform(get("/api/v1/admin/workspaces").asStaff("workspace.read", "account.read"))
            .andExpect(jsonPath("$.values[0].ownerLabel").value("acc_p@example.com"))
    }

    @Test
    fun `search matches name or address, and the cursor pages newest first`() {
        mvc.perform(get("/api/v1/admin/workspaces").param("q", "ALPHA").asStaff("workspace.read"))
            .andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].id").value(alpha))
        mvc.perform(get("/api/v1/admin/workspaces").param("q", "beta-c").asStaff("workspace.read"))
            .andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].id").value(beta))
        mvc.perform(get("/api/v1/admin/workspaces").param("q", "nothing").asStaff("workspace.read")).andExpect(jsonPath("$.values.length()").value(0))

        val first = mvc.perform(get("/api/v1/admin/workspaces").param("size", "1").asStaff("workspace.read"))
            .andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].id").value(beta)).andExpect(jsonPath("$.cursor.hasNext").value(true)).andReturn()
        val next = Regex("\"nextCursor\"\\s*:\\s*\"([^\"]+)\"").find(first.response.contentAsString)!!.groupValues[1]
        mvc.perform(get("/api/v1/admin/workspaces").param("size", "1").param("cursor", next).asStaff("workspace.read"))
            .andExpect(jsonPath("$.values[0].id").value(alpha)).andExpect(jsonPath("$.cursor.hasNext").value(false))
    }

    @Test
    fun `the detail lists members and the pending invitation count, shows closed teams, and 404s an unknown id`() {
        mvc.perform(get("/api/v1/admin/workspaces/$alpha").asStaff("workspace.read"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.id").value(alpha))
            .andExpect(jsonPath("$.value.status").value("ACTIVE"))
            .andExpect(jsonPath("$.value.memberCount").value(2))
            .andExpect(jsonPath("$.value.pendingInvitations").value(0))
            .andExpect(jsonPath("$.value.seatLimit").value(null as Int?))
            .andExpect(jsonPath("$.value.members.length()").value(2))
            .andExpect(jsonPath("$.value.members[0].accountId").value("acc_o"))
            .andExpect(jsonPath("$.value.members[0].role").value("OWNER"))
            .andExpect(jsonPath("$.value.members[0].label").value(null as String?))
        mvc.perform(get("/api/v1/admin/workspaces/$alpha").asStaff("workspace.read", "account.read"))
            .andExpect(jsonPath("$.value.members[0].label").value("acc_o@example.com")).andExpect(jsonPath("$.value.ownerLabel").value("acc_o@example.com"))

        store.closeWorkspace(beta, Instant.now())
        mvc.perform(get("/api/v1/admin/workspaces/$beta").asStaff("workspace.read")).andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("CLOSED"))
        mvc.perform(get("/api/v1/admin/workspaces/ws_nope").asStaff("workspace.read")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("WORKSPACE.NOT_FOUND"))
    }
}
