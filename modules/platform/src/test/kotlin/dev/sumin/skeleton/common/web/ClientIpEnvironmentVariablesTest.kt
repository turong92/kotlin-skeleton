package dev.sumin.skeleton.common.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * 홈서버 플랫폼이 넣는 `<ENV_PREFIX>_WEB_CLIENT_IP_MODE` · `<ENV_PREFIX>_WEB_CLIENT_IP_TRUSTED_PROXIES` 가 진짜 환경변수 모양으로 `web.client-ip.*` 에 묶인다
 * (docs/deploy.md §5). 신뢰 프록시는 도커 네트워크 CIDR 이라 선언에 박지 않고 배포가 넣는다.
 */
class ClientIpEnvironmentVariablesTest {
    private fun bind(path: String, vararg env: Pair<String, String>): WebProperties.ClientIp {
        val source = SystemEnvironmentPropertySource("systemEnvironment", mapOf(*env))
        val environment = org.springframework.core.env.StandardEnvironment()
        environment.propertySources.addFirst(source)
        return Binder.get(environment).bind(path, WebProperties.ClientIp::class.java).get()
    }

    @Test
    fun `mode and a comma separated proxy list bind from the environment`() {
        val p = bind("skeleton.web.client-ip", "SKELETON_WEB_CLIENT_IP_MODE" to "cloudflare", "SKELETON_WEB_CLIENT_IP_TRUSTED_PROXIES" to "172.18.0.0/16, 10.0.0.0/8")
        assertEquals(ClientIpMode.CLOUDFLARE, p.mode)
        assertEquals(listOf("172.18.0.0/16", "10.0.0.0/8"), p.trustedProxies.map(String::trim))
        assertTrue(ClientIps(p).configured)
    }

    @Test
    fun `a stamped project's prefix works the same way`() {
        val p = bind("ovation.web.client-ip", "OVATION_WEB_CLIENT_IP_MODE" to "proxy", "OVATION_WEB_CLIENT_IP_TRUSTED_PROXIES" to "172.18.0.0/16")
        assertEquals(ClientIpMode.PROXY, p.mode)
        assertEquals(listOf("172.18.0.0/16"), p.trustedProxies)
    }

    @Test
    fun `exactly what the homeserver platform injects - upper-case mode and one CIDR - binds and satisfies the guard's configured check`() {
        for (mode in listOf("PROXY", "CLOUDFLARE")) {
            val p = bind("skeleton.web.client-ip", "SKELETON_WEB_CLIENT_IP_MODE" to mode, "SKELETON_WEB_CLIENT_IP_TRUSTED_PROXIES" to "172.18.0.0/16")
            assertEquals(ClientIpMode.valueOf(mode), p.mode)
            assertEquals(listOf("172.18.0.0/16"), p.trustedProxies)
            assertTrue(ClientIps(p).configured)
        }
        // the platform's fallback when it cannot read the network
        assertTrue(ClientIps(bind("skeleton.web.client-ip", "SKELETON_WEB_CLIENT_IP_MODE" to "PROXY", "SKELETON_WEB_CLIENT_IP_TRUSTED_PROXIES" to "127.0.0.1/32")).configured)
    }
}
