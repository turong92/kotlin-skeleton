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
            strings = setOf("general", "PUBLISHED", "HIDDEN", "DRAFT", "DELETED", "LIKE", "DISLIKE", "EMPATHY", "board", "comment.created", "comment.replied", "INFO", w.yPostTitle, w.yAcc,
                "http:", "cdn.test", "cdn", "test", "app", "public",   // 공개 사진 주소의 앞부분(CDN 주소 · 공개 접두사)은 모두에게 같은 어휘다 - 뒤의 키는 첨부마다 다르다

                "New comment on your post: ${w.yPostTitle}",   // 알림 제목: Y 의 글 제목이 들어간다 — 두 알림이 같은 글에 달렸다는 구조
            ),
            // 숫자는 종류.칸 별로만: 댓글이 놓인 글(Y 의 것)과, 자작 스레드의 뿌리(A 의 댓글 c1)와 Y 의 댓글(c0)이 답글의 parentId · rootId 로 보이는 구조
            structuralNumbers = buildMap {
                listOf("postId", "post_id").forEach { put("comment.$it", setOf(w.yPost)) }
                listOf("id", "rootId", "parentId", "root_id", "parent_id").forEach { put("comment.$it", setOf(w.c0, w.c1)) }
            },
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

    /** 운영자(MODERATOR)가 읽는 경로 — 일반 읽기 + 숨김 · 임시저장 목록 + 숨김 · 복구 응답 (게시판 운영자도 연결을 보지 못해야 한다, 스펙 §3) */
    fun moderatorReads(): List<Captured> {
        val m = http.mark()
        listOf("$base/posts?status=HIDDEN", "$base/posts?status=DRAFT", "$base/posts?status=DRAFT&sort=reactions", "$base/posts/${w.postDraft}").forEach { http.get(it, w.modToken) }
        http.put("$base/posts/${w.postA}/moderation", w.modToken, """{"status":"HIDDEN","pinned":true}""")
        http.get("$base/posts?status=HIDDEN", w.modToken)
        attachmentReads(w.modToken)
        http.put("$base/posts/${w.postA}/moderation", w.modToken, """{"status":"PUBLISHED","pinned":false}""")
        http.put("$base/posts/${w.yPost}/comments/${w.c2}/moderation", w.modToken, """{"status":"HIDDEN"}""")
        http.get("$base/posts/${w.yPost}/comments", w.modToken)
        http.put("$base/posts/${w.yPost}/comments/${w.c2}/moderation", w.modToken, """{"status":"PUBLISHED"}""")
        return http.since(m) + boardReads(w.modToken)
    }

    /** 서명 읽기 주소 경로 - 글 · 댓글의 사진을 볼 수 있는 사람(운영자)이 읽는다. 응답은 키가 든 서명 주소뿐이어야 한다 */
    fun attachmentReads(token: String) {
        val post = http.get("$base/posts/${w.postA}/attachments?key=${w.keys[0]}", token)
        val comment = http.get("$base/posts/${w.yPost}/comments/${w.cPicture}/attachments?key=${w.cPictureKey}", token)
        check(post.status == 200 && comment.status == 200) { "attachment access for the moderator: ${post.status} ${post.body} / ${comment.status} ${comment.body}" }
    }

    /** 반응을 누르고 거두는 응답 (개수와 `myReactions` 뿐이지만 헤더 · 오류까지 같은 스캔을 받는다) — 끝나면 처음 상태로 돌려 둔다 */
    fun reactionWrites(): List<Captured> {
        val m = http.mark()
        http.put("$base/posts/${w.postA}/reactions", w.yToken, """{"type":"EMPATHY"}""")
        http.delete("$base/posts/${w.postB}/reactions?type=LIKE", w.yToken)
        http.put("$base/posts/${w.postB}/reactions", w.yToken, """{"type":"LIKE"}""")
        http.put("$base/posts/${w.yPost}/comments/${w.c1}/reactions", w.yToken, """{"type":"LIKE"}""")
        http.put("$base/posts/${w.postA}/reactions", w.yToken, """{"type":"SAD"}""")   // 오류 응답
        return http.since(m)
    }

    /** 값 찾기 — X 의 계정 id · 이메일로 검색하면 0건이고, OpenAPI 문서에도 없다 (스캔이 문서 전체를 본다) */
    fun valueSearches(token: String?): List<Captured> {
        val m = http.mark()
        http.get("$base/posts?q=${w.xAcc}", token)
        http.get("$base/posts?q=${w.xEmail}", token)
        http.get("/api/v1/docs", token)
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
