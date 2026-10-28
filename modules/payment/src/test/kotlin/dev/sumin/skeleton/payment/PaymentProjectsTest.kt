package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.project.NoProjectContextException
import dev.sumin.skeleton.common.project.ProjectConfigKeys
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks
import dev.sumin.skeleton.payment.testing.MutableTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.mock.env.MockEnvironment

/** 프로젝트 모드의 결제: 결제사 키 · 라우팅 · 웹훅 비밀값은 프로젝트마다, 한 프로젝트의 것이 다른 프로젝트에서 쓰이지 않는다 */
class PaymentProjectsTest {
    data class KeyedProps(val enabled: Boolean = false, val key: String = "")

    private class KeyedGateway(val key: String) : PaymentWebhookGateway {
        override val providerId = "keyed"
        override val signed = false
        override fun parse(delivery: WebhookDelivery): WebhookEnvelope = error("not used")
    }

    private val source = PaymentGatewaySource { settings ->
        val p = settings.bind("skeleton.keyed-pay", KeyedProps::class.java)
        if (p.enabled) KeyedGateway(p.key) else null
    }

    private fun registry(vararg extra: Pair<String, String>, supported: List<String>? = null) = ProjectRegistry.from(
        MockEnvironment().apply {
            setProperty("skeleton.projects.a.hosts", "a.test"); setProperty("skeleton.projects.a.schema", "pa")
            setProperty("skeleton.projects.b.hosts", "b.test"); setProperty("skeleton.projects.b.schema", "pb")
            setProperty("skeleton.projects.c.hosts", "c.test"); setProperty("skeleton.projects.c.schema", "pc")
            val cfg = "skeleton.projects.%s.config."
            setProperty(cfg.format("a") + "keyed-pay.enabled", "true"); setProperty(cfg.format("a") + "keyed-pay.key", "KEY-A")
            setProperty(cfg.format("a") + "payment.webhook.path-token", "token-of-project-a-0123456789abcdef")
            setProperty(cfg.format("b") + "keyed-pay.enabled", "true"); setProperty(cfg.format("b") + "keyed-pay.key", "KEY-B")
            setProperty(cfg.format("b") + "payment.webhook.path-token", "token-of-project-b-0123456789abcdef")
            extra.forEach { (k, v) -> setProperty(k, v) }
        },
        supported,
    )

    private fun router(registry: ProjectRegistry) = PaymentProviderRouter.forProjects(registry, emptyList(), listOf(source))

    private fun <T> ProjectRegistry.inProject(id: String, block: () -> T): T = ProjectContext.runIn(projects.single { it.id == id }, block)

    @Test
    fun `each project resolves its own provider key - a provider another project switched on does not exist here`() {
        val registry = registry()
        val router = router(registry)

        assertEquals("KEY-A", (registry.inProject("a") { router.gateway("keyed", PaymentGateway::class) } as KeyedGateway).key)
        assertEquals("KEY-B", (registry.inProject("b") { router.gateway("keyed", PaymentGateway::class) } as KeyedGateway).key)
        assertFailsWith<PaymentProviderNotFoundException> { registry.inProject("c") { router.gateway("keyed", PaymentGateway::class) } }
        assertEquals(emptyList(), registry.inProject("c") { router.providerIds(PaymentWebhookGateway::class) }, "project c lists nothing for its payment screen")
        assertFailsWith<NoProjectContextException> { router.providerIds(PaymentWebhookGateway::class) }
    }

    @Test
    fun `routing settings are per project - a provider switched off for B stays on for A`() {
        val registry = registry("skeleton.projects.b.config.payment.providers.keyed.enabled" to "false")
        val router = router(registry)

        assertNotNull(registry.inProject("a") { router.gateway("keyed", PaymentGateway::class) })
        assertFailsWith<PaymentProviderNotFoundException> { registry.inProject("b") { router.gateway("keyed", PaymentGateway::class) } }
    }

    @Test
    fun `the webhook path token of project A does not open the webhook route of project B`() {
        val registry = registry()
        val router = router(registry)
        val store = InMemoryPaymentLedgerStore()
        val time = MutableTime()
        val inbox = PaymentWebhookInbox(store, InMemoryPaymentTasks(store), router, PaymentLedgerWriter(time), PaymentProperties())

        assertNotNull(registry.inProject("a") { inbox.admit("keyed", "token-of-project-a-0123456789abcdef") })
        assertNotNull(registry.inProject("b") { inbox.admit("keyed", "token-of-project-b-0123456789abcdef") })
        assertFailsWith<PaymentWebhookNotFoundException> { registry.inProject("b") { inbox.admit("keyed", "token-of-project-a-0123456789abcdef") } }
        assertFailsWith<PaymentWebhookNotFoundException> { registry.inProject("a") { inbox.admit("keyed", "token-of-project-b-0123456789abcdef") } }
        assertFailsWith<PaymentWebhookNotFoundException> { registry.inProject("c") { inbox.admit("keyed", "token-of-project-a-0123456789abcdef") } }
    }

