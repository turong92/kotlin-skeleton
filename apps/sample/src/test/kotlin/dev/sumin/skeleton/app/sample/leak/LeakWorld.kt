package dev.sumin.skeleton.app.sample.leak

import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import java.util.concurrent.CopyOnWriteArrayList

data class Profile(val id: String, val name: String?, val tag: String?)

/**
 * 스펙 §12 1~2 의 세계 — 계정 X 가 프로필 A · B · C, 관찰자 Y(평범한 사용자), 정지 · 탈퇴를 맡을 관리자.
 * X 는 A 로 글, B 로 글(둘 다 OPAQUE 첨부), Y 의 글에 A 로 댓글 · B 로 대댓글(자작 스레드) · B 로 Y 의 댓글에 답글 · C 로 댓글, 반응 몇 개.
 * 계정 이름은 첫 프로필(A)이 복사해 간 **뒤에** 바꾼다 — 금지 값인 현재 계정 이름이 A 의 공개 이름과 달라야 스캔이 둘을 구별한다.
 */
class LeakWorld(private val http: LeakHttp, registry: NotificationSubscriptionRegistry) {
    val nonce: String = System.nanoTime().toString(36)
    val xEmail = "xavier.leak@example.com"
    val xAccountName = "SecretLabel$nonce"
    val yPostTitle = "Y post kiwi $nonce"

    val xToken: String
    val yToken: String
    val adminToken: String
    val modToken: String
    val xAcc: String
    val yAcc: String
    val a: Profile
    var b: Profile; private set
    var c: Profile; private set
    val yProfile: Profile
    var yPost = 0L; private set
    var postA = 0L; private set
    var postDraft = 0L; private set   // A 의 임시저장 글 — 운영자만 읽는다
    var postB = 0L; private set
    var c0 = 0L; private set   // Y 의 댓글
    var c1 = 0L; private set   // A 의 댓글 (Y 의 글, 최상위)
    var c2 = 0L; private set   // B 의 답글 (c1 아래 — 자작)
    var c3 = 0L; private set   // B 의 답글 (c0 아래 — Y 에게 알림)
    var c4 = 0L; private set   // C 의 댓글
    val keys = mutableListOf<String>()
    /** 구독해 둔 알림 — SSE 가 흘리는 것과 같은 이벤트 */
    val events = CopyOnWriteArrayList<NotificationEvent>()

    /** 세계를 만드는 동안 받은 **응답** 전부 (로그인 · 프로필 · 글 · 댓글 · 첨부 · 반응 · 이름 바꾸기) — 요청 본문은 없다. 쓰기 응답을 값으로 훑는 데 쓴다 */
    val journey: List<Captured>

    /** 어디에도 없어야 하는 값 (X 의 계정 id · 이메일 · 계정 톰스톤 · 현재 계정 이름) */
    val forbidden: Map<String, String> by lazy {
        mapOf("account id" to xAcc, "email" to xEmail, "tombstone" to AccountTombstone.of(xAcc), "account name" to xAccountName)
    }

    init {
        registry.subscribe(setOf("board")) { events += it }
        xToken = login(xEmail).first
        yToken = login("yolanda.leak@example.com").first
        adminToken = login("admin.leak@example.com").first
        modToken = login("mod.leak@example.com").first
        xAcc = login(xEmail).second
        yAcc = login("yolanda.leak@example.com").second
        yProfile = profiles(yToken).first()
        a = profiles(xToken).first()                       // GET 이 첫 프로필을 보장한다 (계정 이름을 복사)
        b = createProfile("Bobby$nonce")
        c = createProfile("Carol$nonce")
        distinctTags()
        http.patch("/api/v1/account/me", xToken, """{"displayName":${json(xAccountName)}}""").ok()
        yPost = createPost(yToken, yProfile, yPostTitle, "kiwi from Y")
        c0 = createComment(yToken, yProfile, yPost, "Y 의 첫 댓글 $nonce")
        postA = createPost(xToken, a, "A post kiwi $nonce", "written as A", attach())
        postB = createPost(xToken, b, "B post kiwi $nonce", "written as B", attach())
        postDraft = createPost(xToken, a, "A draft kiwi $nonce", "a draft of A", status = "DRAFT")
        c1 = createComment(xToken, a, yPost, "A 가 단 댓글 $nonce")
        c2 = createComment(xToken, b, yPost, "B 가 자기 댓글에 답글 $nonce", c1)
        c3 = createComment(xToken, b, yPost, "B 가 Y 에게 답글 $nonce", c0)
        c4 = createComment(xToken, c, yPost, "C 가 단 댓글 $nonce")
        react(yToken, "posts/$postA", "EMPATHY"); react(yToken, "posts/$postB", "LIKE"); react(yToken, "posts/$yPost/comments/$c1", "LIKE")
        react(adminToken, "posts/$postA", "LIKE"); react(adminToken, "posts/$yPost/comments/$c2", "LIKE")
        react(xToken, "posts/$yPost", "LIKE"); react(xToken, "posts/$yPost/comments/$c0", "EMPATHY")   // X 자신의 반응 — 계정 키 행
        journey = http.since(0)
    }

