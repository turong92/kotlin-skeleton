package dev.sumin.skeleton.payment.naverpay

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import dev.sumin.skeleton.payment.PaymentCheckoutGateway
import dev.sumin.skeleton.payment.PaymentConfirmGateway
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.PaymentGatewaySource
import dev.sumin.skeleton.payment.PaymentProviderNotFoundException
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.naverpay.support.FakeNaverPayServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 프로젝트 모드의 네이버페이: 클라이언트 시크릿 · 아이디 · 체인 · 돌아올 주소는 프로젝트마다, A 의 키는 B 의 요청에 실리지 않고 B 가 안 켠 네이버페이는 B 의 결제 화면에 없다 */
class NaverPayProjectsTest {
    private val server = FakeNaverPayServer(clientSecret = "secret-of-project-a")

    @AfterTest
    fun tearDown() = server.close()

    private fun runner() = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, NaverPayAutoConfiguration::class.java))
        .withPropertyValues(
            "skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa",
            "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb",
            "skeleton.projects.c.hosts=c.test", "skeleton.projects.c.schema=pc",
        )

    private fun naver(project: String, secret: String, mode: String = "development", id: String = "id-$project", returnUrl: String = "https://$project.test/payments/return") = arrayOf(
        "skeleton.projects.$project.config.payment-naverpay.enabled=true",
        "skeleton.projects.$project.config.payment-naverpay.client-secret=$secret",
        "skeleton.projects.$project.config.payment-naverpay.client-id=$id",
        "skeleton.projects.$project.config.payment-naverpay.chain-id=chain-$project",
        "skeleton.projects.$project.config.payment-naverpay.mode=$mode",
        "skeleton.projects.$project.config.payment-naverpay.return-url=$returnUrl",
        "skeleton.projects.$project.config.payment-naverpay.base-url=${server.baseUrl}",
    )

    private val order = CheckoutOrder(orderId = "ord_abcdef123456", orderName = "x", amount = PaymentAmount(3_000, "KRW"), accountId = "acc_1")

    @Test
    fun `the window values and the apply of project B carry B's ids and B's secret, never A's - and each project returns its buyers to its own address`() {
        runner().withPropertyValues(*naver("a", "secret-of-project-a"), *naver("b", "secret-of-project-b")).run { ctx ->
            val router = ctx.getBean(PaymentProviderRouter::class.java)
            val (a, b, c) = ctx.getBean(ProjectRegistry::class.java).projects

            val forA = ProjectContext.runIn(a) { router.gateway("naverpay", PaymentCheckoutGateway::class).checkout(order) }
            val forB = ProjectContext.runIn(b) { router.gateway("naverpay", PaymentCheckoutGateway::class).checkout(order) }
            val paymentId = server.buyerPays(order.orderId, 3_000)
            runCatching {
                ProjectContext.runIn(b) { router.gateway("naverpay", PaymentConfirmGateway::class).confirm(PaymentConfirmRequest(paymentId, PaymentAmount(3_000, "KRW"), merchantReferenceId = order.orderId)) }
            }

            assertEquals("id-a", forA.clientKey)
            assertEquals("id-b", forB.clientKey)
            assertEquals("chain-b", forB.attributes["chainId"])
            assertTrue(forA.attributes["returnUrl"]!!.startsWith("https://a.test/payments/return"))
            assertTrue(forB.attributes["returnUrl"]!!.startsWith("https://b.test/payments/return"))
            val sent = server.requests.single()
            assertEquals("secret-of-project-b", sent.header("x-naver-client-secret"), "B's request carries B's secret")
            assertEquals("id-b", sent.header("x-naver-client-id"))
            assertFailsWith<PaymentProviderNotFoundException> { ProjectContext.runIn(c) { router.gateway("naverpay", PaymentCheckoutGateway::class) } }
            assertEquals(emptyList(), ProjectContext.runIn(c) { router.providerIds(PaymentCheckoutGateway::class) })
        }
    }

    @Test
    fun `a client secret, a client id, a chain id or a return address written in the shared settings is not inherited - the start fails naming the project and the key`() {
        for ((shared, key) in listOf(
            "skeleton.payment-naverpay.client-secret=shared-secret" to "payment-naverpay.client-secret",
            "skeleton.payment-naverpay.client-id=shared-id" to "payment-naverpay.client-id",
            "skeleton.payment-naverpay.chain-id=shared-chain" to "payment-naverpay.chain-id",
            "skeleton.payment-naverpay.return-url=https://shared.test/return" to "payment-naverpay.return-url",
        )) {
            val own = arrayOf(
                "skeleton.projects.a.config.payment-naverpay.enabled=true", "skeleton.projects.a.config.payment-naverpay.client-secret=secret-a", "skeleton.projects.a.config.payment-naverpay.client-id=id-a",
                "skeleton.projects.a.config.payment-naverpay.chain-id=chain-a", "skeleton.projects.a.config.payment-naverpay.return-url=https://a.test/r",
            )
            val ownWithoutShadowed = own.filterNot { shared.substringBefore('=').substringAfter('.') in it }.toTypedArray()
            runner().withPropertyValues(*ownWithoutShadowed, shared).run { ctx ->
                val message = generateSequence(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
                assertTrue(ctx.startupFailure != null && "project 'a'" in message && key in message && "inherited" in message, "$shared -> $message")
            }
        }
    }

    @Test
    fun `two projects with the same client secret stop the start - one provider account must not serve two projects`() {
        runner().withPropertyValues(*naver("a", "same-secret"), *naver("b", "same-secret")).run { ctx ->
            val message = generateSequence(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue("'a'" in message && "'b'" in message && "payment-naverpay.client-secret" in message, message)
        }
    }

    @Test
    fun `in a protected environment a project that switched NaverPay on without a client secret stops the start naming the project`() {
        runner().withPropertyValues("skeleton.projects.b.config.payment-naverpay.enabled=true").run { ctx ->
            val guard = ctx.getBean("naverPayProjectKeyGuard", DeployGuard::class.java)
            val prod = DeployContext(DeployEnv.PROD, emptySet())
            assertTrue(guard.problems(prod).any { it.startsWith("project 'b':") && "client-secret" in it }, guard.problems(prod).toString())
            assertTrue(guard.problems(prod).none { it.startsWith("project 'a':") || it.startsWith("project 'c':") }, "projects that did not switch NaverPay on are not judged")
        }
    }

    @Test
    fun `a project in development mode is stopped in a protected environment, another project in production mode is not`() {
        runner().withPropertyValues(*naver("a", "secret-a", mode = "development"), *naver("b", "secret-b", mode = "production")).run { ctx ->
            val guard = ctx.getBean("naverPayProjectKeyGuard", DeployGuard::class.java)
            val problems = guard.problems(DeployContext(DeployEnv.PROD, emptySet())).filter { "development mode" in it }
            assertEquals(1, problems.size, problems.toString())
            assertTrue(problems.single().startsWith("project 'a':"), problems.toString())
        }
    }

    @Test
    fun `without a project list the single NaverPay provider bean is unchanged`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, NaverPayAutoConfiguration::class.java))
            .withPropertyValues("skeleton.payment-naverpay.enabled=true", "skeleton.payment-naverpay.client-id=i", "skeleton.payment-naverpay.client-secret=s", "skeleton.payment-naverpay.chain-id=c")
            .run { ctx ->
                assertEquals(1, ctx.getBeansOfType(NaverPayProvider::class.java).size)
                assertNull(ctx.getBeanProvider(PaymentGatewaySource::class.java).ifAvailable)
            }
    }
}
