package dev.sumin.skeleton.migration

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import org.springframework.core.env.Environment

/** [MigrationCleanGuardRules] 를 DeployGuard 로 내놓는다 — 플랫폼의 기동 요약에 보인다. 실제 차단은 더 이른 EnvironmentPostProcessor 가 한다(DB 에 닿기 전). */
class MigrationCleanDeployGuard(private val environment: Environment) : DeployGuard {
    override val name: String = "migration-clean"

    override fun problems(context: DeployContext): List<String> = MigrationCleanGuardRules.problems(environment, context)
}
