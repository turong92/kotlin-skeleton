package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.wake.SimpleOnRequestTask
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** 깨우기 주소의 인증과 순서 — 비밀값이 틀리거나 없거나 비어 있으면 일감에도 주기 작업에도 손대지 않는다. */
class JobWakeControllerTest {
    private val order = CopyOnWriteArrayList<String>()
    private val drainer = JobDrainer { budget -> order += "drain(${budget.seconds}s)"; DrainResult(processed = 2, rounds = 2, timedOut = false, elapsedMillis = 5) }
    private val secret = "a-long-random-wake-secret-0123456789"

    private fun mvc(
        secret: String = this.secret,
        tasks: List<OnRequestTask> = listOf(SimpleOnRequestTask("t1") { order += "t1" }),
        mode: JobQueueProperties.Mode = JobQueueProperties.Mode.ON_REQUEST,
    ): MockMvc =
        MockMvcBuilders.standaloneSetup(JobWakeController(drainer, tasks, JobQueueProperties(mode = mode, wake = JobQueueProperties.Wake(secret = secret, timeBudget = Duration.ofSeconds(25))))).build()

    @Test
    fun `맞는 비밀값이면 주기 작업을 먼저 깨우고 일감을 비운 뒤 결과를 돌려준다`() {
        mvc().post("/internal/jobs/drain") { header("X-Wake-Secret", secret) }.andExpect {
            status { isOk() }
            jsonPath("$.processed") { value(2) }
            jsonPath("$.timedOut") { value(false) }
            jsonPath("$.tasks[0].name") { value("t1") }
            jsonPath("$.tasks[0].ok") { value(true) }
        }
        assertEquals(listOf("t1", "drain(25s)"), order.toList())
    }

    @Test
    fun `비밀값이 없거나 틀리면 401 이고 아무것도 돌리지 않는다`() {
        mvc().post("/internal/jobs/drain").andExpect { status { isUnauthorized() } }
        mvc().post("/internal/jobs/drain") { header("X-Wake-Secret", "wrong") }.andExpect { status { isUnauthorized() } }
        mvc().post("/internal/jobs/drain") { header("X-Wake-Secret", "$secret-x") }.andExpect { status { isUnauthorized() } }
        assertTrue(order.isEmpty(), "아무것도 돌지 않아야 한다: $order")
    }

    @Test
    fun `서버에 비밀값이 비어 있으면 주소가 닫혀 있다 - 빈 헤더로도 열리지 않는다`() {
        mvc(secret = "").post("/internal/jobs/drain") { header("X-Wake-Secret", "") }.andExpect { status { isNotFound() } }
        mvc(secret = "").post("/internal/jobs/drain").andExpect { status { isNotFound() } }
        assertTrue(order.isEmpty())
    }

    @Test
    fun `폴링 모드에서는 맞는 비밀값이어도 404 이고 아무것도 돌리지 않는다 - 빈은 서 있어도 모드는 실행 때 본다`() {
        mvc(mode = JobQueueProperties.Mode.POLLING).post("/internal/jobs/drain") { header("X-Wake-Secret", secret) }.andExpect { status { isNotFound() } }
        assertTrue(order.isEmpty(), "아무것도 돌지 않아야 한다: $order")
    }

    @Test
    fun `프로젝트 N개 모드에서 서비스 주소(어느 프로젝트의 호스트도 아닌 run-app)로 불러도 호스트 필터가 421 로 막지 않는다`() {
        val registry = dev.sumin.skeleton.common.project.ProjectRegistry.from(
            org.springframework.mock.env.MockEnvironment()
                .withProperty("skeleton.projects.a.hosts", "a.example").withProperty("skeleton.projects.a.schema", "pa"),
        )
        val properties = JobQueueProperties(mode = JobQueueProperties.Mode.ON_REQUEST, wake = JobQueueProperties.Wake(secret = secret))
        val filter = dev.sumin.skeleton.common.project.ProjectFilter(registry, tools.jackson.databind.ObjectMapper(), listOf(JobWakeAutoConfiguration().jobWakeUnscopedPath(properties)))
        val web = MockMvcBuilders.standaloneSetup(JobWakeController(drainer, emptyList(), properties)).addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(filter).build()

        web.post("/internal/jobs/drain") { header("X-Wake-Secret", secret); with { it.serverName = "svc-abc.a.run.app"; it } }.andExpect { status { isOk() } }
        assertEquals(listOf("drain(25s)"), order.toList())
        // 다른 경로는 여전히 421 — 예외는 이 경로 하나뿐이다
        web.post("/api/v1/other") { with { it.serverName = "svc-abc.a.run.app"; it } }.andExpect { status { isEqualTo(421) } }
    }

