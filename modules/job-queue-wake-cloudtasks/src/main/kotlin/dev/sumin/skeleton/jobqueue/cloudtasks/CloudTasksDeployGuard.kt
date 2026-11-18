package dev.sumin.skeleton.jobqueue.cloudtasks

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties

/**
 * 보호 환경(stage · prod)에서 Cloud Tasks 깨우기가 켜져 있는데 구글 주소가 기본이 아니거나, 태스크 대상이 https 가 아니거나, 깨우기 비밀값이 비어 있으면 기동 실패.
 * 시험용 가짜 서버 주소가 운영에 남아 토큰 · 비밀값이 엉뚱한 곳으로 나가지 않게 한다. 메시지에는 속성 이름만 싣고 값은 싣지 않는다. 꺼져 있으면(넷 중 하나라도 비어 있으면) 볼 것이 없다.
 */
class CloudTasksDeployGuard(
    private val props: CloudTasksProperties,
    private val queue: JobQueueProperties,
) : DeployGuard {
    override val name: String = "job-queue-wake-cloudtasks"

    override fun problems(context: DeployContext): List<String> {
        if (!props.configured || !context.protectedBy(PROTECTED_PROFILES)) return emptyList()
        return buildList {
            if (props.apiBaseUrl != CloudTasksProperties.DEFAULT_API_BASE_URL) add("$PREFIX.api-base-url is not the Cloud Tasks address: the access token would be sent elsewhere; leave it unset outside tests")
            if (props.metadataUrl != CloudTasksProperties.DEFAULT_METADATA_URL) add("$PREFIX.metadata-url is not the metadata server address: leave it unset outside tests")
            if (!props.targetUrl.startsWith("https://")) add("$PREFIX.target-url must be an https address: the task carries the wake secret")
            if (queue.wake.secret.isBlank()) add("skeleton.job-queue.wake.secret (<P>_JOB_QUEUE_WAKE_SECRET) is empty: the tasks would call a closed address")
        }
    }

    private companion object {
        const val PREFIX = "skeleton.job-queue-wake-cloudtasks"
        val PROTECTED_PROFILES = listOf("prod", "staging")
    }
}
