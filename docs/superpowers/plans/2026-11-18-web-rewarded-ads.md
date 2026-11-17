# 웹 보상형 광고 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 웹 GPT 보상형 광고의 `rewardedSlotGranted` 를 서버가 광고 세션으로 받아 남용 한도 안에서 한 번만 보상하고(기본은 point 적립, 아니면 1회용 토큰), react 에서 동의 → 재생 → 보상까지를 훅 · 컴포넌트로 감싼다.

**Architecture:** kotlin 은 `ads-web-rewarded`(서비스 · 컨트롤러 · 포트, 보상은 platform `RewardIssuer`)와 `ads-web-rewarded-jdbc`(세션 · 하루 카운터 표) 두 모듈, 「하루」 계산은 platform `DayWindow` 하나를 `ads-admob` 과 같이 쓴다. react 는 `@skeleton/ads` — 프레임워크 없는 흐름 컨트롤러 + 훅 + 동의 창, GPT 는 주입(진짜 로더 / 가짜).

**Tech Stack:** Kotlin, Spring Boot MVC, JdbcClient + `SqlDialect`, Flyway(PostgreSQL · MySQL), Testcontainers / TypeScript, React 19, vitest, Storybook.

**Spec:** `docs/superpowers/specs/2026-11-18-web-rewarded-ads-design.md` (API 계약 · 설정 · 규칙의 정본 — 여기서 값을 다시 정하지 않는다)

**줄기와 순서:** kotlin 줄기 K1 → (K2 ∥ K3), react 줄기 R1 → R2. 두 줄기는 서로 기다리지 않는다 — 계약은 스펙 표 하나. 각 줄기의 카탈로그 항목은 짝을 `null` 로 두고, 셋 다 끝난 뒤 J1 이 두 카탈로그를 잇는다.

**선행 조건(K1):** `ads-admob` 하루 상한 작업(main 체크아웃에 커밋 전으로 있는 `AdmobProperties.dailyLimit/dayZone/dayWindow/limitAt`, `PointEarnLimit`, `earnWithin`)이 main 에 들어간 뒤 시작한다. R1 · R2 는 바로 시작해도 된다.

**커밋:** 각 작업의 끝은 「바뀐 파일 목록 보고」까지 — 커밋은 지휘 세션이 한다.

## Global Constraints

- kotlin 모듈 `modules/ads-web-rewarded` · `modules/ads-web-rewarded-jdbc`, 패키지 `dev.sumin.skeleton.ads.webrewarded`(· `.jdbc`), 설정 접두사 `skeleton.ads-web-rewarded`, 기본 경로 `/api/v1/ads/web-rewarded`, 오류 코드 접두사 `ADS_WEB_REWARDED.`, 세션 id 접두사 `wrs_`, 지급 `sourceType` `"ADS_WEB_REWARDED"`.
- 설정 기본값(스펙 그대로): `enabled=false` · `session-ttl=15m` · `min-elapsed=7s` · `account-daily-limit`/`ip-daily-limit` 없음 · `day-zone=UTC` · `reward-type` 없음(→ 토큰 모드) · `reward-params={}` · `token-ttl=10m`.
- 오류 코드: `DAILY_LIMIT` 409 · `SESSION_NOT_FOUND` 404 · `SESSION_USED` 409 · `SESSION_EXPIRED` 409 · `TOO_EARLY` 409 · `REWARD_UNAVAILABLE` 503.
- 모듈 규칙: 빈은 AutoConfiguration 으로만, 등록 빈은 `@ConditionalOnMissingBean`, 다른 모듈 기본값을 바꾸지 않는다, 형제 모듈 `testImplementation(project(...))` 금지, 새 파일 200줄 넘으면 나눈다, 모듈 문서 `docs/modules/<module>.md`(고정 틀) + `docs/config/modules/<module>.yml` + `CHANGELOG.md` `[Unreleased]` + `capabilities.json`(→ `perl scripts/build-capabilities.pl`). 마이그레이션은 `./gradlew newMigration` 이름 규칙, 두 방언, `perl scripts/migrations-lock.pl` 로 잠근다.
- react 패키지 `packages/ads`(`@skeleton/ads`): `exports` `.` = `./src/index.ts`, `test` · `typecheck` 스크립트, barrel 만 공개, README API 표, 문구는 prop(기본 영어), 색 · 간격은 의미 토큰, import 시 브라우저 전역 접근 금지, `any` 금지, `import.meta.env` 금지.
- 소리: 진짜 GPT 는 스토리 · 테스트 · CI 어디서도 싣지 않는다(가짜만). `makeRewardedVisible()` 는 사용자 클릭 핸들러 경로에서만.
- 공개 레포: 문서 · 예시에 다른 앱 이름 · 개인 도메인 · 이메일 금지, 예시 주소는 `example.com`.
- 시험은 바뀐 모듈 · 패키지만. 전체 스위트는 마지막에 지휘 세션이 한 번(실행 위치 · `kscope.sh` 등은 지휘 세션 관례).

