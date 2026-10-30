package dev.sumin.skeleton.workspace.web

import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.workspace.FakeWorkspaceStore
import dev.sumin.skeleton.workspace.WorkspaceService
import dev.sumin.skeleton.workspace.mail.WorkspaceMailTransport
import dev.sumin.skeleton.workspacetest.WorkspaceWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class InviteTestFacts : AccountFacts {
    val verified = mutableMapOf<String, String>()
    override fun createdAt(accountId: String) = null
    override fun verifiedEmail(accountId: String) = verified[accountId]
}

class CapturedMails : WorkspaceMailTransport {
    class Sent(val to: String, val subject: String, val text: String)

    val sent = mutableListOf<Sent>()
    override fun send(to: String, subject: String, text: String): Boolean { sent += Sent(to, subject, text); return true }
    fun tokenOf(to: String) = Regex("token=([A-Za-z0-9_-]+)").find(sent.last { it.to == to }.text)!!.groupValues[1]
}

@TestConfiguration(proxyBeanMethods = false)
class InviteTestBeans {
    @Bean fun inviteStore(): FakeWorkspaceStore = FakeWorkspaceStore()
    @Bean fun inviteLabels(): AccountLabels = AccountLabels { ids -> ids.associateWith { "$it@example.com" } }
    @Bean fun inviteFacts(): InviteTestFacts = InviteTestFacts()
    @Bean fun inviteMails(): CapturedMails = CapturedMails()
}

