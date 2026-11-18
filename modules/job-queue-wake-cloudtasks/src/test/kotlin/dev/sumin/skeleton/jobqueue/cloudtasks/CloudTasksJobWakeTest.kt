package dev.sumin.skeleton.jobqueue.cloudtasks

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

class CloudTasksJobWakeTest {
    private val google = FakeGoogle()
    private val time = MutableTime()
    private val secret = "acme-wake-secret-0123456789-abcdef"

    @AfterTest fun stop() = google.close()

    private fun props(project: String = "acme-proj") = CloudTasksProperties(
        project = project, region = "asia-northeast3", queue = "acme-drain", targetUrl = "https://acme-api.example.com/internal/jobs/drain",
        apiBaseUrl = google.baseUrl, metadataUrl = google.baseUrl,
    )

    private fun wakeProps(budget: Duration = Duration.ofSeconds(25)) = JobQueueProperties.Wake(secret = secret, timeBudget = budget)

    private fun jobWake(props: CloudTasksProperties = props(), wake: JobQueueProperties.Wake = wakeProps()) =
        CloudTasksJobWake(props, wake, MetadataTokenSource(props.metadataUrl, props.timeout, time), time)

    private fun scheduleOf(index: Int = 0): String = jsonOf(google.taskRequests[index].body)["task"]["scheduleTime"].asString()

    @Test
    fun `creates task at requested time with wake header`() {
        val at = time.now.plusSeconds(600)
        jobWake().request(at)

        val sent = google.taskRequests.single()
        assertEquals("/v2/projects/acme-proj/locations/asia-northeast3/queues/acme-drain/tasks", sent.path)
        assertEquals("Bearer fake-access-token-1", sent.authorization)
        assertEquals("Google", google.tokenRequests.single().flavor)
        val task = jsonOf(sent.body)["task"]
        assertEquals("2026-11-18T00:10:00Z", task["scheduleTime"].asString())
        assertEquals("https://acme-api.example.com/internal/jobs/drain", task["httpRequest"]["url"].asString())
        assertEquals("POST", task["httpRequest"]["httpMethod"].asString())
        assertEquals(secret, task["httpRequest"]["headers"]["X-Wake-Secret"].asString())
        assertEquals("50s", task["dispatchDeadline"].asString())
    }

    @Test
    fun `dispatch deadline is twice the time budget within 15s and 1800s`() {
        jobWake(wake = wakeProps(Duration.ofSeconds(2))).request(time.now.plusSeconds(10))
        jobWake(wake = wakeProps(Duration.ofHours(1))).request(time.now.plusSeconds(10))
        assertEquals("15s", jsonOf(google.taskRequests[0].body)["task"]["dispatchDeadline"].asString())
        assertEquals("1800s", jsonOf(google.taskRequests[1].body)["task"]["dispatchDeadline"].asString())
    }

    @Test
    fun `clamps schedule time to now and now+29d`() {
        val wake = jobWake()
        wake.request(time.now.minus(Duration.ofDays(3)))
        assertEquals("2026-11-18T00:00:00Z", scheduleOf(0))

        // 앞 예약보다 이른 쪽이 아니므로 합쳐지지 않게 새 인스턴스로 본다
        jobWake().request(time.now.plus(Duration.ofDays(90)))
        assertEquals("2026-12-17T00:00:00Z", scheduleOf(1))
    }

    @Test
    fun `coalesces a later request behind an armed earlier one`() {
        val wake = jobWake()
        wake.request(time.now.plusSeconds(60))
        wake.request(time.now.plusSeconds(120))   // 앞 예약(60초)이 먼저 불린다 - 새로 만들지 않는다
        assertEquals(1, google.taskRequests.size)

        wake.request(time.now.plusSeconds(30))    // 더 이른 시각은 새로 만든다
        assertEquals(2, google.taskRequests.size)

        time.now = time.now.plusSeconds(61)       // 앞 예약이 이미 지났다 - 다시 만든다
        wake.request(time.now.plusSeconds(10))
        assertEquals(3, google.taskRequests.size)
    }

    @Test
    fun `does nothing when not configured`() {
        val unset = CloudTasksProperties(apiBaseUrl = google.baseUrl, metadataUrl = google.baseUrl)
        jobWake(props = unset).request(time.now.plusSeconds(60))
        assertTrue(google.taskRequests.isEmpty())
        assertTrue(google.tokenRequests.isEmpty())
    }

    @Test
    fun `swallows http failure and logs status only`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        try {
            val wake = jobWake()
            google.taskStatus = 503
            wake.request(time.now.plusSeconds(60))
            google.taskStatus = 200
            google.tokenStatus = 500
            time.now = time.now.plusSeconds(7200)   // 토큰 캐시가 지났다 - 토큰 요청도 실패시킨다
            wake.request(time.now.plusSeconds(60))

            val logged = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
            assertTrue(logged.contains("503"), logged)
            assertTrue(logged.contains("500"), logged)
            assertTrue(!logged.contains(secret) && !logged.contains("fake-access-token"), logged)
        } finally {
            root.detachAppender(appender)
        }
    }

    @Test
    fun `a failed request does not count as armed`() {
        val wake = jobWake()
        google.taskStatus = 500
        wake.request(time.now.plusSeconds(60))
        google.taskStatus = 200
        wake.request(time.now.plusSeconds(60))
        assertEquals(2, google.taskRequests.size)
    }

    @Test
    fun `caches metadata token until expiry`() {
        val wake = jobWake()
        google.tokenExpiresIn = 3600
        wake.request(time.now.plusSeconds(10))
        time.now = time.now.plusSeconds(100)
        wake.request(time.now.plusSeconds(10))
        assertEquals(1, google.tokenRequests.size, "만료 전에는 토큰을 다시 받지 않는다")

        time.now = time.now.plusSeconds(3600 - 60)   // expires_in - 60초가 지났다
        wake.request(time.now.plusSeconds(10))
        assertEquals(2, google.tokenRequests.size)
        assertEquals("Bearer fake-access-token-2", google.taskRequests.last().authorization)
    }

    @Test
    fun `scheduleTime is RFC3339 in UTC rounded up to the second so it never fires early`() {
        jobWake().request(Instant.parse("2026-11-18T09:30:15.987Z"))
        assertEquals("2026-11-18T09:30:16Z", scheduleOf())
    }

    @Test
    fun `does not hold a lock while calling the network`() {
        val wake = jobWake()
        google.taskDelayMillis = 3000   // 두 요청 모두 3초 — 락을 쥐면 약 5.8초, 안 쥐면 약 3초
        val first = Thread { wake.request(time.now.plusSeconds(60)) }.apply { start() }
        Thread.sleep(200)   // 첫 요청이 Tasks 응답을 기다리는 중
        val started = System.nanoTime()
        wake.request(time.now.plusSeconds(30))   // 락을 안 잡으니 기다리지 않고 자기 호출을 한다(겹쳐 둘이 생겨도 무해)
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        first.join()
        assertTrue(tookMillis < 5000, "다른 요청이 끝나길 기다리지 않는다")
        assertEquals(2, google.taskRequests.size)
    }
}
