# 요청 라우팅 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 직원 요청의 `baggage: route=<서비스>:<대상>` 표시를 서비스들이 이어 넘기고, 허용 목록의 후보 · 로컬 주소로 그 요청(과 그 요청이 넣은 작업 · 메시지)만 보낸다. 로컬 대상은 관리 API 로 연 창 안에서만.

**Architecture:** `platform` 에 표시 모양(`RouteBaggage`), 새 모듈 `request-routing` 이 들어올 때(보안 체인 뒤 필터: 믿기 · 검사 · MDC · 리버스 프록시) · 나갈 때(`ExternalHttpClientCustomizer`) · 로컬 창 API · 큐 차선 구현 · Kafka 컨테이너 꾸미개 · 후보 가드를 맡는다. 창 저장은 `request-routing-jdbc`, 차선은 `job-queue-jdbc` 의 포트 `JobLanes` + 열 하나.

**Tech Stack:** Kotlin, Spring Boot(MVC, WebClient), JdbcClient + `SqlDialect`, Flyway(PostgreSQL · MySQL), spring-kafka(`ContainerCustomizer` · `RecordInterceptor`), JDK `java.net.http.HttpClient`(프록시), JDK `HttpServer`(시험 스텁), Testcontainers(두 방언 묶음), spring-security-test.

**Spec:** `docs/superpowers/specs/2026-11-16-request-routing-design.md`

## Global Constraints

- 설정 접두사 `skeleton.routing`, 모듈 `modules/request-routing` · `modules/request-routing-jdbc`, 패키지 `dev.sumin.skeleton.routing`(· `.jdbc`). `enabled` 기본 `false` — 꺼지면 빈 · 필터 · 커스터마이저 · 꾸미개 · `JobLanes` 가 하나도 없다.
- 표시: `baggage` 멤버 `route`, 값 `svc:target|svc:target`, 이름 `^[a-z][a-z0-9-]{0,39}$`, 쌍 ≤ 8, 값 ≤ 256자, 같은 서비스 두 번 금지. MDC 키 `route`.
- 권한 키 `routing.use`(표시 쓰기) · `routing.window`(창 열고 닫기), 둘 다 기본 역할 없음. 권한 선언 모듈 이름 `request-routing`.
- 오류 코드: `ROUTING.MALFORMED` 400 · `ROUTING.UNKNOWN_TARGET` 400 · `ROUTING.LOCAL_CLOSED` 403 · `ROUTING.LOOP` 508 · `ROUTING.TARGET_UNREACHABLE` 502. 창 API 검증 실패는 `COMMON.VALIDATION_FAILED`.
- 창: 표 `routing_local_windows`, 경로 `/api/v1/admin/routing/local-window`, 스위치 `skeleton.routing.admin.enabled`(기본 false → 404), `minutes` 1~480 그리고 ≤ `local.max-window`(기본 `8h`, 8h 초과 설정은 기동 실패), `reason` 1~200자, `local.cache-ttl` 기본 `5s`. 감사 action `routing.local-window.opened` · `routing.local-window.closed`, targetType `local-window`, targetId = 창 id, 사유는 감사에 싣지 않는다.
- 큐: 열 `jobs.lane varchar(40) null`, 설정 `skeleton.job-queue.lane-grace` 기본 `1h`.
- Kafka: 후보 소비 그룹 `<원래 그룹>-<instance>`, `auto.offset.reset=latest`.
- `inbound` 기본 `edge`, `proxy.timeout` 기본 `30s`.
- 모듈 규칙: 빈은 AutoConfiguration 으로만, 등록 빈 `@ConditionalOnMissingBean`, 선택 통합은 `compileOnly` + 자기 `@ConditionalOnClass` 자동 구성 + `noOptionalTest`(그 통합의 시험만 `alert` · `inquiry` 선례대로 같은 모듈을 `testImplementation`, 그 밖의 형제 모듈 시험 의존 금지), 다른 모듈 기본값 안 바꿈. 가드 메시지엔 속성 이름만. 새 파일 200줄 넘으면 나눈다. 마이그레이션은 새 `V<UTC yyyyMMddHHmmss>__<이름>.sql` 을 두 방언에 두고 `perl scripts/migrations-lock.pl` 로 잠근다(잠긴 파일은 고치지 않는다).
- 공개 레포: 예시 주소는 `example.com` · `*.a.run.app` 모양만. 개인 도메인 · 이메일 · 다른 앱 이름 금지.
- 시험은 바뀐 모듈만. 전체 스위트는 마지막에 지휘 세션이 한 번.

## Review Focus

1. **익명 · 일반 계정 요청에 표시가 실린 경우** — 표시가 조용히 사라지고 응답 · 나가는 호출 · 넣은 잡의 차선 어디에도 흔적이 없어야 한다. → Task 3 `customerRouteIsDroppedSilently`, Task 9 `customerRequestEnqueuesDefaultLane`.
2. **표시 값에 주소를 직접 넣는 경우**(`orders:https://evil.example.com`, `orders:../x`) — 400(직원) / 버림(손님), 어떤 경우에도 그 주소로 나가지 않는다. → Task 1 `rejectsUrlLikeTarget`, Task 3 `staffUrlLikeTargetIs400`.
3. **창이 요청 도중 · 다른 인스턴스에서 닫힌 경우** — 나갈 때 다시 보고 403, 다른 인스턴스는 캐시 시간 안에 따라 닫힌다. 원래 서버로 몰래 가지 않는다. → Task 4 `localTargetRecheckedAtCallTime`, Task 6 `otherInstanceFollowsAfterCacheTtl`.
4. **후보가 내려간 채 차선 잡이 쌓인 경우** — 유예 뒤 기본 일꾼이 집고, 그 전엔 안 집는다. → Task 8 `defaultWorkerTakesLaneJobAfterGrace`.
5. **SSE 같은 긴 응답을 프록시로 받는 경우** — 한 줄씩 바로 흘러야 한다. → Task 5 `streamsEventStreamLineByLine`.

