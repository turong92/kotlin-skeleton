package dev.sumin.skeleton.app.workbench.api

import com.jayway.jsonpath.JsonPath
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.alert.jdbc.JdbcAlertStore
import dev.sumin.skeleton.app.workbench.TestcontainersConfiguration
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 모듈이 함께 있을 때(alert + alert-jdbc + job-queue-jdbc + platform 필터 체인): 웹훅 주소가 있으면 5xx 몰림과 죽은 작업이 웹훅에 도착하고 DB 에 기록된다.
 * 웹훅 주소가 없으면 같은 앱이 아무것도 보내지 않는다는 것은 다른 워크벤치 테스트가 (주소 없이) 뜨는 것으로 증명된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, AlertCompositionIntegrationTest.Probe::class)
class AlertCompositionIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jobs: JobQueue
    @Autowired lateinit var alerts: JdbcAlertStore

    @Test
    fun `a 5xx surge reaches the webhook and the ledger`() {
        val token = login()
        mockMvc.get("/api/v1/test/alert-boom") { header("Authorization", "Bearer $token"); accept = MediaType.APPLICATION_JSON }

        awaitWebhook("SERVER_ERROR_SURGE", "Server errors (5xx) are piling up")
        assertNotNull(alerts.find("SERVER_ERROR_SURGE", ""))
    }

    @Test
    fun `a job that dies reaches the webhook through the dead-job hook`() {
        jobs.enqueue("no-handler-for-this", "{}", maxAttempts = 1)

        awaitWebhook("JOB_DEAD", "A background job ran out of attempts (DEAD)")
        assertNotNull(alerts.find("JOB_DEAD", "no-handler-for-this"))
    }

    private fun awaitWebhook(kind: String, title: String) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (received.any { it.contains(title) }) return
            Thread.sleep(200)
        }
        throw AssertionError("no webhook for $kind within 20s; received=$received")
    }

    private fun login(): String {
        val response = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andReturn().response.contentAsString
        return JsonPath.read(response, "$.value.accessToken")
    }

    @TestConfiguration
    class Probe {
        @Bean fun alertProbeController() = AlertProbeController()
    }

    @RestController
    class AlertProbeController {
        @GetMapping("/api/v1/test/alert-boom")
        fun boom(): Nothing = throw IllegalStateException("boom")
    }

    companion object {
        private val received = CopyOnWriteArrayList<String>()
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                received += exchange.requestBody.readBytes().decodeToString()
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("skeleton.alert.webhook-url") { "http://127.0.0.1:${server.address.port}/hook/SECRET" }
            registry.add("skeleton.alert.surge.threshold") { "1" }
            registry.add("skeleton.alert.environment") { "workbench-test" }
        }
    }
}
