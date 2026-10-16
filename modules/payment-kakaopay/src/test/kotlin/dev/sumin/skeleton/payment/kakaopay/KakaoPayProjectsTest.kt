package dev.sumin.skeleton.payment.kakaopay

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
import dev.sumin.skeleton.payment.PaymentGatewaySource
import dev.sumin.skeleton.payment.PaymentProviderNotFoundException
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.kakaopay.support.FakeKakaoPayServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 프로젝트 모드의 카카오페이: 시크릿 키 · 가맹점 코드 · 돌아올 주소는 프로젝트마다, A 의 키는 B 의 요청에 실리지 않고 B 가 안 켠 카카오페이는 B 의 결제 화면에 없다 */
class KakaoPayProjectsTest {
    private val server = FakeKakaoPayServer(secretKey = "key-of-project-a")

    @AfterTest
    fun tearDown() = server.close()

    private fun runner() = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, KakaoPayAutoConfiguration::class.java))
        .withPropertyValues(
            "skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa",
            "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb",
            "skeleton.projects.c.hosts=c.test", "skeleton.projects.c.schema=pc",
        )

    private fun kakao(project: String, secret: String, cid: String = "TC0ONETIME", returnUrl: String = "https://$project.test/payments/return") = arrayOf(
        "skeleton.projects.$project.config.payment-kakaopay.enabled=true",
        "skeleton.projects.$project.config.payment-kakaopay.secret-key=$secret",
        "skeleton.projects.$project.config.payment-kakaopay.cid=$cid",
        "skeleton.projects.$project.config.payment-kakaopay.return-url=$returnUrl",
        "skeleton.projects.$project.config.payment-kakaopay.base-url=${server.baseUrl}",
    )

    private val order = CheckoutOrder(orderId = "ord_abcdef123456", orderName = "x", amount = PaymentAmount(3_000, "KRW"), accountId = "acc_1")

    @Test
    fun `a request of project B carries B's secret key, never A's - and each project returns its buyers to its own address`() {
        runner().withPropertyValues(*kakao("a", "key-of-project-a"), *kakao("b", "key-of-project-b")).run { ctx ->
            val router = ctx.getBean(PaymentProviderRouter::class.java)
            val (a, b, c) = ctx.getBean(ProjectRegistry::class.java).projects

            ProjectContext.runIn(a) { router.gateway("kakaopay", PaymentCheckoutGateway::class).checkout(order) }
            runCatching { ProjectContext.runIn(b) { router.gateway("kakaopay", PaymentCheckoutGateway::class).checkout(order) } }

            assertEquals(listOf("SECRET_KEY key-of-project-a", "SECRET_KEY key-of-project-b"), server.requests.map { it.header("authorization") })
            assertTrue("https://a.test/payments/return" in server.requests[0].body && "b.test" !in server.requests[0].body)
            assertTrue("https://b.test/payments/return" in server.requests[1].body && "a.test" !in server.requests[1].body)
            assertFailsWith<PaymentProviderNotFoundException> { ProjectContext.runIn(c) { router.gateway("kakaopay", PaymentCheckoutGateway::class) } }
            assertEquals(emptyList(), ProjectContext.runIn(c) { router.providerIds(PaymentCheckoutGateway::class) })
        }
    }

    @Test
    fun `a secret key, a merchant code or a return address written in the shared settings is not inherited - the start fails naming the project and the key`() {
        for ((shared, key) in listOf(
            "skeleton.payment-kakaopay.secret-key=shared-key" to "payment-kakaopay.secret-key",
            "skeleton.payment-kakaopay.cid=TC0ONETIME" to "payment-kakaopay.cid",
            "skeleton.payment-kakaopay.return-url=https://shared.test/return" to "payment-kakaopay.return-url",
        )) {
            val own = arrayOf("skeleton.projects.a.config.payment-kakaopay.enabled=true", "skeleton.projects.a.config.payment-kakaopay.secret-key=key-a", "skeleton.projects.a.config.payment-kakaopay.cid=TC0ONETIME", "skeleton.projects.a.config.payment-kakaopay.return-url=https://a.test/r")
            val ownWithoutShadowed = own.filterNot { shared.substringBefore('=').removePrefix("skeleton.") in it }.toTypedArray()
            runner().withPropertyValues(*ownWithoutShadowed, shared).run { ctx ->
                val message = generateSequence(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
                assertTrue(ctx.startupFailure != null && "project 'a'" in message && key in message && "inherited" in message, "$shared -> $message")
            }
        }
    }

    @Test
    fun `two projects with the same secret key stop the start - one provider account must not serve two projects`() {
        runner().withPropertyValues(*kakao("a", "same-key"), *kakao("b", "same-key")).run { ctx ->
            val message = generateSequence(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue("'a'" in message && "'b'" in message && "payment-kakaopay.secret-key" in message, message)
        }
    }

    @Test
    fun `in a protected environment a project that switched KakaoPay on without a secret key stops the start naming the project`() {
        runner().withPropertyValues("skeleton.projects.b.config.payment-kakaopay.enabled=true").run { ctx ->
            val guard = ctx.getBean("kakaoPayProjectKeyGuard", DeployGuard::class.java)
            val prod = DeployContext(DeployEnv.PROD, emptySet())
            assertTrue(guard.problems(prod).any { it.startsWith("project 'b':") && "secret-key" in it }, guard.problems(prod).toString())
            assertTrue(guard.problems(prod).none { it.startsWith("project 'a':") || it.startsWith("project 'c':") }, "projects that did not switch KakaoPay on are not judged")
        }
    }

    @Test
    fun `a project on a test merchant code is stopped in a protected environment, another project on a live code is not`() {
        runner().withPropertyValues(*kakao("a", "key-a", cid = "TC0ONETIME"), *kakao("b", "key-b", cid = "1234567890")).run { ctx ->
            val guard = ctx.getBean("kakaoPayProjectKeyGuard", DeployGuard::class.java)
            val problems = guard.problems(DeployContext(DeployEnv.PROD, emptySet())).filter { "test merchant code" in it }
            assertEquals(1, problems.size, problems.toString())
            assertTrue(problems.single().startsWith("project 'a':"), problems.toString())
        }
    }

    @Test
    fun `without a project list the single KakaoPay provider bean is unchanged`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, KakaoPayAutoConfiguration::class.java))
            .withPropertyValues("skeleton.payment-kakaopay.enabled=true", "skeleton.payment-kakaopay.secret-key=k", "skeleton.payment-kakaopay.cid=TC0ONETIME")
            .run { ctx ->
                assertEquals(1, ctx.getBeansOfType(KakaoPayProvider::class.java).size)
                assertNull(ctx.getBeanProvider(PaymentGatewaySource::class.java).ifAvailable)
            }
    }
}
