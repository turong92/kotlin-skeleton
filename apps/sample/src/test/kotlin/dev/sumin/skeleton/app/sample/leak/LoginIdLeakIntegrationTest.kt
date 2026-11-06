package dev.sumin.skeleton.app.sample.leak

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.TestcontainersConfiguration
import dev.sumin.skeleton.app.sample.notes.SampleIntegrationTest
import dev.sumin.skeleton.booking.BookingPools
import dev.sumin.skeleton.booking.NewBookingPool
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import dev.sumin.skeleton.review.ReviewPurchases
import dev.sumin.skeleton.review.ReviewTarget
import dev.sumin.skeleton.video.PlayableResult
import dev.sumin.skeleton.video.TrackStatus
import dev.sumin.skeleton.video.VideoKeys
import dev.sumin.skeleton.video.VideoLane
import dev.sumin.skeleton.video.VideoPlaybackTokens
import dev.sumin.skeleton.video.VideoStore
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.ObjectMapper

/**
 * **로그인 아이디가 새지 않는다** (스펙 §11 의 1 · 2) — 아이디가 나가는 곳은 셋뿐이다: 본인의 `GET/PATCH /account/me`(와 정하기의 응답), 계정 이메일로 가는 찾기 메일, 관리자 조회.
 *
 * 1. **응답 전수 스캔.** 눈에 띄는 아이디([LOGIN_ID], `leak-seeds.yml` 의 X)를 가진 X 가 글 · 댓글 · 프로필 · 첨부 · 알림을 만든 뒤(`LeakWorld`), 다른 사용자 Y · 익명 · `MODERATOR` 가 읽을 수 있는 응답의
 *    **본문과 헤더**에서 그 문자열을 찾는다(`LeakScan.forbiddenHits`). 대상 경로는 손으로 적지 않고 **OpenAPI 문서(`/api/v1/docs`)의 GET 전부**에서 뽑는다 — 새 엔드포인트가 생기면 자동으로 훑는다.
 *    경로 변수는 여정에서 얻은 id 로 채우고, **채우지 못한 경로가 있으면 시험이 실패한다**(조용히 건너뛰지 않는다). 허용: X 본인의 `me`, `ADMIN` 의 관리자 조회.
 * 2. **응답 타입 가드.** 앱 컨텍스트의 모든 핸들러 반환 타입(제네릭 인자 · 필드를 재귀로)과 OpenAPI 의 응답 스키마에서 `loginId` 라는 속성을 가진 타입이 [ALLOWED_TYPES] 셋뿐인지 본다. 누가 다른 DTO 에 칸을 더하면 여기서 깨진다.
 *
 * 세 번째 겹(DB 전수 스캔)은 `modules/account-jdbc` 의 `PlantedDataScan` · `JdbcErasureDbTest` 가 한다.
 */
