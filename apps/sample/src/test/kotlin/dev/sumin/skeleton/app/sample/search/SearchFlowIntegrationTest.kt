package dev.sumin.skeleton.app.sample.search

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.cheer.CheerIntegrationBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.put

/**
 * 노트 → 검색 색인, 상품 → 검색 색인이 견본 앱 안에서 맞물리는지(진짜 PostgreSQL · 보안 체인 · 큐 일꾼).
 * 노트는 주인에게만, 상품은 로그인한 모두에게 보이고, 검색은 로그인만 읽는다.
 */
class SearchFlowIntegrationTest : CheerIntegrationBase() {
    private lateinit var userId: String

    @BeforeEach
    fun clean() {
        userId = accountOf("user@example.com")
        wipeNotes()
    }

    @AfterEach
    fun restore() {
        wipeNotes()
        // 상품 시험이 판매를 멈춘 채 끝나도 다음 시험에 새지 않게
        adminPut("/api/v1/admin/catalog/products/pro", operator, """{"selling":true}""")
    }

    private fun wipeNotes() {
        jdbc.sql("delete from notes where owner_id = :o").param("o", userId).update()
        jdbc.sql("delete from search_documents where doc_type = 'note'").update()
    }

    private fun adminPut(path: String, token: String, body: String) = mvc.put(path) {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun createNote(title: String, body: String = ""): String =
        read(post("/api/v1/notes", user, """{"title":"$title","body":"$body","status":"ACTIVE","pinned":false}""", java.util.UUID.randomUUID().toString()).andExpect { status { isCreated() } }, "$.value.id")

    private fun search(token: String?, q: String, types: String = "note"): List<String> =
        JsonPath.read(get("/api/v1/search?q=$q&types=$types", token).andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.values[*].id")

    @Test
    fun `a note is found by a two-letter piece of a Korean word, by its owner only`() {
        val id = createNote("여름 가방끈 수선 메모", "끈이 닳아서 고친다")
        assertEquals(listOf(id), search(user, "방끈"))
        assertTrue(search(stranger, "방끈").isEmpty(), "somebody else's note never shows")
        get("/api/v1/search?q=방끈", null).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `editing a note moves it in the index and deleting it removes it`() {
        val id = createNote("가방끈 메모")
        mvc.put("/api/v1/notes/$id") {
            header("Authorization", "Bearer $user")
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"신발 끈 메모","body":"","status":"ACTIVE","pinned":false}"""
        }.andExpect { status { isOk() } }
        assertTrue(search(user, "방끈").isEmpty(), "the old title is gone")
        assertEquals(listOf(id), search(user, "신발"))

        mvc.delete("/api/v1/notes/$id") { header("Authorization", "Bearer $user") }.andExpect { status { isNoContent() } }
        assertTrue(search(user, "신발").isEmpty())
    }

    @Test
    fun `seeded products are searchable by everyone logged in, a stopped sale leaves the index and a resumed one returns`() {
        assertTrue("pro" in search(user, "프로", "product"))
        assertTrue("pro" in search(stranger, "프로", "product"))

        adminPut("/api/v1/admin/catalog/products/pro", operator, """{"selling":false}""").andExpect { status { isOk() } }
        assertTrue("pro" !in search(user, "프로", "product"))
        adminPut("/api/v1/admin/catalog/products/pro", operator, """{"selling":true}""").andExpect { status { isOk() } }
        assertTrue("pro" in search(user, "프로", "product"))
    }

    @Test
    fun `a rebuild refills notes and products from their sources`() {
        val id = createNote("가방끈 메모")
        jdbc.sql("delete from search_documents").update()
        assertTrue(search(user, "방끈").isEmpty())

        post("/api/v1/admin/search/rebuild", operator, """{"type":"note"}""").andExpect { status { isAccepted() } }
        post("/api/v1/admin/search/rebuild", operator, """{"type":"product"}""").andExpect { status { isAccepted() } }
        await { search(user, "방끈").takeIf { it == listOf(id) } }
        await { search(user, "스탠다드", "product").takeIf { "standard" in it } }
        post("/api/v1/admin/search/rebuild", user, """{"type":"note"}""").andExpect { status { isForbidden() } }
    }
}