/** 초대 경로 - 프론트가 읽는 응답 모양, 오류 코드, 그리고 토큰이 응답 어디에도 없다는 것 */
@SpringBootTest(
    classes = [WorkspaceWebTestApplication::class],
    properties = ["skeleton.workspace.invitation.enabled=true", "skeleton.workspace.mail.link-base-url=https://app.example.com"],
)
@AutoConfigureMockMvc
@Import(InviteTestBeans::class)
class WorkspaceInvitationWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeWorkspaceStore
    @Autowired lateinit var service: WorkspaceService
    @Autowired lateinit var facts: InviteTestFacts
    @Autowired lateinit var mails: CapturedMails

    private fun MockHttpServletRequestBuilder.by(id: String) = principal(TestingAuthenticationToken(id, "n/a", "ROLE_USER"))
    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

    private lateinit var ws: String

    @BeforeEach
    fun seed() {
        store.clear()
        mails.sent.clear()
        facts.verified.clear()
        ws = service.create("acc_o", "Example Team").workspace.id
    }

    private fun inviteBody(vararg emails: String, role: String = "MEMBER") = """{"emails":[${emails.joinToString(",") { "\"$it\"" }}],"role":"$role"}"""

    @Test
    fun `invite, list, preview and accept - the whole journey with the shapes the frontend reads, and no token in any response`() {
        val bodies = mutableListOf<String>()
        fun capture(r: org.springframework.test.web.servlet.ResultActions) = r.andReturn().response.contentAsString.also { bodies += it }

        capture(mvc.perform(post("/api/v1/workspaces/$ws/invitations").by("acc_o").header("Accept-Language", "ko-KR,ko;q=0.9").json(inviteBody("Alice@Example.com", "bob@example.com")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.invited.length()").value(2))
            .andExpect(jsonPath("$.value.invited[0]").value("alice@example.com"))
            .andExpect(jsonPath("$.value.alreadyInvited.length()").value(0))
            .andExpect(jsonPath("$.value.seatLimited.length()").value(0)))
        assertEquals(2, mails.sent.size)
        assertTrue(mails.sent.first().text.contains("https://app.example.com/invite?token="), "the link is in the mail")
        assertTrue(mails.sent.first().text.contains("초대했어요"), "the mail speaks the inviter's language")

        capture(mvc.perform(get("/api/v1/workspaces/$ws/invitations").by("acc_o"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(2))
            .andExpect(jsonPath("$.values[0].email").value("alice@example.com"))
            .andExpect(jsonPath("$.values[0].role").value("MEMBER"))
            .andExpect(jsonPath("$.values[0].expired").value(false))
            .andExpect(jsonPath("$.values[0].sentCount").value(1))
            .andExpect(jsonPath("$.values[0].invitedBy").value("acc_o"))
            .andExpect(jsonPath("$.values[0].expiresAt").exists()))
        mvc.perform(get("/api/v1/workspaces/$ws").by("acc_o")).andExpect(jsonPath("$.value.pendingInvitations").value(2))

        val token = mails.tokenOf("alice@example.com")
        // 미리보기 - 로그인 없이
        capture(mvc.perform(post("/api/v1/workspace-invitations/preview").json("""{"token":"$token"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.workspaceName").value("Example Team"))
            .andExpect(jsonPath("$.value.inviterLabel").value("acc_o@example.com"))
            .andExpect(jsonPath("$.value.emailMasked").value("a***@e***.com"))
            .andExpect(jsonPath("$.value.role").value("MEMBER"))
            .andExpect(jsonPath("$.value.expiresAt").exists()))

        // 수락 - 로그인, 확인된 같은 주소
        facts.verified["acc_alice"] = "alice@example.com"
        capture(mvc.perform(post("/api/v1/workspace-invitations/accept").by("acc_alice").json("""{"token":"$token"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.id").value(ws))
            .andExpect(jsonPath("$.value.slug").value("example-team"))
            .andExpect(jsonPath("$.value.role").value("MEMBER")))
        mvc.perform(get("/api/v1/workspaces/$ws/members").by("acc_o")).andExpect(jsonPath("$.values.length()").value(2))
        mvc.perform(get("/api/v1/workspaces/$ws").by("acc_o")).andExpect(jsonPath("$.value.pendingInvitations").value(1))

        mails.sent.map { mails.tokenOf(it.to) }.forEach { t -> assertFalse(bodies.any { it.contains(t) }, "a response carries a token") }
    }

    @Test
    fun `the token errors say why, and the accept refusals have their own codes`() {
        mvc.perform(post("/api/v1/workspace-invitations/preview").json("""{"token":"nope"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORKSPACE.INVITATION_INVALID")).andExpect(jsonPath("$.data.reason").value("INVALID"))
        mvc.perform(post("/api/v1/workspace-invitations/preview").json("{}")).andExpect(status().isBadRequest).andExpect(jsonPath("$.data.reason").value("INVALID"))

        mvc.perform(post("/api/v1/workspaces/$ws/invitations").by("acc_o").json(inviteBody("alice@example.com"))).andExpect(status().isOk)
        val token = mails.tokenOf("alice@example.com")
        mvc.perform(post("/api/v1/workspace-invitations/accept").json("""{"token":"$token"}""")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/workspace-invitations/accept").by("acc_alice").json("""{"token":"$token"}""")).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("WORKSPACE.EMAIL_UNVERIFIED"))
        facts.verified["acc_alice"] = "mallory@example.com"
        mvc.perform(post("/api/v1/workspace-invitations/accept").by("acc_alice").json("""{"token":"$token"}""")).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("WORKSPACE.INVITATION_EMAIL_MISMATCH"))

        facts.verified["acc_alice"] = "alice@example.com"
        mvc.perform(post("/api/v1/workspace-invitations/accept").by("acc_alice").json("""{"token":"$token"}""")).andExpect(status().isOk)
        mvc.perform(post("/api/v1/workspace-invitations/preview").json("""{"token":"$token"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.data.reason").value("USED"))

        // 이미 멤버인 사람이 또 받은 초대 - 409, 팀 주소를 돌려줘 화면이 그리로 보낸다
        mvc.perform(post("/api/v1/workspaces/$ws/invitations").by("acc_o").json(inviteBody("alice@example.com"))).andExpect(status().isOk)
        mvc.perform(post("/api/v1/workspace-invitations/accept").by("acc_alice").json("""{"token":"${mails.tokenOf("alice@example.com")}"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORKSPACE.ALREADY_MEMBER")).andExpect(jsonPath("$.data.slug").value("example-team"))
    }

    @Test
    fun `resend, revoke and the admin-only rules answer 204, 429, 403 and 404 with their codes`() {
        mvc.perform(post("/api/v1/workspaces/$ws/invitations").by("acc_o").json(inviteBody("alice@example.com"))).andExpect(status().isOk)
        val id = store.invitationRows().single().id

        mvc.perform(post("/api/v1/workspaces/$ws/invitations/$id/resend").by("acc_o")).andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("WORKSPACE.RESEND_TOO_SOON"))
        mvc.perform(get("/api/v1/workspaces/$ws/invitations").by("acc_stranger")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("WORKSPACE.NOT_FOUND"))
        mvc.perform(get("/api/v1/workspaces/$ws/invitations")).andExpect(status().isUnauthorized)

        mvc.perform(delete("/api/v1/workspaces/$ws/invitations/$id").by("acc_o")).andExpect(status().isNoContent)
        mvc.perform(delete("/api/v1/workspaces/$ws/invitations/$id").by("acc_o")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("WORKSPACE.INVITATION_NOT_FOUND"))
        mvc.perform(post("/api/v1/workspaces/$ws/invitations").by("acc_o").json(inviteBody("not-an-address"))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORKSPACE.INVALID"))
    }
}
