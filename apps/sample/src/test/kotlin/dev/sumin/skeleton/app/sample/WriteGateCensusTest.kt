package dev.sumin.skeleton.app.sample

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RequestPredicate
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.core.io.Resource
import java.util.function.Function

/**
 * **변경 엔드포인트 전수** (스펙 §9.3): 이 앱의 변경 메서드(`POST` · `PUT` · `PATCH` · `DELETE`) 핸들러를 **전부** 뽑아, 주소 없는 계정(소셜 첫 로그인 같은, 아이디는 정했다)에게
 * 열린 것이 [OPEN_TO_ADDRESS_LESS] 와 **정확히 같은지** 본다. 새 변경 엔드포인트는 누가 목록에 적지 않는 한 막힌다 — 이 목록이 늘면 시험이 깨지고, 늘린 사람이 이유를 적는다.
 * 결제(`/api/v1/payments` 아래의 사용자 변경 메서드: 주문 만들기 · 승인 · 포기 · 환불 요청 · 환불 요청 거둠 · 구독 만들기 · 카드 등록 + 첫 결제 · 해지 예약)는 주소 없는 계정에게 막히는지 이름으로 한 번 더 본다. 결제사 웹훅(`/api/v1/payments/webhooks/{provider}[/{token}]`)은 익명 호출이라 계정 게이트와 무관하다.
 *
 * **무엇을 보나**: 목록은 손으로 쓰지 않고 앱 컨텍스트에서 뽑는다 — `RequestMappingHandlerMapping`(컨트롤러. 조건부로 등록된 것 포함: 아이디가 꺼지면 사라지는 `LoginIdController` 가 여기 보인다)과
 * 함수형 라우트(`RouterFunction` 빈. 소셜 로그인 `POST /api/v1/auth/social/{provider}/login` 이 여기다). "열렸나"는 규칙 목록을 읽지 않고 **진짜 요청**으로 본다: 핸들러마다 새 주소 없는 계정이
 * 요청을 보내 `403 ACCOUNT.EMAIL_REQUIRED` 가 아니면 열린 것이다 (본문은 `{}` — 핸들러가 400 · 404 로 답해도 게이트는 지난 것).
 *
 * **못 덮는 것** (§18.2 실측): ① 이 두 길(MVC 매핑 · 함수형 라우트)로 등록되지 않은 경로 — 서블릿 · 필터가 직접 받는 요청, 액추에이터(`/api/` 밖이라 이 게이트의 범위도 아니다), WebSocket 연결(소켓의 메시지는
 * 이 게이트가 보지 않는다, 스펙 §9.3) — 은 보이지 않는다. 그런 경로가 변경 요청을 받으면 [HAND_WRITTEN] 에 `METHOD /경로` 로 **손으로 더하고** 이 시험이 같은 방식으로 찌른다(지금은 없다).
 * ② 함수형 라우트는 술어의 글자(`toString`)에서 메서드 · 경로를 읽는다 — 읽지 못하면 조용히 건너뛰지 않고 시험이 실패한다.
 */