---

### Task 1: 표시 모양 `RouteBaggage`(platform) + Kafka 발행 머리

**Files:**
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/routing/RouteBaggage.kt`
- Test: `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/routing/RouteBaggageTest.kt`
- Modify: `modules/event-kafka/src/main/kotlin/dev/sumin/skeleton/event/kafka/KafkaEventHeaders.kt`(`BAGGAGE = "baggage"`), `KafkaEventMessageFactory.kt`(`standardHeaders`)
- Test: `modules/event-kafka/src/test/kotlin/dev/sumin/skeleton/event/kafka/KafkaEventMessageFactoryTest.kt`
- Modify: `docs/modules/platform.md`(한 줄), `docs/modules/event-kafka.md`(머리 한 줄)

**Interfaces:**
- Produces:
  ```kotlin
  data class RouteEntry(val service: String, val target: String)
  data class Route(val entries: List<RouteEntry>) {
      fun targetOf(service: String): String?
      fun format(): String                 // "orders:alice|api:candidate"
  }
  sealed interface RouteParse { data object Absent : RouteParse; data class Valid(val route: Route) : RouteParse; data object Malformed : RouteParse }
  object RouteBaggage {
      const val HEADER = "baggage"; const val MEMBER = "route"; const val MDC_KEY = "route"
      const val MAX_ENTRIES = 8; const val MAX_LENGTH = 256
      fun parseHeader(values: List<String>): RouteParse     // 여러 줄 · 다른 멤버 · `;속성` · 퍼센트 인코딩
      fun parseValue(value: String): Route?                 // null = 모양 오류
      fun withRoute(existing: String?, route: Route): String // route 멤버만 갈아 끼운다
      fun current(): Route?                                 // MDC 에서
      fun restore(value: String?): AutoCloseable            // 유효하면 MDC 에 두고 close 때 이전 값으로
  }
  ```

- [ ] **Step 1: 실패하는 시험** — `RouteBaggageTest`:
  - `readsRouteAmongOtherMembers`: `parseHeader(listOf("userId=1, route=orders:alice;p=1", "other=x"))` == `Valid(Route(listOf(RouteEntry("orders","alice"))))`
  - `absentWhenNoRouteMember`: `parseHeader(listOf("userId=1"))` == `Absent`; `parseHeader(emptyList())` == `Absent`
  - `rejectsUrlLikeTarget`: `parseValue("orders:https://evil.example.com")` == null, `parseValue("orders:../x")` == null, `parseValue("Orders:a")` == null
  - `rejectsTooManyOrTooLong`: 쌍 9개 → null, 257자 → null, 같은 서비스 두 번 → null
  - `percentDecodes`: `parseHeader(listOf("route=orders%3Aalice"))` → `orders`/`alice`
  - `withRouteReplacesOnlyRouteMember`: `withRoute("a=1,route=x:y", r)` == `"a=1,route=orders:alice"`; `withRoute(null, r)` == `"route=orders:alice"`
  - `restoreSetsAndRestoresMdc`: 이전 MDC `route=a:b` → `restore("orders:alice").use { current() 가 alice }` 뒤 `MDC.get("route") == "a:b"`; 모양 오류 값은 MDC 를 건드리지 않는다
- [ ] **Step 2:** `./gradlew :modules:platform:test --tests '*RouteBaggageTest'` → FAIL(컴파일)
- [ ] **Step 3:** `RouteBaggage.kt` 구현.
- [ ] **Step 4:** 같은 명령 → PASS
- [ ] **Step 5: Kafka 시험** — `addsBaggageWhenRouteInMdc`(MDC `route=orders:alice` → `headers["baggage"] == "route=orders:alice"`) · `noBaggageWithoutRoute` · `eventHeaderBaggageKeepsOtherMembers`(이벤트 `headers["baggage"]="a=1"` → `"a=1,route=orders:alice"`). `./gradlew :modules:event-kafka:test --tests '*KafkaEventMessageFactoryTest'` → FAIL
- [ ] **Step 6:** `standardHeaders` 에 `RouteBaggage.current()?.let { headers[BAGGAGE] = RouteBaggage.withRoute(headers[BAGGAGE], it) }`. 같은 명령 → PASS
- [ ] **Step 7:** `./gradlew :modules:platform:test --tests '*ModuleDocumentationTest'` → PASS
- [ ] **Step 8: Commit** `feat(platform): route baggage member for request routing`

### Task 2: 모듈 뼈대 — 설정 · 허용 목록 · 창 포트 · 권한 · 문서 · 카탈로그

**Files:**
- Modify: `settings.gradle.kts`(`include(":modules:request-routing")`), `apps/workbench/build.gradle.kts`(기본 꺼진 다른 모듈처럼), `capabilities.json`(+ `perl scripts/build-capabilities.pl`), `CHANGELOG.md`(`[Unreleased]`), `docs/modules/README.md`, `docs/errors.md`(오류 코드 표가 있으면)
- Create: `modules/request-routing/build.gradle.kts` — `api(project(":modules:platform"))`, `compileOnly("org.springframework.security:spring-security-core")`, `implementation("org.springframework:spring-tx")`, webmvc · autoconfigure · validation; 시험 spring-security-test · webmvc-test; `noOptionalTest` 묶음(시큐리티 없이 뜬다)
- Create: `src/main/kotlin/dev/sumin/skeleton/routing/RequestRoutingProperties.kt`, `RouteTargets.kt`, `LocalRouteWindow.kt`(포트 둘 + 캐시 판정), `RoutingErrorCode.kt`, `RoutingPermissions.kt`, `RequestRoutingAutoConfiguration.kt`, `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `docs/modules/request-routing.md`(고정 틀), `docs/config/modules/request-routing.yml`(스펙 4.9 의 `skeleton.routing` 키 · 기본값, 선택 키는 주석 예)
- Test: `modules/request-routing/src/test/kotlin/dev/sumin/skeleton/routing/RouteTargetsTest.kt`, `LocalRouteWindowTest.kt`, `RequestRoutingAutoConfigurationTest.kt`, `src/noOptionalTest/kotlin/dev/sumin/skeleton/routing/RoutingWithoutSecurityTest.kt`

