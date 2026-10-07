package dev.sumin.skeleton.app.sample.leak

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.notification.sse.NotificationSseService
import tools.jackson.databind.ObjectMapper

/** Y(로그인한 제3자)와 Z(비로그인)가 **읽을 수 있는 모든 경로** — 스펙 §12 3. 응답 본문과 헤더가 [Captured] 로 쌓인다 */
class LeakSweep(private val http: LeakHttp, private val w: LeakWorld, private val mapper: ObjectMapper) {
    private val base = "/api/v1/boards/general"

    /** 같은 요청을 X 의 프로필 id · 없는 id · 모양이 틀린 id 로 보낸 응답들 — 하나로 같아야 한다 (존재 여부를 알려 주지 않는다) */
    val probes = linkedMapOf<String, MutableList<Captured>>()

    /** 두 작성자의 조각이 공유해도 되는 값 — 모두에게 같은 어휘와, 두 조각이 놓인 같은 글(Y 의 것)의 구조뿐 */
    val allow: LeakAllow by lazy {
        LeakAllow(
            strings = setOf("general", "PUBLISHED", "LIKE", "DISLIKE", "EMPATHY", "board", "comment.created", "comment.replied", "INFO", w.yPostTitle, w.yAcc,
                "New comment on your post: ${w.yPostTitle}",   // 알림 제목: Y 의 글 제목이 들어간다 — 두 알림이 같은 글에 달렸다는 구조
            ),
            structuralNumbers = setOf(w.yPost, w.c0, w.c1),
        )
    }

    /** 게시판 읽기 경로 전부 — [token] 이 null 이면 비로그인 */
    fun boardReads(token: String?): List<Captured> {
        val m = http.mark()
        listOf(
            "/api/v1/boards", "/api/v1/boards/config", base, "$base/posts", "$base/posts?sort=latest", "$base/posts?sort=reactions", "$base/posts?sort=comments",
            "$base/posts?reaction=LIKE", "$base/posts?reaction=EMPATHY", "$base/posts?reaction=DISLIKE&sort=reactions", "$base/posts?q=kiwi", "$base/posts?q=kiwi&sort=reactions",
            "$base/posts?size=1&page=0", "$base/posts?size=1&page=1", "$base/posts?size=1&page=2", "$base/posts?mine=true",
        ).forEach { http.get(it, token) }
        listOf(w.yPost, w.postA, w.postB).forEach { http.get("$base/posts/$it", token) }
        listOf("oldest", "latest", "reactions").forEach { http.get("$base/posts/${w.yPost}/comments?sort=$it", token) }
        listOf("$base/posts/999999", "$base/posts/${w.yPost}/comments/999999", "$base/posts?sort=zzz", "/api/v1/boards/nope", "$base/posts/${w.yPost}/comments?sort=zzz").forEach { http.get(it, token) }
        http.get("/api/v1/author-profiles", token)   // 비로그인이면 401
        return http.since(m)
    }

    /** Y 만의 경로 — 받은편지함 · X 의 프로필 경로 찔러 보기 · 남의 프로필로 쓰기 시도의 오류 응답 */
    fun observerOnly(): List<Captured> {
        val m = http.mark()
        http.get("/api/v1/notifications?size=100", w.yToken)
        http.get("/api/v1/notifications?size=100&topic=board", w.yToken)
        http.get("/api/v1/author-profiles", w.yToken)
        http.get("/api/v1/author-profiles/config", w.yToken)
        http.get("/api/v1/admin/author-profiles/${w.a.id}", w.yToken)
        probes.clear()
        probeIds().forEach { id ->
            probes.getOrPut("PATCH profile") { mutableListOf() } += http.patch("/api/v1/author-profiles/$id", w.yToken, """{"name":"Zed"}""")
            probes.getOrPut("DELETE profile") { mutableListOf() } += http.delete("/api/v1/author-profiles/$id", w.yToken)
            probes.getOrPut("GET profile") { mutableListOf() } += http.get("/api/v1/author-profiles/$id", w.yToken)
            probes.getOrPut("POST post") { mutableListOf() } += http.post("$base/posts", w.yToken, """{"title":"t","body":"b","authorId":"$id"}""")
            probes.getOrPut("POST comment") { mutableListOf() } += http.post("$base/posts/${w.yPost}/comments", w.yToken, """{"body":"b","authorId":"$id"}""")
        }
        http.post("$base/posts", w.yToken, """{"title":"t","body":"b"}""")
        return http.since(m)
    }

    /** X 의 프로필 셋 · 없는 id · 모양이 틀린 id · 아직 넘겨받지 않은 행의 공개 id 꼴 */
    fun probeIds(): List<String> = listOf(w.a.id, w.b.id, w.c.id, "prf_" + "0".repeat(32), "prf_zz", "pending:p1")

    /** 알림 이벤트 중 Y 가 SSE 로 받을 것 (받는 사람이 Y 이거나 정해지지 않은 것) — 같은 JSON 으로 흘린다 */
    fun sseEventsForY(): List<Captured> =
        w.events.filter { NotificationSseService.isVisibleTo(it, w.yAcc) }.map { Captured("SSE ${it.type}", 200, mapper.writeValueAsString(it), emptyMap()) }

    /** 글 · 댓글 응답에서 작성자별 조각을 모은다 */
    fun boardPieces(seen: List<Captured>): LeakPieces =
        LeakPieces().also { p -> seen.filter { it.status == 200 && "/posts" in it.label && "/notifications" !in it.label }.forEach { p.addJson(it.body) } }

    /** 받은편지함 응답 · SSE 이벤트에서 알림 한 건을 한 조각으로 — 작성자는 payload.authorId, 나머지 칸은 알림의 것 */
    fun noticePieces(seen: List<Captured>): LeakPieces {
        val items = seen.filter { it.status == 200 && ("/notifications" in it.label || it.label.startsWith("SSE ")) }.flatMap { c ->
            if (c.label.startsWith("SSE ")) listOf(JsonPath.parse(c.body).json<Map<String, Any?>>()) else JsonPath.read<List<Map<String, Any?>>>(c.body, "$.values")
        }
        return LeakPieces().also { p ->
            items.forEach { item ->
                @Suppress("UNCHECKED_CAST") val payload = item["payload"] as? Map<String, Any?> ?: return@forEach
                p.addNode(mapOf("authorId" to payload["authorId"]) + item.filterKeys { it != "payload" } + payload.filterKeys { it != "authorId" })
            }
        }
    }
}