// 구독을 켠다 — 구독 경로가 전수에 보이고(주소 없는 계정에게 막히는지 본다) 가짜 결제사가 빌링 능력을 낸다
@SpringBootTest(properties = ["spring.config.import=classpath:test-seeds.yml", "skeleton.payment.subscription.enabled=true"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, dev.sumin.skeleton.app.sample.notes.SampleIntegrationTest.FakeStorageConfiguration::class, dev.sumin.skeleton.app.sample.cheer.CheerIntegrationBase.Provider::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WriteGateCensusTest {
    companion object {
        /** 변경 메서드로 부르는 경로 중 주소 없는 계정에게 열려 있어야 하는 것 — `METHOD /템플릿` (핸들러의 경로 템플릿 그대로) */
        val OPEN_TO_ADDRESS_LESS: Set<String> = setOf(
            // 계정이 빠져나가는 길 · 이메일 등록 (코드에 고정 — GatePathRule.BUILT_IN)
            "POST /api/v1/account/email/change",
            "POST /api/v1/account/email/change/confirm",
            "POST /api/v1/account/discard",
            "POST /api/v1/account/delete",
            "POST /api/v1/account/delete/confirmation",
            // 아이디 정하기와 실시간 확인 (GatePathRule.LOGIN_ID_PATHS)
            "POST /api/v1/account/login-id",
            "POST /api/v1/account/login-id/availability",
            // 쓰기 때 방식의 고정 허용: 자기 계정 살림 — 언어 · 시간대 · 닉네임 (GatePathRule.WRITE_MODE_PATHS)
            "PATCH /api/v1/account/me",
            // 소셜 로그인 수단의 연결 · 해제 - 다시 인증(이미 연결된 제공자의 새 코드)은 서버가 그대로 요구한다 (GatePathRule.WRITE_MODE_PATHS)
            "POST /api/v1/account/identities/social/{provider}",
            "DELETE /api/v1/account/identities/{id}",
            // 이 앱이 yml(email.extra-exclude-paths)로 연 사소한 쓰기 · 읽기처럼 보이는 POST
            "PATCH /api/v1/notifications/{eventId}/read",
            "PATCH /api/v1/notifications/read-all",
            "POST /api/v1/storage/presign-download",
        )

        /** `/api/v1/auth/` · `/api/v1/legal/` 아래는 접두사로 열려 있다 (`GatePathRule.BUILT_IN`: 로그인 · 로그아웃 · 세션 · 약관 동의) — 그 아래에 실제로 있는 변경 경로를 이름으로 고정한다 */
        val OPEN_PREFIXES = listOf("/api/v1/auth/", "/api/v1/legal/")
        val OPEN_UNDER_PREFIXES: Set<String> = setOf(
            "DELETE /api/v1/auth/sessions", "DELETE /api/v1/auth/sessions/{id}", "POST /api/v1/auth/login", "POST /api/v1/auth/logout",
            "POST /api/v1/auth/magic-link/redeem", "POST /api/v1/auth/magic-link/request", "POST /api/v1/auth/refresh", "POST /api/v1/auth/social/{provider}/login",
            "POST /api/v1/auth/verify-email", "POST /api/v1/legal/consents", "POST /api/v1/legal/consents/{type}/withdraw",
        )

        /**
         * `/api/` 밖에서 변경 요청을 받는 경로 — 이 게이트의 범위 밖이다.
         *  · 서블릿 컨테이너의 오류 디스패치가 향하는 `BasicErrorController`(사용자 요청이 직접 닿는 곳이 아니다)
         *  · 작업 큐의 깨우기 주소 `POST /internal/jobs/drain`(`job-queue-jdbc`): 사람 계정의 쓰기가 아니다 — 계정이 아니라 공유 비밀값 헤더(`X-Wake-Secret`)로 인증하는 Cloud Scheduler 같은 밖의 호출자용이라 이메일 · 아이디 게이트와 무관하다.
         *    기본(폴링) 모드에서는 요청마다 404 이고, 이 목록에 이름으로 올려 둔다 — 깨우기 경로가 바뀌거나 `/api/` 밖에 다른 변경 경로가 생기면 여기서 깨져 누가 알게 된다
         */
        val OUTSIDE_THE_API: Set<String> = setOf("POST /error", "PUT /error", "PATCH /error", "DELETE /error", "POST /internal/jobs/drain")

        /** MVC 매핑 · 함수형 라우트로 보이지 않는데 변경 요청을 받는 경로 (`METHOD /경로`) — 지금은 없다. 앱이 서블릿 · 필터로 변경 요청을 받으면 여기 더한다 */
        val HAND_WRITTEN: List<String> = emptyList()

        private val MUTATING = setOf("POST", "PUT", "PATCH", "DELETE")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var accounts: AccountRepository
    @Autowired lateinit var authAccounts: AuthAccountRepository
    @Autowired lateinit var tokens: AuthTokenResponseFactory

    private data class Route(val method: String, val template: String) {
        override fun toString() = "$method $template"
    }

    /** 컨트롤러 핸들러의 변경 메서드 경로 — 메서드를 안 정한 매핑(전부)은 변경 메서드 넷으로 센다 */
    private fun mappedRoutes(): Set<Route> = context.getBeansOfType(RequestMappingHandlerMapping::class.java).values.flatMap { mapping ->
        mapping.handlerMethods.keys.flatMap { info: RequestMappingInfo ->
            val patterns = info.pathPatternsCondition?.patternValues ?: info.patternsCondition?.patterns.orEmpty()
            val methods = info.methodsCondition.methods.map(RequestMethod::name).ifEmpty { MUTATING.toList() }
            patterns.flatMap { p -> methods.filter { it in MUTATING }.map { Route(it, p) } }
        }
    }.toSet()

    /** 함수형 라우트 — 술어의 글자에서 메서드 · 경로를 읽는다. 읽지 못한 것은 [unreadable] 에 모아 시험이 실패하게 한다 */
    private fun functionalRoutes(unreadable: MutableList<String>): Set<Route> {
        val found = linkedSetOf<Route>()
        val visitor = object : RouterFunctions.Visitor {
            override fun startNested(predicate: RequestPredicate) { unreadable += "nested route $predicate" }
            override fun endNested(predicate: RequestPredicate) {}
            override fun route(predicate: RequestPredicate, handlerFunction: HandlerFunction<*>) {
                val text = predicate.toString()
                val methods = Regex("\\b(GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS)\\b").findAll(text).map { it.value }.toSet()
                val path = Regex("(/[^\\s()&|]*)").find(text)?.value
                if (path == null) unreadable += text else methods.filter { it in MUTATING }.forEach { found += Route(it, path) }
                if (methods.isEmpty()) unreadable += text   // 메서드가 없는 술어는 모든 메서드다 — 읽었다고 하지 않는다
            }
            override fun resources(lookupFunction: Function<org.springframework.web.servlet.function.ServerRequest, java.util.Optional<Resource>>) {}
            override fun attributes(attributes: MutableMap<String, Any>) {}
            override fun unknown(routerFunction: RouterFunction<*>) { unreadable += "unknown router function $routerFunction" }
        }
        context.getBeansOfType(RouterFunction::class.java).values.forEach { it.accept(visitor) }
        return found
    }

    private fun handWritten(): Set<Route> = HAND_WRITTEN.map { entry -> entry.trim().split(Regex("\\s+")).let { Route(it[0].uppercase(), it[1]) } }.toSet()

    private fun allRoutes(): Set<Route> {
        val unreadable = mutableListOf<String>()
        val routes = mappedRoutes() + functionalRoutes(unreadable) + handWritten()
        assertEquals(emptyList(), unreadable, "every functional route must be readable as METHOD + path - add what cannot be read to HAND_WRITTEN")
        return routes
    }

    /** 새 주소 없는 계정 하나(아이디는 정했다) — 소셜 첫 로그인 뒤 아이디를 정한 모습. 요청마다 새로 만든다: 열린 길 중에는 계정을 지우는 것도 있다 */
    private fun addressLessWithLoginId(): String {
        val id = "acc_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val now = Instant.now()
        check(accounts.insert(Account(id, null, false, AccountStatus.ACTIVE, setOf("USER"), "Census ${id.takeLast(4)}", null, null, now, now, lastLoginAt = now), listOf(Identity("idn_${id.takeLast(12)}", id, "kakao", "kakao-$id", true, createdAt = now))))
        val bearer = tokens.issue(authAccounts.findBy(AccountIdentifier(accountId = id))!!).accessToken
        val claim = mvc.perform(
            MockMvcRequestBuilders.post("/api/v1/account/login-id").with { it.remoteAddr = TestClientIps.fresh(); it }.contentType(MediaType.APPLICATION_JSON).content("""{"loginId":"census.${id.takeLast(10)}"}""").header("Authorization", "Bearer $bearer"),
        ).andReturn().response
        assertEquals(200, claim.status, "setup: the address-less account chose its login ID: ${claim.contentAsString}")
        return bearer
    }

    private enum class Verdict { BLOCKED, OPEN }

    /** 이 경로로 요청을 보낸다 — `403 ACCOUNT.EMAIL_REQUIRED` 면 막힘, 그 밖이면 게이트를 지난 것(핸들러가 400 · 404 · 409 … 무엇으로 답하든) */
    private fun probe(route: Route): Pair<Verdict, String> {
        val path = route.template.replace(Regex("\\{[^/}]*}"), "x")
        val response = mvc.perform(
            MockMvcRequestBuilders.request(HttpMethod.valueOf(route.method), path)
                .contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("Authorization", "Bearer ${addressLessWithLoginId()}")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andReturn().response
        val code = runCatching { JsonPath.read<String>(response.contentAsString, "$.code") }.getOrNull()
        assertTrue(code != "ACCOUNT.LOGIN_ID_REQUIRED" && code != "LEGAL.RECONSENT_REQUIRED", "$route: setup is wrong, another gate answered ($code)")
        val verdict = if (response.status == 403 && code == "ACCOUNT.EMAIL_REQUIRED") Verdict.BLOCKED else Verdict.OPEN
        return verdict to "${response.status} ${code ?: ""}".trim()
    }

    private fun isOpenByPrefix(route: Route) = OPEN_PREFIXES.any { route.template.startsWith(it) }

    private lateinit var census: Map<Route, Pair<Verdict, String>>

    @BeforeAll
    fun takeTheCensus() {
        census = allRoutes().sortedWith(compareBy({ it.template }, { it.method })).associateWith { probe(it) }
    }

    @Test
    fun `the census sees conditional controllers and functional routes, not only the always-on ones`() {
        val seen = census.keys.map { it.toString() }.toSet()
        // 조건부 컨트롤러 (아이디가 켜져야 등록된다) · 함수형 라우트 (auth-social 이 RouterFunction 으로 등록한다) · 모듈의 평범한 컨트롤러 · 이 앱의 컨트롤러
        listOf(
            "POST /api/v1/account/login-id", "POST /api/v1/account/login-id/forgot", "POST /api/v1/auth/social/{provider}/login",
            "POST /api/v1/boards/{code}/posts", "PATCH /api/v1/notifications/read-all", "POST /api/v1/notes", "POST /api/v1/storage/presign",
        ).forEach { assertTrue(it in seen, "the census must see $it (saw ${seen.size} routes)") }
        assertTrue(census.size >= 30, "a census of ${census.size} routes is too small to be everything")
    }

    @Test
    fun `an account without an address can use exactly the listed write endpoints - every other write is refused with ACCOUNT_EMAIL_REQUIRED`() {
        // 이 게이트의 범위는 /api/ 아래다 — 밖의 변경 경로는 게이트가 보지 않으니 이름으로 고정해 둔다 (밖에 새 변경 경로가 생기면 여기서 깨져 누가 알게 된다)
        val outside = census.keys.filter { !it.template.startsWith("/api/") }.map { it.toString() }.sorted()
        assertEquals(OUTSIDE_THE_API.sorted(), outside, "write endpoints outside /api/ (the gate does not look there)")
        val open = census.filter { it.value.first == Verdict.OPEN && it.key.template.startsWith("/api/") }.keys
        val unexpected = open.filter { it.toString() !in OPEN_TO_ADDRESS_LESS && !isOpenByPrefix(it) }
        val missing = OPEN_TO_ADDRESS_LESS.filter { listed -> open.none { it.toString() == listed } }
        val prefixOpen = open.filter(::isOpenByPrefix).map { it.toString() }.sorted()
        assertEquals(emptyList(), unexpected.map { "$it -> ${census.getValue(it).second}" }, "write endpoints open to an address-less account that are not in OPEN_TO_ADDRESS_LESS")
        assertEquals(emptyList(), missing, "listed endpoints that are not open (or do not exist)")
        // /auth/ · /legal/ 아래는 접두사로 열려 있다 — 어떤 변경 경로가 거기 있는지는 이름으로 고정한다
        assertEquals(OPEN_UNDER_PREFIXES.sorted(), prefixOpen, "the write endpoints under /api/v1/auth/ and /api/v1/legal/")
        // 닫힌 쪽이 비어 있지 않다: 글 · 댓글 · 반응 · 프로필 · 첨부 · 노트 · 소셜 연결 · 비밀번호 · 관리자 …
        val blocked = census.filter { it.value.first == Verdict.BLOCKED }.keys.map { it.toString() }
        listOf("POST /api/v1/boards/{code}/posts", "POST /api/v1/boards/{code}/posts/{postId}/comments", "PUT /api/v1/boards/{code}/posts/{postId}/reactions", "POST /api/v1/author-profiles",
            "POST /api/v1/notes", "POST /api/v1/storage/presign", "POST /api/v1/storage/multipart/start", "POST /api/v1/account/password/change", "POST /api/v1/account/contact-email",
        ).forEach { assertTrue(it in blocked, "$it must be refused for an account without an address") }
        println("WRITE_GATE_CENSUS open=${(open.map { it.toString() }).sorted()} blocked=${blocked.size} total=${census.size}")
    }

    @Test
    fun `payments - the user write endpoints are refused for an account without an address, the anonymous webhook is not account-gated`() {
        val webhookPrefix = "/api/v1/payments/webhooks/"
        val payments = census.filterKeys { it.template.startsWith("/api/v1/payments") && !it.template.startsWith(webhookPrefix) }
        // 사용자 엔드포인트의 변경 메서드가 전부 막혀 있어야 한다 (영수증 · 환불 안내가 갈 주소가 없는 결제를 만들지 않는다, 스펙 §9.3)
        assertTrue(payments.isNotEmpty(), "the sample assembles the payment ledger, so its write endpoints must be in the census")
        // 구독 쓰기 셋(만들기 · 카드 등록 + 첫 결제 · 해지 예약)도 전수에 있고 같은 문에 막힌다 — 새 구독 경로를 목록에서 빠뜨리지 않았다는 증거
        listOf("POST /api/v1/payments/subscriptions", "POST /api/v1/payments/subscriptions/{id}/activate", "POST /api/v1/payments/subscriptions/{id}/cancel").forEach { name ->
            assertTrue(payments.keys.any { it.toString() == name }, "$name must be in the census")
        }
        payments.forEach { (route, result) -> assertEquals(Verdict.BLOCKED, result.first, "$route must be refused for an account without an address (it answered ${result.second})") }
        // 웹훅은 결제사가 부르는 익명 호출이다 — 계정 게이트(이메일 · 아이디)가 보지 않는다. (census 는 토큰을 실어 보내므로 막힘으로 나올 뿐이다 — 익명으로 보내 게이트가 아닌 것이 답하는지 본다)
        val webhooks = census.keys.filter { it.template.startsWith(webhookPrefix) }
        assertTrue(webhooks.isNotEmpty(), "the webhook endpoints must be in the census")
        webhooks.forEach { route ->
            val response = mvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(route.method), route.template.replace(Regex("\\{[^/}]*}"), "x")).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response
            val code = runCatching { JsonPath.read<String>(response.contentAsString, "$.code") }.getOrNull()
            assertTrue(code?.startsWith("ACCOUNT.") != true && response.status != 401, "$route is anonymous and must not be answered by an account gate or the login wall (${response.status} $code)")
        }
        println("WRITE_GATE_CENSUS payments=${payments.keys.map { it.toString() }} webhooks=${webhooks.map { it.toString() }}")
    }
}
