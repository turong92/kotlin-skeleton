package dev.sumin.skeleton.video.jdbc

import dev.sumin.skeleton.video.VideoWakeRuns
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/** 깨우기 기록의 세 질문(창 안 성공 수 · 마지막 성공 · 마지막 성공 뒤 실패 수)이 두 방언에서 같은 답을 한다 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [VideoJdbcTestApplication::class])
class VideoWakeRunsDbTest {
    @Autowired lateinit var runs: VideoWakeRuns
    @Autowired lateinit var jdbc: JdbcTemplate

    private val t0 = Instant.parse("2026-11-04T00:00:00.123456Z")

    @BeforeTest
    fun clean() {
        jdbc.update("delete from video_wake_runs")
    }

    @Test
    fun `counts only accepted wakes inside the window and reports the last one`() {
        assertNull(runs.lastOkAt())
        runs.record(t0, true, "executions/a", null)
        runs.record(t0.plusSeconds(60), false, null, "HTTP 503")
        runs.record(t0.plusSeconds(120), true, "executions/b", null)

        assertEquals(2, runs.okSince(t0))
        assertEquals(1, runs.okSince(t0.plusSeconds(1)))
        assertEquals(t0.plusSeconds(120), runs.lastOkAt())
    }

    @Test
    fun `consecutive failures count only those after the last success`() {
        runs.record(t0, false, null, "HTTP 500")
        runs.record(t0.plusSeconds(1), false, null, "HTTP 500")
        assertEquals(2, runs.consecutiveFailures())   // 성공이 한 번도 없으면 실패 전부
        runs.record(t0.plusSeconds(2), true, "executions/a", null)
        assertEquals(0, runs.consecutiveFailures())
        runs.record(t0.plusSeconds(3), false, null, "HTTP 500")
        assertEquals(1, runs.consecutiveFailures())
    }

    @Test
    fun `deleting old rows keeps newer ones`() {
        runs.record(t0, true, null, null)
        runs.record(t0.plusSeconds(100), true, null, null)
        assertEquals(1, runs.deleteOlderThan(t0.plusSeconds(50)))
        assertEquals(1, runs.okSince(t0))
    }
}