**Interfaces:**
- Consumes: Task 1 `Route`, `RouteEntry`.
- Produces:
  ```kotlin
  @ConfigurationProperties("skeleton.routing")
  data class RequestRoutingProperties(
      val enabled: Boolean = false, val service: String = "", val instance: String = "",
      val inbound: Inbound = Inbound.EDGE, val services: Map<String, Service> = emptyMap(),
      val admin: Admin = Admin(), val local: Local = Local(), val proxy: Proxy = Proxy(),
  ) {
      enum class Inbound { EDGE, INTERNAL }
      data class Service(val targets: Map<String, Target> = emptyMap())
      data class Target(val url: String, val local: Boolean = false, val headers: Map<String, String> = emptyMap())
      data class Admin(val enabled: Boolean = false)
      data class Local(val maxWindow: Duration = Duration.ofHours(8), val cacheTtl: Duration = Duration.ofSeconds(5))
      data class Proxy(val timeout: Duration = Duration.ofSeconds(30))
  }
  data class RouteTarget(val service: String, val name: String, val origin: URI, val local: Boolean, val headers: Map<String, String>)
  class RouteTargets(properties: RequestRoutingProperties) {  // 생성 때 검사: 이름 패턴 · url 은 http(s) 원점만 — 어기면 IllegalStateException(속성 이름만)
      fun knowsService(service: String): Boolean
      fun find(service: String, target: String): RouteTarget?
      val propagatedServices: Set<String>
      val hasLocalTargets: Boolean
  }
  data class LocalWindow(val id: Long, val openedBy: String, val reason: String, val openedAt: Instant, val openUntil: Instant, val closedBy: String?, val closedAt: Instant?)
  interface LocalRouteWindowStore {                     // 구현은 request-routing-jdbc (Task 7)
      fun findOpen(now: Instant): LocalWindow?          // closed_at null && open_until > now, 가장 늦게 연 것
      fun open(openedBy: String, reason: String, now: Instant, openUntil: Instant): LocalWindow   // 열린 줄을 closed_by=openedBy 로 닫고 새 줄
      fun closeAll(closedBy: String, now: Instant): List<Long>   // 닫은 창 id
  }
  interface LocalRouteWindow { fun openUntil(): Instant?; fun invalidate() {} }
  fun LocalRouteWindow.isOpen(now: Instant): Boolean
  class StoreLocalRouteWindow(store: LocalRouteWindowStore, ttl: Duration, time: TimeProvider) : LocalRouteWindow   // ttl 캐시, invalidate() 로 비움
  object ClosedLocalRouteWindow : LocalRouteWindow { override fun openUntil(): Instant? = null }   // 저장소 빈이 없을 때
  enum class RoutingErrorCode : ErrorCode { MALFORMED, UNKNOWN_TARGET, LOCAL_CLOSED, LOOP, TARGET_UNREACHABLE }
  object RoutingPermissions { const val USE = "routing.use"; const val WINDOW = "routing.window" }
  ```

- [ ] **Step 1: 실패하는 시험** — `RouteTargetsTest`: `rejectsUrlWithPath`(메시지에 `skeleton.routing.services.orders.targets.alice.url`, 값 미포함) · `rejectsNonHttpScheme` · `rejectsBadNames` · `findsTarget` · `propagatedServicesAreServiceKeys`. `LocalRouteWindowTest`(가짜 저장소 · 고정 시계): `closedWithoutStore` · `cachesForTtl`(TTL 안에는 저장소 1회 호출) · `invalidateRereads` · `closedAfterOpenUntil`. `RequestRoutingAutoConfigurationTest`: `noBeansWhenDisabled` · `declaresBothPermissions` · `closedWindowWhenNoStoreBean` · `storeBackedWindowWhenStoreBean`.
- [ ] **Step 2:** `./gradlew :modules:request-routing:test` → FAIL
- [ ] **Step 3:** 구현. AutoConfiguration `@ConditionalOnProperty("skeleton.routing.enabled", havingValue="true")`; `LocalRouteWindow` 는 저장소 빈이 있으면 `StoreLocalRouteWindow`, 없으면 `ClosedLocalRouteWindow`(둘 다 `@ConditionalOnMissingBean`).
- [ ] **Step 4:** 같은 명령 → PASS. `./gradlew :modules:request-routing:noOptionalTest` → PASS
- [ ] **Step 5: 문서 · 카탈로그** — 모듈 페이지 · 설정 조각 · 색인 · `capabilities.json` 항목(요약 「내 요청만 후보 · 로컬 서버로 보내는 머리표 라우팅」, notFor 「서비스 하나짜리 앱 · ExternalHttpClient 를 안 쓰는 호출」, keywords ko/en) → `perl scripts/build-capabilities.pl`. `./gradlew :modules:platform:test` · `./gradlew :apps:workbench:test --tests '*Snippet*'` → PASS. `perl scripts/build-capabilities.pl --check` → 0
- [ ] **Step 6: Commit** `feat(request-routing): module skeleton, targets allowlist and window port`

### Task 3: 들어올 때 — 믿기 · 검사 · MDC

**Files:**
- Create: `modules/request-routing/src/main/kotlin/dev/sumin/skeleton/routing/web/RouteInboundFilter.kt`, `web/RoutingErrorWriter.kt`(`RateLimit` 필터처럼 `ApiError` JSON 직접)
- Modify: `RequestRoutingAutoConfiguration.kt`(`routeInboundFilterRegistration`, order = 보안 필터 체인 순서(-100) + 10, 서블릿 웹 앱일 때만)
- Test: `modules/request-routing/src/test/kotlin/dev/sumin/skeleton/routing/web/RouteInboundFilterTest.kt`

