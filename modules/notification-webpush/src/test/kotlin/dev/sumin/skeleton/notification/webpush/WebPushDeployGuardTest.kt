package dev.sumin.skeleton.notification.webpush

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.notification.push.PushSender
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class WebPushDeployGuardTest {
    private val keys = TestKeys()
    private val stage = DeployContext(DeployEnv.STAGE, emptySet())
    private val local = DeployContext(DeployEnv.LOCAL, emptySet())

    private fun props(
        publicKey: String = keys.vapidPublic,
        privateKey: String = keys.vapidPrivate,
        subject: String = "mailto:ops@example.com",
        allowed: List<String> = WebPushProperties.DEFAULT_ALLOWED_HOSTS,
    ) = WebPushProperties(enabled = true, publicKey = publicKey, privateKey = privateKey, subject = subject, allowedHosts = allowed)

    private fun problems(p: WebPushProperties, context: DeployContext = stage) = WebPushDeployGuard(p).problems(context)

    @Test
    fun `a correct configuration passes a protected environment`() {
        assertThat(problems(props())).isEmpty()
    }

    @Test
    fun `blank keys or subject stop a protected environment and name only the environment variables`() {
        listOf(props(privateKey = ""), props(publicKey = ""), props(subject = ""), props(publicKey = "", privateKey = "", subject = "")).forEach {
            val found = problems(it)
            assertThat(found).isNotEmpty()
            assertThat(found.joinToString()).contains("NOTIFICATION_WEBPUSH").doesNotContain(keys.vapidPrivate)
        }
    }

    @Test
    fun `a public key that does not belong to the private key stops a protected environment`() {
        val other = TestKeys()
        val found = problems(props(publicKey = other.vapidPublic))
        assertThat(found).hasSize(1)
        assertThat(found.single()).contains("pair").doesNotContain(keys.vapidPrivate).doesNotContain(other.vapidPublic)
    }

    @Test
    fun `malformed keys or a subject that is neither mailto nor https stop a protected environment`() {
        assertThat(problems(props(privateKey = "not-a-key"))).isNotEmpty()
        assertThat(problems(props(publicKey = "AAAA"))).isNotEmpty()
        assertThat(problems(props(subject = "ops@example.com"))).isNotEmpty()
        assertThat(problems(props(subject = "http://example.com"))).isNotEmpty()
        assertThat(problems(props(subject = "https://example.com/contact"))).isEmpty()
    }

    @Test
    fun `the well-known sample key is refused in a protected environment but fine locally`() {
        val sample = props(publicKey = WebPushTestKeys.PUBLIC_KEY, privateKey = TEST_PRIVATE)
        assertThat(problems(sample)).hasSize(1).first().asString().contains("test")
        assertThat(problems(sample, local)).isEmpty()
        assertThat(WebPushDeployGuard(sample).warnings(local)).isNotEmpty()
    }

    @Test
    fun `the sample key really is a pair, so the sample app can run with it`() {
        val p = props(publicKey = WebPushTestKeys.PUBLIC_KEY, privateKey = TEST_PRIVATE)
        assertThat(WebPushConfigCheck.keysOf(p)).isNotNull()
    }

    @Test
    fun `an http entry in the allow-list stops a protected environment, not a local one`() {
        val p = props(allowed = WebPushProperties.DEFAULT_ALLOWED_HOSTS + "http://127.0.0.1:9100")
        assertThat(problems(p)).hasSize(1).first().asString().contains("allowed-hosts")
        assertThat(problems(p, local)).isEmpty()
    }

    @Test
    fun `a nonsense allow-list entry stops a protected environment`() {
        assertThat(problems(props(allowed = listOf("*")))).isNotEmpty()
        assertThat(problems(props(allowed = listOf("*.com")))).isNotEmpty()
    }

    @Test
    fun `outside a protected environment problems are only warnings`() {
        val bad = props(privateKey = "")
        assertThat(problems(bad, local)).isEmpty()
        assertThat(WebPushDeployGuard(bad).warnings(local)).isNotEmpty()
    }

    // ---- 조립

    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(WebPushAutoConfiguration::class.java))

    @Test
    fun `off by default - no sender and no guard`() {
        runner.run { ctx ->
            assertThat(ctx.getBeansOfType(PushSender::class.java)).isEmpty()
            assertThat(ctx.getBeansOfType(DeployGuard::class.java)).isEmpty()
        }
    }

    @Test
    fun `enabled with a valid pair registers the WEBPUSH sender and the guard`() {
        runner.withPropertyValues(*valid()).run { ctx ->
            val sender = ctx.getBean(PushSender::class.java)
            assertThat(sender.kind).isEqualTo("WEBPUSH")
            assertThat(sender.publicKey).isEqualTo(keys.vapidPublic)
            assertThat(ctx.getBeansOfType(DeployGuard::class.java).values.map { it.name }).contains("notification-webpush")
        }
    }

    @Test
    fun `enabled with a broken pair has no sender - registration is refused - while the guard still stands to stop a protected start`() {
        runner.withPropertyValues(*valid(publicKey = TestKeys().vapidPublic)).run { ctx ->
            assertThat(ctx.getBeansOfType(PushSender::class.java)).isEmpty()
            val guard = ctx.getBean(DeployGuard::class.java)
            assertThat(guard.problems(stage)).isNotEmpty()
        }
    }

    private fun valid(publicKey: String = keys.vapidPublic) = arrayOf(
        "skeleton.notification.webpush.enabled=true",
        "skeleton.notification.webpush.public-key=$publicKey",
        "skeleton.notification.webpush.private-key=${keys.vapidPrivate}",
        "skeleton.notification.webpush.subject=mailto:ops@example.com",
    )

    private companion object {
        /** [WebPushTestKeys.PUBLIC_KEY] 의 짝 — 견본용 시험 키(K4 가 application-local.yml 에 쓴다). 실제 서비스 키가 아니다 */
        const val TEST_PRIVATE = "71dZ0ZsGh3NhlPCHjrZtu2lsuR0hgB6uI9bTsbNRCFg"
    }
}
