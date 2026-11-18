package dev.sumin.skeleton.jobqueue.cloudtasks

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudTasksDeployGuardTest {
    private val prod = DeployContext(DeployEnv.PROD, emptySet())
    private val dev = DeployContext(null, emptySet())
    private val secret = "acme-wake-secret-0123456789-abcdef"

    private fun props(api: String = CloudTasksProperties.DEFAULT_API_BASE_URL, metadata: String = CloudTasksProperties.DEFAULT_METADATA_URL, target: String = "https://acme-api.example.com/internal/jobs/drain") =
        CloudTasksProperties(project = "acme-proj", region = "asia-northeast3", queue = "acme-drain", targetUrl = target, apiBaseUrl = api, metadataUrl = metadata)

    private fun guard(props: CloudTasksProperties, wakeSecret: String = secret) =
        CloudTasksDeployGuard(props, JobQueueProperties(wake = JobQueueProperties.Wake(secret = wakeSecret)))

    @Test
    fun `valid settings are fine`() = assertEquals(emptyList(), guard(props()).problems(prod))

    @Test
    fun `non default api url in protected env is a problem`() {
        val problems = guard(props(api = "http://127.0.0.1:9999")).problems(prod) + guard(props(metadata = "http://127.0.0.1:9998")).problems(prod)
        assertEquals(2, problems.size)
        assertTrue(problems[0].contains("skeleton.job-queue-wake-cloudtasks.api-base-url"))
        assertTrue(problems[1].contains("skeleton.job-queue-wake-cloudtasks.metadata-url"))
        assertTrue(problems.none { it.contains("127.0.0.1") }, "값은 싣지 않는다: $problems")
        assertEquals(emptyList(), guard(props(api = "http://127.0.0.1:9999")).problems(dev), "보호 환경이 아니면 막지 않는다")
    }

    @Test
    fun `http target url is a problem`() {
        val problems = guard(props(target = "http://acme-api.example.com/internal/jobs/drain")).problems(prod)
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("skeleton.job-queue-wake-cloudtasks.target-url"))
    }

    @Test
    fun `blank wake secret is a problem`() {
        val problems = guard(props(), wakeSecret = "").problems(prod)
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("skeleton.job-queue.wake.secret"))
    }

    @Test
    fun `a switched off provider has nothing to check`() {
        assertEquals(emptyList(), guard(CloudTasksProperties(apiBaseUrl = "http://127.0.0.1:1"), wakeSecret = "").problems(prod))
    }
}
