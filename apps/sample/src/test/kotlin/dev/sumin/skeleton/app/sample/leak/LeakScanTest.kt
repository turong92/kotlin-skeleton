package dev.sumin.skeleton.app.sample.leak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 누출 스캔 함수 자체의 양성 대조 — 합성 응답에 일부러 심은 값을 **찾아야** 하고, 허용 목록의 값은 못 본 척해야 한다.
 * 이것이 먼저 통과해야 통합 시험의 "아무것도 안 나왔다"가 뜻을 갖는다 (스캔이 눈이 멀었다면 빈 결과는 증거가 아니다).
 */
class LeakScanTest {
    private val forbidden = mapOf("account id" to "acc_secret1", "email" to "x@leak.example.com", "tombstone" to "deleted:0123456789abcdef", "account name" to "SecretName")

    private fun captured(body: String, headers: Map<String, String> = emptyMap()) = Captured("GET /t", 200, body, headers)

    @Test
    fun `finds a forbidden value in a body, a header, a nested field, a url-encoded form and in another letter case`() {
        assertEquals(listOf("account id"), LeakScan.forbiddenHits(listOf(captured("""{"value":{"a":[{"authorId":"acc_secret1"}]}}""")), forbidden).map { it.label })
        assertEquals(listOf("email"), LeakScan.forbiddenHits(listOf(captured("{}", mapOf("Location" to "/u?mail=x%40leak.example.com"))), forbidden).map { it.label })
        assertEquals(listOf("tombstone"), LeakScan.forbiddenHits(listOf(captured("""{"authorId":"deleted:0123456789abcdef"}""")), forbidden).map { it.label })
        assertEquals(listOf("account name"), LeakScan.forbiddenHits(listOf(captured("""{"authorName":"secretNAME"}""")), forbidden).map { it.label })
    }

    @Test
    fun `reports nothing for a clean response`() {
        assertEquals(emptyList(), LeakScan.forbiddenHits(listOf(captured("""{"authorId":"prf_aaaa","authorName":"Bob"}""", mapOf("Cache-Control" to "no-store"))), forbidden))
    }

    private val allow = LeakAllow(strings = setOf("general", "PUBLISHED", "LIKE"), structuralNumbers = setOf(7L))

    private fun pieces(vararg json: String) = LeakPieces().also { p -> json.forEach { p.addJson(it) } }

    @Test
    fun `finds a string, a number and an attachment segment that two authors share and ignores the allowed ones`() {
        val a = pieces("""{"values":[{"id":1,"authorId":"prf_a","boardCode":"general","status":"PUBLISHED","viewCount":3,"title":"t1","shared":"same-text","n":42,"attachments":["uploads/o/u1.mac1/file.png"],"mine":false,"authorName":null}]}""")
        val b = pieces("""{"values":[{"id":2,"authorId":"prf_b","boardCode":"general","status":"PUBLISHED","viewCount":3,"title":"t2","shared":"same-text","n":42,"attachments":["uploads/o/u2.mac2/file.png"],"mine":false,"authorName":null}]}""")
        val common = LeakScan.commonValues(a.of("prf_a"), b.of("prf_b"), allow)
        assertEquals(setOf("string same-text", "number post 42"), common.toSet(), common.toString())

        val c = pieces("""{"values":[{"id":3,"authorId":"prf_c","attachments":["uploads/o/u3.sameMac/file.jpg"]}]}""")
        val d = pieces("""{"values":[{"id":4,"authorId":"prf_d","attachments":["uploads/o/u4.sameMac/file.jpg"]}]}""")
        assertTrue(LeakScan.commonValues(c.of("prf_c"), d.of("prf_d"), allow).isEmpty(), "different uuid.mac segments are different values")
        val e = pieces("""{"values":[{"id":5,"authorId":"prf_e","attachments":["uploads/o/shared.mac/file.jpg"]}]}""")
        val f = pieces("""{"values":[{"id":6,"authorId":"prf_f","attachments":["uploads/o/shared.mac/photo.png"]}]}""")
        assertEquals(listOf("string shared.mac"), LeakScan.commonValues(e.of("prf_e"), f.of("prf_f"), allow))
    }

    @Test
    fun `a nested reply belongs to its own author, not to the parent that carries it in replies`() {
        val p = pieces("""{"values":[{"id":1,"postId":7,"authorId":"prf_a","body":"root","replies":[{"id":2,"postId":7,"parentId":1,"authorId":"prf_b","body":"reply"}]}]}""")
        assertEquals(setOf("root"), p.of("prf_a").filter { it.key == "body" }.map { it.value }.toSet())
        assertEquals(setOf("reply"), p.of("prf_b").filter { it.key == "body" }.map { it.value }.toSet())
        assertTrue(LeakScan.commonValues(p.of("prf_a"), p.of("prf_b"), allow.copy(structuralNumbers = setOf(7L, 1L))).isEmpty(), "thread structure (post id, root id) is not a link")
    }

    @Test
    fun `a database row is read like a response piece`() {
        val a = LeakPieces().apply { addRow("prf_a", "post", mapOf("id" to 1L, "title" to "t1", "author_ref" to "prf_a", "view_count" to 0L, "board_code" to "general")) }
        val b = LeakPieces().apply { addRow("prf_b", "post", mapOf("id" to 2L, "title" to "t1", "author_ref" to "prf_b", "view_count" to 0L, "board_code" to "general")) }
        assertEquals(listOf("string t1"), LeakScan.commonValues(a.of("prf_a"), b.of("prf_b"), allow))
    }
}
