package dev.sumin.skeleton.migration

import dev.sumin.skeleton.common.deploy.DeployContext
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment

/**
 * DB 를 지울 수 있는 설정 — skeleton.migration.clean-on-validation-error=true, Flyway spring.flyway.clean-disabled=false,
 * Liquibase spring.liquibase.drop-first=true — 이 허용 프로필(skeleton.migration.clean-allowed-profiles, 기본 local) 밖에서 켜져 있으면 문제다.
 * `skeleton.env` 를 stage · prod 로 정했다면 허용 프로필이 켜져 있어도 문제다 (옵트인 — 정하지 않으면 이전과 같다).
 * 환경 · 바인더만 읽는다 — 빈이 없는 가장 이른 시점(EnvironmentPostProcessor)에서도 같은 규칙으로 돈다.
 */
internal object MigrationCleanGuardRules {
    fun problems(environment: Environment, context: DeployContext): List<String> {
        val binder = Binder.get(environment)
        // 생성자 바인딩(데이터 클래스)은 kotlin-reflect 없이 실패하므로 키를 하나씩 읽는다 — 기본값은 MigrationProperties 와 같게
        val defaults = MigrationProperties()
        val cleanOnValidationError = binder.bind("skeleton.migration.clean-on-validation-error", Boolean::class.javaObjectType)
            .orElse(null) ?: defaults.cleanOnValidationError
        val allowedProfiles = binder.bind("skeleton.migration.clean-allowed-profiles", Bindable.listOf(String::class.java))
            .orElse(null) ?: defaults.cleanAllowedProfiles
        val flywayCleanEnabled = (binder.bind("spring.flyway.clean-disabled", Boolean::class.javaObjectType).orElse(null) ?: true).not()
        val liquibaseDropFirst = binder.bind("spring.liquibase.drop-first", Boolean::class.javaObjectType).orElse(null) ?: false
        val allowed = !context.protectedEnv && context.activeProfiles.any { it in allowedProfiles }
        if (allowed) return emptyList()
        val offending = buildList {
            if (cleanOnValidationError) add("skeleton.migration.clean-on-validation-error=true")
            if (flywayCleanEnabled) add("spring.flyway.clean-disabled=false")
            if (liquibaseDropFirst) add("spring.liquibase.drop-first=true")
        }
        if (offending.isEmpty()) return emptyList()
        val why = if (context.protectedEnv) {
            "not allowed with ${DeployContext.PROPERTY}=${context.env!!.name.lowercase()}"
        } else {
            "only allowed in profiles $allowedProfiles (skeleton.migration.clean-allowed-profiles); active profiles: " +
                context.activeProfiles.ifEmpty { setOf("default") }
        }
        return listOf("${offending.joinToString()} can wipe the database and is $why")
    }
}
