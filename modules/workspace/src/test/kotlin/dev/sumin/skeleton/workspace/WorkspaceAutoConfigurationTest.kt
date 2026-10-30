package dev.sumin.skeleton.workspace

import dev.sumin.skeleton.common.account.AccountDeletionCheck
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.workspace.web.WorkspaceController
import dev.sumin.skeleton.workspace.web.WorkspaceWebAutoConfiguration
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner

/** 조립 - `account` · `notification-mail` 없이 저장소 하나만 있으면 뜬다. 저장소가 없으면 서비스가 켜지지 않고, 기본값은 앱이 같은 타입의 빈으로 덮는다 */
class WorkspaceAutoConfigurationTest {
    private val runner = WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(WorkspaceAutoConfiguration::class.java, WorkspaceWebAutoConfiguration::class.java))
    private val store = FakeWorkspaceStore()

    @Test
    fun `with a store the module boots on platform alone and exposes its contracts`() {
        runner.withBean(WorkspaceStore::class.java, Supplier { store }).run { ctx ->
            assertNotNull(ctx.getBean(Workspaces::class.java))
            assertNotNull(ctx.getBean(CurrentWorkspace::class.java))
            assertNotNull(ctx.getBean(WorkspaceController::class.java))
            assertNull(ctx.getBean(WorkspaceSeatPolicy::class.java).limitOf("ws_1"), "no limit unless the app sets one")
            assertTrue(ctx.getBeansOfType(AccountDeletionCheck::class.java).isNotEmpty())
            assertEquals(listOf("workspace"), ctx.getBeansOfType(AccountErasureListener::class.java).values.map { it.name })
        }
    }

    @Test
    fun `without a store no service is registered`() {
        runner.run { ctx ->
            assertTrue(ctx.getBeansOfType(WorkspaceService::class.java).isEmpty())
            assertTrue(ctx.getBeansOfType(CurrentWorkspace::class.java).isEmpty())
            assertTrue(ctx.getBeansOfType(AccountDeletionCheck::class.java).isEmpty())
        }
    }

    @Test
    fun `an app seat policy replaces the default, and http disabled closes the paths but keeps the boundary`() {
        runner.withBean(WorkspaceStore::class.java, Supplier { store }).withBean(WorkspaceSeatPolicy::class.java, Supplier { WorkspaceSeatPolicy { 5 } })
            .withPropertyValues("skeleton.workspace.http.enabled=false").run { ctx ->
                assertEquals(5, ctx.getBean(WorkspaceSeatPolicy::class.java).limitOf("ws_1"))
                assertTrue(ctx.getBeansOfType(WorkspaceController::class.java).isEmpty())
                assertNotNull(ctx.getBean(CurrentWorkspace::class.java))
            }
    }
}
