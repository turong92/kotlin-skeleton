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

    private fun mvc(secret: String = this.secret, tasks: List<OnRequestTask> = listOf(SimpleOnRequestTask("t1") { order += "t1" })): MockMvc =
        MockMvcBuilders.standaloneSetup(JobWakeController(drainer, tasks, secret, Duration.ofSeconds(25))).build()

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
        fun problems(secret: String) = JobWakeDeployGuard(JobQueueProperties.Wake(secret = secret)).problems(prod)

        assertEquals(1, problems("").size)
        assertEquals(1, problems("short-secret").size)
        assertEquals(1, problems("aaaaaaaaaaaaaaaaaaaaaaaaaaaa").size) // 길어도 글자 종류가 모자란다
        assertEquals(0, problems(secret).size)
        assertTrue(problems("short-secret").none { it.contains("short-secret") })
        // 보호 환경이 아니면 경고만
        val local = DeployContext(env = null, activeProfiles = setOf("local"))
        assertEquals(0, JobWakeDeployGuard(JobQueueProperties.Wake(secret = "")).problems(local).size)
        assertEquals(1, JobWakeDeployGuard(JobQueueProperties.Wake(secret = "")).warnings(local).size)
    }
}
