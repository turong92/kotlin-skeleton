package dev.sumin.skeleton.workspace

import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.notification.mail.MailSendResult
import dev.sumin.skeleton.notification.mail.MailSender
import dev.sumin.skeleton.workspace.mail.LogOnlyWorkspaceMailTransport
import dev.sumin.skeleton.workspace.mail.MailSenderWorkspaceTransport
import dev.sumin.skeleton.workspace.mail.WorkspaceMailAutoConfiguration
import dev.sumin.skeleton.workspace.mail.WorkspaceMailTransport
import dev.sumin.skeleton.workspace.web.WorkspaceInvitationAcceptController
import dev.sumin.skeleton.workspace.web.WorkspaceInvitationController
import dev.sumin.skeleton.workspace.web.WorkspaceWebAutoConfiguration
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner

/** 초대 조립 - 켜야 켜지고, `AccountFacts` 없이는 서지 않고, 메일 모듈이 있으면 그리로 · 없으면 로그로, 보호 환경에서는 가드가 막는다 */
class WorkspaceInvitationAssemblyTest {
    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WorkspaceMailAutoConfiguration::class.java, WorkspaceAutoConfiguration::class.java, WorkspaceWebAutoConfiguration::class.java))
        .withBean(WorkspaceStore::class.java, Supplier { FakeWorkspaceStore() })
    private val on = "skeleton.workspace.invitation.enabled=true"

    @Test
    fun `invitations are off until the app turns them on, and then they need nothing but AccountFacts and a link address`() {
        runner.run { ctx ->
            assertTrue(ctx.getBeansOfType(WorkspaceInvitationService::class.java).isEmpty())
            assertTrue(ctx.getBeansOfType(WorkspaceInvitationController::class.java).isEmpty())
            assertTrue(ctx.getBeansOfType(WorkspaceDeployGuard::class.java).isEmpty())
        }
        runner.withBean(AccountFacts::class.java, Supplier { AccountFacts { null } }).withPropertyValues(on).run { ctx ->
            assertEquals(null, ctx.startupFailure)
            assertNotNull(ctx.getBean(WorkspaceInvitationService::class.java))
            assertNotNull(ctx.getBean(WorkspaceInvitationController::class.java))
            assertNotNull(ctx.getBean(WorkspaceInvitationAcceptController::class.java))
            assertTrue(ctx.getBean(WorkspaceMailTransport::class.java) is LogOnlyWorkspaceMailTransport, "no MailSender: log only")
            assertNotNull(ctx.getBean(WorkspaceDeployGuard::class.java))
        }
    }

    @Test
    fun `turning invitations on without an AccountFacts bean fails the start and names the bean and the switch`() {
        runner.withPropertyValues(on).run { ctx ->
            val message = generateSequence(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue(message.contains("AccountFacts") && message.contains("skeleton.workspace.invitation.enabled") && message.contains("modules:account"), message)
        }
    }

    @Test
    fun `with notification-mail's MailSender the invitation goes through it`() {
        runner.withBean(AccountFacts::class.java, Supplier { AccountFacts { null } })
            .withBean(MailSender::class.java, Supplier { MailSender { MailSendResult(true) } })
            .withPropertyValues(on).run { ctx ->
                assertTrue(ctx.getBean(WorkspaceMailTransport::class.java) is MailSenderWorkspaceTransport)
            }
    }

    @Test
    fun `in stage or prod the guard refuses log-only mail, shown links and an empty link address`() {
        val prod = DeployContext(DeployEnv.PROD, emptySet())
        val local = DeployContext(DeployEnv.LOCAL, emptySet())
        val logOnly = LogOnlyWorkspaceMailTransport(false)
        val sender = MailSenderWorkspaceTransport { MailSendResult(true) }

        val bare = WorkspaceDeployGuard(WorkspaceProperties(), transport = { logOnly })
        assertEquals(2, bare.problems(prod).size, bare.problems(prod).toString())
        assertTrue(bare.problems(local).isEmpty())

        val shown = WorkspaceProperties(mail = WorkspaceProperties.Mail("https://app.example.com", logLinks = WorkspaceProperties.Mail.LogLinks.ON))
        assertTrue(WorkspaceDeployGuard(shown, transport = { sender }).problems(prod).single().contains("log-links=ON"))

        val ready = WorkspaceProperties(mail = WorkspaceProperties.Mail("https://app.example.com"))
        assertTrue(WorkspaceDeployGuard(ready, transport = { sender }).problems(prod).isEmpty())

        // 프로젝트 모드: 링크 주소는 프로젝트마다 자기 값이어야 한다
        val projects = listOf(WorkspaceDeployGuard.ProjectView("a", "https://a.example.com"), WorkspaceDeployGuard.ProjectView("b", ""))
        val problems = WorkspaceDeployGuard(ready, projects) { sender }.problems(prod)
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("project 'b'"))
    }
}
