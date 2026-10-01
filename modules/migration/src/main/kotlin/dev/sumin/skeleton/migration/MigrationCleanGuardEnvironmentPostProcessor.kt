package dev.sumin.skeleton.migration

import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment

/**
 * 가드 (도구 무관): DB 를 지울 수 있는 설정 — skeleton.migration.clean-on-validation-error=true,
 * Flyway spring.flyway.clean-disabled=false, Liquibase spring.liquibase.drop-first=true — 이
 * 허용 프로필(skeleton.migration.clean-allowed-profiles, 기본 local) 밖에서 켜져 있으면 DB 에 닿기 전에 기동을 실패시킨다.
 * 마이그레이션 도구 설정 자체(out-of-order, locations 등)는 건드리지 않는다 — 그건 앱이 자기 yml 에 적는 선택이다.
 * application.yml 과 프로필이 다 읽힌 뒤에 돌도록 가장 늦게 실행된다.
 */
class MigrationCleanGuardEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        guard(environment)
    }

    private fun guard(environment: ConfigurableEnvironment) {
        val binder = Binder.get(environment)
        // 생성자 바인딩(데이터 클래스)은 kotlin-reflect 없이 실패하므로 키를 하나씩 읽는다 — 기본값은 MigrationProperties 와 같게
        val defaults = MigrationProperties()
        val cleanOnValidationError = binder.bind("skeleton.migration.clean-on-validation-error", Boolean::class.javaObjectType)
            .orElse(null) ?: defaults.cleanOnValidationError
        val allowedProfiles = binder.bind("skeleton.migration.clean-allowed-profiles", Bindable.listOf(String::class.java))
            .orElse(null) ?: defaults.cleanAllowedProfiles
        val flywayCleanEnabled = (binder.bind("spring.flyway.clean-disabled", Boolean::class.javaObjectType).orElse(null) ?: true).not()
        val liquibaseDropFirst = binder.bind("spring.liquibase.drop-first", Boolean::class.javaObjectType).orElse(null) ?: false
        val active = environment.activeProfiles.toSet()
        if (active.any { it in allowedProfiles }) return
        val offending = buildList {
            if (cleanOnValidationError) add("skeleton.migration.clean-on-validation-error=true")
            if (flywayCleanEnabled) add("spring.flyway.clean-disabled=false")
            if (liquibaseDropFirst) add("spring.liquibase.drop-first=true")
        }
        check(offending.isEmpty()) {
            "${offending.joinToString()} can wipe the database and is only allowed in profiles " +
                "$allowedProfiles (skeleton.migration.clean-allowed-profiles); active profiles: " +
                active.ifEmpty { setOf("default") }
        }
    }
}
