package dev.sumin.skeleton.app.sample

import dev.sumin.skeleton.common.web.ClientIpMode
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.WebProperties
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * 홈서버 플랫폼이 넣는 **진짜 이름과 값 모양**으로 이 앱의 `application.yml` 이 클라이언트 IP 설정을 받는다 — 플랫폼 `infra/modules/app-docker/app.tf` (홈서버 main):
 *
 *     "${var.app.env_prefix}_CLIENT_IP_MODE=${local.has_domain ? "cloudflare" : "proxy"}"
 *     "${var.app.env_prefix}_CLIENT_IP_TRUSTED_PROXIES=${local.web_trusted_proxies}"        # join(",", local.web_subnets)
 *
 * `WEB_` 가 **없다**, 값은 소문자, 신뢰 프록시는 쉼표로 이은 CIDR 들. 이름이 갈리면 Spring 은 모르는 환경변수를 조용히 버리고 prod 는 기동을 거부한다.
 */
class ClientIpPlatformEnvTest {
    private fun bind(vararg env: Pair<String, String>): WebProperties.ClientIp {
        var bound: WebProperties.ClientIp? = null
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withInitializer { it.environment.propertySources.addFirst(SystemEnvironmentPropertySource("systemEnvironment", mapOf(*env))) }
            .run { ctx -> bound = Binder.get(ctx.environment).bind("skeleton.web.client-ip", WebProperties.ClientIp::class.java).orElse(WebProperties.ClientIp()) }
        return bound!!
    }

    @Test
    fun `exactly the names and values the platform injects reach the client IP settings`() {
        for ((mode, expected) in listOf("proxy" to ClientIpMode.PROXY, "cloudflare" to ClientIpMode.CLOUDFLARE)) {
            val p = bind("SKELETON_CLIENT_IP_MODE" to mode, "SKELETON_CLIENT_IP_TRUSTED_PROXIES" to "172.18.0.0/16,172.19.0.0/16")
            assertEquals(expected, p.mode)
            assertEquals(listOf("172.18.0.0/16", "172.19.0.0/16"), p.trustedProxies.map(String::trim))
            assertTrue(ClientIps(p).configured)
        }
    }

    @Test
    fun `the longer relaxed-binding name keeps working`() {
        val p = bind("SKELETON_WEB_CLIENT_IP_MODE" to "PROXY", "SKELETON_WEB_CLIENT_IP_TRUSTED_PROXIES" to "172.18.0.0/16")
        assertEquals(ClientIpMode.PROXY, p.mode)
        assertTrue(ClientIps(p).configured)
    }

    @Test
    fun `with neither name the mode stays unset - the account guard then refuses a protected boot`() {
        assertEquals(null, bind().mode)
    }

    @Test
    fun `the names in the homeserver app tf are the names this app reads - skipped when the homeserver checkout is not above this repo`() {
        val tf = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.map { it.resolve("infra/modules/app-docker/app.tf") }.firstOrNull { Files.isRegularFile(it) }
        assumeTrue(tf != null, "no homeserver checkout above this repo")
        val text = Files.readString(tf!!)
        val injected = Regex("""\$\{var\.app\.env_prefix\}_(CLIENT_IP_[A-Z_]+)=""").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("CLIENT_IP_MODE", "CLIENT_IP_TRUSTED_PROXIES"), injected, "the platform injects other client-IP names than this contract test knows")
        val yml = Files.readString(Path.of("src/main/resources/application.yml"))
        injected.forEach { assertTrue("\${SKELETON_$it:" in yml, "application.yml does not read SKELETON_$it, the name app.tf injects") }
        assertTrue("\"cloudflare\"" in text && "\"proxy\"" in text, "the platform's mode values changed")
    }
}