    @Test
    fun `폴링 모드에서는 깨우기 경로가 프로젝트 없는 경로가 아니다`() {
        val paths = JobWakeAutoConfiguration().jobWakeUnscopedPath(JobQueueProperties()).paths()
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `환경변수 모양의 모드 값 ON_REQUEST 와 설정 모양 on-request 가 모두 요청 구동으로 묶인다`() {
        fun bound(key: String, value: String): JobQueueProperties.Mode {
            val env = org.springframework.core.env.StandardEnvironment()
            env.propertySources.addFirst(org.springframework.core.env.SystemEnvironmentPropertySource("env", mapOf(key to value)))
            return org.springframework.boot.context.properties.bind.Binder.get(env).bind("skeleton.job-queue", JobQueueProperties::class.java).get().mode
        }
        assertEquals(JobQueueProperties.Mode.ON_REQUEST, bound("SKELETON_JOB_QUEUE_MODE", "ON_REQUEST"))
        assertEquals(JobQueueProperties.Mode.ON_REQUEST, bound("SKELETON_JOB_QUEUE_MODE", "on-request"))
    }

    @Test
    fun `주기 작업 하나가 던져도 다음 작업과 일감 비우기는 이어진다`() {
        val tasks = listOf(SimpleOnRequestTask("bad") { throw IllegalStateException("boom") }, SimpleOnRequestTask("good") { order += "good" })

        mvc(tasks = tasks).post("/internal/jobs/drain") { header("X-Wake-Secret", secret) }.andExpect {
            status { isOk() }
            jsonPath("$.tasks[0].ok") { value(false) }
            jsonPath("$.tasks[1].ok") { value(true) }
        }
        assertEquals(listOf("good", "drain(25s)"), order.toList())
    }

    @Test
    fun `보호 환경에서 비밀값이 비었거나 약하면 기동 실패 문제이고 값은 메시지에 없다`() {
        val prod = DeployContext(env = DeployEnv.PROD, activeProfiles = emptySet())
        fun problems(secret: String) = JobWakeDeployGuard(JobQueueProperties(mode = JobQueueProperties.Mode.ON_REQUEST, wake = JobQueueProperties.Wake(secret = secret))).problems(prod)

        assertEquals(1, problems("").size)
        assertEquals(1, problems("short-secret").size)
        assertEquals(1, problems("aaaaaaaaaaaaaaaaaaaaaaaaaaaa").size) // 길어도 글자 종류가 모자란다
        assertEquals(0, problems(secret).size)
        assertTrue(problems("short-secret").none { it.contains("short-secret") })
        // 보호 환경이 아니면 경고만
        val local = DeployContext(env = null, activeProfiles = setOf("local"))
        assertEquals(0, JobWakeDeployGuard(JobQueueProperties(mode = JobQueueProperties.Mode.ON_REQUEST, wake = JobQueueProperties.Wake(secret = ""))).problems(local).size)
        assertEquals(1, JobWakeDeployGuard(JobQueueProperties(mode = JobQueueProperties.Mode.ON_REQUEST, wake = JobQueueProperties.Wake(secret = ""))).warnings(local).size)
        // 폴링 모드(기본)는 비밀값이 없어도 할 말이 없다
        assertEquals(0, JobWakeDeployGuard(JobQueueProperties()).problems(prod).size)
    }
}

/** 깨우기 경로가 Bearer 를 JWT 필터에 맡기지 않는 것은 요청 구동 모드에서만이다 (Scheduler OIDC 토큰을 함께 받는 구성). */
class JobWakeBearerIgnoredTest {
    @Test
    fun `the wake path is Bearer-ignored only in on-request mode`() {
        val on = JobQueueProperties(mode = JobQueueProperties.Mode.ON_REQUEST)
        assertEquals(listOf("/internal/jobs/drain"), JobWakeAutoConfiguration().jobWakeBearerIgnored(on).paths())
        assertTrue(JobWakeAutoConfiguration().jobWakeBearerIgnored(JobQueueProperties()).paths().isEmpty())
    }
}