## Review Focus

1. **같은 세션에 보상 요청이 동시에 두 번**(더블 클릭 · 재시도) — 지급은 한 번, 다른 하나는 `SESSION_USED`. → K3 `concurrentGrantsOfOneSessionPayOnce`.
2. **상한 직전에 서로 다른 세션 둘이 동시에 보상 요청** — 하나만 성공, 카운터는 정확히 max. → K3 `concurrentGrantsAtLimitOnlyOneWins`.
3. **지급기(point)가 실패하거나 DB 가 끊김** — 세션은 열린 채, 카운터는 그대로, 503, 다시 요청하면 지급. → K3 `issuerFailureRollsBackSessionAndCounter`.
4. **남의 세션 id · 남의 토큰** — 404 / null, 아무 상태도 안 바뀐다. → K2 `grantOfAnotherAccountsSessionIs404`, K3 `redeemRefusesOtherAccountOrPurpose`.
5. **손짓 없이 광고를 띄우거나 끝난 뒤 저절로 다음 광고** — `makeRewardedVisible` 미호출, 새 슬롯 없음. → R1 `watchWithoutUserActivationDoesNotShow`, R1 `noAutomaticRestartAfterEnd`.

---

### Task K1 (kotlin): `DayWindow` + `ads-web-rewarded` 핵심(설정 · 모델 · 포트 · 서비스)