@SpringBootTest(
    properties = [
        "skeleton.job-queue.poll-interval=250ms",   // AuthorLinkLeakIntegrationTest 와 같은 시드(leak-seeds.yml)를 쓰지만 **다른 컨텍스트 · 다른 DB** 여야 한다 — 그 시험은 X 를 지우고 끝난다 (설정 값이 하나라도 달라야 컨텍스트가 나뉜다)
        "spring.config.import=classpath:leak-seeds.yml",
        "skeleton.board.http.allow-anonymous-read=true",   // 익명이 읽을 수 있게 — 비로그인 · 검색엔진
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, SampleIntegrationTest.FakeStorageConfiguration::class, dev.sumin.skeleton.app.sample.cheer.CheerIntegrationBase.Provider::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginIdLeakIntegrationTest {
    companion object {
        /** `leak-seeds.yml` 의 X(`acc_leak_x`)의 아이디 — 어디서도 마주칠 수 없는 모양으로 골랐다 */
        const val LOGIN_ID = "zq7.probe-x1"

        /** `loginId` 속성을 가질 수 있는 응답 타입 (스펙 §11): 본인 · 관리자 응답, 그리고 값이 아니라 길이 규칙만 담는 `GET /auth/methods` */
        val ALLOWED_TYPES = setOf("MeView", "AdminAccountResponse", "AuthMethodsView")

        /** 스트림이라 한 번의 응답으로 끝나지 않는 GET — 이름으로 빼고, 이벤트는 `sweep.sseEventsForY()` 로 따로 훑는다 */
        val STREAMS = setOf("/api/v1/notifications/sse", "/api/v1/booking/pools/{id}/seats/stream")

        /** 자기 순번표(헤더)가 있어야만 답하는 GET — 남의 표는 403 이라 토큰만 든 독자는 2xx 를 못 받는다. 답은 부른 사람 자신의 순번뿐이다 */
        val TICKET_GATED = setOf("/api/v1/waiting-rooms/{id}/status")

        /** 시험이 만든 재생 가능 영상의 원본 재생 목록 폴더 (`o-<해시 16자>`) */
        const val VIDEO_ORIG_FOLDER = "o-0123456789abcdef"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var registry: NotificationSubscriptionRegistry
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var purchases: ReviewPurchases
    @Autowired lateinit var bookingPools: BookingPools
    @Autowired lateinit var jdbc: org.springframework.jdbc.core.simple.JdbcClient
    @Autowired lateinit var storage: dev.sumin.skeleton.app.sample.notes.FakePresignedStorage
    @Autowired lateinit var videoStore: VideoStore
    @Autowired lateinit var videoKeys: VideoKeys
    @Autowired lateinit var videoTokens: VideoPlaybackTokens

    private val http by lazy { LeakHttp(mvc) }
    private val w by lazy { LeakWorld(http, registry) }
    private val sweep by lazy { LeakSweep(http, w, mapper) }

    private val forbidden get() = mapOf("login id" to LOGIN_ID)

    /** OpenAPI 문서가 광고하는 GET 경로 템플릿 전부 */
    private fun documentedGets(): List<String> {
        val docs = http.get("/api/v1/docs", w.yToken, record = false).ok()
        return JsonPath.read<Map<String, Map<String, Any?>>>(docs.body, "$.paths").filterValues { "get" in it }.keys.sorted()
    }

    private fun createNote(token: String, title: String): String =
        http.post("/api/v1/notes", token, """{"title":${json(title)},"body":"a note of mine"}""", record = false).ok().read("$.value.id")

    /** Y 가 만든 워크스페이스 하나 - 팀 경로(`/api/v1/workspaces/{id}…` · `by-slug/{slug}` · 운영자 `/api/v1/admin/workspaces/{id}`)의 변수 값 */
    private val workspace: Pair<String, String> by lazy {
        val created = http.post("/api/v1/workspaces", w.yToken, """{"name":"Leak ${w.nonce}"}""", record = false).ok()
        created.read<String>("$.value.id") to created.read<String>("$.value.slug")
    }

    /** X · Y 가 각각 구매 신호를 받고 상품 `basic` 에 리뷰를 하나씩 쓴다 - 리뷰 경로(`/api/v1/reviews/targets/{type}/{id}/…` · `/api/v1/reviews/{id}/photos/{index}`)의 변수 값. 반환 = 리뷰 id 들 */
    private val reviewIds: List<String> by lazy {
        listOf(w.xAcc to w.xToken, w.yAcc to w.yToken).map { (acc, token) ->
            purchases.record(acc, ReviewTarget("product", "basic"), "leak-${w.nonce}-$acc")
            val photo = http.post("/api/v1/storage/presign", token, """{"fileName":"photo.png","contentType":"image/png","sizeBytes":1024}""", record = false).ok().read<String>("$.value.key")   // 본인 업로드 키 한 장 - 사진 열람(302)이 닿게
            http.post("/api/v1/reviews/targets/product/basic/reviews", token, """{"rating":5,"body":"a review by ${acc.takeLast(1)} ${w.nonce}","photos":[${json(photo)}]}""", record = false).ok().read<String>("$.value.id")
        }
    }

    private class Videos(val uploading: String, val playback: Playback)
    private class Playback(val id: String, val token: String)

    /** Y 가 영상 둘을 올린다 - 하나는 올리는 중으로 두고, 하나는 일꾼이 마친 것처럼 재생 가능으로 만들어 재생 목록을 저장소에 둔다 */
    private val video: Videos by lazy {
        fun start() = http.post("/api/v1/videos", w.yToken, """{"bytes":6000000,"contentType":"video/mp4","purpose":"note","visibility":"PRIVATE"}""", record = false).ok().read<String>("$.value.id")
        val uploading = start()
        val playable = start()
        jdbc.sql("update videos set status = 'QUEUED' where id = :id").param("id", playable).update()
        val at = java.time.Instant.now()
        videoStore.claimNext(VideoLane.REMUX, "leak-worker", at.plusSeconds(600), "b".repeat(32), at)
        val folder = videoKeys.folder(videoStore.find(playable)!!)
        val result = PlayableResult(
            origKey = "$folder$VIDEO_ORIG_FOLDER/index.m3u8", origBytes = 100, origSha256 = "0".repeat(64), srcContainer = "mov,mp4", srcCodec = "avc1", srcCodecString = "avc1.640028",
            srcWidth = 640, srcHeight = 360, srcRotation = 0, srcDurationMs = 3000, srcFps = 30.0, srcBitrate = 1_000_000, srcAudioCodec = "aac",
            posterKey = null, posterWidth = null, posterHeight = null, compat = TrackStatus.SKIPPED, ladder = TrackStatus.SKIPPED,
        )
        check(videoStore.markPlayable(playable, "leak-worker", result, at)) { "the fake worker could not finish the video" }
        storage.objects[result.origKey] = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:3\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:3.0,\ns00001.m4s\n#EXT-X-ENDLIST\n".toByteArray()
        Videos(uploading, Playback(playable, videoTokens.issue(playable, java.time.Duration.ofHours(1)).value))
    }

    /** 경로 변수 → 여정에서 얻은 값들. `{id}` 는 경로마다 뜻이 달라 접두사로 가른다 */
    private val candidates: Map<String, List<String>> by lazy {
        val eventId = http.get("/api/v1/notifications?size=100", w.yToken, record = false).ok().read<List<String>>("$.values[*].eventId").first()
        mapOf(
            "code" to listOf("general"),
            "index" to listOf("0", "1"),   // 리뷰 사진 열람 (/api/v1/reviews/{id}/photos/{index}) - 사진 없는 리뷰는 404 여야 한다
            "postId" to listOf(w.yPost, w.postA, w.postB, w.postDraft).map { it.toString() },
            "commentId" to listOf(w.c0, w.c1, w.c2, w.c3, w.c4, w.cPicture).map { it.toString() },
            "eventId" to listOf(eventId),
            "type" to listOf("terms", "privacy", "product"),   // 약관 종류 · 리뷰 대상 종류
            "key" to listOf("app.board-posts", "app.board-reactions", "coupon.top-used"),   // 대시보드 순위 하나 (/api/v1/admin/dashboard/rankings/{key}) - 견본 앱에 실제로 붙은 앱 통계 순위
            "slug" to listOf(workspace.second),   // 팀 주소로 풀기 (/api/v1/workspaces/by-slug/{slug})
            "accountId" to listOf(w.xAcc, w.yAcc),   // 운영자의 포인트 잔액 조회 (/api/v1/admin/points/{accountId})
            // 비공개 영상 재생 (/api/v1/videos/play/{token}/{folder}/{file}) - 변환이 끝난 영상 하나의 출입증 · 재생 목록 위치
            "token" to listOf(video.playback.token),
            "folder" to listOf(VIDEO_ORIG_FOLDER),
            "file" to listOf("index.m3u8"),
        )
    }

    private val idByPrefix: Map<String, List<String>> by lazy {
        mapOf(
            "/api/v1/reviews/targets/" to listOf("basic"),   // 대상 id (아래 `/api/v1/reviews/` 보다 앞에 둔다 - 접두사로 첫 일치를 고른다)
            "/api/v1/reviews/" to reviewIds,
            "/api/v1/notes/" to listOf(createNote(w.yToken, "Y note ${w.nonce}"), createNote(w.xToken, "X note ${w.nonce}")),
            "/api/v1/workspaces/" to listOf(workspace.first),
            "/api/v1/admin/workspaces/" to listOf(workspace.first),
            "/api/v1/videos/" to listOf(video.uploading, video.playback.id),   // Y 의 영상 둘 - 올리는 중(조각 목록 /api/v1/videos/{id}/parts)과 변환이 끝난 것(조회 /api/v1/videos/{id})
            "/api/v1/author-profiles/" to listOf(w.yProfile.id, w.a.id, w.b.id),
            "/api/v1/admin/accounts/" to listOf(w.xAcc, w.yAcc),
            "/api/v1/admin/mfa/accounts/" to listOf(w.xAcc, w.yAcc),   // 운영자의 2단계 상태 보기 (/api/v1/admin/mfa/accounts/{id})
            "/api/v1/admin/broadcasts/" to listOf(http.post("/api/v1/admin/broadcasts", w.adminToken, """{"type":"notice.service","title":"Leak ${w.nonce}","body":"b","channels":["push"],"audience":{"kind":"all"}}""", record = false).ok().read<String>("$.value.id")),   // 보내지 않은 공지 초안 하나 (/api/v1/admin/broadcasts/{id})
            "/api/v1/booking/pools/" to listOf(bookingPools.create(NewBookingPool("Leak ${w.nonce}", java.time.Instant.now().minusSeconds(60), maxPerHold = 2)).id),   // 대기실 없는 풀 하나 - 상세 · 배치도 (/api/v1/booking/pools/{id} · /seats)
            "/api/v1/admin/author-profiles/" to listOf(w.a.id, w.yProfile.id),
            "/api/v1/campaigns/" to listOf(http.post("/api/v1/admin/campaigns", w.adminToken, """{"kind":"attendance","name":"Leak ${w.nonce}","startsAt":"2026-01-01T00:00:00Z","config":{"daily":[{"type":"point","params":{"amount":"10"}}]}}""", record = false).ok().read<String>("$.value.id")),   // 출석 캠페인 하나 - 운영자가 만든다
            // 쿠폰 정의 한 개를 만들어 X · Y 에게 한 장씩 준다 - 현황 · 발급 목록 (/api/v1/admin/coupon-definitions/{id}/stats · /coupons)
            "/api/v1/admin/coupon-definitions/" to listOf(
                http.post("/api/v1/admin/coupon-definitions", w.adminToken, """{"name":"Leak ${w.nonce}","stages":[{"type":"FREE","periods":1}]}""", record = false).ok().read<String>("$.value.id").also { id ->
                    http.post("/api/v1/admin/coupon-definitions/$id/issue", w.adminToken, """{"accountIds":["${w.xAcc}","${w.yAcc}"]}""", record = false).ok()
                },
            ),
            "/api/v1/payments/orders/" to listOf(http.post("/api/v1/payments/orders", w.yToken, """{"itemType":"cheer","itemRef":"cheer-1000","quantity":1}""", record = false).ok().read<String>("$.value.order.id")),   // 결제 장부의 주문 한 건 읽기 - Y 의 주문(결제사는 시험 소스의 가짜)
        )
    }

    /** 템플릿 하나 → 채운 경로들. 채우지 못한 변수가 하나라도 있으면 null (호출한 쪽이 시험을 실패시킨다) */
    private fun fill(template: String): List<String>? {
        val variables = Regex("\\{([^/}:]+)(?::[^}]*)?}").findAll(template).map { it.groupValues[1] }.toList()
        var paths = listOf(template)
        for (name in variables) {
            val values = if (name == "id") idByPrefix.entries.firstOrNull { template.startsWith(it.key) }?.value else candidates[name]
            if (values == null) return null
            paths = paths.flatMap { p -> values.map { v -> p.replaceFirst(Regex("\\{$name(?::[^}]*)?}"), java.util.regex.Matcher.quoteReplacement(v)) } }
        }
        // 사진 열람 경로는 `index` 가 필수다 - 첫 번째 사진 · 없는 순번 · 고친 판의 목록으로 채운다
        if (template.endsWith("/attachments")) paths = paths.flatMap { p -> listOf("$p?index=0", "$p?index=1", "$p?index=0&from=edit") }
        // 리뷰 요약은 `targets=type:id` 가 필수다
        if (template == "/api/v1/reviews/summaries") paths = listOf("$template?targets=product:basic")
        // 검색은 질의어 두 개로 훑는다 - X 의 아이디(값으로 찾기) · 노트 제목 조각 (색인에 있는 문서만 나온다)
        if (template == "/api/v1/search") paths = listOf("$template?q=$LOGIN_ID", "$template?q=Y+note", "$template?q=basic")
        // 운영자의 계정별 쿠폰 목록은 `accountId` 가 필수다
        if (template == "/api/v1/admin/coupons") paths = listOf(w.xAcc, w.yAcc).map { "$template?accountId=$it" }
        // 공지 대상 수 미리보기는 `kind` 가 필수다
        if (template == "/api/v1/admin/broadcasts/audience-count") paths = listOf("$template?kind=all", "$template?kind=role&param=USER")
        // 앱 인앱결제 설정은 `platform` 이 필수다 - 두 플랫폼으로 훑는다
        if (template == "/api/v1/payments/iap/config") paths = listOf("$template?platform=ios&storefront=KR", "$template?platform=android")
        // 출석 달력은 `month` 가 필수다
        if (template.endsWith("/attendance")) paths = paths.map { "$it?month=${java.time.YearMonth.now()}" }
        return paths
    }

    private class Reading(val reader: String, val captured: Captured)

    private fun readAll(reader: String, token: String?, paths: List<String>): List<Reading> =
        paths.map { Reading(reader, http.get(it, token, record = false)) }

    private fun hits(readings: List<Reading>) = LeakScan.forbiddenHits(readings.map { it.captured }, forbidden)

    private fun resolved(): Map<String, List<String>> {
        val templates = documentedGets().filter { it !in STREAMS && it !in TICKET_GATED }
        val unfilled = templates.filter { fill(it) == null }
        assertEquals(emptyList(), unfilled, "path variables the journey does not know - give them a value in `candidates` / `idByPrefix` (a path is never skipped silently)")
        assertTrue(STREAMS.all { it in documentedGets() }, "STREAMS names a path the documentation does not list")
        return templates.associateWith { fill(it)!! }
    }

    @Test
    fun `the scan finds the login ID where it is allowed - X's own me and the administrator's lookup`() {
        val own = http.get("/api/v1/account/me", w.xToken, record = false).ok()
        assertEquals(emptyList(), listOf(own.read<String>("$.value.loginId")).filter { it != LOGIN_ID }, "the seeded account really has the ID")
        assertEquals(setOf("login id"), LeakScan.forbiddenHits(listOf(own), forbidden).map { it.label }.toSet(), "X reading me: the same scan function sees the ID")
        val byAdmin = http.get("/api/v1/admin/accounts/${w.xAcc}", w.adminToken, record = false).ok()
        assertEquals(setOf("login id"), LeakScan.forbiddenHits(listOf(byAdmin), forbidden).map { it.label }.toSet(), "the administrator's lookup carries it")
        val search = http.get("/api/v1/admin/accounts?loginId=$LOGIN_ID", w.adminToken, record = false).ok()
        assertEquals(1, search.read<List<Any?>>("$.values").size, "the exact-match search finds X")
        // 다른 사람은 그 조회에 닿지 못한다
        assertEquals(403, http.get("/api/v1/admin/accounts/${w.xAcc}", w.yToken, record = false).status)
        assertEquals(401, http.get("/api/v1/admin/accounts/${w.xAcc}", null, record = false).status)
        // 정하기 · 실시간 확인의 응답은 부른 사람이 넣은 값을 돌려받는 것이다 (남의 아이디가 나가는 곳이 아니다) — 이 값이 새는 길이 아님을 눈으로 남긴다
        assertEquals(LOGIN_ID, JsonPath.read<String>(http.post("/api/v1/account/login-id/availability", null, """{"loginId":"$LOGIN_ID"}""", record = false).ok().body, "$.value.normalized"))
    }

    /**
     * X 는 계정 사진, Y 는 자기 프로필의 사진을 하나씩 정한다 - 서명 주소를 돌려주는 GET(`/account/me/avatar` · `/author-profiles/{id}/avatar`)은 사진이 있어야 2xx 를 낸다
     * (없으면 404 라 「어느 독자도 닿지 못한 경로」로 걸린다). 키는 본인 업로드 한 장(presign)이다.
     */
    private val avatars: Unit by lazy {
        // presign 한 키를 올리지 않고 그대로 쓴다 - 소유 확인은 키의 꼴(OPAQUE 의 MAC)만 보고, 서명 주소는 저장소에 객체가 없어도 계산된다
        val x = http.post("/api/v1/storage/presign", w.xToken, """{"fileName":"me.png","contentType":"image/png","sizeBytes":1024}""", record = false).ok().read<String>("$.value.key")
        http.patch("/api/v1/account/me", w.xToken, """{"avatarKey":${json(x)}}""").ok()
        val y = http.post("/api/v1/storage/presign", w.yToken, """{"fileName":"profile.png","contentType":"image/png","sizeBytes":1024}""", record = false).ok().read<String>("$.value.key")
        http.patch("/api/v1/author-profiles/${w.yProfile.id}", w.yToken, """{"avatarKey":${json(y)}}""").ok()
        Unit
    }

    /** 독자별로 OpenAPI 의 GET 전부를 한 번씩 (한 번만 읽는다 — 아래 두 시험이 나눠 쓴다) */
    private val everyReading: Map<String, List<Reading>> by lazy {
        avatars
        val every = resolved().values.flatten()
        val templates = resolved().keys
        listOf("/api/v1/account/me", "/api/v1/boards/{code}/posts", "/api/v1/boards/{code}/posts/{postId}", "/api/v1/boards/{code}/posts/{postId}/comments", "/api/v1/notifications", "/api/v1/author-profiles",
            "/api/v1/notes/{id}", "/api/v1/admin/accounts", "/api/v1/admin/accounts/{id}", "/api/v1/legal/documents/{type}",
        ).forEach { assertTrue(it in templates, "the OpenAPI document must list GET $it (it lists ${templates.size} GET paths)") }
        assertTrue(every.size >= 30, "${templates.size} documented GET paths filled into ${every.size} requests per reader - fewer than this means the document or the filling shrank")
        mapOf(
            "Y" to readAll("Y", w.yToken, every), "anonymous" to readAll("anonymous", null, every), "moderator" to readAll("moderator", w.modToken, every),
            "administrator" to readAll("administrator", w.adminToken, every), "X" to readAll("X", w.xToken, every),
        )
    }

    @Test
    fun `nothing Y, an anonymous caller or a moderator can read from any documented GET names X's login ID`() {
        val paths = resolved()
        val y = everyReading.getValue("Y") + sweep.boardReads(w.yToken).map { Reading("Y", it) } + sweep.observerOnly().map { Reading("Y", it) } + sweep.sseEventsForY().map { Reading("Y SSE", it) }
        val z = everyReading.getValue("anonymous") + sweep.boardReads(null).map { Reading("anonymous", it) }
        val mod = everyReading.getValue("moderator") + sweep.moderatorReads().map { Reading("moderator", it) }
        // 값으로 찾기: X 의 아이디로 글을 검색하면 0건이다
        listOf(w.yToken, null).forEach { token ->
            val found = http.get("/api/v1/boards/general/posts?q=$LOGIN_ID", token, record = false)
            assertEquals(0, found.read<Number>("$.pagination.totalElements").toInt(), "${token?.let { "Y" } ?: "anonymous"}: searching posts by the login ID finds nothing")
        }

        // 훑은 것이 비어 있지 않다: 경로마다 적어도 한 독자가 2xx(또는 사진의 서명 주소 302)를 받았다 (잘못 채운 값으로 404 만 받아 아무것도 안 본 경로가 없다)
        val reached = everyReading.values.flatten().filter { it.captured.status in 200..299 || it.captured.status == 302 }.map { it.captured.label.substringBefore('?').removePrefix("GET ") }.toSet()
        val untouched = paths.filter { (_, filled) -> filled.none { it.substringBefore('?') in reached } }.keys
        assertEquals(emptySet(), untouched, "documented GET paths that no reader (Y, anonymous, moderator, administrator, X) ever got a 2xx for - the journey's values do not reach them")

        listOf("Y" to y, "anonymous" to z, "moderator" to mod).forEach { (who, readings) ->
            assertTrue(readings.size > paths.values.flatten().size, "$who: scanned ${readings.size} responses")
            assertEquals(emptyList(), hits(readings).map { it.where }, "$who: responses that contain the login ID")
        }
    }

    @Test
    fun `the responses to the journey's own writes - login, profile, post, comment, upload, reaction - never carry X's login ID`() {
        // 위의 값 스캔은 GET 뿐이다. 쓰기 응답(POST · PUT · PATCH)은 타입 가드가 이름(`loginId`)으로만 지키므로 다른 이름의 칸은 값으로 잡는다.
        // 허용: X 본인의 `PATCH /account/me` 응답(스펙 §11 의 첫째) — `me` 와 같은 모양이다. 요청 본문은 기록하지 않으므로 X 가 보낸 값이 걸리지 않는다.
        val journey = w.journey
        assertTrue(journey.size >= 25, "the journey recorded ${journey.size} responses - login, profiles, posts, comments, uploads, reactions")
        assertTrue(journey.any { it.label.startsWith("POST /api/v1/boards/general/posts") } && journey.any { it.label.startsWith("PUT ") } && journey.any { it.label == "POST /api/v1/auth/login" }, "the record has writes of each kind")
        val found = LeakScan.forbiddenHits(journey, forbidden).map { it.where }.toSet()
        assertEquals(setOf("PATCH /api/v1/account/me"), found, "journey responses that contain the login ID (only X's own me is allowed)")
    }

    @Test
    fun `the administrator sees the login ID only in the administrator's account lookup, and X only in me`() {
        val admin = hits(everyReading.getValue("administrator")).map { it.where }.toSet()
        assertTrue(admin.isNotEmpty() && admin.all { it.startsWith("GET /api/v1/admin/accounts") }, "the administrator reads the ID only from /admin/accounts: $admin")
        val own = hits(everyReading.getValue("X")).map { it.where }.toSet()
        assertEquals(setOf("GET /api/v1/account/me"), own, "X reads the ID only from their own me")
    }

    @Test
    fun `only MeView, AdminAccountResponse and AuthMethodsView have a loginId property - reflected from every handler and read from the OpenAPI document`() {
        // (1) 모든 핸들러의 반환 타입을 재귀로 (제네릭 인자 · 필드). Any · 와일드카드로 가려진 타입은 못 본다 — 그것은 (2) 가 문서의 응답 스키마로 본다
        val seen = linkedSetOf<Class<*>>()
        fun walk(type: Type?) {
            when (type) {
                null -> {}
                is Class<*> -> {
                    if (type.isArray) return walk(type.componentType)
                    if (type.name.startsWith("java.") || type.name.startsWith("kotlin.") || type.isPrimitive || type.isEnum || type.name.startsWith("org.springframework") || type.name.startsWith("jakarta.") || !seen.add(type)) return
                    type.declaredFields.forEach { walk(it.genericType) }
                    // 상위 클래스 · 구현한 인터페이스의 칸, 그리고 sealed 의 하위 타입 (선언된 반환 타입 뒤에 가려진 것)
                    walk(type.genericSuperclass); type.genericInterfaces.forEach { walk(it) }
                    type.permittedSubclasses?.forEach { walk(it) }
                }
                is ParameterizedType -> { walk(type.rawType); type.actualTypeArguments.forEach { walk(it) } }
                is WildcardType -> (type.upperBounds + type.lowerBounds).forEach { walk(it) }
                is GenericArrayType -> walk(type.genericComponentType)
            }
        }
        val mappings = context.getBeansOfType(RequestMappingHandlerMapping::class.java).values
        val handlers = mappings.flatMap { it.handlerMethods.values }
        assertTrue(handlers.size >= 60, "the guard walks every handler of the app (${handlers.size})")
        handlers.forEach { walk(it.method.genericReturnType) }
        // 함수형 라우트(RouterFunction)의 핸들러는 위 매핑에 없다 — 지금은 소셜 로그인 둘(인가 코드 · 앱 SDK 토큰)이고 둘 다 응답은 토큰이다(아이디 없음, `AuthTokenResponse`).
        // 하나가 더 생기면 이 가드가 못 보므로 여기서 멈춰 사람이 응답을 확인하고 가드를 넓히게 한다
        assertEquals(setOf("oauthSocialAuthRoutes", "oauthSocialTokenRoutes"), context.getBeansOfType(org.springframework.web.servlet.function.RouterFunction::class.java).keys, "functional routes are not walked by this guard - check the new route's response and extend it")
        val withLoginId = seen.filter { c -> c.declaredFields.any { it.name == "loginId" } || c.declaredMethods.any { it.name == "getLoginId" } }.map { it.simpleName }.toSet()
        assertEquals(ALLOWED_TYPES, withLoginId, "response types reachable from the handlers that have a loginId property")

        // (2) OpenAPI 의 응답 스키마에서 닿는 모든 스키마 — 반환 타입이 Any 인 핸들러(@ApiResponseEnvelope 로 문서만 아는 타입)까지
        val docs = JsonPath.parse(http.get("/api/v1/docs", w.yToken, record = false).ok().body).json<Map<String, Any?>>()
        @Suppress("UNCHECKED_CAST") val schemas = ((docs["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>)
        val reachable = linkedSetOf<String>()
        fun refs(node: Any?) {
            when (node) {
                is Map<*, *> -> node.forEach { (k, v) ->
                    if (k == "\$ref" && v is String) { val name = v.substringAfterLast('/'); if (reachable.add(name)) refs(schemas[name]) } else refs(v)
                }
                is List<*> -> node.forEach { refs(it) }
            }
        }
        @Suppress("UNCHECKED_CAST") (docs["paths"] as Map<String, Map<String, Any?>>).values.flatMap { it.values }.forEach { op ->
            ((op as? Map<*, *>)?.get("responses"))?.let { refs(it) }
        }
        assertTrue(reachable.size >= 30, "the response schemas reached from the documented operations (${reachable.size})")
        val documented = reachable.filter { name -> (schemas[name]?.get("properties") as? Map<*, *>)?.containsKey("loginId") == true }.toSet()
        assertEquals(ALLOWED_TYPES, documented, "response schemas in the OpenAPI document that have a loginId property")
    }
}
