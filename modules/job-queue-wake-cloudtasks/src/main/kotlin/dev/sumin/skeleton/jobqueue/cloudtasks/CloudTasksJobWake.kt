package dev.sumin.skeleton.jobqueue.cloudtasks

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.JobWake
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

/**
 * [JobWake] 를 Cloud Tasks 로 — `POST {api}/v2/projects/{project}/locations/{region}/queues/{queue}/tasks`, 실행 계정 토큰(Bearer).
 * 태스크는 [CloudTasksProperties.targetUrl] 로 `POST` 하며 `X-Wake-Secret` 을 싣는다. 설정이 비어 있으면 아무것도 하지 않는다(실행 때 판정).
 * - `scheduleTime` = `at` 을 지금 ~ 지금+29일(Tasks 한도)로 맞춘 값, `dispatchDeadline` = 시간 한도의 두 배(15초 ~ 1800초).
 * - 합치기: 이 인스턴스가 이미 건 예약 시각이 `지금 < 예약 ≤ at` 이면 새로 만들지 않는다 — 그 시각의 깨우기가 drain 으로 이어서 처리하고 끝에 다시 무장한다.
 * - 실패(네트워크 · 4xx · 5xx · 토큰)는 상태 코드 · 예외 종류만 WARN 한 줄로 남기고 삼킨다. 비밀 · 토큰은 로그에 싣지 않는다.
 */
class CloudTasksJobWake(
    private val props: CloudTasksProperties,
    private val wake: JobQueueProperties.Wake,
    private val tokens: MetadataTokenSource,
    private val time: TimeProvider,
) : JobWake {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newBuilder().connectTimeout(props.timeout).followRedirects(HttpClient.Redirect.NEVER).build()
    private val tasksUrl: URI by lazy {
        URI.create("${props.apiBaseUrl.trimEnd('/')}/v2/projects/${props.project}/locations/${props.region}/queues/${props.queue}/tasks")
    }

    private var armedAt: Instant? = null

    @Synchronized
    override fun request(at: Instant) {
        if (!props.configured) return
        val now = time.now()
        armedAt?.let { if (now.isBefore(it) && !it.isAfter(at)) return }
        val schedule = at.coerceIn(now, now.plus(MAX_AHEAD)).truncatedTo(ChronoUnit.SECONDS)
        try {
            create(schedule)
            armedAt = schedule
        } catch (e: IOException) {
            log.warn("Cloud Tasks wake request failed: {}", e.message)
        } catch (e: RuntimeException) {
            log.warn("Cloud Tasks wake request failed: {}", e.javaClass.simpleName)
        }
    }

    private fun create(schedule: Instant) {
        val deadline = (wake.timeBudget.seconds * 2).coerceIn(MIN_DEADLINE_SECONDS, MAX_DEADLINE_SECONDS)
        val body = mapOf(
            "task" to mapOf(
                "scheduleTime" to schedule.toString(),
                "dispatchDeadline" to "${deadline}s",
                "httpRequest" to mapOf(
                    "url" to props.targetUrl,
                    "httpMethod" to "POST",
                    "headers" to mapOf("X-Wake-Secret" to wake.secret),
                ),
            ),
        )
        val request = HttpRequest.newBuilder(tasksUrl).timeout(props.timeout)
            .header("Authorization", "Bearer ${tokens.token()}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("task creation was interrupted")
        } catch (e: IOException) {
            throw IOException("task creation did not get through: ${e.javaClass.simpleName}")
        }
        if (response.statusCode() !in 200..299) throw IOException("task creation was refused: HTTP ${response.statusCode()}")
    }

    private companion object {
        val MAX_AHEAD: Duration = Duration.ofDays(29)   // Cloud Tasks 는 30일 넘게 먼 시각을 받지 않는다
        const val MIN_DEADLINE_SECONDS = 15L
        const val MAX_DEADLINE_SECONDS = 1800L
    }
}