**Files:**
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/time/DayWindow.kt`
- Test: `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/time/DayWindowTest.kt`
- Modify: `modules/ads-admob/src/main/kotlin/dev/sumin/skeleton/ads/admob/AdmobProperties.kt` (`dayWindow` 가 `DayWindow` 를 돌려준다 — 구조 분해 `val (from, to)` 호출자는 그대로 컴파일된다), `docs/modules/platform.md` 한 줄
- Create: `modules/ads-web-rewarded/build.gradle.kts`, `.../webrewarded/WebRewardedProperties.kt`, `WebRewardModels.kt`, `WebRewardStore.kt`, `WebRewardService.kt`, `WebRewardedErrors.kt`
- Modify: `settings.gradle.kts`(`include(":modules:ads-web-rewarded")` — `-jdbc` 의 include 는 K3)
- Test: `modules/ads-web-rewarded/src/test/kotlin/dev/sumin/skeleton/adswebtest/WebRewardTestSupport.kt`(메모리 가짜 저장소 · 기록하는 가짜 `RewardIssuer` · 고정 시계), `.../webrewarded/WebRewardServiceTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  // platform
  data class DayWindow(val from: Instant, val to: Instant) {
      companion object { fun containing(now: Instant, zone: ZoneId): DayWindow }   // zone 의 자정 ~ 다음 자정 [from, to)
  }
  // ads-web-rewarded
  @ConfigurationProperties("skeleton.ads-web-rewarded")
  data class WebRewardedProperties(
      val enabled: Boolean = false, val sessionTtl: Duration = Duration.ofMinutes(15), val minElapsed: Duration = Duration.ofSeconds(7),
      val accountDailyLimit: Long? = null, val ipDailyLimit: Long? = null, val dayZone: String = "UTC",
      val rewardType: String? = null, val rewardParams: Map<String, String> = emptyMap(), val tokenTtl: Duration = Duration.ofMinutes(10),
  )  // init 검증은 스펙 「검증 규칙」
  data class WebRewardSession(val id: String, val accountId: String, val purpose: String, val clientKey: String,
      val openedAt: Instant, val expiresAt: Instant, val grantedAt: Instant?, val rewardType: String?, val rewardRef: String?,
      val tokenExpiresAt: Instant?, val redeemedAt: Instant?)
  enum class WebRewardScope { ACCOUNT, IP }
  data class WebRewardGranted(val session: WebRewardSession, val token: String?)   // token 은 토큰 모드에서만, 원문은 이 한 번만
  interface WebRewardStore {
      fun <T> inTransaction(block: () -> T): T
      fun insert(session: WebRewardSession)
      fun lockOwned(sessionId: String, accountId: String): WebRewardSession?          // 잠금(for update), 남의 것 = null
      fun grantedOn(scope: WebRewardScope, subject: String, dayStart: Instant): Long
      fun incrementWithin(scope: WebRewardScope, subject: String, dayStart: Instant, max: Long): Boolean   // max 미만일 때만 +1, 원자적
      fun markGranted(sessionId: String, grantedAt: Instant, rewardType: String?, rewardRef: String?, tokenHash: String?, tokenExpiresAt: Instant?)
      fun redeem(tokenHash: String, accountId: String, purpose: String, now: Instant): WebRewardSession?   // 원자적 1회
      fun eraseAccount(accountId: String)
  }
  fun interface WebRewardTokens { fun redeem(token: String, accountId: String, purpose: String): WebRewardSession? }
  class WebRewardService(store: WebRewardStore, properties: WebRewardedProperties, issuer: RewardIssuer?, time: TimeProvider) : WebRewardTokens {
      fun open(accountId: String, purpose: String, clientKey: String): WebRewardSession
      fun grant(accountId: String, sessionId: String): WebRewardGranted
  }
  enum class WebRewardedErrorCode : ErrorCode   // 코드 · 상태는 Global Constraints
  ```

- [ ] **Step 1: 실패하는 테스트** — `DayWindowTest.seoulMidnightBoundary`: `containing(2026-11-18T14:59:59Z, Asia/Seoul)` 의 `to == 2026-11-18T15:00:00Z`, `containing(2026-11-18T15:00:00Z, …)` 의 `from == 2026-11-18T15:00:00Z`. `WebRewardServiceTest`(가짜 저장소 · 시계 · 기록 지급기):
  - `grantBeforeMinElapsedIsTooEarlyAndKeepsSessionOpen` — 6초 뒤 `TOO_EARLY`, 7초 뒤 같은 세션 성공.
  - `grantAfterExpiryIsExpired` · `secondGrantIsSessionUsed` · `openAtAccountLimitIsDailyLimit`(상한 1, 하나 받은 뒤 `open` → `DAILY_LIMIT`) · `ipLimitCountsAcrossAccounts`(두 계정 같은 clientKey).
  - `pointModeCallsIssuerOnceWithSessionKey` — 기록 지급기에 `RewardGrantView(id = sessionId, type = "point", params = {amount=1}, sourceType = "ADS_WEB_REWARDED", sourceKey = sessionId)` 한 번, 결과 `token == null`, `session.rewardRef == 지급기 resultRef`.
  - `tokenModeRedeemsOnce` — `token != null`, 첫 `redeem` 은 세션, 두 번째는 null, 다른 purpose 는 null.
- [ ] **Step 2: 실패 확인** — `./gradlew :modules:platform:test --tests '*DayWindowTest*' :modules:ads-web-rewarded:test` → 컴파일 실패.
- [ ] **Step 3: 구현** — 순서 · 트랜잭션 · 「에러는 트랜잭션 밖에서」는 스펙 「서버 규칙」. 결과는 `inTransaction` 이 sealed 결과로 돌려주고 밖에서 던진다(point 의 `JdbcPointStore.earn` 방식). 토큰은 `SecureRandom` 32바이트 base64url, 저장은 SHA-256 hex. 지급기 예외 · `DataAccessException` 은 서비스가 `REWARD_UNAVAILABLE`(503) `ApplicationException` 으로 바꿔 던진다(트랜잭션은 되돌아간 뒤). `build.gradle.kts`: `api(project(":modules:platform"))`, `implementation` spring-boot-starter · webmvc · `spring-security-core`(이 모듈은 로그인 호출자가 전부라 선택 의존이 아니다) · `spring-tx` · springdoc · kotlin-reflect · jackson-kotlin, 시험 의존은 `ads-admob` 와 같은 줄.
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS. `./gradlew :modules:ads-admob:test` → PASS(동작 그대로).

### Task K2 (kotlin): HTTP · 자동 구성 · 문서 · 카탈로그

**Files:**
- Create: `.../webrewarded/web/WebRewardedController.kt`, `web/WebRewardedDtos.kt`, `WebRewardedAutoConfiguration.kt`, `WebRewardErasure.kt`, `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `docs/modules/ads-web-rewarded.md`, `docs/config/modules/ads-web-rewarded.yml`(스펙 yml 그대로)
- Modify: `docs/modules/README.md`(행), `docs/minimal-composition.md`(접두사 표 · 부팅 표 행), `capabilities.json`(+ 생성물), `CHANGELOG.md`, `modules/AGENTS.md`(한 줄)
- Test: `modules/ads-web-rewarded/src/test/kotlin/.../webrewarded/WebRewardedWebTest.kt`, `WebRewardedSwitchTest.kt`

