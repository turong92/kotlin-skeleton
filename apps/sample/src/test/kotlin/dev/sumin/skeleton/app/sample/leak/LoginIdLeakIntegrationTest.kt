package dev.sumin.skeleton.app.sample.leak

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.TestcontainersConfiguration
import dev.sumin.skeleton.app.sample.notes.SampleIntegrationTest
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
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
        val STREAMS = setOf("/api/v1/notifications/sse")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var registry: NotificationSubscriptionRegistry
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var context: ApplicationContext

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

    /** 경로 변수 → 여정에서 얻은 값들. `{id}` 는 경로마다 뜻이 달라 접두사로 가른다 */
    private val candidates: Map<String, List<String>> by lazy {
        val eventId = http.get("/api/v1/notifications?size=100", w.yToken, record = false).ok().read<List<String>>("$.values[*].eventId").first()
        mapOf(
            "code" to listOf("general"),
            "postId" to listOf(w.yPost, w.postA, w.postB, w.postDraft).map { it.toString() },
            "commentId" to listOf(w.c0, w.c1, w.c2, w.c3, w.c4).map { it.toString() },
            "eventId" to listOf(eventId),
            "type" to listOf("terms", "privacy"),
        )
    }

    private val idByPrefix: Map<String, List<String>> by lazy {
        mapOf(
            "/api/v1/notes/" to listOf(createNote(w.yToken, "Y note ${w.nonce}"), createNote(w.xToken, "X note ${w.nonce}")),
            "/api/v1/author-profiles/" to listOf(w.yProfile.id, w.a.id, w.b.id),
            "/api/v1/admin/accounts/" to listOf(w.xAcc, w.yAcc),
            "/api/v1/admin/author-profiles/" to listOf(w.a.id, w.yProfile.id),
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
        return paths
    }

    private class Reading(val reader: String, val captured: Captured)

    private fun readAll(reader: String, token: String?, paths: List<String>): List<Reading> =
        paths.map { Reading(reader, http.get(it, token, record = false)) }

    private fun hits(readings: List<Reading>) = LeakScan.forbiddenHits(readings.map { it.captured }, forbidden)

    private fun resolved(): Map<String, List<String>> {
        val templates = documentedGets().filter { it !in STREAMS }
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

    /** 독자별로 OpenAPI 의 GET 전부를 한 번씩 (한 번만 읽는다 — 아래 두 시험이 나눠 쓴다) */
    private val everyReading: Map<String, List<Reading>> by lazy {
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

        // 훑은 것이 비어 있지 않다: 경로마다 적어도 한 독자가 2xx 를 받았다 (잘못 채운 값으로 404 만 받아 아무것도 안 본 경로가 없다)
        val reached = everyReading.values.flatten().filter { it.captured.status in 200..299 }.map { it.captured.label.substringBefore('?').removePrefix("GET ") }.toSet()
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
        // 함수형 라우트(RouterFunction)의 핸들러는 위 매핑에 없다 — 지금은 소셜 로그인 하나뿐이고 응답은 토큰이다(아이디 없음, `AuthTokenResponse`).
        // 하나가 더 생기면 이 가드가 못 보므로 여기서 멈춰 사람이 응답을 확인하고 가드를 넓히게 한다
        assertEquals(setOf("oauthSocialAuthRoutes"), context.getBeansOfType(org.springframework.web.servlet.function.RouterFunction::class.java).keys, "functional routes are not walked by this guard - check the new route's response and extend it")
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
