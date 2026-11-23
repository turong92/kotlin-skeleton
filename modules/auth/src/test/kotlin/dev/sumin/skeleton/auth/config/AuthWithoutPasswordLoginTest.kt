package dev.sumin.skeleton.auth.config

import dev.sumin.skeleton.auth.api.AuthController
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.security.JwtAuthenticationFilter
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.common.web.PublicEndpointRegistry
import dev.sumin.skeleton.common.web.WebProperties
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.web.DefaultSecurityFilterChain
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AnyRequestMatcher
import org.springframework.web.bind.annotation.PostMapping
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/** `auth` 만 있고 `auth-password` 가 없으면 비밀번호 로그인 길이 없다 — 나머지(토큰 발급 · 필터 · /auth/me)는 그대로 선다 */
class AuthWithoutPasswordLoginTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuthAutoConfiguration::class.java))
        .withBean(ObjectMapper::class.java, { JsonMapper.builder().build() })
        .withBean(SecurityFilterChain::class.java, { DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE) })

    @Test
    fun `without auth-password there is no login endpoint and the rest of auth still starts`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(AuthController::class.java)
            assertThat(context).hasSingleBean(AuthTokenResponseFactory::class.java)
            assertThat(context).hasSingleBean(JwtAuthenticationFilter::class.java)

            val registry = PublicEndpointRegistry.from(WebProperties(), context.getBeanProvider(PublicEndpointContributor::class.java).orderedStream().toList())
            assertThat(registry.methodSpecificEndpoints.map { it.pattern }).doesNotContain("/api/v1/auth/login")

            val postMappings = context.getBeansWithAnnotation(org.springframework.web.bind.annotation.RestController::class.java).values
                .flatMap { it.javaClass.methods.toList() }
                .mapNotNull { it.getAnnotation(PostMapping::class.java) }
                .flatMap { it.value.toList() }
            assertThat(postMappings).doesNotContain("/login")
        }
    }
}
