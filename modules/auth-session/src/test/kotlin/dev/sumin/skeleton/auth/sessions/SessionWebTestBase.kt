package dev.sumin.skeleton.auth.sessions

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.sessionstest.MutableAccounts
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

abstract class SessionWebTestBase {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var accounts: MutableAccounts
    @Autowired lateinit var store: SessionStore

    @org.junit.jupiter.api.BeforeEach
    fun clean() {
        accounts.reset()
        listOf("acc_ann", "acc_bob").forEach { store.revokeAll(it, null, java.time.Instant.now(), "TEST") }
        store.purge(java.time.Instant.now().plusSeconds(60))
    }

    /** 1단계를 통과한 계정의 토큰 발급 — `auth` 의 발급 팩토리가 세션을 연다 (비밀번호 확인은 `auth-password` 의 몫이라 이 모듈 시험에 없다) */
    fun login(email: String = "ann@example.com", ua: String = "JUnit-UA", ip: String = "203.0.113.9"): MvcResult =
        mvc.perform(
            post("/api/v1/auth/test-first-step-passed").contentType(MediaType.APPLICATION_JSON).header("User-Agent", ua).header("X-Device-Name", "Test phone")
                .with { it.remoteAddr = ip; it }
                .content("""{"email":"$email"}"""),
        ).andReturn()

    fun MvcResult.str(path: String): String? =
        try { JsonPath.read<Any?>(response.contentAsString, path)?.toString() } catch (_: com.jayway.jsonpath.PathNotFoundException) { null }
}
