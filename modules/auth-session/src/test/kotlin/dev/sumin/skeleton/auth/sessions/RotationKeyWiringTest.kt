package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.auth.config.AuthProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * 회전의 후속 토큰은 서버 키로 만든다 — 키가 JWT 비밀에서 나온다는 **배선**을 잡는다: 비밀이 다르면 같은 옛 토큰의 후속이 달라야 하고(그래서 기본 개발 비밀을 모르는 운영에서는
 * 옛 토큰만으로 후속 사슬을 계산할 수 없다), 같으면 인스턴스끼리 같아야 한다. 이 파생 줄을 지우면(상수 · 무작위 키로) 이 시험이 실패한다.
 */
class RotationKeyWiringTest {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration::class.java, AuthSessionAutoConfiguration::class.java))

    private fun successor(secret: String, token: String = "r1.the-same-old-token-for-every-secret"): String {
        var out = ""
        runner.withBean(AuthProperties::class.java, { AuthProperties(jwt = AuthProperties.Jwt(secret = secret)) }).run { ctx -> out = ctx.getBean(SessionService::class.java).successorOf(token) }
        return out
    }

    @Test
    fun `the successor chain depends on the JWT secret - instances with one secret agree, another secret cannot compute it`() {
        val a1 = successor("a-secret-that-is-long-enough-for-hs256-1")
        val a2 = successor("a-secret-that-is-long-enough-for-hs256-1")
        val b = successor("another-secret-that-is-long-enough-for-hs-2")
        assertEquals(a1, a2)
        assertNotEquals(a1, b)
        assertNotEquals(successor(AuthProperties.Jwt.DEFAULT_SECRET), a1, "the built-in dev secret yields a chain anyone can compute - protected profiles refuse to boot with it")
    }
}
