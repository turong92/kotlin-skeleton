package dev.sumin.skeleton.authorprofile.web

import dev.sumin.skeleton.authorprofiletest.AuthorProfileWebTestApplication
import dev.sumin.skeleton.authorprofiletest.FakeAuthorProfileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 상한 · 한도 · 겹침 오류의 HTTP 매핑 — 좁은 설정 (UNIQUE · 상한 3 · 만들기 한도 2 · 이름 바꾸기 한도 1) */
@SpringBootTest(
    classes = [AuthorProfileWebTestApplication::class],
    properties = [
        "skeleton.account.display-name.uniqueness=UNIQUE",
        "skeleton.account.display-name.change-limit=1",
        "skeleton.author-profile.max-per-account=3",
        "skeleton.author-profile.create-limit=2",
        "skeleton.author-profile.create-window=2h",
    ],
)
@AutoConfigureMockMvc
@Import(FakeAuthorProfileStore::class)
class AuthorProfileWebLimitsTest : WebTestSupport() {
    private val base = "/api/v1/author-profiles"

    @Test
    fun `a name another profile already uses is 409 AUTHOR_PROFILE_NAME_TAKEN, on create and on rename`() {
        val first = caller()
        val second = caller()
        call(post(base).idem().json("""{"name":"Taken Name"}"""), first).andExpect(status().isCreated)
        call(post(base).idem().json("""{"name":"taken  NAME"}"""), second).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("AUTHOR_PROFILE.NAME_TAKEN")).andExpect(jsonPath("$.status").value(409))
        val mine = idOf(call(post(base).idem().json("""{"name":"Free Name"}"""), second).andReturn())
        call(patch("$base/$mine").json("""{"name":"Taken Name"}"""), second).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AUTHOR_PROFILE.NAME_TAKEN"))
    }

    @Test
    fun `the cap on living profiles is 409 AUTHOR_PROFILE_LIMIT_REACHED and a deleted profile frees a place`() {
        val who = caller("Mina")   // 첫 프로필이 한 자리를 쓴다
        val a = idOf(call(post(base).idem().json("""{"name":"Cap A"}"""), who).andExpect(status().isCreated).andReturn())
        call(post(base).idem().json("""{"name":"Cap B"}"""), who).andExpect(status().isCreated)
        call(post(base).idem().json("""{"name":"Cap C"}"""), who).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AUTHOR_PROFILE.LIMIT_REACHED"))
        call(delete("$base/$a"), who).andExpect(status().isNoContent)
        call(post(base).idem().json("""{"name":"Cap C"}"""), who).andExpect(status().isTooManyRequests)   // 자리는 났지만 만들기 한도(2)를 썼다
    }

    @Test
    fun `the create limit is 429 AUTHOR_PROFILE_RATE_LIMITED with Retry-After and data retryAfterSeconds, the same shape as the account's`() {
        val who = caller("Mina")
        call(post(base).idem().json("""{"name":"Rate A"}"""), who).andExpect(status().isCreated)
        val b = idOf(call(post(base).idem().json("""{"name":"Rate B"}"""), who).andExpect(status().isCreated).andReturn())
        call(delete("$base/$b"), who).andExpect(status().isNoContent)
        val limited = call(post(base).idem().json("""{"name":"Rate C"}"""), who).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("AUTHOR_PROFILE.RATE_LIMITED")).andExpect(jsonPath("$.data.retryAfterSeconds").value(7200))
            .andExpect(header().string("Retry-After", "7200")).andReturn()
        assertEquals(429, limited.response.status)
    }

    @Test
    fun `the rename limit is 429 too, and a refused or unchanged request does not use it`() {
        val who = caller("Mina")
        val id = idOf(call(post(base).idem().json("""{"name":"Ren A"}"""), who).andReturn())
        call(patch("$base/$id").json("""{"name":"Ren A"}"""), who).andExpect(status().isOk)    // 같은 값은 세지 않는다
        call(patch("$base/$id").json("""{"name":"bad#name"}"""), who).andExpect(status().isBadRequest)   // 규칙에 걸린 요청도 세지 않는다
        call(patch("$base/$id").json("""{"name":"Ren B"}"""), who).andExpect(status().isOk)
        call(patch("$base/$id").json("""{"name":"Ren C"}"""), who).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("AUTHOR_PROFILE.RATE_LIMITED")).andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.data.retryAfterSeconds").isNumber)
    }

    @Test
    fun `with UNIQUE the tag is the fixed one so it is not shown`() {
        val who = caller()
        call(post(base).idem().json("""{"name":"Plain"}"""), who).andExpect(status().isCreated).andExpect(jsonPath("$.value.tag").doesNotExist())
        assertEquals(listOf<Any?>(null), com.jayway.jsonpath.JsonPath.read<List<Any?>>(text(call(get(base), who)), "$.values[?(@.name=='Plain')].tag"))
    }

    @Test
    fun `the 409 and 429 bodies and headers hold no account id either`() {
        val who = caller("Mina")
        val other = caller("Other")
        call(post(base).idem().json("""{"name":"Scan One"}"""), other).andExpect(status().isCreated)
        val taken = call(post(base).idem().json("""{"name":"Scan One"}"""), who).andExpect(status().isConflict).andReturn().response
        val two = idOf(call(post(base).idem().json("""{"name":"Scan Two"}"""), who).andExpect(status().isCreated).andReturn())
        call(post(base).idem().json("""{"name":"Scan Three"}"""), who).andExpect(status().isCreated)
        val capped = call(post(base).idem().json("""{"name":"Scan Four"}"""), who).andExpect(status().isConflict).andReturn().response
        call(delete("$base/$two"), who).andExpect(status().isNoContent)
        val limited = call(post(base).idem().json("""{"name":"Scan Five"}"""), who).andExpect(status().isTooManyRequests).andReturn().response
        val all = listOf(taken, capped, limited).joinToString("\n") { r -> r.contentAsString + r.headerNames.joinToString { "$it=${r.getHeaders(it)}" } }
        assertTrue(all.contains("AUTHOR_PROFILE.NAME_TAKEN") && all.contains("AUTHOR_PROFILE.LIMIT_REACHED") && all.contains("AUTHOR_PROFILE.RATE_LIMITED"), all)
        assertTrue(!all.contains(who.name) && !all.contains(other.name) && !all.contains("acc_"), all)
    }
}