**Interfaces:**
- Consumes: Task 1 `RouteBaggage.parseHeader`; Task 2 `RouteTargets`, `LocalRouteWindow.isOpen`, `RoutingErrorCode`, `RoutingPermissions.USE`; platform `OperatorAccess`, `TimeProvider`.
- Produces: `class RouteInboundFilter(properties, targets, window, access: OperatorAccess, time: TimeProvider, errors: RoutingErrorWriter) : OncePerRequestFilter` — 체인 동안 `MDC[route]`, 끝나면 지운다. Task 5 가 생성자 끝에 `proxy: RouteProxy?` 를 더한다. 직원 판정: 익명 아닌 `Authentication` → `OperatorRef(auth.name, authorities.map { removePrefix("ROLE_") })` → `access.has(ref, USE)`; 시큐리티가 없으면 아무도 직원이 아니다.

- [ ] **Step 1: 실패하는 시험** — 보안 체인 MockMvc(시험 컨트롤러가 `MDC.get("route")` 를 돌려준다):
  - `customerRouteIsDroppedSilently`: 익명 · 권한 없는 계정 + `route=orders:alice` → 200, 본문 route 없음
  - `staffRouteIsKept`: `routing.use` + `orders:candidate` → 본문 `orders:candidate`
  - `staffUrlLikeTargetIs400`: → 400 `ROUTING.MALFORMED`
  - `unknownTargetOfKnownServiceIs400`: `orders:nobody` → 400 `ROUTING.UNKNOWN_TARGET`
  - `unknownServiceIsPassedOn`: `payments:candidate` → 200, 본문 그대로
  - `localClosedIs403`: 로컬 대상 + 닫힌 창 → 403 `ROUTING.LOCAL_CLOSED`
  - `localOpenIsLogged`: 열린 창 → 200, WARN 한 줄에 행위자 id · `orders:alice`
  - `internalTrustsWithoutAuth`: `inbound=internal`, 익명 + 표시 → 본문 `orders:candidate`
  - `mdcClearedAfterRequest`
- [ ] **Step 2:** `./gradlew :modules:request-routing:test --tests '*RouteInboundFilterTest'` → FAIL
- [ ] **Step 3:** 구현(스펙 4.2 순서: 파싱 → 믿기 → 검사 → MDC → 체인).
- [ ] **Step 4:** 같은 명령 → PASS
- [ ] **Step 5: Commit** `feat(request-routing): trust staff routes at the edge`

### Task 4: 나갈 때 — baggage 싣기 · 원점 바꾸기

**Files:**
- Create: `modules/request-routing/src/main/kotlin/dev/sumin/skeleton/routing/client/RouteOutboundCustomizer.kt`
- Modify: `RequestRoutingAutoConfiguration.kt`(`@Bean @ConditionalOnMissingBean fun routeOutboundCustomizer(...): ExternalHttpClientCustomizer`)
- Test: `modules/request-routing/src/test/kotlin/dev/sumin/skeleton/routing/client/RouteOutboundCustomizerTest.kt`

**Interfaces:**
- Consumes: Task 1 `RouteBaggage.current`, `withRoute`; Task 2 `RouteTargets`, `LocalRouteWindow`; platform `ExternalHttpClientCustomizer`, `DefaultExternalHttpClient`.
- Produces: `class RouteOutboundCustomizer(targets, window, time) : ExternalHttpClientCustomizer` — `clientName in targets.propagatedServices` 일 때만 `ExchangeFilterFunction` 하나. 로컬 대상 호출마다 WARN `routing: local target used service={} target={}`.

- [ ] **Step 1: 실패하는 시험** — `HttpServer` 두 개(원래 · 후보), `skeleton.http.clients.orders.base-url` = 원래 + `/base`:
  - `routesToTargetKeepingPathAndQuery`: MDC `route=orders:candidate`, `get("orders", "/items?x=1")` → 후보가 `/base/items?x=1`, 원래 0건
  - `propagatesBaggage` · `keepsExistingBaggageMembers`(`"a=1,route=orders:candidate"`) · `noRouteGoesToBaseline` · `otherClientsGetNothing`(`toss`) · `addsTargetHeaders`
  - `localTargetRecheckedAtCallTime`: 로컬 대상, 창 닫힘 → `ApplicationException`(`ROUTING.LOCAL_CLOSED`), 두 서버 0건
  - `restoredRouteReachesTarget`: `RouteBaggage.restore("orders:candidate").use { 호출 }` → 후보(큐 일꾼 · Kafka 처리 경로와 같다)
- [ ] **Step 2:** `./gradlew :modules:request-routing:test --tests '*RouteOutboundCustomizerTest'` → FAIL
- [ ] **Step 3:** 구현 — `ClientRequest.from(req).url(UriComponentsBuilder.fromUri(req.url()).scheme(..).host(..).port(..).build(true).toUri())`.
- [ ] **Step 4:** 같은 명령 → PASS
- [ ] **Step 5: Commit** `feat(request-routing): propagate and apply routes on outbound calls`

### Task 5: 입구 서비스가 대상일 때 — 리버스 프록시

**Files:**
- Create: `modules/request-routing/src/main/kotlin/dev/sumin/skeleton/routing/web/RouteProxy.kt`
- Modify: `RouteInboundFilter.kt`, `RequestRoutingAutoConfiguration.kt`(`service` 가 있을 때 `RouteProxy` 빈, JDK `HttpClient` 하나)
- Test: `modules/request-routing/src/test/kotlin/dev/sumin/skeleton/routing/web/RouteProxyTest.kt`

