package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 요청 구동 모드(`skeleton.job-queue.mode=on-request`)의 깨우기 주소 (docs/cloud-run-scale-to-zero.md). 서블릿 웹 앱이고 워커가 있을 때만.
 * 기본 폴링 모드에서는 아무 빈도 만들지 않는다 — 이 파일 때문에 기본 동작이 바뀌지 않는다.
 */
@AutoConfiguration(after = [JobQueueJdbcAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(JobQueueWorker::class)
@ConditionalOnProperty(prefix = "skeleton.job-queue", name = ["mode"], havingValue = "on-request")
class JobWakeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun jobWakeController(worker: JobQueueWorker, tasks: ObjectProvider<OnRequestTask>, properties: JobQueueProperties): JobWakeController =
        JobWakeController(worker, tasks.orderedStream().toList(), properties.wake.secret, properties.wake.timeBudget)

    /** 깨우기 주소는 로그인 없이 열린다 — 비밀값 헤더가 인증이다 */
    @Bean
    @ConditionalOnMissingBean(name = ["jobWakePublicEndpointContributor"])
    fun jobWakePublicEndpointContributor(properties: JobQueueProperties): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("POST", properties.wake.path) }

    @Bean
    @ConditionalOnMissingBean(name = ["jobWakeDeployGuard"])
    fun jobWakeDeployGuard(properties: JobQueueProperties): DeployGuard = JobWakeDeployGuard(properties.wake)
}

/**
 * 깨우기 비밀값이 없거나 약하면 보호 환경(stage · prod)에서 기동 실패, 그 밖에서는 경고다. 메시지에는 속성 · 환경변수 이름만 적고 값은 싣지 않는다.
 * 약함 = [MIN_LENGTH]자 미만 · 서로 다른 글자 [MIN_DISTINCT]개 미만.
 */
class JobWakeDeployGuard(private val wake: JobQueueProperties.Wake) : DeployGuard {
    override val name: String = "job-queue-wake"

    override fun problems(context: DeployContext): List<String> = if (context.protectedBy(PROTECTED_PROFILES)) findings() else emptyList()

    override fun warnings(context: DeployContext): List<String> = if (context.protectedBy(PROTECTED_PROFILES)) emptyList() else findings()

    private fun findings(): List<String> = when {
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
