package dev.sumin.skeleton.common.web

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 켠 웹 속도 제한이 인스턴스별 메모리로 세면 한도가 인스턴스 수만큼 느슨해진다 - 보호 환경에서는 경고(막지 않는다) */
class RateLimitDeployGuardTest {
    private val prod = DeployContext(DeployEnv.PROD, emptySet())
    private val local = DeployContext(null, setOf("local"))
    private val shared = object : RateLimitStore by InMemoryFixedWindowRateLimitStore() {}

    @Test
    fun `the in-memory store is a warning in stage and prod, never a problem`() {
        val guard = RateLimitDeployGuard { InMemoryFixedWindowRateLimitStore() }

        assertEquals(1, guard.warnings(prod).size)
        assertTrue(guard.warnings(prod).single().contains("redis-rate-limit"))
        assertEquals(0, guard.problems(prod).size)
    }

    @Test
    fun `a shared store or an unprotected environment says nothing`() {
        assertEquals(0, RateLimitDeployGuard { shared }.warnings(prod).size)
        assertEquals(0, RateLimitDeployGuard { InMemoryFixedWindowRateLimitStore() }.warnings(local).size)
    }
}
