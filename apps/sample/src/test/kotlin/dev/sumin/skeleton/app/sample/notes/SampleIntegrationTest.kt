package dev.sumin.skeleton.app.sample.notes

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.TestcontainersConfiguration
import java.util.UUID
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/**
 * 샘플 앱 통합 테스트의 바탕 — 진짜 PostgreSQL(Testcontainers) · 진짜 보안 체인 · 진짜 Flyway, 저장소만 메모리다.
 * 같은 설정이라 컨텍스트는 한 번만 뜬다. 매 테스트 전에 notes · 받은편지함 · 잡을 비운다.
 */
@SpringBootTest(properties = ["skeleton.job-queue.poll-interval=200ms", "spring.config.import=classpath:test-seeds.yml"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, SampleIntegrationTest.FakeStorageConfiguration::class)
abstract class SampleIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class FakeStorageConfiguration {
        @Bean
        fun fakeStorage(): FakePresignedStorage = FakePresignedStorage()
    }

    @Autowired protected lateinit var mockMvc: MockMvc
    @Autowired protected lateinit var jdbc: JdbcClient
    @Autowired protected lateinit var storage: FakePresignedStorage

    protected lateinit var user: String
    protected lateinit var other: String

    @BeforeEach
    fun cleanNotes() {
        jdbc.sql("delete from notes").update()
        // 게시판 — 자식부터 (시드 게시판 general 은 남긴다)
        listOf("skeleton_board_reactions", "skeleton_board_comments", "skeleton_board_post_attachments", "skeleton_board_posts").forEach { jdbc.sql("delete from $it").update() }
        jdbc.sql("delete from skeleton_notification_inbox").update()
        jdbc.sql("delete from skeleton_jobs").update()
        user = login("user@example.com")
        other = login("admin@example.com")
    }

    protected fun login(email: String): String {
        val body = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return JsonPath.read<String>(body, "$.value.accessToken").also { assertTrue(it.isNotBlank()) }
    }

    protected fun createNote(
        token: String,
        title: String = "새 노트",
        body: String = "",
        status: String? = null,
        pinned: Boolean? = null,
        key: String = UUID.randomUUID().toString(),
    ): ResultActionsDsl =
        mockMvc.post("/api/v1/notes") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = buildString {
                append("""{"title":${json(title)},"body":${json(body)}""")
                status?.let { append(""","status":"$it"""") }
                pinned?.let { append(""","pinned":$it""") }
                append("}")
            }
        }

    protected fun createdId(token: String, title: String = "새 노트", body: String = "", status: String? = null, pinned: Boolean? = null): String =
        createNote(token, title, body, status, pinned).andExpect { status { isCreated() } }.andReturn().readString("$.value.id")

    protected fun putNote(token: String, id: String, json: String): ResultActionsDsl =
        mockMvc.put("/api/v1/notes/$id") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json
        }

    protected fun getNote(token: String, id: String): ResultActionsDsl =
        mockMvc.get("/api/v1/notes/$id") { header("Authorization", "Bearer $token") }

    protected fun deleteNote(token: String, id: String): ResultActionsDsl =
        mockMvc.delete("/api/v1/notes/$id") { header("Authorization", "Bearer $token") }

    protected fun inbox(token: String): String =
        mockMvc.get("/api/v1/notifications?size=100&topic=notes") { header("Authorization", "Bearer $token") }
            .andReturn().response.contentAsString

    /** 비동기로 도착하는 것(잡 · 알림)을 기다린다 — 최대 [seconds] 초 */
    protected fun <T> await(seconds: Long = 15, probe: () -> T?): T {
        val deadline = System.nanoTime() + seconds * 1_000_000_000
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(100)
        }
        error("조건이 ${seconds}초 안에 충족되지 않았다")
    }

    protected fun MvcResult.readString(path: String): String = JsonPath.read(response.contentAsString, path)

    protected fun json(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