    fun login(email: String): Pair<String, String> {
        val r = http.post("/api/v1/auth/login", null, """{"email":"$email","password":"password"}""").ok()
        return r.read<String>("$.value.accessToken") to r.read("$.value.principal.accountId")
    }

    fun relogin(email: String): String = login(email).first

    fun profiles(token: String): List<Profile> =
        http.get("/api/v1/author-profiles", token, record = false).ok().read<List<Map<String, Any?>>>("$.values").map { Profile(it["id"] as String, it["name"] as String?, it["tag"] as String?) }

    private fun createProfile(name: String): Profile = newProfile(xToken, name)

    /** [token] 계정의 새 프로필 (첫 프로필은 `profiles(token)` 이 보장한다) */
    fun newProfile(token: String, name: String): Profile {
        tick()
        val r = http.post("/api/v1/author-profiles", token, """{"name":${json(name)}}""").ok()
        return Profile(r.read("$.value.id"), r.read("$.value.name"), r.read("$.value.tag"))
    }

    /** 꼬리표가 우연히 겹치면 (만분의 일) 짝 스캔이 거짓 양성을 낸다 — 이름을 바꿔 다시 추첨한다 */
    private fun distinctTags() {
        repeat(5) {
            val tags = profiles(xToken).filter { it.tag != null }.associate { it.id to it.tag }
            val dup = tags.entries.groupBy { it.value }.values.firstOrNull { it.size > 1 } ?: return
            val victim = dup.last().key
            val renamed = profiles(xToken).first { it.id == victim }.let { p -> http.patch("/api/v1/author-profiles/$victim", xToken, """{"name":${json(p.name + "x")}}""").ok() }
            if (victim == b.id) b = Profile(b.id, renamed.read("$.value.name"), renamed.read("$.value.tag"))
            if (victim == c.id) c = Profile(c.id, renamed.read("$.value.name"), renamed.read("$.value.tag"))
        }
    }

    private fun attach(): List<String> {
        val r = http.post("/api/v1/storage/presign", xToken, """{"fileName":"photo.png","contentType":"image/png","sizeBytes":1024}""").ok()
        return listOf(r.read<String>("$.value.key").also { keys += it })
    }

    private fun tick() = Thread.sleep(4)   // 시각 값이 두 쓰기에서 우연히 같아지지 않게

    fun createPost(token: String, author: Profile, title: String, body: String, attachments: List<String> = emptyList(), status: String? = null): Long {
        tick()
        val files = attachments.joinToString(",", "[", "]") { json(it) }
        val st = status?.let { ""","status":"$it"""" } ?: ""
        return http.post("/api/v1/boards/general/posts", token, """{"title":${json(title)},"body":${json(body)},"authorId":"${author.id}","attachments":$files$st}""").ok().read<Number>("$.value.id").toLong()
    }

    fun createComment(token: String, author: Profile, post: Long, text: String, parent: Long? = null): Long {
        tick()
        val p = parent?.let { ""","parentId":$it""" } ?: ""
        return http.post("/api/v1/boards/general/posts/$post/comments", token, """{"body":${json(text)},"authorId":"${author.id}"$p}""").ok().read<Number>("$.value.id").toLong()
    }

    private fun react(token: String, path: String, type: String) { tick(); http.put("/api/v1/boards/general/$path/reactions", token, """{"type":"$type"}""").ok() }
}