    @Test
    fun `project config accepts provider keys and routing, and refuses payment settings that are not per project`() {
        fun supported(vararg config: Pair<String, String>) = ProjectRegistry.from(
            MockEnvironment().apply {
                setProperty("skeleton.projects.a.hosts", "a.test"); setProperty("skeleton.projects.a.schema", "pa")
                config.forEach { (k, v) -> setProperty("skeleton.projects.a.config.$k", v) }
            },
            ProjectConfigKeys.SUPPORTED,
        )
        supported("payment-toss.secret-key" to "x", "payment.routing.country-providers.KR" to "toss", "payment.webhook.path-token" to "t", "payment.default-provider" to "toss")
        val e = assertFailsWith<IllegalStateException> { supported("payment.refund.settle-delay" to "5m") }
        assertTrue("payment.refund.settle-delay" in e.message!!, e.message)
    }

    @Test
    fun `without a project list the router is the same as before`() {
        val gateway = KeyedGateway("only")
        val router = PaymentProviderRouter(listOf(gateway), PaymentProperties())
        assertEquals(gateway, router.gateway("keyed", PaymentGateway::class))
    }

    @Test
    fun `the country map and the default provider are the current project's - the same visitor country picks a different provider per project`() {
        val registry = registry(
            "skeleton.projects.a.config.payment.routing.country-providers.KR" to "toss",
            "skeleton.projects.b.config.payment.routing.country-providers.KR" to "paddle",
        )
        val router = router(registry)
        val routing = dev.sumin.skeleton.payment.web.PaymentCountryRouting({ router.properties() }, dev.sumin.skeleton.common.web.ClientIps(), { listOf("toss", "paddle") })
        val cf = dev.sumin.skeleton.common.web.ClientIps(dev.sumin.skeleton.common.web.WebProperties.ClientIp(mode = dev.sumin.skeleton.common.web.ClientIpMode.CLOUDFLARE, trustedProxies = listOf("127.0.0.1/32")))
        val withCountry = dev.sumin.skeleton.payment.web.PaymentCountryRouting({ router.properties() }, cf, { listOf("toss", "paddle") })
        fun request() = org.springframework.mock.web.MockHttpServletRequest().apply { remoteAddr = "127.0.0.1"; addHeader("CF-IPCountry", "KR") }

        assertEquals("toss", registry.inProject("a") { withCountry.of(request()).provider })
        assertEquals("paddle", registry.inProject("b") { withCountry.of(request()).provider })
        assertEquals(null, registry.inProject("c") { routing.of(org.springframework.mock.web.MockHttpServletRequest()).provider }, "project c has no map and two open providers - no pick")
    }

    @Test
    fun `the webhook secret check runs for each project and names the project that is too weak`() {
        val registry = registry("skeleton.projects.b.config.payment.webhook.path-token" to "weak")
        val guard = PaymentLedgerAutoConfiguration().paymentProjectWebhookDeployGuard(router(registry))

        val problems = guard.problems(dev.sumin.skeleton.common.deploy.DeployContext(dev.sumin.skeleton.common.deploy.DeployEnv.PROD, emptySet()))
        assertTrue(problems.any { it.startsWith("project 'b':") && "path-token" in it }, problems.toString())
        assertTrue(problems.none { it.startsWith("project 'a':") }, "a's token is long enough")
    }

    @Test
    fun `a project with a billing provider but no crypto key of its own stops the start naming the project - billing keys are never stored unencrypted`() {
        val kit = dev.sumin.skeleton.payment.testing.PaymentLedgerKit()
        val billing = PaymentGatewaySource { kit.gateway }   // ScriptedGateway has the billing capability
        val registry = registry()
        val router = PaymentProviderRouter.forProjects(registry, emptyList(), listOf(billing))
        val onlyAHasKeys = object : dev.sumin.skeleton.crypto.TextEncryptor {
            override fun encrypt(plainText: String): String = if (ProjectContext.current().id == "a") "enc:$plainText" else throw dev.sumin.skeleton.crypto.CryptoException("project '${ProjectContext.current().id}' has no crypto keys")
            override fun decrypt(cipherText: String): String = cipherText.removePrefix("enc:")
        }
        val beans = org.springframework.beans.factory.support.DefaultListableBeanFactory().apply {
            registerSingleton("plans", kit.plans); registerSingleton("encryptor", onlyAHasKeys); registerSingleton("registry", registry)
        }

        val e = assertFailsWith<IllegalStateException> {
            PaymentSubscriptionAutoConfiguration().paymentSubscriptions(
                kit.store, router, beans.getBeanProvider(SubscriptionPlans::class.java), beans.getBeanProvider(dev.sumin.skeleton.crypto.TextEncryptor::class.java), beans.getBeanProvider(ProjectRegistry::class.java),
                kit.lifecycle, kit.charges, kit.tasks, kit.writer, kit.properties, beans.getBeanProvider(PaymentDiscounts::class.java), kit.subscriptionDiscounts,
            )
        }
        assertTrue("project 'b'" in e.message!!, e.message)
    }

