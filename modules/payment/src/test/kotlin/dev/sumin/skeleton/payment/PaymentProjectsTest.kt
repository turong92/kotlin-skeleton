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
            setProperty(cfg.format("a") + "payment.webhook.path-token", "token-of-project-a")
            setProperty(cfg.format("b") + "keyed-pay.enabled", "true"); setProperty(cfg.format("b") + "keyed-pay.key", "KEY-B")
            setProperty(cfg.format("b") + "payment.webhook.path-token", "token-of-project-b")
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

        assertNotNull(registry.inProject("a") { inbox.admit("keyed", "token-of-project-a") })
        assertNotNull(registry.inProject("b") { inbox.admit("keyed", "token-of-project-b") })
        assertFailsWith<PaymentWebhookNotFoundException> { registry.inProject("b") { inbox.admit("keyed", "token-of-project-a") } }
        assertFailsWith<PaymentWebhookNotFoundException> { registry.inProject("a") { inbox.admit("keyed", "token-of-project-b") } }
        assertFailsWith<PaymentWebhookNotFoundException> { registry.inProject("c") { inbox.admit("keyed", "token-of-project-a") } }
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
}
