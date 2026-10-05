package dev.sumin.skeleton.migration

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployFinding
import dev.sumin.skeleton.common.deploy.DeployGuardViolationException
import org.springframework.boot.SpringApplication
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
        val problems = MigrationCleanGuardRules.problems(environment, DeployContext.from(environment))
        if (problems.isNotEmpty()) throw DeployGuardViolationException(problems.map { DeployFinding("migration-clean", it) })
    }
}
