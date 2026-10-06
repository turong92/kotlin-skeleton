package dev.sumin.skeleton.auth.social.config

import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.MapPropertySource

/**
 * 소셜 제공자 환경변수 규칙 — **모든 제공자에 하나**.
 *
 * 제공자 설정이 Map 값(`providers.<코드>.*`)이면 진짜 환경변수 `SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_ID` 는 스프링의 느슨한 바인딩으로 묶이지 않는다
 * (환경변수의 `_` 는 모두 칸 구분이 되어 `auth.social` · `client.id` 로 쪼개진다 — `ENABLED` 만 조건 검사의 직접 조회로 우연히 닿는다). 그래서 이름을 점 표기 프로퍼티로 다시 적는다:
 * `<envPrefix><코드>_<키>` → `<propertyPrefix>.<코드 소문자>.<키: 소문자, _ 는 ->`.
 * 코드는 **한 단어**(영문 대문자 · 숫자) — 여러 단어 코드는 환경변수로 표현할 수 없으니 yml 로 적는다. `nestedPrefixes` 에 든 키 접두(예 `claims`)만 한 단계 중첩으로 나눈다. 값이 빈 변수는 건너뛴다.
 * 별칭 속성 소스는 `systemEnvironment` 바로 뒤에 놓여 yml 보다 우선한다.
 *
 * 적용 대상(규칙의 전부): `auth-social` → `SKELETON_AUTH_SOCIAL_PROVIDERS_<코드>_<키>`, `auth-social-oidc` → `SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_<코드>_<키>`.
 * 제공자 하나짜리 모듈(`auth-social-x`)은 Map 이 아니라 평범한 속성 클래스라 `SKELETON_AUTH_SOCIAL_X_<키>` 가 그대로 묶인다 — 별칭이 필요 없다.
 */
object ProviderEnvironmentAliases {
    fun fromEnvironment(env: Map<String, String>, envPrefix: String, propertyPrefix: String, nestedPrefixes: Set<String> = emptySet()): Map<String, Any> {
        val name = Regex("^" + Regex.escape(envPrefix) + "([A-Z0-9]+)_([A-Z0-9_]+)$")
        val out = linkedMapOf<String, Any>()
        for ((variable, value) in env) {
            if (value.isEmpty()) continue
            val m = name.matchEntire(variable) ?: continue
            val key = m.groupValues[2].lowercase()
            val nested = nestedPrefixes.firstOrNull { key.startsWith(it + "_") }
            val property = if (nested != null) "$nested." + key.removePrefix(nested + "_").replace('_', '-') else key.replace('_', '-')
            out["$propertyPrefix.${m.groupValues[1].lowercase()}.$property"] = value
        }
        return out
    }

    /** `systemEnvironment` 속성 소스를 읽어 별칭을 그 바로 뒤에 놓는다 (같은 이름의 소스가 있으면 갈아 끼운다) */
    fun apply(environment: ConfigurableEnvironment, envPrefix: String, propertyPrefix: String, sourceName: String, nestedPrefixes: Set<String> = emptySet()) {
        val system = environment.propertySources.get("systemEnvironment") as? EnumerablePropertySource<*> ?: return
        val aliases = fromEnvironment(system.propertyNames.mapNotNull { n -> system.getProperty(n)?.let { n to it.toString() } }.toMap(), envPrefix, propertyPrefix, nestedPrefixes)
        environment.propertySources.remove(sourceName)
        if (aliases.isNotEmpty()) environment.propertySources.addAfter("systemEnvironment", MapPropertySource(sourceName, aliases))
    }
}
