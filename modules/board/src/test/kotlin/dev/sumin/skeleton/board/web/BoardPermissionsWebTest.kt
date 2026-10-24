package dev.sumin.skeleton.board.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.board.FakeStore
import dev.sumin.skeleton.boardtest.BoardWebTestApplication
import dev.sumin.skeleton.boardtest.FakeBoardRepositories
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 계정마다 지금 가진 권한을 바꿀 수 있고 몇 번 물었는지 세는 대역 — 토큰은 그대로 두고 회수를 흉내 낸다 */
class MutableOperatorAccess : OperatorAccess {
    val grants = ConcurrentHashMap<String, Set<String>>()
    val asked = AtomicInteger()
    override fun permissionsOf(ref: OperatorRef): Set<String> {
        asked.incrementAndGet()
        return grants[ref.accountId] ?: emptySet()
    }
}

@TestConfiguration(proxyBeanMethods = false)
class MutableAccessBeans {
    @Bean fun mutableOperatorAccess() = MutableOperatorAccess()
}

/** 게시판 운영 판정은 권한 두 개 — `board.moderate`(숨기기 · 숨은 글 보기) · `board.manage`(게시판 만들기 · 설정) */
@SpringBootTest(classes = [BoardWebTestApplication::class], properties = ["skeleton.board.http.allow-anonymous-read=true"])
@AutoConfigureMockMvc
@Import(FakeBoardRepositories::class, MutableAccessBeans::class)
class BoardPermissionsWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeStore
    @Autowired lateinit var access: MutableOperatorAccess

    private val writer = TestingAuthenticationToken("acc_writer", "n/a", "ROLE_USER")
    // 토큰의 역할은 USER 뿐 — 판정은 OperatorAccess 가 한다
    private val staff = TestingAuthenticationToken("acc_staff", "n/a", "ROLE_USER")

    @BeforeEach
    fun reset() {
        store.clear()
        access.grants.clear()
        access.asked.set(0)
        access.grants["acc_root"] = setOf("*")
        call(post("/api/v1/boards").json("""{"code":"general","name":"General"}"""), TestingAuthenticationToken("acc_root", "n/a", "ROLE_USER")).andExpect(status().isCreated)
    }

    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

    private fun call(builder: MockHttpServletRequestBuilder, who: TestingAuthenticationToken? = null): ResultActions =
        mvc.perform(if (who != null) builder.principal(who) else builder)

    private fun hiddenPost(): Long {
        val id = JsonPath.read<Number>(
            call(post("/api/v1/boards/general/posts").header("Idempotency-Key", java.util.UUID.randomUUID().toString()).json("""{"title":"Hello","body":"World"}"""), writer)
                .andExpect(status().isCreated).andReturn().response.contentAsString, "$.value.id",
        ).toLong()
        access.grants["acc_root"] = setOf("*")
        call(put("/api/v1/boards/general/posts/$id/moderation").json("""{"status":"HIDDEN"}"""), TestingAuthenticationToken("acc_root", "n/a", "ROLE_USER")).andExpect(status().isOk)
        return id
    }

    @Test
    fun `without board moderate a signed-in caller neither sees a hidden post nor hides another's, with it both work`() {
        val id = hiddenPost()
        access.grants["acc_staff"] = setOf("board.manage")
        call(get("/api/v1/boards/general/posts/$id"), staff).andExpect(status().isNotFound)
        call(put("/api/v1/boards/general/posts/$id/moderation").json("""{"status":"PUBLISHED"}"""), staff).andExpect(status().isForbidden)

        access.grants["acc_staff"] = setOf("board.moderate")
        call(get("/api/v1/boards/general/posts/$id"), staff).andExpect(status().isOk)
        call(put("/api/v1/boards/general/posts/$id/moderation").json("""{"status":"PUBLISHED"}"""), staff).andExpect(status().isOk)
    }

    @Test
    fun `without board manage creating and editing a board is 403, with it 201 and 200`() {
        access.grants["acc_staff"] = setOf("board.moderate")
        call(post("/api/v1/boards").json("""{"code":"news","name":"News"}"""), staff).andExpect(status().isForbidden)
        call(patch("/api/v1/boards/general").json("""{"name":"Renamed"}"""), staff).andExpect(status().isForbidden)

        access.grants["acc_staff"] = setOf("board.manage")
        call(post("/api/v1/boards").json("""{"code":"news","name":"News"}"""), staff).andExpect(status().isCreated)
        call(patch("/api/v1/boards/general").json("""{"name":"Renamed"}"""), staff).andExpect(status().isOk)
    }

    @Test
    fun `the super permission now manages boards too - the admin role gains board powers`() {
        access.grants["acc_staff"] = setOf("*")
        call(post("/api/v1/boards").json("""{"code":"news","name":"News"}"""), staff).andExpect(status().isCreated)
        val id = hiddenPost()
        call(get("/api/v1/boards/general/posts/$id"), staff).andExpect(status().isOk)
    }

    @Test
    fun `revoking board moderate takes effect on the next call with the same token`() {
        val id = hiddenPost()
        access.grants["acc_staff"] = setOf("board.moderate")
        call(get("/api/v1/boards/general/posts/$id"), staff).andExpect(status().isOk)
        access.grants.remove("acc_staff")
        call(get("/api/v1/boards/general/posts/$id"), staff).andExpect(status().isNotFound)
        call(put("/api/v1/boards/general/posts/$id/moderation").json("""{"status":"PUBLISHED"}"""), staff).andExpect(status().isForbidden)
    }

    @Test
    fun `an anonymous read never asks the operator access`() {
        access.asked.set(0)
        call(get("/api/v1/boards/general/posts")).andExpect(status().isOk)
        call(get("/api/v1/boards/config")).andExpect(status().isOk).andExpect(jsonPath("$.value.canModerate").value(false))
        assertEquals(0, access.asked.get())
    }
}
