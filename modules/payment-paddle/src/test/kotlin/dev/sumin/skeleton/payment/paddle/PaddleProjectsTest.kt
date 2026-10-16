package dev.sumin.skeleton.payment.paddle

import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import dev.sumin.skeleton.payment.PaymentProviderNotFoundException
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentWebhookGateway
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.WebhookDelivery
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 프로젝트 모드의 Paddle: 웹훅 비밀값은 프로젝트마다 — A 의 비밀값으로 서명한 웹훅은 B 도메인에서 거절된다 */
class PaddleProjectsTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, PaddlePaymentAutoConfiguration::class.java))
        .withPropertyValues(
            "skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa",
            "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb",
            "skeleton.projects.c.hosts=c.test", "skeleton.projects.c.schema=pc",
        )

    private fun paddle(project: String, webhookSecret: String) = arrayOf(
        "skeleton.projects.$project.config.payment-paddle.enabled=true",
        "skeleton.projects.$project.config.payment-paddle.api-key=key-$project",
        "skeleton.projects.$project.config.payment-paddle.client-token=token-$project",
        "skeleton.projects.$project.config.payment-paddle.webhook-secret=$webhookSecret",
    )

    @Test
    fun `a webhook signed with project A's secret is rejected on project B's domain - and accepted on A's`() {
        runner.withPropertyValues(*paddle("a", "pdl_secret_a"), *paddle("b", "pdl_secret_b")).run { ctx ->
            val router = ctx.getBean(PaymentProviderRouter::class.java)
            val (a, b, c) = ctx.getBean(ProjectRegistry::class.java).projects
            val body = transactionCompleted()
            val signedByA = WebhookDelivery(mapOf("Paddle-Signature" to sign(body, ts = Instant.now().epochSecond, secret = "pdl_secret_a")), body.toByteArray())

            assertEquals("evt_1", ProjectContext.runIn(a) { router.gateway("paddle", PaymentWebhookGateway::class).parse(signedByA).eventKey })
            assertFailsWith<PaymentWebhookRejectedException> { ProjectContext.runIn(b) { router.gateway("paddle", PaymentWebhookGateway::class).parse(signedByA) } }
            assertFailsWith<PaymentProviderNotFoundException>("a project that did not switch Paddle on has no webhook route for it") {
                ProjectContext.runIn(c) { router.gateway("paddle", PaymentWebhookGateway::class) }
            }
        }
    }

    @Test
    fun `a project missing one of the three secrets has no Paddle - the others keep theirs`() {
        runner.withPropertyValues(*paddle("a", "pdl_secret_a"), "skeleton.projects.b.config.payment-paddle.enabled=true", "skeleton.projects.b.config.payment-paddle.api-key=k").run { ctx ->
            val router = ctx.getBean(PaymentProviderRouter::class.java)
            val (a, b) = ctx.getBean(ProjectRegistry::class.java).projects
            assertEquals(listOf("paddle"), ProjectContext.runIn(a) { router.providerIds(PaymentWebhookGateway::class) })
            assertEquals(emptyList(), ProjectContext.runIn(b) { router.providerIds(PaymentWebhookGateway::class) })
        }
    }
}
