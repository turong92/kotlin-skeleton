package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.project.ProjectUnscopedPaths
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.web.BearerIgnoredPaths
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 요청 구동 모드(`skeleton.job-queue.mode=on-request`)의 깨우기 주소 (docs/cloud-run-scale-to-zero.md). 서블릿 웹 앱이고 워커가 있을 때 선다 — **모드 조건 없이**:
 * 모드를 빈 조건으로 걸면 AOT · 네이티브 이미지에서 빌드 때 굳어, 실행 환경변수로 켠 이미지에 주소가 없다. 기본 폴링 모드에서는 요청마다 404 로 답해 주소가 없는 것과 같다.
 */
@AutoConfiguration(after = [JobQueueJdbcAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(JobQueueWorker::class)
class JobWakeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun jobWakeController(worker: JobQueueWorker, tasks: ObjectProvider<OnRequestTask>, properties: JobQueueProperties): JobWakeController =
        JobWakeController(worker, tasks.orderedStream().toList(), properties)

    /** 깨우기 주소는 로그인 없이 열린다 — 비밀값 헤더가 인증이다 */
    @Bean
    @ConditionalOnMissingBean(name = ["jobWakePublicEndpointContributor"])
    fun jobWakePublicEndpointContributor(properties: JobQueueProperties): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("POST", properties.wake.path) }

    /** 프로젝트 N개 모드: 깨우기는 서비스 주소(어느 프로젝트의 호스트도 아닌 run.app)로 불린다 — 호스트 필터가 421 로 막지 않게. 순회는 워커가 프로젝트마다 한다. 요청 구동일 때만 */
    @Bean
    @ConditionalOnMissingBean(name = ["jobWakeUnscopedPath"])
    fun jobWakeUnscopedPath(properties: JobQueueProperties): ProjectUnscopedPaths =
        ProjectUnscopedPaths { if (properties.mode == JobQueueProperties.Mode.ON_REQUEST) listOf(properties.wake.path) else emptyList() }

    /** 깨우기 주소는 자기 인증(비밀값 헤더)을 한다 — Scheduler 의 OIDC 가 싣는 Google ID 토큰(Authorization: Bearer)을 JWT 필터가 우리 토큰으로 검증하려다 401 을 내지 않게 한다. 요청 구동일 때만 */
    @Bean
    @ConditionalOnMissingBean(name = ["jobWakeBearerIgnored"])
    fun jobWakeBearerIgnored(properties: JobQueueProperties): BearerIgnoredPaths =
        BearerIgnoredPaths { if (properties.mode == JobQueueProperties.Mode.ON_REQUEST) listOf(properties.wake.path) else emptyList() }

    @Bean
    @ConditionalOnMissingBean(name = ["jobWakeDeployGuard"])
    fun jobWakeDeployGuard(properties: JobQueueProperties): DeployGuard = JobWakeDeployGuard(properties)
}

/**
 * 깨우기 비밀값이 없거나 약하면 보호 환경(stage · prod)에서 기동 실패, 그 밖에서는 경고다. 메시지에는 속성 · 환경변수 이름만 적고 값은 싣지 않는다.
 * 약함 = [MIN_LENGTH]자 미만 · 서로 다른 글자 [MIN_DISTINCT]개 미만.
 */
class JobWakeDeployGuard(private val properties: JobQueueProperties) : DeployGuard {
    override val name: String = "job-queue-wake"

    override fun problems(context: DeployContext): List<String> = if (context.protectedBy(PROTECTED_PROFILES)) findings() else emptyList()

    override fun warnings(context: DeployContext): List<String> = if (context.protectedBy(PROTECTED_PROFILES)) emptyList() else findings()

    // 실행 때 판정한다(빈은 모드와 상관없이 서 있다) — 폴링 모드는 할 말이 없다
    private fun findings(): List<String> = if (properties.mode != JobQueueProperties.Mode.ON_REQUEST) emptyList() else secretFindings(properties.wake)

    private fun secretFindings(wake: JobQueueProperties.Wake): List<String> = when {
        wake.secret.isBlank() -> listOf("$PROPERTY ($ENV_NAME) is empty while skeleton.job-queue.mode=on-request: the wake address stays closed (404), so nothing ever drains the queue; set a random secret of at least $MIN_LENGTH characters")
        wake.secret.length < MIN_LENGTH || wake.secret.toSet().size < MIN_DISTINCT ->
            listOf("$PROPERTY ($ENV_NAME) is too weak: it is the only proof of the caller; use at least $MIN_LENGTH characters with at least $MIN_DISTINCT different ones")
        else -> emptyList()
    }

    private companion object {
        const val PROPERTY = "skeleton.job-queue.wake.secret"
        const val ENV_NAME = "<P>_JOB_QUEUE_WAKE_SECRET"
        val PROTECTED_PROFILES = listOf("prod", "staging")   // 스위치(SKELETON_ENV=stage|prod)도 보호 환경으로 본다
        const val MIN_LENGTH = 24
        const val MIN_DISTINCT = 8
    }
}