    private val secretSource = PaymentGatewaySource.of(listOf("keyed-pay.key")) { settings ->
        val p = settings.bind("skeleton.keyed-pay", KeyedProps::class.java)
        if (p.enabled) KeyedGateway(p.key) else null
    }

    private fun startFailure(registry: ProjectRegistry): String = assertFailsWith<IllegalStateException> {
        PaymentProviderRouter.forProjects(registry, emptyList(), listOf(secretSource))
    }.let { e -> generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ") }

    @Test
    fun `a project that switched a provider on but took its secret from the shared settings stops the start`() {
        val message = startFailure(registry("skeleton.keyed-pay.key" to "SHARED", "skeleton.projects.c.config.keyed-pay.enabled" to "true"))
        assertTrue("project 'c'" in message && "inherited" in message && "own payment provider account" in message, message)
    }

    @Test
    fun `two projects with the same provider secret - or the same webhook path token - stop the start however they got it`() {
        val sameKey = startFailure(registry("skeleton.projects.b.config.keyed-pay.key" to "KEY-A"))
        assertTrue("'a'" in sameKey && "'b'" in sameKey && "keyed-pay.key" in sameKey, sameKey)
        val sameToken = startFailure(registry("skeleton.projects.b.config.payment.webhook.path-token" to "token-of-project-a-0123456789abcdef"))
        assertTrue("'a'" in sameToken && "'b'" in sameToken && "path-token" in sameToken, sameToken)
    }

    @Test
    fun `a provider switched on nowhere is not judged, and a closed project does not count for sameness`() {
        PaymentProviderRouter.forProjects(registry(), emptyList(), listOf(secretSource))   // c has nothing on, a and b have their own
        PaymentProviderRouter.forProjects(registry("skeleton.projects.b.config.keyed-pay.key" to "KEY-A", "skeleton.projects.b.status" to "closed", "skeleton.projects.b.schema" to ""), emptyList(), listOf(secretSource))
    }

    @Test
    fun `a PaymentGateway bean the app defined is shared by all projects - a warning, and a start failure in a protected environment`() {
        val guard = PaymentAutoConfiguration().paymentSharedGatewaysDeployGuard(listOf(KeyedGateway("app")))
        val prod = dev.sumin.skeleton.common.deploy.DeployContext(dev.sumin.skeleton.common.deploy.DeployEnv.PROD, emptySet())
        val local = dev.sumin.skeleton.common.deploy.DeployContext(dev.sumin.skeleton.common.deploy.DeployEnv.LOCAL, emptySet())
        assertEquals(1, guard.problems(prod).size)
        assertEquals(emptyList(), guard.warnings(prod))
        assertEquals(emptyList(), guard.problems(local))
        assertEquals(1, guard.warnings(local).size)
        assertEquals(emptyList(), PaymentAutoConfiguration().paymentSharedGatewaysDeployGuard(emptyList()).problems(prod))
    }

    @Test
    fun `a paired public key such as the client key must be the project's own too - but equal values are fine`() {
        val paired = PaymentGatewaySource.of(listOf("keyed-pay.key"), listOf("keyed-pay.client")) { settings ->
            val p = settings.bind("skeleton.keyed-pay", KeyedProps::class.java)
            if (p.enabled) KeyedGateway(p.key) else null
        }
        val inherited = assertFailsWith<IllegalStateException> {
            PaymentProviderRouter.forProjects(registry("skeleton.keyed-pay.client" to "SHARED-CLIENT"), emptyList(), listOf(paired))
        }.let { generateSequence<Throwable>(it) { e -> e.cause }.mapNotNull { m -> m.message }.joinToString(" | ") }
        assertTrue("keyed-pay.client" in inherited && "inherited" in inherited, inherited)

        PaymentProviderRouter.forProjects(
            registry("skeleton.projects.a.config.keyed-pay.client" to "SAME", "skeleton.projects.b.config.keyed-pay.client" to "SAME"),
            emptyList(), listOf(paired),
        )
    }
}