**Interfaces:**
- Consumes: K1 의 `WebRewardService` · `WebRewardStore` · `WebRewardedProperties` · `WebRewardTokens`.
- Produces: 스펙 「API 계약」 표의 두 경로와 JSON(필드 이름 · null 규칙 그대로). DTO `OpenSessionRequest(@field:Pattern purpose)`, `WebRewardSessionResponse(sessionId, purpose, expiresAt)`, `WebRewardGrantResponse(sessionId, purpose, grantedAt, reward: RewardRef?, token: String?, tokenExpiresAt: Instant?)`, `RewardRef(type, ref)`. `WebRewardGrantResponse.toString` 은 `token` 을 가린다. 빈: `webRewardService`(store 빈이 있을 때, `WebRewardTokens` 로도 노출), `webRewardedController`, `webRewardErasure: AccountErasureListener`.

- [ ] **Step 1: 실패하는 테스트** — `WebRewardedWebTest`(MockMvc, 가짜 저장소 빈 + 기록 지급기):
  - `anonymousIs401` — 두 경로 모두.
  - `openThenGrantMatchesContract` — 열기 200 `$.value.sessionId` 가 `wrs_` 로 시작, 시계를 8초 넘긴 뒤 grant 200 `$.value.reward.type == "point"`, `$.value.token == null`.
  - `grantOfAnotherAccountsSessionIs404` — 계정 B 가 A 의 세션 → 404 `ADS_WEB_REWARDED.SESSION_NOT_FOUND`, 그 뒤 A 의 grant 는 성공.
  - `badPurposeIs400` — `"Unlock Episode"` → 400 `COMMON.VALIDATION_FAILED`.
  `WebRewardedSwitchTest`(ApplicationContextRunner): `offMeans404`(`enabled` 없음 → 컨트롤러 빈 없음) · `missingIssuerFailsStartup`(`reward-type=point`, 지급기 빈 없음 → 기동 실패, 메시지에 속성 이름) · `badRewardParamsFailStartup`(지급기 `validate` 가 던짐 → 기동 실패).
