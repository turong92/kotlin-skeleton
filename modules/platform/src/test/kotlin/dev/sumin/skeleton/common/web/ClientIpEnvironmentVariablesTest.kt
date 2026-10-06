package dev.sumin.skeleton.common.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * 프로퍼티 `web.client-ip.*` 의 **느슨한 바인딩 이름**(`<ENV_PREFIX>_WEB_CLIENT_IP_MODE` · `_TRUSTED_PROXIES`)이 진짜 환경변수 모양으로 묶인다.
 * 주의: 홈서버 플랫폼이 실제로 넣는 이름은 `WEB_` 이 없는 `<ENV_PREFIX>_CLIENT_IP_MODE` · `_CLIENT_IP_TRUSTED_PROXIES`(소문자 값, 쉼표 목록)이고 그 이름은 각 앱의
 * `application.yml` 별칭이 받는다 — 그 증거는 앱의 `ClientIpPlatformEnvTest` 다 (docs/deploy.md §3). 이 파일은 긴 이름이 계속 먹는다는 것만 지킨다.
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
    fun `the long relaxed-binding name with an upper-case mode and one CIDR binds and satisfies the guard's configured check`() {
        for (mode in listOf("PROXY", "CLOUDFLARE")) {
            val p = bind("skeleton.web.client-ip", "SKELETON_WEB_CLIENT_IP_MODE" to mode, "SKELETON_WEB_CLIENT_IP_TRUSTED_PROXIES" to "172.18.0.0/16")
            assertEquals(ClientIpMode.valueOf(mode), p.mode)
            assertEquals(listOf("172.18.0.0/16"), p.trustedProxies)
            assertTrue(ClientIps(p).configured)
        }
    }
}
