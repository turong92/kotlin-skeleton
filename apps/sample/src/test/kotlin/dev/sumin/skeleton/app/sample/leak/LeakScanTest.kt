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

    private val allow = LeakAllow(strings = setOf("general", "PUBLISHED", "LIKE"), structuralNumbers = mapOf("comment.postId" to setOf(7L), "comment.rootId" to setOf(1L), "comment.parentId" to setOf(1L), "comment.id" to setOf(1L)))

    private fun pieces(vararg json: String) = LeakPieces().also { p -> json.forEach { p.addJson(it) } }

    @Test
    fun `finds a string, a number and an attachment segment that two authors share and ignores the allowed ones`() {
        val a = pieces("""{"values":[{"id":1,"authorId":"prf_a","boardCode":"general","status":"PUBLISHED","viewCount":3,"title":"t1","shared":"same-text","n":42,"attachments":["uploads/o/u1.mac1/file.png"],"mine":false,"authorName":null}]}""")
        val b = pieces("""{"values":[{"id":2,"authorId":"prf_b","boardCode":"general","status":"PUBLISHED","viewCount":3,"title":"t2","shared":"same-text","n":42,"attachments":["uploads/o/u2.mac2/file.png"],"mine":false,"authorName":null}]}""")
        val common = LeakScan.commonValues(a.of("prf_a"), b.of("prf_b"), allow)
        assertEquals(setOf("string same-text", "number post 42"), common.toSet(), common.toString())
        // 숫자 허용은 종류.칸 별이다 — 같은 값이 허용되지 않은 칸에 있으면 잡힌다
        val x = pieces("""{"values":[{"id":1,"postId":7,"parentId":null,"rootId":1,"depth":0,"authorId":"prf_x","views":9}]}""")
        val y = pieces("""{"values":[{"id":2,"postId":7,"parentId":1,"rootId":1,"depth":1,"authorId":"prf_y","views":9}]}""")
        assertEquals(listOf("number comment 9"), LeakScan.commonValues(x.of("prf_x"), y.of("prf_y"), allow), "postId / rootId / parentId / id of the thread are allowed, depth is a counter, an unlisted key is not")

        val mac = "0123456789abcdef0123456789abcdef"
        val uuidA = "11111111-1111-4111-8111-111111111111"
        val uuidB = "22222222-2222-4222-8222-222222222222"
        val c = pieces("""{"values":[{"id":3,"authorId":"prf_c","attachments":["uploads/o/$uuidA.$mac/file.jpg"]}]}""")
        val d = pieces("""{"values":[{"id":4,"authorId":"prf_d","attachments":["uploads/o/$uuidB.${mac.reversed()}/file.jpg"]}]}""")
        assertTrue(LeakScan.commonValues(c.of("prf_c"), d.of("prf_d"), allow).isEmpty(), "different uuid and mac, same file.jpg: nothing that ties them")
        // 한 쪽만 같아도 잇는다: 같은 mac, 다른 uuid / 같은 uuid, 다른 mac (마디를 `.` 로 쪼개 비교한다)
        val sameMac = pieces("""{"values":[{"id":5,"authorId":"prf_e","attachments":["uploads/o/$uuidA.$mac/file.png"]}]}""")
        val otherUuid = pieces("""{"values":[{"id":6,"authorId":"prf_f","attachments":["uploads/o/$uuidB.$mac/photo.png"]}]}""")
        assertEquals(listOf("string $mac"), LeakScan.commonValues(sameMac.of("prf_e"), otherUuid.of("prf_f"), allow))
        val sameUuid = pieces("""{"values":[{"id":7,"authorId":"prf_g","attachments":["uploads/o/$uuidA.${mac.reversed()}/file.png"]}]}""")
        assertEquals(listOf("string $uuidA"), LeakScan.commonValues(sameMac.of("prf_e"), sameUuid.of("prf_g"), allow))
    }

    @Test
    fun `a nested reply belongs to its own author, not to the parent that carries it in replies`() {
        val p = pieces("""{"values":[{"id":1,"postId":7,"authorId":"prf_a","body":"root","replies":[{"id":2,"postId":7,"parentId":1,"authorId":"prf_b","body":"reply"}]}]}""")
        assertEquals(setOf("root"), p.of("prf_a").filter { it.key == "body" }.map { it.value }.toSet())
        assertEquals(setOf("reply"), p.of("prf_b").filter { it.key == "body" }.map { it.value }.toSet())
        assertTrue(LeakScan.commonValues(p.of("prf_a"), p.of("prf_b"), allow).isEmpty(), "thread structure (post id, root id) is not a link")
    }

    @Test
    fun `a database row is read like a response piece`() {
        val a = LeakPieces().apply { addRow("prf_a", "post", mapOf("id" to 1L, "title" to "t1", "author_ref" to "prf_a", "view_count" to 0L, "board_code" to "general")) }
        val b = LeakPieces().apply { addRow("prf_b", "post", mapOf("id" to 2L, "title" to "t1", "author_ref" to "prf_b", "view_count" to 0L, "board_code" to "general")) }
        assertEquals(listOf("string t1"), LeakScan.commonValues(a.of("prf_a"), b.of("prf_b"), allow))
    }
}