- [ ] **Step 2: 실패 확인** — `./gradlew :modules:ads-web-rewarded:test` → FAIL.
- [ ] **Step 3: 구현** — 자동 구성은 `AdmobAutoConfiguration` 모양: 서블릿 웹 앱 + `enabled=true` + `WebRewardStore` 빈. 지급기는 `ObjectProvider<RewardIssuer>` 에서 `type == rewardType` 하나를 찾는다(503 변환은 K1 서비스 몫). IP 키는 `ClientIps.of(request).limitKey`. 문서는 고정 틀 + 흐름 · IP 상한은 `skeleton.web.client-ip.mode` 를 정해야 믿을 수 있다는 줄 · 정책 체크는 react-skeleton `packages/ads/README.md` 에 있다는 줄. 카탈로그 항목의 `frontend` 는 `null`(J1 이 채운다), `notFor` 에 「저장소가 없다 — ads-web-rewarded-jdbc 를 같이 고른다」 · 「서버가 시청을 증명하지 못한다 — 남용은 한도로 막는다」.
- [ ] **Step 4: 통과 확인** — `./gradlew :modules:ads-web-rewarded:test :modules:platform:test --tests '*CapabilitiesCatalogTest*' --tests '*ModuleDocumentationTest*' --tests '*ModuleRegistrationRulesTest*'` → PASS, `perl scripts/build-capabilities.pl --check` → 0.

### Task K3 (kotlin): `ads-web-rewarded-jdbc`

**Files:**
- Create: `modules/ads-web-rewarded-jdbc/build.gradle.kts`(`point-jdbc` 모양: `api(project(":modules:ads-web-rewarded"))`, `persistence-jdbc`, `platform`), `.../jdbc/JdbcWebRewardStore.kt`, `.../jdbc/WebRewardJdbcAutoConfiguration.kt`, imports 파일
- Create: `src/main/resources/db/migration/{postgresql,mysql}/V<UTC>__ads_web_rewarded.sql` — 표 `ads_web_rewarded_sessions`(id pk · account_id · purpose · client_key · opened_at · expires_at · granted_at · reward_type · reward_ref · token_hash unique · token_expires_at · redeemed_at, account_id 인덱스) · `ads_web_rewarded_daily_counts`(scope · subject · day_start · granted, pk 셋). 시각 열은 PG `timestamptz` / MySQL `datetime(6)`.
- Create: `src/postgresTest/.../DbTestDatabase.kt`, `src/mysqlTest/.../DbTestDatabase.kt`(point-jdbc 복사), `src/dbTest/kotlin/.../jdbc/WebRewardStoreDbTest.kt`
- Modify: `settings.gradle.kts`(`include(":modules:ads-web-rewarded-jdbc")`), 루트 `build.gradle.kts` `dbTestModules`, `migrations.lock`, `docs/modules/ads-web-rewarded-jdbc.md` + 색인 행, `capabilities.json`(+ 생성물), `CHANGELOG.md`

**Interfaces:**
- Consumes: K1 의 `WebRewardStore` 계약 그대로(잠금 · 원자성 주석이 시험 기준).
- Produces: 빈 `webRewardStore: WebRewardStore`(`@ConditionalOnMissingBean`, `DataSource` + `SqlDialect` 있을 때). `inTransaction` 은 `TransactionTemplate`.

- [ ] **Step 1: 실패하는 테스트** — `WebRewardStoreDbTest`(실 DB, 진짜 `WebRewardService` + 기록 지급기 · 고정 시계):
  - `concurrentGrantsOfOneSessionPayOnce` — 스레드 8개가 같은 세션 grant → 성공 1, 나머지 `SESSION_USED`, 지급기 호출 1.
  - `concurrentGrantsAtLimitOnlyOneWins` — `account-daily-limit=1`, 열린 세션 2개 동시 grant → 성공 1, `DAILY_LIMIT` 1, `grantedOn(ACCOUNT) == 1`.
  - `issuerFailureRollsBackSessionAndCounter` — 지급기가 던짐 → `REWARD_UNAVAILABLE`, 세션 `grantedAt == null`, `grantedOn == 0`; 지급기를 고친 뒤 같은 세션 grant 성공.
  - `redeemRefusesOtherAccountOrPurpose` — 다른 계정 · 다른 purpose → null, 맞는 것 → 세션, 다시 → null, `token-ttl` 지난 뒤 → null.
  - `eraseAccountRemovesSessionsAndAccountCounters` — IP 카운터는 남는다.
