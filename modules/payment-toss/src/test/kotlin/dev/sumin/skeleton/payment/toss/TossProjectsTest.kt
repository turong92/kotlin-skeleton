package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import dev.sumin.skeleton.payment.PaymentCheckoutGateway
import dev.sumin.skeleton.payment.PaymentConfirmGateway
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.PaymentProviderNotFoundException
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.toss.support.FakeTossServer
import dev.sumin.skeleton.payment.toss.support.FakeTossServer.Reply
import dev.sumin.skeleton.payment.toss.support.TossFixtures
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 프로젝트 모드의 토스: 시크릿 키 · 클라이언트 키는 프로젝트마다, A 의 키는 B 의 요청에 실리지 않고 B 가 안 켠 토스는 B 의 결제 화면에 없다 */
class TossProjectsTest {
    private val server = FakeTossServer(secretKey = "test_sk_project_a")

    @AfterTest
    fun tearDown() = server.close()

    private fun runner() = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, TossPaymentAutoConfiguration::class.java))
        .withPropertyValues(
            "skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa",
            "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb",
            "skeleton.projects.c.hosts=c.test", "skeleton.projects.c.schema=pc",
        )

    private fun toss(project: String, secret: String, client: String) = arrayOf(
        "skeleton.projects.$project.config.payment-toss.enabled=true",
        "skeleton.projects.$project.config.payment-toss.secret-key=$secret",
        "skeleton.projects.$project.config.payment-toss.client-key=$client",
        "skeleton.projects.$project.config.payment-toss.base-url=${server.baseUrl}",
    )

    private fun basic(secret: String) = "Basic " + Base64.getEncoder().encodeToString("$secret:".toByteArray())

    @Test
    fun `a request of project B carries B's secret key, never A's - and each project's checkout shows its own client key`() {
        server.on("POST", "/v1/payments/confirm", Reply.Json(200, TossFixtures.payment()))
        runner().withPropertyValues(*toss("a", "test_sk_project_a", "test_ck_a"), *toss("b", "test_sk_project_b", "test_ck_b")).run { ctx ->
            val router = ctx.getBean(PaymentProviderRouter::class.java)
            val (a, b, c) = ctx.getBean(ProjectRegistry::class.java).projects
            val confirm = PaymentConfirmRequest(providerPaymentId = "pay_key_1", amount = PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456")

            ProjectContext.runIn(a) { router.gateway("toss", PaymentConfirmGateway::class).confirm(confirm) }
            runCatching { ProjectContext.runIn(b) { router.gateway("toss", PaymentConfirmGateway::class).confirm(confirm) } }

            assertEquals(listOf(basic("test_sk_project_a"), basic("test_sk_project_b")), server.requests.map { it.header("authorization") })
            val order = CheckoutOrder(orderId = "ord_abcdef123456", orderName = "x", amount = PaymentAmount(3_000, "KRW"))
            assertEquals("test_ck_a", ProjectContext.runIn(a) { router.gateway("toss", PaymentCheckoutGateway::class).checkout(order).clientKey })
            assertEquals("test_ck_b", ProjectContext.runIn(b) { router.gateway("toss", PaymentCheckoutGateway::class).checkout(order).clientKey })
            assertFailsWith<PaymentProviderNotFoundException> { ProjectContext.runIn(c) { router.gateway("toss", PaymentCheckoutGateway::class) } }
            assertEquals(emptyList(), ProjectContext.runIn(c) { router.providerIds(PaymentCheckoutGateway::class) })
        }
    }

    @Test
    fun `in a protected environment a project that switched Toss on without a secret key stops the start naming the project`() {
        runner().withPropertyValues("skeleton.projects.b.config.payment-toss.enabled=true").run { ctx ->
            val guard = ctx.getBean("tossProjectKeyGuard", DeployGuard::class.java)
            val prod = DeployContext(DeployEnv.PROD, emptySet())
            assertTrue(guard.problems(prod).any { it.startsWith("project 'b':") && "secret-key" in it }, guard.problems(prod).toString())
            assertTrue(guard.problems(prod).none { it.startsWith("project 'a':") || it.startsWith("project 'c':") }, "projects that did not switch Toss on are not judged")
        }
    }

    @Test
    fun `a key the provider rejected raises the alert inside the project that owns the key - on the provider's response thread too`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val alert = dev.sumin.skeleton.payment.PaymentProviderCredentialAlert { seen += ProjectContext.currentOrNull()?.id }
        server.on("POST", "/v1/payments/confirm", Reply.Json(200, TossFixtures.payment()))
        runner().withBean(dev.sumin.skeleton.payment.PaymentProviderCredentialAlert::class.java, java.util.function.Supplier { alert })
            .withPropertyValues(*toss("a", "test_sk_project_a", "test_ck_a"), *toss("b", "test_sk_wrong_key_of_b", "test_ck_b")).run { ctx ->
                val router = ctx.getBean(PaymentProviderRouter::class.java)
                val b = ctx.getBean(ProjectRegistry::class.java).projects.single { it.id == "b" }
                val confirm = PaymentConfirmRequest(providerPaymentId = "pay_key_1", amount = PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456")

                runCatching { ProjectContext.runIn(b) { router.gateway("toss", PaymentConfirmGateway::class).confirm(confirm) } }

                assertEquals(listOf<String?>("b"), seen, "the money alert was raised once, in project b's context")
            }
    }

    @Test
    fun `the easy pays of project A are in A's checkout only - B without a list sees the plain checkout`() {
        runner().withPropertyValues(*toss("a", "test_sk_project_a", "test_ck_a"), *toss("b", "test_sk_project_b", "test_ck_b"), "skeleton.projects.a.config.payment-toss.easy-pays=NAVERPAY,KAKAOPAY").run { ctx ->
            val router = ctx.getBean(PaymentProviderRouter::class.java)
            val (a, b) = ctx.getBean(ProjectRegistry::class.java).projects
            val order = CheckoutOrder(orderId = "ord_abcdef123456", orderName = "x", amount = PaymentAmount(3_000, "KRW"))

            assertEquals(mapOf("method" to "CARD", "easyPays" to "NAVERPAY,KAKAOPAY"), ProjectContext.runIn(a) { router.gateway("toss", PaymentCheckoutGateway::class).checkout(order).attributes })
            assertEquals(mapOf("method" to "CARD"), ProjectContext.runIn(b) { router.gateway("toss", PaymentCheckoutGateway::class).checkout(order).attributes })
        }
    }

    @Test
    fun `an unknown easy pay code stops the start - in one project and in the single mode alike`() {
        runner().withPropertyValues(*toss("a", "test_sk_project_a", "test_ck_a"), "skeleton.projects.a.config.payment-toss.easy-pays=NAVERPAI").run { ctx ->
            assertTrue(ctx.startupFailure != null, "project mode starts with a typo in easy-pays")
            assertTrue(generateSequence(ctx.startupFailure) { it.cause }.any { it.message?.contains("NAVERPAI") == true }, "the message names the code")
        }
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, TossPaymentAutoConfiguration::class.java))
            .withPropertyValues("skeleton.payment-toss.enabled=true", "skeleton.payment-toss.secret-key=test_sk_123", "skeleton.payment-toss.easy-pays=NAVERPAI")
            .run { ctx -> assertTrue(ctx.startupFailure != null, "single mode starts with a typo in easy-pays") }
    }

    @Test
    fun `without a project list the single Toss provider bean is unchanged`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, TossPaymentAutoConfiguration::class.java))
            .withPropertyValues("skeleton.payment-toss.enabled=true", "skeleton.payment-toss.secret-key=test_sk_123")
            .run { ctx ->
                assertEquals(1, ctx.getBeansOfType(TossPaymentProvider::class.java).size)
                assertNull(ctx.getBeanProvider(dev.sumin.skeleton.payment.PaymentGatewaySource::class.java).ifAvailable)
            }
    }
}
