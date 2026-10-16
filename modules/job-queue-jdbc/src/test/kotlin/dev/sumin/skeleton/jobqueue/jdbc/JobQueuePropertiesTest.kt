package dev.sumin.skeleton.jobqueue.jdbc

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** 0 이면 판이 늘 "가득 찼다"가 되거나 쉬지 않고 도는 값은 기동 때 거절한다. */
class JobQueuePropertiesTest {
    @Test
    fun `values that would make the worker spin are refused`() {
        assertFailsWith<IllegalArgumentException> { JobQueueProperties(batchSize = 0) }
        assertFailsWith<IllegalArgumentException> { JobQueueProperties(pollInterval = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { JobQueueProperties(concurrency = 0) }
        JobQueueProperties(batchSize = 1, pollInterval = Duration.ofMillis(1))
    }
}
