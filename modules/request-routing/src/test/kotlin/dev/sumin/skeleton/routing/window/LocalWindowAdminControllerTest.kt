package dev.sumin.skeleton.routing.window

import dev.sumin.skeleton.common.GlobalExceptionHandler
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.routing.LocalRouteWindowStore
import dev.sumin.skeleton.routing.RequestRoutingAutoConfiguration
import dev.sumin.skeleton.routing.RoutingPermissions
import dev.sumin.skeleton.routing.StoreLocalRouteWindow
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.json.JsonMapper

class LocalWindowAdminControllerTest {
    private val now = Instant.parse("2026-11-16T00:00:00Z")
    private val time = TimeProvider { now }
    private val store = InMemoryLocalRouteWindowStore()
    private val audits = mutableListOf<OperatorAction>()
    private val access = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = if (ref.accountId == "alice") setOf(RoutingPermissions.WINDOW) else emptySet()
    }

    private fun mvc(maxWindow: Duration = Duration.ofHours(8)): MockMvc {
        val service = LocalWindowService(store, StoreLocalRouteWindow(store, Duration.ofSeconds(5), time), OperatorAuditLog { audits += it }, RollbackingTransactions(store), time, maxWindow)
        return MockMvcBuilders.standaloneSetup(LocalWindowAdminController(service, access)).setControllerAdvice(GlobalExceptionHandler()).build()
    }

    private fun who(name: String) = TestingAuthenticationToken(name, "n", "ROLE_USER").apply { isAuthenticated = true }

    private fun open(minutes: Int, reason: String = "hotfix", maxWindow: Duration = Duration.ofHours(8), account: String = "alice") =
        mvc(maxWindow).perform(
            post(PATH).principal(who(account)).contentType(MediaType.APPLICATION_JSON)
                .content("""{"minutes":$minutes,"reason":"$reason"}"""),
        )

    @Test
    fun `disabled is 404 - no controller bean without the admin switch`() {
        val runner = WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RequestRoutingAutoConfiguration::class.java, LocalWindowAdminAutoConfiguration::class.java))
            .withBean(TimeProvider::class.java, { time })
            .withBean(OperatorAccess::class.java, { access })
            .withBean(OperatorAuditLog::class.java, { OperatorAuditLog { } })
            .withBean(TransactionOperations::class.java, { TransactionOperations.withoutTransaction() })
            .withBean(LocalRouteWindowStore::class.java, { store })
            .withBean(JsonMapper::class.java, { JsonMapper.builder().build() })
            .withPropertyValues("skeleton.routing.enabled=true")

        runner.run { assertEquals(0, it.getBeansOfType(LocalWindowAdminController::class.java).size) }
        runner.withPropertyValues("skeleton.routing.admin.enabled=true")
            .run { assertEquals(1, it.getBeansOfType(LocalWindowAdminController::class.java).size) }
    }

    @Test
    fun `anonymous is 401, a caller without the permission is 403`() {
        mvc().perform(get(PATH)).andExpect(status().isUnauthorized)
        mvc().perform(get(PATH).principal(AnonymousAuthenticationToken("k", "anon", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))).andExpect(status().isUnauthorized)
        mvc().perform(get(PATH).principal(who("nobody"))).andExpect(status().isForbidden)
        mvc().perform(post(PATH).principal(who("nobody")).contentType(MediaType.APPLICATION_JSON).content("""{"minutes":5,"reason":"x"}""")).andExpect(status().isForbidden)
        mvc().perform(delete(PATH).principal(who("nobody"))).andExpect(status().isForbidden)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `opens a window and audits it without the reason`() {
        open(30).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.openedBy").value("alice"))
            .andExpect(jsonPath("$.value.reason").value("hotfix"))
            .andExpect(jsonPath("$.value.openUntil").value("2026-11-16T00:30:00Z"))

        assertEquals(
            listOf(OperatorAction("alice", "routing.local-window.opened", "local-window", "1", mapOf("openUntil" to "2026-11-16T00:30:00Z"))),
            audits,
        )
        mvc().perform(get(PATH).principal(who("alice"))).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.open").value(true)).andExpect(jsonPath("$.value.window.id").value(1))
    }

    @Test
    fun `status says closed when nothing is open`() {
        mvc().perform(get(PATH).principal(who("alice"))).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.open").value(false)).andExpect(jsonPath("$.value.window").doesNotExist())
    }

    @Test
    fun `opening again closes the previous window and the status shows the new one`() {
        open(30)
        open(10, account = "alice")

        assertEquals("alice", store.rows.first().closedBy)
        assertNull(store.rows.last().closedBy)
        mvc().perform(get(PATH).principal(who("alice"))).andExpect(jsonPath("$.value.window.id").value(2))
    }

    @Test
    fun `minutes over 480, over the configured max, or a blank reason is 400`() {
        open(481).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        open(0).andExpect(status().isBadRequest)
        open(61, maxWindow = Duration.ofHours(1)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        open(30, reason = " ").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `close is 204 even when nothing is open, and audits each closed window`() {
        mvc().perform(delete(PATH).principal(who("alice"))).andExpect(status().isNoContent)
        assertTrue(audits.isEmpty())

        open(30)
        audits.clear()
        mvc().perform(delete(PATH).principal(who("alice"))).andExpect(status().isNoContent)

        assertEquals(listOf("routing.local-window.closed"), audits.map { it.action })
        assertNotNull(store.rows.single().closedAt)
    }

    private companion object {
        const val PATH = "/api/v1/admin/routing/local-window"
    }
}