**Interfaces:**
- Consumes: Task 2 `RouteTarget`; Task 3 `RouteInboundFilter`, `RoutingErrorWriter`.
- Produces: `class RouteProxy(client: java.net.http.HttpClient, timeout: Duration) { fun forward(request: HttpServletRequest, response: HttpServletResponse, target: RouteTarget, route: Route) }` — 버리는 머리: `Connection` 과 거기 적힌 것, `Keep-Alive`, `Proxy-*`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`, `Host`, `Content-Length`(길이는 몸통 발행자에). `baggage` 는 믿은 표시로 갈아 끼우고 `Via: 1.1 request-routing`. 응답은 상태 · 머리(같은 규칙) · 몸통을 읽는 대로 쓰고 flush. `RouteInboundFilter` 생성자 끝에 `proxy: RouteProxy?`.

- [ ] **Step 1: 실패하는 시험** — `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `service=api` + 후보 `HttpServer`:
  - `forwardsStaffRequestToSelfTarget`: 직원 + `route=api:candidate`, `POST /api/v1/echo?x=1` 몸통 `{"a":1}` → 후보가 경로 · 쿼리 · 몸통 · `Authorization` 받음, 클라이언트는 후보의 상태 · 본문
  - `doesNotForwardOnTargetInstance`: `instance=candidate` 앱은 직접 처리
  - `loopIs508` · `unreachableIs502` · `customerIsNeverForwarded`
  - `streamsEventStreamLineByLine`: 후보가 `text/event-stream` 한 줄 쓰고 1초 쉼 → 첫 줄을 1초 안에 받음
  - `localSelfTargetNeedsOpenWindow`: 로컬 대상 + 닫힌 창 → 403, 후보 0건
- [ ] **Step 2:** `./gradlew :modules:request-routing:test --tests '*RouteProxyTest'` → FAIL
- [ ] **Step 3:** 구현 + 필터 연결.
- [ ] **Step 4:** 같은 명령 → PASS
- [ ] **Step 5: Commit** `feat(request-routing): forward self-targeted requests to the target`

### Task 6: 로컬 창 관리 API

**Files:**
- Create: `modules/request-routing/src/main/kotlin/dev/sumin/skeleton/routing/window/LocalWindowService.kt`, `window/LocalWindowAdminController.kt`, `window/LocalWindowDtos.kt`
- Modify: `RequestRoutingAutoConfiguration.kt`(서비스 · 컨트롤러 빈: `admin.enabled=true` + 서블릿 웹 + `Authentication` 클래스, 저장소 빈 필요 — 없으면 Task 10 가드가 이름을 말한다)
- Modify: `docs/modules/request-routing.md`(경로 표 · 오류), `capabilities.json` `basePaths` 에 `/api/v1/admin/routing`
- Test: `modules/request-routing/src/test/kotlin/dev/sumin/skeleton/routing/window/LocalWindowAdminControllerTest.kt`, `LocalWindowServiceTest.kt`

**Interfaces:**
- Consumes: Task 2 `LocalRouteWindowStore`, `LocalRouteWindow.invalidate`, `RoutingPermissions.WINDOW`, `RequestRoutingProperties.local.maxWindow`; platform `OperatorAccess`, `OperatorAuditLog`, `TimeProvider`; spring-tx `TransactionOperations`.
- Produces:
  ```kotlin
  class LocalWindowService(store, window: LocalRouteWindow, audit: OperatorAuditLog, tx: TransactionOperations, time: TimeProvider, maxWindow: Duration) {
      fun status(): LocalWindow?
      fun open(actorId: String, minutes: Int, reason: String): LocalWindow   // minutes > maxWindow → ApplicationException(COMMON.VALIDATION_FAILED); 저장 + 감사 한 트랜잭션, 뒤에 invalidate()
      fun close(actorId: String)                                            // 닫은 창마다 감사 한 줄, 뒤에 invalidate()
  }
  data class OpenLocalWindowRequest(@field:Min(1) @field:Max(480) val minutes: Int, @field:NotBlank @field:Size(max = 200) val reason: String)
  data class LocalWindowResponse(val id: Long, val openedBy: String, val reason: String, val openedAt: Instant, val openUntil: Instant)
  data class LocalWindowStatusResponse(val open: Boolean, val window: LocalWindowResponse?)
  // GET → Response.ok(LocalWindowStatusResponse), POST → Response.ok(LocalWindowResponse), DELETE → @NoContentOperation
  ```

- [ ] **Step 1: 실패하는 시험** — 메모리 가짜 저장소(시험 소스에만):
  - `disabledIs404` · `anonymousIs401` · `withoutPermissionIs403`
  - `opensWindow`: `routing.window` 계정, `{minutes:30, reason:"hotfix"}` → 200, `openUntil` = 지금 + 30분, 감사 `OperatorAction(actorId, "routing.local-window.opened", "local-window", "<id>", detail openUntil)`, detail 에 사유 없음
  - `reopenClosesPrevious`: 두 번 열면 앞 창 `closedBy` = 연 사람, GET 은 새 창
  - `over480Is400` · `overConfiguredMaxIs400`(`max-window=1h`, 61분) · `blankReasonIs400`
  - `closeIs204EvenWhenNothingOpen` · `closeAuditsEachClosed`
  - `windowInvalidatedOnOpenAndClose`: 같은 인스턴스 판정이 캐시 시간 기다리지 않고 바뀐다
  - `otherInstanceFollowsAfterCacheTtl`: 같은 저장소를 보는 두 번째 `StoreLocalRouteWindow` 는 TTL 전엔 옛 값, TTL 뒤 새 값(고정 시계로)
  - `auditRollsBackWithStore`: 감사가 던지면 창 줄도 없다
- [ ] **Step 2:** `./gradlew :modules:request-routing:test --tests '*LocalWindow*'` → FAIL
- [ ] **Step 3:** 구현.
- [ ] **Step 4:** 같은 명령 → PASS
- [ ] **Step 5: Commit** `feat(request-routing): admin API to open and close the local window`

### Task 7: 창 저장소 `request-routing-jdbc`

