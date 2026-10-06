package dev.sumin.skeleton.auth.social.oidc

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.MapPropertySource

/**
 * `providers.<코드>.*` 는 Map 값이라 진짜 환경변수 `SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_CLIENT_ID` 가 스프링의 느슨한 바인딩으로 묶이지 않는다
 * (환경변수의 `_` 는 모두 칸 구분이 되어 `auth.social.oidc` · `client.id` 로 쪼개진다). 이 후처리기가 점 표기 프로퍼티로 다시 적어 준다.
 * 규칙은 `auth-social` 의 `AuthSocialEnvironmentAliasPostProcessor` 와 같다: `SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_<코드>_<키>` → `skeleton.auth-social-oidc.providers.<코드 소문자>.<키: 소문자, _ 는 ->`.
 * 코드는 **한 단어**(영문 · 숫자)다 — 여러 단어 코드는 환경변수로 표현할 수 없으니 yml 로 적는다. 중첩 키는 `CLAIMS_` 접두만 `claims.` 로 나눈다
 * (`…_CLAIMS_EMAIL_VERIFIED` → `claims.email-verified`); `authorize-params` 같은 맵 키는 yml 로. 값이 빈 변수는 건너뛴다. 환경변수 바로 뒤에 놓여 yml 보다 우선한다.
 */
object OidcEnvironmentAliases {
    private val NAME = Regex("^SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_([A-Z0-9]+)_([A-Z0-9_]+)$")

    fun fromEnvironment(env: Map<String, String>): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        for ((name, value) in env) {
            if (value.isEmpty()) continue
            val m = NAME.matchEntire(name) ?: continue
            val key = m.groupValues[2].lowercase().let { if (it.startsWith("claims_")) "claims." + it.removePrefix("claims_").replace('_', '-') else it.replace('_', '-') }
            out["skeleton.auth-social-oidc.providers.${m.groupValues[1].lowercase()}.$key"] = value
        }
        return out
    }
}

class OidcEnvironmentAliasPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val system = environment.propertySources.get("systemEnvironment") as? EnumerablePropertySource<*> ?: return
        val aliases = OidcEnvironmentAliases.fromEnvironment(system.propertyNames.mapNotNull { n -> system.getProperty(n)?.let { n to it.toString() } }.toMap())
        if (aliases.isEmpty()) return
        environment.propertySources.addAfter("systemEnvironment", MapPropertySource("authSocialOidcEnvironmentAliases", aliases))
    }
}
