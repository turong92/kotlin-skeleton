package dev.sumin.skeleton.scheduler.web

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.project.ProjectUnscopedPaths
import dev.sumin.skeleton.common.web.BearerIgnoredPaths
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.scheduler.SkeletonScheduledTaskRegistrar
import dev.sumin.skeleton.scheduler.SkeletonSchedulerAutoConfiguration
import dev.sumin.skeleton.scheduler.SkeletonSchedulerProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 밖의 스케줄러가 부르는 작업 주소 (mode=EXTERNAL, docs/modules/scheduler.md). 서블릿 웹 앱이면 **모드 조건 없이** 선다 —
 * 모드를 빈 조건으로 걸면 AOT · 네이티브 이미지에서 빌드 때 굳는다. 기본 LOCAL 에서는 요청마다 404 라 주소가 없는 것과 같다.
 */
@AutoConfiguration(after = [SkeletonSchedulerAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.web.servlet.DispatcherServlet"])
@ConditionalOnBean(SkeletonScheduledTaskRegistrar::class)
class SchedulerTriggerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun schedulerTriggerController(registrar: SkeletonScheduledTaskRegistrar, properties: SkeletonSchedulerProperties): SchedulerTriggerController =
        SchedulerTriggerController(registrar, properties)

    /** 로그인 없이 열린다 — 비밀값 헤더가 인증이다 */
    @Bean
    @ConditionalOnMissingBean(name = ["schedulerTriggerPublicEndpointContributor"])
    fun schedulerTriggerPublicEndpointContributor(properties: SkeletonSchedulerProperties): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("POST", "${properties.external.path}/*") }

    /** 프로젝트 N개 모드: 서비스 주소(run.app)로 불린다 — 호스트 필터가 421 로 막지 않게. EXTERNAL 일 때만 */
    @Bean
    @ConditionalOnMissingBean(name = ["schedulerTriggerUnscopedPath"])
    fun schedulerTriggerUnscopedPath(properties: SkeletonSchedulerProperties): ProjectUnscopedPaths =
        ProjectUnscopedPaths { if (properties.mode == SkeletonSchedulerProperties.Mode.EXTERNAL) listOf("${properties.external.path}/*") else emptyList() }

    /** Scheduler 의 OIDC 가 싣는 Google ID 토큰(Authorization: Bearer)을 JWT 필터가 읽다 401 을 내지 않게. EXTERNAL 일 때만 */
    @Bean
    @ConditionalOnMissingBean(name = ["schedulerTriggerBearerIgnored"])
    fun schedulerTriggerBearerIgnored(properties: SkeletonSchedulerProperties): BearerIgnoredPaths =
        BearerIgnoredPaths { if (properties.mode == SkeletonSchedulerProperties.Mode.EXTERNAL) listOf("${properties.external.path}/*") else emptyList() }

    @Bean
    @ConditionalOnMissingBean(name = ["schedulerTriggerDeployGuard"])
    fun schedulerTriggerDeployGuard(properties: SkeletonSchedulerProperties): DeployGuard = SchedulerTriggerDeployGuard(properties)
}

/** EXTERNAL 인데 비밀값이 없거나 약하면 보호 환경(stage · prod)에서 기동 실패, 그 밖에서는 경고. 값은 메시지에 싣지 않는다 */
class SchedulerTriggerDeployGuard(private val properties: SkeletonSchedulerProperties) : DeployGuard {
    override val name: String = "scheduler-external"

    override fun problems(context: DeployContext): List<String> = if (context.protectedBy(PROTECTED_PROFILES)) findings() else emptyList()

    override fun warnings(context: DeployContext): List<String> = if (context.protectedBy(PROTECTED_PROFILES)) emptyList() else findings()

    private fun findings(): List<String> {
        if (properties.mode != SkeletonSchedulerProperties.Mode.EXTERNAL) return emptyList()
        val secret = properties.external.secret
        return when {
            secret.isBlank() -> listOf("$PROPERTY ($ENV_NAME) is empty while skeleton.scheduler.mode=external: task addresses stay closed (404) and no scheduled task ever runs; set a random secret of at least $MIN_LENGTH characters")
            secret.length < MIN_LENGTH || secret.toSet().size < MIN_DISTINCT ->
                listOf("$PROPERTY ($ENV_NAME) is too weak: it is the only proof of the caller; use at least $MIN_LENGTH characters with at least $MIN_DISTINCT different ones")
            else -> emptyList()
        }
    }

    private companion object {
        const val PROPERTY = "skeleton.scheduler.external.secret"
        const val ENV_NAME = "<P>_SCHEDULER_EXTERNAL_SECRET"
        val PROTECTED_PROFILES = listOf("prod", "staging")
        const val MIN_LENGTH = 24
        const val MIN_DISTINCT = 8
    }
}
