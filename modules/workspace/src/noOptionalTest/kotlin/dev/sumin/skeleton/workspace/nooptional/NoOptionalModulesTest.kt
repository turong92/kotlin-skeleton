package dev.sumin.skeleton.workspace.nooptional

import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.workspace.WorkspaceAutoConfiguration
import dev.sumin.skeleton.workspace.WorkspaceInvitationService
import dev.sumin.skeleton.workspace.WorkspaceStore
import dev.sumin.skeleton.workspace.mail.LogOnlyWorkspaceMailTransport
import dev.sumin.skeleton.workspace.mail.WorkspaceMailAutoConfiguration
import dev.sumin.skeleton.workspace.mail.WorkspaceMailTransport
import dev.sumin.skeleton.workspace.web.WorkspaceInvitationController
import dev.sumin.skeleton.workspace.web.WorkspaceWebAutoConfiguration
import java.lang.reflect.Proxy
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner

/**
 * 선택 통합(`notification-mail`)이 클래스패스에 **정말 없을 때**도 초대가 뜬다 - compileOnly 의존은 소비자에게 따라오지 않으므로,
 * 앱이 그 모듈을 안 더했을 때의 모습이다. 이때 초대 링크는 로그로만 간다(보호 환경에서는 가드가 막는다).
 */
class NoOptionalModulesTest {
    private val store = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WorkspaceStore::class.java)) { _, method, _ -> error("not used: ${method.name}") } as WorkspaceStore

    @Test
    fun `notification-mail is not on this classpath`() {
        assertTrue(runCatching { Class.forName("dev.sumin.skeleton.notification.mail.MailSender") }.isFailure)
    }

    @Test
    fun `invitations start and fall back to log-only mail without it`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WorkspaceMailAutoConfiguration::class.java, WorkspaceAutoConfiguration::class.java, WorkspaceWebAutoConfiguration::class.java))
            .withBean(WorkspaceStore::class.java, Supplier { store })
            .withBean(AccountFacts::class.java, Supplier { AccountFacts { null } })
            .withPropertyValues("skeleton.workspace.invitation.enabled=true")
            .run { ctx ->
                assertTrue(ctx.startupFailure == null, ctx.startupFailure?.toString())
                assertTrue(ctx.getBean(WorkspaceMailTransport::class.java) is LogOnlyWorkspaceMailTransport)
                assertTrue(ctx.getBeansOfType(WorkspaceInvitationService::class.java).isNotEmpty())
                assertTrue(ctx.getBeansOfType(WorkspaceInvitationController::class.java).isNotEmpty())
            }
    }
}
