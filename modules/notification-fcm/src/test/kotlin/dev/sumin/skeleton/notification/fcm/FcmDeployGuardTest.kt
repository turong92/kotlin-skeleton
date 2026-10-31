package dev.sumin.skeleton.notification.fcm

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.notification.push.PushSender
import java.util.Base64
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class FcmDeployGuardTest {
    private val account = TestServiceAccount()
    private val stage = DeployContext(DeployEnv.STAGE, emptySet())
    private val local = DeployContext(DeployEnv.LOCAL, emptySet())

    private fun problems(credentials: String, context: DeployContext = stage) =
        FcmDeployGuard(FcmProperties(enabled = true, credentials = credentials)).problems(context)

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun `a readable service account passes a protected environment`() {
        assertThat(problems(account.base64())).isEmpty()
        assertThat(problems(account.json())).`as`("plain JSON is accepted too").isEmpty()
    }

    @Test
    fun `empty or unreadable credentials stop a protected environment and name only the property and variable`() {
        val unreadable = listOf(
            "", "   ", "not base64 !!", b64("not json"), b64("{}"),
            b64(account.json().replace("-----BEGIN PRIVATE KEY-----", "garbage")),
            b64(account.json().replace("\"client_email\"", "\"x\"")),
            b64(account.json().replace("\"service_account\"", "\"authorized_user\"")),
        )
        unreadable.forEach { credentials ->
            val found = problems(credentials)
            assertThat(found).`as`(credentials.take(20)).isNotEmpty()
            assertThat(found.joinToString()).contains("NOTIFICATION_FCM_CREDENTIALS").doesNotContain(account.privatePem.lines()[1])
        }
    }

    @Test
    fun `a project id that could change the request path is refused`() {
        listOf("../other", "a/b", "demo project", "demo?x=1", "x", "UPPER-case").forEach {
            assertThat(problems(b64(account.json(projectId = it)))).`as`(it).isNotEmpty()
        }
    }

    @Test
    fun `outside a protected environment problems are only warnings`() {
        assertThat(problems("", local)).isEmpty()
        assertThat(FcmDeployGuard(FcmProperties(enabled = true, credentials = "")).warnings(local)).isNotEmpty()
    }

    // ---- 조립

    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FcmAutoConfiguration::class.java))

    @Test
    fun `off by default - no sender and no guard`() {
        runner.run { ctx ->
            assertThat(ctx.getBeansOfType(PushSender::class.java)).isEmpty()
            assertThat(ctx.getBeansOfType(DeployGuard::class.java)).isEmpty()
        }
    }

    @Test
    fun `enabled with readable credentials registers the FCM sender and the guard`() {
        runner.withPropertyValues("skeleton.notification.fcm.enabled=true", "skeleton.notification.fcm.credentials=${account.base64()}").run { ctx ->
            assertThat(ctx.getBean(PushSender::class.java).kind).isEqualTo("FCM")
            assertThat(ctx.getBeansOfType(DeployGuard::class.java).values.map { it.name }).contains("notification-fcm")
        }
    }

    @Test
    fun `enabled with unreadable credentials has no sender while the guard still stands to stop a protected start`() {
        runner.withPropertyValues("skeleton.notification.fcm.enabled=true", "skeleton.notification.fcm.credentials=").run { ctx ->
            assertThat(ctx.getBeansOfType(PushSender::class.java)).isEmpty()
            assertThat(ctx.getBean(DeployGuard::class.java).problems(stage)).isNotEmpty()
        }
    }
}
