package dev.sumin.skeleton.authorprofile.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.authorprofiletest.AuthorProfileWebTestApplication
import dev.sumin.skeleton.authorprofiletest.FakeAuthorProfileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

/** 기본 설정(상한 5 · 닉네임 방식 TAGGED 아님 = NONE)의 HTTP 계약 — 스펙 §8 */
@SpringBootTest(classes = [AuthorProfileWebTestApplication::class], properties = ["skeleton.account.display-name.reserved=Staff"])
@AutoConfigureMockMvc
@Import(FakeAuthorProfileStore::class)
class AuthorProfileWebTest : WebTestSupport() {
    private val base = "/api/v1/author-profiles"

    @Test
    fun `config reports the cap and the name length`() {
        call(get("$base/config"), caller()).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.maxPerAccount").value(5))
            .andExpect(jsonPath("$.value.nameMaxLength").value(60))
    }

    @Test
    fun `an anonymous call is 401 on every endpoint`() {
        call(get("$base/config")).andExpect(status().isUnauthorized)
        call(get(base)).andExpect(status().isUnauthorized)
        call(post(base).json("""{"name":"x"}""")).andExpect(status().isUnauthorized)
        call(patch("$base/prf_x").json("""{"name":"x"}""")).andExpect(status().isUnauthorized)
        call(delete("$base/prf_x")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `listing first guarantees the first profile - a nameless account gets a nameless one with null name and tag`() {
        val who = caller()
        val body = text(call(get(base), who).andExpect(status().isOk).andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].name").doesNotExist()))
        val first = JsonPath.read<Map<String, Any?>>(body, "$.values[0]")
        assertEquals(setOf("id", "name", "tag", "createdAt"), first.keys, "the profile has exactly id, name, tag and createdAt")
        assertTrue((first["id"] as String).startsWith("prf_"))
        assertEquals(null, first["name"])
        assertEquals(null, first["tag"])
    }

    @Test
    fun `listing copies the account name into the first profile once and then lists what was created in order`() {
        val who = caller("Mina")
        call(get(base), who).andExpect(jsonPath("$.values[0].name").value("Mina"))
        call(post(base).json("""{"name":"Second"}"""), who).andExpect(status().isCreated)
        call(get(base), who).andExpect(jsonPath("$.values.length()").value(2))
            .andExpect(jsonPath("$.values[0].name").value("Mina")).andExpect(jsonPath("$.values[1].name").value("Second"))
    }

    @Test
    fun `create answers 201 with the profile in the body and a Location`() {
        val who = caller("Mina")
        val result = call(post(base).json("""{"name":"  Night  Owl "}"""), who).andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.name").value("Night Owl")).andExpect(jsonPath("$.value.tag").doesNotExist())
            .andExpect(jsonPath("$.value.createdAt").exists()).andReturn()
        val id = idOf(result)
        assertTrue(id.startsWith("prf_"))
        assertEquals("$base/$id", result.response.getHeader("Location")?.substringAfter("localhost"))
    }

    @Test
    fun `create refuses a bad name with 400 on the field name and the nickname codes`() {
        val who = caller()
        fun bad(body: String, code: String) = call(post(base).json(body), who).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED")).andExpect(jsonPath("$.errors[0].field").value("name")).andExpect(jsonPath("$.errors[0].code").value(code))
        bad("""{}""", "Required")
        bad("""{"name":"   "}""", "Required")
        bad("""{"name":"${"a".repeat(61)}"}""", "Size")
        bad("""{"name":"has#hash"}""", "Pattern")
        bad("""{"name":"staff"}""", "Reserved")
        call(get(base), who).andExpect(jsonPath("$.values.length()").value(1))   // 아무것도 만들어지지 않았다
    }

    @Test
    fun `patch renames with 200 and the same bad-name codes`() {
        val who = caller("Mina")
        val id = idOf(call(post(base).json("""{"name":"Old"}"""), who).andReturn())
        call(patch("$base/$id").json("""{"name":"New"}"""), who).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.id").value(id)).andExpect(jsonPath("$.value.name").value("New"))
        call(get(base), who).andExpect(jsonPath("$.values[1].name").value("New"))
        call(patch("$base/$id").json("""{"name":"staff"}"""), who).andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("name")).andExpect(jsonPath("$.errors[0].code").value("Reserved"))
        call(patch("$base/$id").json("""{"name":""}"""), who).andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].code").value("Required"))
    }

