package dev.sumin.skeleton.idempotency

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 인메모리 저장소는 인스턴스끼리 갈라지고 재시작에 사라진다 - 보호 환경(stage · prod)에서는 기동 실패 문제, 그 밖은 조용하다 */
class IdempotencyDeployGuardTest {
    private val prod = DeployContext(env = DeployEnv.PROD, activeProfiles = emptySet())
    private val stage = DeployContext(env = DeployEnv.STAGE, activeProfiles = emptySet())
    private val local = DeployContext(env = null, activeProfiles = setOf("local"))
    private val persistent = object : IdempotencyStore {
        override fun reserve(request: IdempotencyRequest) = IdempotencyReservation.Started(request)
        override fun complete(scopedKey: String, response: StoredIdempotencyResponse) = Unit
        override fun release(scopedKey: String) = Unit
    }

    @Test
    fun `the in-memory store is a problem in stage and prod and names the module that fixes it`() {
        val guard = IdempotencyDeployGuard { InMemoryIdempotencyStore() }

        assertEquals(1, guard.problems(prod).size)
        assertEquals(1, guard.problems(stage).size)
        assertTrue(guard.problems(prod).single().contains("idempotency-jdbc"))
    }

    @Test
    fun `local and unset environments are not protected`() {
        val guard = IdempotencyDeployGuard { InMemoryIdempotencyStore() }

        assertEquals(0, guard.problems(local).size)
        assertEquals(0, guard.problems(DeployContext(env = DeployEnv.LOCAL, activeProfiles = emptySet())).size)
    }

    @Test
    fun `a store the app brings is fine`() {
        assertEquals(0, IdempotencyDeployGuard { persistent }.problems(prod).size)
    }

    @Test
    fun `the auto configuration registers one guard that sees the default in-memory store`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration::class.java))
            .withBean(tools.jackson.databind.ObjectMapper::class.java, { tools.jackson.databind.ObjectMapper() })
            .run { context ->
                val guard = context.getBeansOfType(DeployGuard::class.java).values.single()
                assertEquals("idempotency", guard.name)
                assertEquals(1, guard.problems(prod).size)
            }
    }
}