- [ ] **Step 2: 실패 확인** — `./gradlew :modules:ads-web-rewarded-jdbc:postgresTest` → FAIL.
- [ ] **Step 3: 구현** — `lockOwned` 는 `select … for update`, `incrementWithin` 은 방언의 insert-ignore 로 0 줄을 만든 뒤 `update … set granted = granted + 1 where … and granted < :max` 의 갱신 행 수 1, `redeem` 은 `update … set redeemed_at = :now where token_hash = :h and account_id = :a and purpose = :p and redeemed_at is null and token_expires_at > :now` 의 갱신 행 수 1. 시각은 `SqlDialect.instantParam` / `readInstant` 로만.
- [ ] **Step 4: 통과 확인** — `./gradlew :modules:ads-web-rewarded-jdbc:postgresTest :modules:ads-web-rewarded-jdbc:mysqlTest` → PASS, `perl scripts/migrations-lock.pl --check` · `perl scripts/migration-risk.pl` · `perl scripts/build-capabilities.pl --check` → 0.

### Task R1 (react): `@skeleton/ads` 핵심 — API · 로더 · 가짜 · 흐름 컨트롤러

레포: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/react-skeleton`

**Files:**
- Create: `packages/ads/package.json`(`captcha-turnstile` 모양, 의존 `@skeleton/api-client`, peer `react`), `tsconfig.json`, `src/index.ts`, `src/types.ts`, `src/webRewardedApi.ts`, `src/googletag.ts`(쓰는 부분 타입 · `loadGoogletag` · `GPT_SCRIPT_SRC`), `src/fakeGoogletag.ts`, `src/rewardedAd.ts`
- Modify: `packages/api-client/src/errorCodes.ts`(`ADS_WEB_REWARDED_DAILY_LIMIT: 'ADS_WEB_REWARDED.DAILY_LIMIT'`)
- Test: `src/webRewardedApi.test.ts`, `src/rewardedAd.test.ts`

**Interfaces:**
- Produces:
  ```ts
  export type WebRewardedSession = { sessionId: string; purpose: string; expiresAt: string }
  export type WebRewardedGrant = { sessionId: string; purpose: string; grantedAt: string
    reward: { type: string; ref: string } | null; token: string | null; tokenExpiresAt: string | null }
  export type WebRewardedApi = { openSession(purpose: string): Promise<WebRewardedSession>; grant(sessionId: string): Promise<WebRewardedGrant> }
  export function createWebRewardedApi(client: Pick<ApiClient, 'value'>, opts?: { basePath?: string }): WebRewardedApi  // 기본 '/ads/web-rewarded', 둘 다 POST
  export const GPT_SCRIPT_SRC = 'https://securepubads.g.doubleclick.net/tag/js/gpt.js'
  export function loadGoogletag(env?: GoogletagLoaderEnv): Promise<GoogletagLike>   // loadTurnstile 과 같은 규칙(한 번 · 실패 뒤 재시도 · 브라우저 아니면 거절)
  export type FakeRewardedOutcome = 'granted' | 'closed' | 'no-fill' | 'load-error'
  export function createFakeGoogletag(opts?: { outcome?: FakeRewardedOutcome; watchMs?: number }): { loader: () => Promise<GoogletagLike> }  // 기본 'granted', 8000
  export type RewardedAdStatus = 'idle' | 'opening' | 'loading' | 'ready' | 'playing' | 'granting' | 'rewarded' | 'forfeited' | 'unavailable' | 'limited' | 'failed'
  export type RewardedAdState = { status: RewardedAdStatus; grant?: WebRewardedGrant; error?: unknown }
  export type RewardedAdOptions = { api: WebRewardedApi; adUnitPath: string; purpose: string
    loadGoogletag?: () => Promise<GoogletagLike>; isUserActive?: () => boolean }   // 기본: 진짜 로더, navigator.userActivation?.isActive ?? true
  export type RewardedAd = { getState(): RewardedAdState; subscribe(listener: () => void): () => void
    start(): void; watch(): void; cancel(): void; destroy(): void }
  export function createRewardedAd(options: RewardedAdOptions): RewardedAd
  ```
  상태 전이는 스펙 「react」 절 그대로. `start()` 는 `idle` · 끝 상태에서만 듣는다. 가짜는 `display` 다음 틱에 `rewardedSlotReady`, `makeRewardedVisible` 뒤 `watchMs` 에 granted(+ 바로 closed) 또는 closed, `no-fill` 은 `slotRenderEnded{isEmpty:true}`, `load-error` 는 로더 거절. 미디어 요소를 만들지 않는다.

- [ ] **Step 1: 실패하는 테스트** — `webRewardedApi.test.ts`: 기록 클라이언트로 `openSession('unlock')` → `value('/ads/web-rewarded/sessions', { method: 'POST', json: { purpose: 'unlock' } })`, `grant('wrs_1')` → `value('/ads/web-rewarded/sessions/wrs_1/grant', { method: 'POST' })`, `basePath` 바꾸면 그 경로. `rewardedAd.test.ts`(가짜 googletag · 가짜 api · vitest 가짜 타이머):
  - `watchWithoutUserActivationDoesNotShow` — `isUserActive: () => false` 로 `ready` 에서 `watch()` → 상태 `ready` 유지, 가짜의 `makeRewardedVisible` 호출 0.
  - `grantedTwiceGrantsOnce` — granted 이벤트 두 번 → `api.grant` 1회, 상태 `rewarded`, `grant` 값 보존.
  - `closedWithoutGrantIsForfeited` — `outcome: 'closed'` → `forfeited`, `api.grant` 0, `destroySlots` 1.
  - `noAutomaticRestartAfterEnd` — `rewarded` · `forfeited` 뒤 타이머를 1분 돌려도 `defineOutOfPageSlot` 호출 1, `api.openSession` 1.
  - `dailyLimitAtOpenDefinesNoSlot` — `openSession` 이 `ADS_WEB_REWARDED.DAILY_LIMIT` `ApiRequestError` → `limited`, `defineOutOfPageSlot` 0.
  - `noFillIsUnavailable` — `outcome: 'no-fill'` → `unavailable`, `destroySlots` 1.
- [ ] **Step 2: 실패 확인** — `pnpm --filter @skeleton/ads test` → FAIL.
- [ ] **Step 3: 구현** — GPT 호출 순서는 스펙 「흐름」 2~5, 모두 `googletag.cmd.push` 안. 리스너는 자기 슬롯(`event.slot === slot`)만 본다. 로더는 `captcha-turnstile/src/loader.ts` 모양.
- [ ] **Step 4: 통과 확인** — `pnpm --filter @skeleton/ads test` · `pnpm --filter @skeleton/ads typecheck` · `pnpm --filter @skeleton/api-client test` → PASS.

### Task R2 (react): 훅 · 동의 창 · 스토리 · README · 카탈로그

레포: react-skeleton

**Files:**
- Create: `packages/ads/src/useRewardedAd.ts`, `src/RewardedAdPrompt.tsx`(+ `.module.css` 필요할 때만), `src/RewardedAdPrompt.stories.tsx`, `src/RewardedAdPrompt.test.tsx`, `packages/ads/README.md`
- Modify: `src/index.ts`, `package.json`(`@skeleton/ui` 의존), `tests/support/ssrFixtures.ts`(두 줄), `docs/ui-catalog.md`(스토리 행), `capabilities.json`(+ `pnpm capabilities`), `tests/skeleton.repo.test.ts` 목록, `docs/repo-map.md` 행, `CHANGELOG.md`, `scripts/AGENTS.md` 가 말하는 함께 볼 곳

**Interfaces:**
- Consumes: R1 의 `createRewardedAd` · `RewardedAdOptions` · `RewardedAdState`.
- Produces:
  ```ts
  export function useRewardedAd(options: RewardedAdOptions): RewardedAdState & Pick<RewardedAd, 'start' | 'watch' | 'cancel'>
  // 컨트롤러는 effect 안에서 만들고 unmount 에 destroy, 상태는 useSyncExternalStore. 서버 렌더는 { status: 'idle' }
  export type RewardedAdPromptProps = { ad: ReturnType<typeof useRewardedAd>; reward: ReactNode
    title?: string; watchLabel?: string; cancelLabel?: string }   // 기본 'Watch an ad to get your reward?' · 'Watch' · 'Not now'
  export function RewardedAdPrompt(props: RewardedAdPromptProps): JSX.Element | null   // status === 'ready' 일 때만 Dialog, [Watch] onClick → ad.watch(), [Not now] → ad.cancel()
  ```
- [ ] **Step 1: 실패하는 테스트** — `RewardedAdPrompt.test.tsx`: `ready` 가 아니면 아무것도 그리지 않는다, `ready` 면 `reward` 문구가 보이고 [Watch] 클릭이 `ad.watch` 를 1회 부른다. 스토리 `play`: 가짜 googletag(`watchMs: 50`) + 가짜 api 로 앱 버튼 → 동의 창에 보상 문구 → [Watch] → 보상 받음 문구.
- [ ] **Step 2: 실패 확인** — `pnpm --filter @skeleton/ads test` → FAIL.
- [ ] **Step 3: 구현** — `@skeleton/ui` 의 `Dialog` · `Button` 만. README: 짝 백엔드 표(모듈 · 두 경로 · DTO 가 있는 Kotlin 파일), 쓰는 법(앱 버튼 → `start`, `<RewardedAdPrompt reward="10 points" />`, 로컬은 `loadGoogletag: createFakeGoogletag().loader`, `rewarded` 에서 앱이 자기 쿼리를 무효화), 공개 표면 표, **정책 체크**(스펙 그 절 그대로 — 항목마다 이 패키지가 지키는 것 / 앱이 지킬 것 표시), 테스트가 재지 않는 것(진짜 GPT · 진짜 광고 소리 · 채움률). 카탈로그 항목 `backend: null`(J1 이 채운다), `notFor` 에 「배너 · 전면 광고는 없다」 · 「보상의 근거는 브라우저 신호다 — 서버 한도가 남용을 막는다」.
- [ ] **Step 4: 통과 확인** — `pnpm --filter @skeleton/ads test` · `typecheck` → PASS, 루트 `vitest run --pool forks --config vitest.root.config.ts tests/capabilities.test.ts tests/ssr.safety.test.ts tests/stories.test.ts tests/workspace.test.ts` → PASS, `pnpm capabilities:check` → 0. 스토리 실행(`pnpm test:stories`)은 지휘 세션이 마지막에 한 번.

### Task J1 (두 레포): 카탈로그 잇기 — K2 · K3 · R2 뒤

**Files:**
- Modify: kotlin `capabilities.json` 의 `ads-web-rewarded` · `ads-web-rewarded-jdbc` `frontend` → `{ capabilities: ["ads"], packages: ["@skeleton/ads"], newProjectFlag: <react 항목 stampFlag.flag> }`, `docs/modules/ads-web-rewarded.md` 「프론트 짝」 → `@skeleton/ads`(+ 생성물)
- Modify: react `capabilities.json` 의 `ads` `backend` → `{ modules: ["ads-web-rewarded", "ads-web-rewarded-jdbc"], basePaths: ["/api/v1/ads/web-rewarded"], docs: ["docs/modules/ads-web-rewarded.md"] … }`(+ `pnpm capabilities`)

- [ ] **Step 1: 확인** — 두 레포가 나란히 있는 상태에서 kotlin `./gradlew :modules:platform:test --tests '*CapabilitiesCatalogTest*' --tests '*ModuleDocumentationTest*'`, react `vitest run --config vitest.root.config.ts tests/capabilities.test.ts tests/capabilities.skeleton.test.ts` → PASS.