    @Test
    fun `delete answers 204 and the profile leaves the list`() {
        val who = caller("Mina")
        val id = idOf(call(post(base).json("""{"name":"Temp"}"""), who).andReturn())
        call(delete("$base/$id"), who).andExpect(status().isNoContent)
        call(get(base), who).andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].name").value("Mina"))
    }

    @Test
    fun `someone else's, a missing and a deleted profile id are one and the same 404 - status, body and headers`() {
        val owner = caller("Mina")
        val other = caller("Other")
        val deletedId = idOf(call(post(base).json("""{"name":"Gone"}"""), owner).andReturn())
        call(delete("$base/$deletedId"), owner).andExpect(status().isNoContent)
        val ownId = idOf(call(post(base).json("""{"name":"Mine"}"""), owner).andReturn())

        /** 시각 · 추적 id 처럼 요청마다 달라지는 칸만 뺀 응답 전체(상태 · 헤더 · 본문) */
        fun shape(builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder): String {
            val response = call(builder, other).andReturn().response
            val body = JsonPath.parse(response.contentAsString).delete("$.timestamp").delete("$.traceId").delete("$.spanId").jsonString()
            val headers = response.headerNames.sorted().filter { !it.equals("Date", true) && !it.equals("X-Span-Id", true) && !it.equals("X-Trace-Id", true) && !it.equals("traceparent", true) && !it.equals("Content-Length", true) }.associateWith { response.getHeaders(it) }
            return "${response.status} $headers $body"
        }

        for ((label, request) in listOf<Pair<String, (String) -> org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>>(
            "PATCH" to { id -> patch("$base/$id").json("""{"name":"Hijack"}""") },
            "DELETE" to { id -> delete("$base/$id") },
        )) {
            val shapes = listOf(ownId, deletedId, "prf_00000000000000000000000000000000", "not-a-profile-id").map { shape(request(it)) }
            assertEquals(1, shapes.toSet().size, "$label: foreign, deleted, missing and malformed ids must look identical:\n${shapes.joinToString("\n")}")
            assertTrue(shapes[0].startsWith("404 "), shapes[0])
            assertTrue("AUTHOR_PROFILE.NOT_FOUND" in shapes[0], shapes[0])
        }
        // 진짜 주인의 것은 건드려지지 않았다
        call(get(base), owner).andExpect(jsonPath("$.values[?(@.id=='$ownId')].name").value("Mine"))
        // 남의 id 로 잘못된 이름을 보내도 400 이 아니라 같은 404 다 (이름 검증이 존재 여부를 알려 주지 않는다)
        val bad = call(patch("$base/$ownId").json("""{"name":"staff"}"""), other).andReturn().response
        assertEquals(404, bad.status)
    }

    @Test
    fun `no response holds an account id`() {
        val who = caller("Mina")
        val bodies = mutableListOf<String>()
        bodies += text(call(get("$base/config"), who))
        bodies += text(call(get(base), who))
        val created = call(post(base).json("""{"name":"Two"}"""), who).andReturn()
        bodies += created.response.contentAsString
        bodies += created.response.headerNames.joinToString("\n") { "$it: ${created.response.getHeaders(it)}" }
        bodies += text(call(patch("$base/${idOf(created)}").json("""{"name":"Three"}"""), who))
        bodies += text(call(get(base), who))
        bodies += text(call(patch("$base/prf_nope").json("""{"name":"x"}"""), who))
        val all = bodies.joinToString("\n")
        assertFalse(all.contains(who.name), all)
        assertFalse(all.contains("acc_"), all)
        assertFalse(all.contains("primaryOf") || all.contains("accountId") || all.contains("status\":\"ACTIVE"), all)
    }

    @Test
    fun `the profile endpoints require login but are not public paths`() {
        call(get("$base/config")).andExpect(status().isUnauthorized).andExpect(header().doesNotExist("Retry-After"))
    }
}
