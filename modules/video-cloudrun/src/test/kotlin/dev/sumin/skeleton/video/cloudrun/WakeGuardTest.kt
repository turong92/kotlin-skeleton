package dev.sumin.skeleton.video.cloudrun

import dev.sumin.skeleton.video.VideoWakeException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 돈과 경보 - 창 안 실행 상한 · 최소 간격을 넘지 않고, 연속 실패는 경보를 낸다 (가짜 구글 서버 위에서) */
class WakeGuardTest {
    private val account = TestServiceAccount()
    private val google = FakeGoogleServer(account)
    private val clock = TestClock()
    private val runs = InMemoryWakeRuns()
    private val alerts = mutableListOf<Pair<Int, String>>()

    private fun guard(max: Int = 3, window: Long = 600, gap: Long = 0, alertAfter: Int = 3): GuardedWaker {
        val props = CloudRunProperties(
            enabled = true, project = "demo-project", region = "asia-northeast3", job = "video-worker", credentialsJson = account.json(),
            apiBase = google.base, tokenUri = "${google.base}/token",
            maxExecutions = max, executionWindow = java.time.Duration.ofSeconds(window), minWakeGap = java.time.Duration.ofSeconds(gap), alertAfterFailures = alertAfter,
        )
        return GuardedWaker(CloudRunWaker(props, clock), runs, props, { n, why -> alerts += n to why }, clock)
    }

    @AfterTest
    fun stop() = google.close()

    @Test
    fun `never starts more executions than the cap inside the window, and resumes when the window moves on`() {
        val waker = guard(max = 2, window = 600)
        repeat(5) { waker.wake(); clock.advance(10) }
        assertEquals(2, google.runRequests.size)

        clock.advance(600)   // 처음 둘이 창 밖으로 나갔다
        waker.wake()
        assertEquals(3, google.runRequests.size)
    }

    @Test
    fun `does not wake again inside the minimum gap`() {
        val waker = guard(gap = 30)
        waker.wake()
        clock.advance(10)
        waker.wake()
        assertEquals(1, google.runRequests.size)
        clock.advance(25)   // 첫 깨우기에서 35초
        waker.wake()
        assertEquals(2, google.runRequests.size)
    }

    @Test
    fun `alerts only after the configured number of consecutive failures, and rethrows so the queue retries`() {
        google.runStatus = 500
        val waker = guard(alertAfter = 3)
        repeat(2) { assertFailsWith<VideoWakeException> { waker.wake() }; clock.advance(60) }
        assertTrue(alerts.isEmpty())
        assertFailsWith<VideoWakeException> { waker.wake() }
        assertEquals(1, alerts.size)
        assertEquals(3, alerts.single().first)
        assertTrue("500" in alerts.single().second)
    }

    @Test
    fun `a success resets the failure streak`() {
        val waker = guard(alertAfter = 2)
        google.runStatus = 500
        assertFailsWith<VideoWakeException> { waker.wake() }
        google.runStatus = 200
        waker.wake()
        google.runStatus = 500
        assertFailsWith<VideoWakeException> { waker.wake() }
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `a refused wake is not retried elsewhere - one failed call, nothing else`() {
        google.runStatus = 500
        assertFailsWith<VideoWakeException> { guard().wake() }
        assertEquals(1, google.runRequests.size)
        assertNull(runs.lastOkAt())
    }
}
