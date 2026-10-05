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

    fun login(email: String = "ann@example.com", password: String = "correct-horse", ua: String = "JUnit-UA", ip: String = "203.0.113.9"): MvcResult =
        mvc.perform(
            post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).header("User-Agent", ua).header("X-Device-Name", "Test phone")
                .with { it.remoteAddr = ip; it }
                .content("""{"email":"$email","password":"$password"}"""),
        ).andReturn()

    fun MvcResult.str(path: String): String? =
        try { JsonPath.read<Any?>(response.contentAsString, path)?.toString() } catch (_: com.jayway.jsonpath.PathNotFoundException) { null }
}