**Files:**
- Modify: `settings.gradle.kts`, `apps/workbench/build.gradle.kts`, `capabilities.json`(+ 생성), `docs/modules/README.md`, `migrations.lock`(`perl scripts/migrations-lock.pl`), `CHANGELOG.md`
- Create: `modules/request-routing-jdbc/build.gradle.kts`(`api(project(":modules:request-routing"))`, `implementation(project(":modules:persistence-jdbc"))` — `SqlDialect`; `job-queue-jdbc` 와 같은 `postgresTest` · `mysqlTest` 묶음 over `src/dbTest`)
- Create: `src/main/kotlin/dev/sumin/skeleton/routing/jdbc/JdbcLocalRouteWindowStore.kt`, `RequestRoutingJdbcAutoConfiguration.kt`(이름을 바꾸지 않는다), imports 파일
- Create: `src/main/resources/db/migration/postgresql/V<UTC14>__routing_local_windows.sql`, `.../mysql/V<같은 시각>__routing_local_windows.sql` — `id` bigint 자동 증가 PK, `opened_by varchar(64) not null`, `reason varchar(200) not null`, `opened_at` · `open_until` not null, `closed_by varchar(64) null`, `closed_at null`(PG `timestamptz`, MySQL `datetime(6)`), 인덱스 `idx_routing_local_windows_open_until(open_until)`
- Create: `docs/modules/request-routing-jdbc.md`
- Test: `modules/request-routing-jdbc/src/dbTest/kotlin/dev/sumin/skeleton/routing/jdbc/JdbcLocalRouteWindowStoreTest.kt`

**Interfaces:**
- Consumes: Task 2 `LocalRouteWindowStore`, `LocalWindow`; `persistence-jdbc` `SqlDialect.instantParam` · `readInstant`.
- Produces: `class JdbcLocalRouteWindowStore(jdbc: JdbcClient, dialect: SqlDialect) : LocalRouteWindowStore` — `open` 은 호출자 트랜잭션(Task 6 의 `tx`) 안에서 닫기 + 넣기.

- [ ] **Step 1: 실패하는 시험**(두 방언): `findOpenIgnoresClosedAndExpired` · `findOpenPicksLatest` · `openClosesPreviousRows`(closed_by · closed_at 채움) · `closeAllReturnsIds` · `instantsRoundTripMicros`(마이크로초까지 같다).
- [ ] **Step 2:** `./gradlew :modules:request-routing-jdbc:postgresTest :modules:request-routing-jdbc:mysqlTest` → FAIL
- [ ] **Step 3:** 저장소 · 마이그레이션 구현, `perl scripts/migrations-lock.pl`.
- [ ] **Step 4:** 같은 명령 → PASS. `perl scripts/migrations-lock.pl --check` → 0. `./gradlew :modules:platform:test` → PASS(문서 · 카탈로그 · `RepositoryMigrationsTest`)
- [ ] **Step 5: Commit** `feat(request-routing-jdbc): local window table for PostgreSQL and MySQL`

### Task 8: 작업 큐 차선(`job-queue-jdbc`)

**Files:**
- Create: `modules/job-queue-jdbc/src/main/kotlin/dev/sumin/skeleton/jobqueue/jdbc/JobLanes.kt`
- Create: `modules/job-queue-jdbc/src/main/resources/db/migration/postgresql/V<UTC14>__jobs_lane.sql`(`alter table jobs add column if not exists lane varchar(40) null;`), `.../mysql/V<같은 시각>__jobs_lane.sql`(`alter table jobs add column lane varchar(40) null;`)
- Modify: `JobQueueProperties.kt`(`laneGrace: Duration = 1h`), `JdbcJobRepository.kt`(`insert` 에 `lane`, `claim` · `claimOne` 에 차선 조건, `map` 에 `lane`), `JobQueue.kt`(`Job.lane: String?`), `JdbcJobQueue.kt`(넣을 때 `lanes.enqueueLane()`), `JobQueueWorker.kt`(차선 일꾼은 정리 단계 건너뜀), `JobQueueJdbcAutoConfiguration.kt`(`@ConditionalOnMissingBean JobLanes = JobLanes.NONE`), `docs/job-queue-jdbc.md`(차선 절 · `schema.sql` 모드의 MySQL 주의), `docs/modules/job-queue-jdbc.md`, `docs/config/modules/job-queue-jdbc.yml`(`lane-grace`), `migrations.lock`, `CHANGELOG.md`
- Test: `modules/job-queue-jdbc/src/dbTest/kotlin/dev/sumin/skeleton/jobqueue/jdbc/JobLaneClaimTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  interface JobLanes {
      fun enqueueLane(): String?   // 넣는 잡의 차선 (null = 기본)
      fun workerLane(): String?    // 이 프로세스 일꾼의 차선 (null = 기본)
      companion object { val NONE: JobLanes }
  }
  // JdbcJobRepository.claim(workerId, limit, now, types, lane: String?, laneCutoff: Instant)
  //   lane == null → "and (lane is null or next_run_at < :laneCutoff)"   (laneCutoff = now - laneGrace)
  //   lane != null → "and lane = :lane"
  // claimOne 도 같은 조건
  ```

- [ ] **Step 1: 실패하는 시험**(두 방언):
  - `defaultWorkerSkipsLaneJob`: 차선 `candidate` 잡 → 기본 claim 0건, 차선 없는 잡은 집음
  - `laneWorkerTakesOnlyItsLane`: `lane=candidate` claim → 그 잡만, 차선 없는 잡 · `alice` 잡 안 집음
  - `defaultWorkerTakesLaneJobAfterGrace`: `next_run_at` 이 `lane-grace` 보다 오래 → 기본 claim 이 집음
  - `claimOneRespectsLane`
  - `enqueueStoresLaneFromJobLanes`: `enqueueLane()="candidate"` → 줄 `lane='candidate'`; `NONE` → null
  - `laneWorkerSkipsHousekeeping`: 차선 일꾼 한 판 뒤 멈춘 RUNNING 잡 · 처리기 없는 종류 잡이 그대로
  - `oldRowsWithoutLaneStillClaimed`: 마이그레이션 전 모양으로 넣은 줄(lane null)을 기본 일꾼이 집음
