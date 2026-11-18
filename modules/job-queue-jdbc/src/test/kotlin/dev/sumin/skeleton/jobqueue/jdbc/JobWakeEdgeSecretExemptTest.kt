package dev.sumin.skeleton.jobqueue.jdbc

import kotlin.test.Test
import kotlin.test.assertEquals

/** edge-secret 모드의 원점 관문(platform)이 깨우기 주소를 비밀 없이 통과시킨다 — Scheduler · Tasks 는 엣지를 거치지 않고 직접 부른다 */
class JobWakeEdgeSecretExemptTest {
    @Test
    fun `wake path is exempt from the edge secret gate`() {
        assertEquals(setOf("/internal/jobs/drain"), JobWakeAutoConfiguration().jobWakeEdgeSecretExempt(JobQueueProperties()).paths())
    }

    @Test
    fun `a configured wake path is the exempt one`() {
        val properties = JobQueueProperties(wake = JobQueueProperties.Wake(path = "/internal/acme/drain"))
        assertEquals(setOf("/internal/acme/drain"), JobWakeAutoConfiguration().jobWakeEdgeSecretExempt(properties).paths())
    }
}
