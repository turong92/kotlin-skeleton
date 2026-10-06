package dev.sumin.skeleton.auth.social.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * 제공자 설정은 Map 값(`skeleton.auth-social.providers.<제공자>.client-id`)이라, 진짜 환경변수 `SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_ID` 는 스프링의 느슨한 바인딩으로
 * 묶이지 않는다(환경변수의 `_` 는 모두 칸 구분이 되어 `auth.social` · `client.id` 로 쪼개진다 — `ENABLED` 만 조건 검사의 직접 조회로 우연히 닿는다).
 * 그래서 문서(docs/deploy.md · 홈서버 `secrets:`)가 적은 이름이 값이 비어 기동 실패(`clientId must not be blank`)가 되었다. 이 후처리기가 그 이름을 점 표기 프로퍼티로 다시 적어 준다.
 * 이름 규칙: `SKELETON_AUTH_SOCIAL_PROVIDERS_<제공자>_<키>` → `skeleton.auth-social.providers.<제공자 소문자>.<키: 소문자, _ 는 ->`. 제공자 이름은 한 단어(영문 · 숫자).
 * 값이 빈 변수는 건너뛴다. 환경변수(systemEnvironment) 바로 뒤에 놓여 yml 보다 우선한다.
 */
object AuthSocialEnvironmentAliases {
    private val NAME = Regex("^SKELETON_AUTH_SOCIAL_PROVIDERS_([A-Z0-9]+)_([A-Z0-9_]+)$")

    fun fromEnvironment(env: Map<String, String>): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        for ((name, value) in env) {
            if (value.isEmpty()) continue
            val m = NAME.matchEntire(name) ?: continue
            out["skeleton.auth-social.providers.${m.groupValues[1].lowercase()}.${m.groupValues[2].lowercase().replace('_', '-')}"] = value
        }
        return out
    }
}

class AuthSocialEnvironmentAliasPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val aliases = AuthSocialEnvironmentAliases.fromEnvironment(environment.systemEnvironment.mapValues { it.value.toString() })
        if (aliases.isEmpty()) return
        val source = MapPropertySource("authSocialEnvironmentAliases", aliases)
        val sources = environment.propertySources
        if (sources.contains("systemEnvironment")) sources.addAfter("systemEnvironment", source) else sources.addLast(source)
    }
}