- [ ] **Step 2:** `./gradlew :modules:job-queue-jdbc:postgresTest :modules:job-queue-jdbc:mysqlTest --tests '*JobLaneClaimTest'` → FAIL
- [ ] **Step 3:** 구현, `perl scripts/migrations-lock.pl`.
- [ ] **Step 4:** 같은 명령 → PASS. 기존 큐 시험 묶음(`./gradlew :modules:job-queue-jdbc:test :modules:job-queue-jdbc:postgresTest :modules:job-queue-jdbc:mysqlTest`) → PASS. `perl scripts/migrations-lock.pl --check` → 0. expand/contract 검사(CI 와 같은 스크립트가 있으면 그것) → 통과
- [ ] **Step 5: Commit** `feat(job-queue-jdbc): job lanes so a candidate worker takes only its own jobs`

### Task 9: 라우팅 ↔ 비동기 잇기 — 큐 차선 구현 · Kafka 꾸미개

**Files:**
- Modify: `modules/request-routing/build.gradle.kts`(`compileOnly(project(":modules:job-queue-jdbc"))`, `compileOnly("org.springframework.kafka:spring-kafka")`; 시험엔 `alert` · `inquiry` 선례대로 같은 둘을 `testImplementation` 에 두고 부재는 `noOptionalTest` 로 증명), `noOptionalTest`(둘 다 없을 때 뜬다)
- Create: `src/main/kotlin/dev/sumin/skeleton/routing/async/RoutingJobLanes.kt`, `async/RequestRoutingJobQueueAutoConfiguration.kt`(`@ConditionalOnClass(JobLanes::class)`, `@AutoConfiguration(before = [JobQueueJdbcAutoConfiguration::class])`), `async/RouteRecordInterceptor.kt`, `async/RouteKafkaContainerCustomizer.kt`, `async/RequestRoutingKafkaAutoConfiguration.kt`(`@ConditionalOnClass(ConcurrentMessageListenerContainer::class)`, 빈 이름 `routeKafkaContainerCustomizer`), imports 파일에 둘 추가
- Modify: `docs/modules/request-routing.md`(compileOnly 행 · 앱이 자기 `ContainerCustomizer` 를 두는 경우)
- Test: `modules/request-routing/src/test/kotlin/dev/sumin/skeleton/routing/async/RoutingJobLanesTest.kt`, `RouteRecordInterceptorTest.kt`, `RouteKafkaContainerCustomizerTest.kt`, `src/noOptionalTest/.../RoutingWithoutAsyncTest.kt`

**Interfaces:**
- Consumes: Task 1 `RouteBaggage`; Task 2 `RequestRoutingProperties.service` · `instance`; Task 8 `JobLanes`; spring-kafka `ContainerCustomizer`, `RecordInterceptor`, `ConcurrentMessageListenerContainer`.
- Produces:
  ```kotlin
  class RoutingJobLanes(properties) : JobLanes {
      override fun enqueueLane() = RouteBaggage.current()?.targetOf(properties.service)   // service 가 비면 null
      override fun workerLane() = properties.instance.ifBlank { null }
  }
  class RouteRecordInterceptor(service: String, instance: String) : RecordInterceptor<Any, Any>   // 원래: 자기 서비스 표시가 있으면 null(건너뜀) · 후보: t == instance 만 통과 · 통과 레코드는 MDC route 복원, afterRecord/실패 때 지움
  class RouteKafkaContainerCustomizer(properties) : ContainerCustomizer<Any, Any, ConcurrentMessageListenerContainer<Any, Any>>
      // 인터셉터를 달고(기존 인터셉터가 있으면 CompositeRecordInterceptor 로 감싼다), 후보면 groupId = "<원래>-<instance>" · kafkaConsumerProperties["auto.offset.reset"]="latest"
  ```
  확인할 것: Spring Boot 자동 구성이 `ContainerCustomizer` 빈을 `kafkaListenerContainerFactory` 에 다는지(아니면 `ConcurrentKafkaListenerContainerFactory` 빈 후처리로) — 시험이 정한다.

- [ ] **Step 1: 실패하는 시험**:
  - `RoutingJobLanesTest`: `enqueueLaneIsSelfTarget`(service=`orders`, MDC `orders:candidate|api:x` → `candidate`) · `customerRequestEnqueuesDefaultLane`(MDC 없음 → null) · `workerLaneIsInstance` · `baselineWorkerLaneNull`
  - `RouteRecordInterceptorTest`: `baselineSkipsSelfTargetedRecord` · `baselinePassesOthers`(다른 서비스 표시 · 표시 없음) · `candidatePassesOnlyItsTarget` · `candidateSkipsUnmarked` · `restoresRouteDuringListener` · `clearsMdcAfterRecord` · `malformedBaggageTreatedAsUnmarked`
  - `RouteKafkaContainerCustomizerTest`: `candidateGetsSuffixedGroupAndLatest`(`orders-consumers` → `orders-consumers-candidate`) · `baselineKeepsGroup` · `keepsExistingInterceptor`
  - `RoutingWithoutAsyncTest`(noOptionalTest): 큐 · Kafka 없이 컨텍스트가 뜬다
- [ ] **Step 2:** `./gradlew :modules:request-routing:test --tests '*async*'` → FAIL
- [ ] **Step 3:** 구현.
- [ ] **Step 4:** 같은 명령 → PASS. `./gradlew :modules:request-routing:noOptionalTest` → PASS
- [ ] **Step 5: Commit** `feat(request-routing): candidate workers take only their own jobs and messages`

### Task 10: 배포 가드 — 후보 · 로컬 인스턴스

**Files:**
- Create: `modules/request-routing/src/main/kotlin/dev/sumin/skeleton/routing/RequestRoutingDeployGuard.kt`
- Modify: `RequestRoutingAutoConfiguration.kt`(가드 빈)
- Test: `modules/request-routing/src/test/kotlin/dev/sumin/skeleton/routing/RequestRoutingDeployGuardTest.kt`

**Interfaces:**
- Consumes: platform `DeployGuard`, `DeployContext`; Task 2 `RequestRoutingProperties`, `RouteTargets.hasLocalTargets`, `LocalRouteWindowStore`; Task 8 `JobLanes`; Task 9 빈 이름 `routeKafkaContainerCustomizer`.
- Produces: `class RequestRoutingDeployGuard(properties, targets, beans: ListableBeanFactory, environment: Environment) : DeployGuard { override val name = "request-routing" }` — 다른 모듈 타입은 클래스 이름 문자열로(`PeriodicRunCheck` 처럼): `dev.sumin.skeleton.scheduler.SkeletonScheduledTaskRegistrar`, `dev.sumin.skeleton.jobqueue.jdbc.JobQueueWorker`, `dev.sumin.skeleton.jobqueue.jdbc.JobLanes`, `org.springframework.kafka.config.KafkaListenerEndpointRegistry`, `org.flywaydb.core.Flyway`.

- [ ] **Step 1: 실패하는 시험**(`problems` / `warnings`):
  - `candidateWithoutServiceFails`(`skeleton.routing.service`)
  - `candidateWithLocalSchedulerFails`(`skeleton.scheduler.mode`); mode=`external` → 0개
  - `candidateLaneWorkerBoots`: 일꾼 빈 + 폴링 + `JobLanes.workerLane()="candidate"` → 0개
  - `candidateWorkerWithoutLaneFails`: 일꾼 빈 + 폴링 + `workerLane()` null → 문제(`skeleton.job-queue.mode`); `on-request` → 0개
  - `candidateKafkaWithCustomizerBoots`: 레지스트리 + 꾸미개 빈 → 0개
  - `candidateKafkaWithoutCustomizerFails`: 레지스트리 + 꾸미개 없음 + `auto-startup` 없음 → 문제(`spring.kafka.listener.auto-startup`); `false` → 0개
  - `candidateWithFlywayFails`(`spring.flyway.enabled`)
  - `baselineIgnoresInstanceRules`: instance 비움 → 위 조건 모두 0개
  - `maxWindowOver8hFails`(`skeleton.routing.local.max-window`) · `adminWithoutStoreFails`(`skeleton.routing.admin.enabled`)
  - `protectedLocalTargetsWithoutStoreWarns` · `jobQueueWithoutRouteKeyWarns`(`skeleton.job-queue.propagated-mdc-keys`)
  - `messagesCarryNamesNotValues`: 메시지에 주소 · 시간 값 문자열이 없다
- [ ] **Step 2:** `./gradlew :modules:request-routing:test --tests '*RequestRoutingDeployGuardTest'` → FAIL
- [ ] **Step 3:** 구현(스펙 4.8).
- [ ] **Step 4:** 같은 명령 → PASS
- [ ] **Step 5: Commit** `feat(request-routing): deploy guard for candidate and local instances`

### Task 11: 쓰는 법 문서 + 손 확인

**Files:**
- Create: `docs/request-routing.md` — ① 무엇 · 언제(핫픽스 고리) ② 원래 서버 설정(`services` · `inbound`, `internal` = 공개 주소 없는 서비스만) ③ 후보 인스턴스 설정(`instance` · `service` · `scheduler.mode: external` · `spring.flyway.enabled: false`; 큐 일꾼 · Kafka 는 켠 채로 차선 · 별도 그룹, Cloud Run `--no-traffic --tag candidate` 주소를 대상으로 — 배포 스크립트 없음) ④ 로컬 고리: Cloudflare 고정 이름 터널 + Access 서비스 토큰(대상 `headers`), 운영 DB · 비밀 사용 · 쓰기가 실제로 일어난다는 경고, `skeleton.env=prod` ⑤ 창: `POST/GET/DELETE /api/v1/admin/routing/local-window`(curl 예), 서비스마다 연다 — 서비스 목록을 도는 셸 예, 최대 8h, 감사(`OperatorAuditLog` 빈) ⑥ 큐(`propagated-mdc-keys: [traceId, route]`, `lane-grace`) · Kafka(별도 그룹 · 건너뛴 레코드 · 앱 꾸미개와 겹칠 때) ⑦ 한계(스펙 7절)
- Modify: `capabilities.json` 두 항목 `docs` 에 `docs/request-routing.md` → `perl scripts/build-capabilities.pl`; 모듈 페이지에서 링크; `CHANGELOG.md` 보강

**Interfaces:**
- Consumes: Task 1~10 의 설정 키 · 타입 · 경로(문서가 코드에 없는 이름을 쓰면 `ModuleDocumentationTest` 가 잡는다).

- [ ] **Step 1:** 문서 작성. 예시 주소는 `example.com` · `*.a.run.app` 만.
- [ ] **Step 2:** `./gradlew :modules:platform:test` · `perl scripts/build-capabilities.pl --check` → PASS
- [ ] **Step 3: 손 확인(QA 몫)** — 모든 모듈을 싣는 데모 앱 `apps/workbench` 를 두 번 띄운다(스타터 `apps/api` 에는 모듈을 더하지 않는다 · 설정은 실행 인자 · 환경변수로만, 커밋하지 않는다). 원래: `enabled=true`, `service=api`, `admin.enabled=true`; 후보: 같은 것 + `instance=candidate` + 4.8 설정, 다른 포트. 대상 `api.candidate.url` = 후보 포트. ① 전권 계정 + `baggage: route=api:candidate` 로 `GET /api/v1/auth/me` → 후보 로그에 그 요청(traceId 같음) ② 일반 계정 + 같은 머리 → 원래 로그에만 ③ 대상을 `local: true` 로 바꿔 닫힌 창 → 403, `POST local-window {minutes:5, reason}` → 통과, `DELETE` 뒤 5초 안에 403 ④ 후보를 스케줄러 local 모드로 띄우면 기동 실패 메시지에 `skeleton.scheduler.mode`. 결과를 보고에 적는다.
- [ ] **Step 4: Commit** `docs(request-routing): how to route your own requests to a candidate or local server`
