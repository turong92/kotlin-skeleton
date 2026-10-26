# 관리자 대시보드 — 방문 · 흐름 · 장사 · 앱 통계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 구현 · 리뷰는 역할(`developer` · `code-reviewer`)로 부른다.

**Goal:** 스켈레톤 앱이 모듈 몇 줄 + 설정 몇 줄로 「보통 대시보드」 한 화면을 얻는다 — 우리 DB 숫자(가입 · 활성 · 순매출 · 결제 · 퍼널)부터 끝에서 끝까지, 그다음 Cloudflare, 그다음 GA · 실시간, 마지막에 편집 저장 · 앱 통계 · e2e · 카탈로그.

**Architecture:** 카드 기여 계약(`DashboardMetric` · `DashboardRanking` · 퍼널)은 `platform` `common/dashboard` 에 둔다(지휘 결정 1 — `account-jdbc` · `payment-jdbc` 가 `dashboard` 를 모르고 기여하려면; authz 의 `PermissionDeclaration` 과 같은 이유). `dashboard` 는 기여 빈을 모아 조립 · 캐시 · 관리 API · 권한 · 적재 작업을 돌리고, `dashboard-jdbc` 는 일별 집계 · 화면 설정 표, `dashboard-cloudflare` · `dashboard-ga` 는 외부 숫자를 기여한다. 기여 자동 설정은 `@ConditionalOnClass(name = "…dashboard…")` 로 — 컴파일 의존 없이, `dashboard` 없는 앱에서는 등록되지 않는다. react 는 새 `@skeleton/dashboard`(가벼운 SVG 차트 직접) + `@skeleton/marketing` 의 분석 스크립트 켜기.

**Spec:** `docs/superpowers/specs/2026-10-26-admin-dashboard-design.md`(형 승인). 화면 시안 E: 홈서버 `.share/dashboard-mock/e.html`. CF 조사: 홈서버 메모리 `scratch-keep/cf-analytics.md`. 이 계획과 스펙이 어긋나면 스펙이 이긴다 — 어긋남은 지휘에 알린다. 계획 단계 결정 다섯(스펙 맨 위 「계획 단계에서 지휘가 정한 것」)은 스펙에 반영돼 있다.

## Global Constraints

- 레포: kotlin `~/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton-wt-dashboard`(브랜치 `dashboard`, 기준 `bc9c1922`), react `…/react-skeleton`(기준 main `d9a6570`). **커밋 · 합치기 · push 는 지휘만.** 조각의 끝은 「바뀐 범위 시험 통과 + 보고」.
- 빌드 · 시험은 **바뀐 범위만**(각 조각의 명령). 전체 스위트 · `kverify` · `test-new-project.sh --full` · react 전체(`pnpm test` · `typecheck` · `lint`) · e2e 는 **지휘가 묶음 끝에 한 번**. 같은 워크트리에서 Gradle 을 둘 동시에 돌리지 않는다.
- 모듈 규칙: 빈은 AutoConfiguration `@Bean` + `@ConditionalOnMissingBean`, `@Component` 금지. **새 모듈은 만드는 조각이** `docs/modules/<m>.md` · `docs/config/modules/<m>.yml` · `docs/modules/README.md` 행 · `capabilities.json` 항목 · `settings.gradle.kts` · workbench 의존 한 줄을 같이 더한다 — 가드(`ModuleDocumentationTest` · `CapabilitiesCatalogTest` · `ConfigPrefixDocumentationTest`)가 그때 바로 막기 때문. 조합 시험 · 레시피 · CHANGELOG 는 K6.
- 마이그레이션: `./gradlew newMigration -Pname=…`, 두 방언(`postgresql` · `mysql`) 짝, `perl scripts/migrations-lock.pl`. 잠긴 파일은 고치지 않는다.
- 반목표 그대로: 화면 요청이 외부 API 를 직접 부르지 않는다(캐시 · 적재가 막는다) · 대시보드 표에는 집계 숫자만(경로는 쿼리 문자열을 떼고 저장) · 키가 없어도 기동하고 그 카드만 `NOT_CONNECTED` · `dashboard` 를 안 붙인 앱은 아무것도 안 바뀐다.
- 스켈레톤 기본값 중립: 관리 API 기본 꺼짐(`skeleton.dashboard.admin.enabled=false` → 404 — 지휘 결정 3), 형 취향은 `apps/sample` yml override.
- **회귀 테스트는 넷에만**: 돈(순매출) · 적재 멱등 · 권한 · 여러 프로젝트 격리(+ 동의 전 GA 미전송 하나 — R2, 법적 요건이라 더함). 화면은 e2e 한 여정. 나머지는 컴파일 · 기동 · 스토리 `play` 로 덮는다.
- KDoc 안에 `/**` 금지. 공개 레포 — 형 도메인 · 이메일 흔적 금지(`example.com`).
- react: 날 요소 · 날값 금지(`@skeleton/ui` · 의미 토큰), 패키지는 `import.meta.env` 를 읽지 않는다(앱이 넘긴다), SSR 안전(`tests/support/ssrFixtures.ts`), 문구는 prop(기본 영어), 차트 라이브러리를 들이지 않는다. 눌리는 요소는 테두리 · 채움으로 눌리게 보인다.

## Review Focus

- **돈**: 순매출 = 기간 안 `paid_at` 의 `paid_amount` 합 − 기간 안 `completed_at` 의 성공 환불 합, **통화별 따로**(합치지 않음). 부분 환불 · 결제사 쪽 취소(`origin=PROVIDER`)도 뺀다, 실패 · 대기 · 거절 환불은 안 뺀다(K2).
- **권한**: `dashboard.read` 없으면 모든 대시보드 경로 403, 익명 401, 스위치 꺼짐 404(K1). 화면 설정은 자기 것만(K6).
- **격리**: 캐시 키 · 외부 호출 단위 · 일별 표 · 화면 설정이 프로젝트마다. A 도메인으로 B 숫자가 보이지 않는다(K1 · K4).
- **반목표**: 운영자 열 명이 동시에 열어도 외부 호출은 캐시 주기당 한 번(K4 · K5), 일별 표 · 응답에 IP · 이메일 · 계정 id 없음, 비밀(CF 토큰 · GA 키)이 로그 · 응답 · 오류 메시지에 없음.
- **멱등**: 같은 날을 두 번 적재해도 같은 행, 값이 바뀌면 덮어쓴다(K3 · K4).

---

## 조각 사이 계약 (K1 에서 확정 — R1 은 이것으로 가짜 API 를 먼저 만든다)

### C1. 관리 API (`skeleton.dashboard.admin.enabled=true` 일 때, 모두 `dashboard.read`)

```
GET /api/v1/admin/dashboard?from=2026-10-20&to=2026-10-26&compare=true
→ DataResponse {
  "range":    { "from": "2026-10-20", "to": "2026-10-26", "zone": "Asia/Seoul", "interval": "DAY" },
  "previous": { "from": "2026-10-13", "to": "2026-10-19" } | null,
  "metrics": [ {
      "key": "payment.net-revenue", "label": "Net revenue", "unit": "MONEY", "source": "db",
      "status": "OK", "freshness": { "kind": "LIVE", "asOf": "2026-10-26T05:00:00Z" },
      "value": [ { "amount": 120000, "currency": "KRW" }, { "amount": 990, "currency": "USD" } ],
      "previousValue": [ … ] | null,
      "series": [ { "at": "2026-10-19T15:00:00Z", "value": 12000 } ], "previousSeries": [ … ] | null,
      "link": "/admin/payments" | null } ],
  "rankings": [ { "key": "cloudflare.referrers", "group": "traffic", "label": "Sources", "source": "cloudflare",
      "status": "NOT_CONNECTED", "freshness": null, "columns": ["Source", "Visits"], "rows": [ { "label": "google.com", "values": [120] } ] } ],
  "funnel": { "steps": [ { "key": "account.signed-up", "label": "Signed up", "reached": 128, "rate": 1.0 } ],
              "reference": { "key": "cloudflare.visits", "label": "Visits", "value": 3200, "rate": 0.04 } | null } }
GET /api/v1/admin/dashboard/rankings/{key}?from&to&limit=100   → DataResponse<Ranking>  (「전체 보기」, limit ≤ 500)
GET /api/v1/admin/dashboard/realtime                           → DataResponse { "status", "freshness", "activeUsers": 7, "pages": [Row], "countries": [Row] }   (K5)
GET /api/v1/admin/dashboard/layout · PUT 같은 경로 ← { "hidden": ["cloudflare.pageviews"], "order": ["tiles", "chart", "rankings.traffic", …] }   (K6)
```
- `from` · `to` 는 대시보드 시간대의 날짜(양 끝 포함). 「오늘」= `from=to=오늘`. `interval`: 하루면 `HOUR`, 92일 이하 `DAY`, 넘으면 `WEEK`. 최대 366일. `previous` = 바로 앞 같은 길이.
- `unit`: `PEOPLE` · `COUNT` · `MONEY` · `PERCENT`. 돈이 아니면 `value` 는 원소 하나(`currency: null`). 돈의 `series` 는 첫 통화(설정 `skeleton.dashboard.currency`, 없으면 금액이 큰 통화)만.
- `compare=true` 여도 `comparable=false` 인 지표(`auth-session.active`)는 `previousValue` · `previousSeries` 가 `null` — 화면은 증감 대신 「비교 불가」.
- `status`: `OK` · `NOT_CONNECTED`(키 없음) · `NO_HISTORY`(지난 기간인데 `dashboard-jdbc` 없음) · `ERROR`(외부 호출 실패 — 화면 전체를 500 으로 만들지 않는다). `freshness.kind`: `LIVE` · `CACHED`(asOf = 외부에서 가져온 시각) · `DAILY`(asOf = 적재 시각).
- 오류(ApiError `code`): `DASHBOARD.RANGE_INVALID` 400 · `DASHBOARD.RANKING_NOT_FOUND` 404 · `DASHBOARD.LAYOUT_UNAVAILABLE` 404(`dashboard-jdbc` 없음 → 프런트는 브라우저 저장) · `DASHBOARD.LAYOUT_INVALID` 400.
- 키 이름 — 타일: `cloudflare.visits` · `cloudflare.pageviews` · `account.signed-up` · `auth-session.active` · `payment.net-revenue` · `payment.paid-count`. 순위: `cloudflare.{referrers,paths,countries,devices}` · `ga.{channels,campaigns,landing-pages,page-transitions,returning}` · `app.*`. 묶음(`group`): `traffic` · `pages` · `geo` · `app`. 화면 설정의 카드 키: `tiles` · `chart` · `rankings.traffic` · `rankings.pages` · `rankings.geo` · `funnel` · `app`.

### C2. 기여 계약 (kotlin, `platform` `dev.sumin.skeleton.common.dashboard`)

```kotlin
data class DashboardRange(val from: Instant, val to: Instant, val zone: ZoneId)          // [from, to)
enum class DashboardInterval { HOUR, DAY, WEEK }
enum class DashboardUnit { PEOPLE, COUNT, MONEY, PERCENT }
data class DashboardAmount(val amount: Long, val currency: String? = null)
data class DashboardPoint(val at: Instant, val value: Long)
data class DashboardRow(val label: String, val values: List<Long>)
data class DashboardFreshness(val kind: Kind, val asOf: Instant?) { enum class Kind { LIVE, CACHED, DAILY } }
data class DashboardResult<T>(val status: Status, val data: T? = null, val freshness: DashboardFreshness? = null) {
    enum class Status { OK, NOT_CONNECTED, NO_HISTORY, ERROR } }
interface DashboardMetric { val key: String; val label: String; val unit: DashboardUnit; val source: String; val order: Int; val link: String? get() = null
    val comparable: Boolean get() = true                                                  // false 면 직전 기간을 계산하지 않는다(활성 — 지휘 결정 4)
    fun value(range: DashboardRange): DashboardResult<List<DashboardAmount>>
    fun series(range: DashboardRange, interval: DashboardInterval): DashboardResult<List<DashboardPoint>> }
interface DashboardRanking { val key: String; val group: String; val label: String; val columns: List<String>; val source: String; val order: Int
    fun top(range: DashboardRange, limit: Int): DashboardResult<List<DashboardRow>> }
data class DashboardCohortMember(val accountId: String, val joinedAt: Instant)            // 메모리에서만 — 저장하지 않는다
interface DashboardCohortSource { val key: String; val label: String
    fun members(range: DashboardRange): List<DashboardCohortMember> }
interface DashboardFunnelStep { val key: String; val label: String; val order: Int
    fun reached(cohort: List<DashboardCohortMember>): Long }                              // 구현은 id 를 1000 개씩 끊어 IN 질의
class DashboardFunnel(val cohort: DashboardCohortSource, val steps: List<DashboardFunnelStep>, val referenceMetricKey: String? = "cloudflare.visits")
```
- 기본 `DashboardFunnel` 빈(`@ConditionalOnMissingBean`)은 `DashboardCohortSource` 하나 + `DashboardFunnelStep` 들을 `order` 순으로. 앱이 자기 `DashboardFunnel` 빈을 두면 물러난다(스펙 「같은 이름 빈」).
- 묶음은 계정 id 목록으로 넘긴다(지휘 결정 2 — 「가입 시각 범위」만으로는 결제 모듈이 계정 표 없이 거를 수 없다).
- `dashboard` 모듈 안(외부 기여용): `DashboardDailyStore`(`upsert(rows)` · `read(source, kind, fromDay, toDay)` · `lastIngested(source)`), `DashboardDailyRow(day: LocalDate, source, kind, dimension, value: Long)`(전체 값은 `dimension = ""`), `DashboardIngestor`(`source` · `ingest(day: LocalDate, zone): List<DashboardDailyRow>`), `DashboardLayoutStore`(`find(accountId)` · `save(accountId, json)` · `delete(accountId)`).

### C3. 설정 키 (`skeleton.dashboard.*`, 비밀은 env)

| 키 | 기본 | 모듈 · 조각 |
|---|---|---|
| `dashboard.admin.enabled` | `false` | dashboard K1 |
| `dashboard.zone` | 비면 `skeleton.time.default-zone`, 그것도 없으면 `UTC` | K1 |
| `dashboard.currency` | 비어 있음 | K1 |
| `dashboard.cache.db` | `30s` | K1 |
| `dashboard.ingest.at` · `.backfill-days` · `.enabled` | `04:00` · `3` · `true` | K3 |
| `dashboard-cloudflare.account-id` · `.site-tag` · `.api-token`(env `SKELETON_DASHBOARD_CLOUDFLARE_API_TOKEN`) · `.live-cache` | 비어 있음 · `5m` | K4 |
| `dashboard-ga.property-id` · `.credentials`(env, base64 서비스 계정 JSON) · `.key-event` · `.realtime-cache` · `.base-url` · `.token-url` | 비어 있음 · `60s` · Google 주소 | K5 |

- 외부 주소는 `skeleton.http.clients.dashboard-cloudflare` · `dashboard-ga` 로 바꾼다(가짜 서버용 — payment-paddle 과 같다). `dashboard-cloudflare.` · `dashboard-ga.` 를 `ProjectConfigKeys.SUPPORTED` 에 더한다(K4 · K5).
- 프런트(앱 env): `VITE_GA_MEASUREMENT_ID` · `VITE_CF_BEACON_TOKEN`(R2).

### C4. 가짜 CF · GA 서버 계약 — **추측, 형이 키를 주면 맞춘다**(M1 · M2)

- CF: `POST {base}/client/v4/graphql`, Bearer 토큰. 계정 범위 `rumPageloadEventsAdaptiveGroups`(필터 `siteTag` · `datetime_geq` · `datetime_lt`), 필드 `count` · `sum { visits }` · `avg { sampleInterval }` · 차원 `date` · `datetimeHour` · `refererHost` · `requestPath` · `countryName` · `deviceType`. 별칭 여러 개를 한 요청에. 응답 `{ "data": { "viewer": { "accounts": [ { "<별칭>": [ { "count", "sum": {"visits"}, "avg": {"sampleInterval"}, "dimensions": {…} } ] } ] } }, "errors": null }`. 환산: 값 × `avg.sampleInterval`(반올림). 페이지뷰 = `count` 환산, 방문 = `sum.visits` 환산.
- GA: 토큰 `POST {token-url}`(JWT bearer grant, RS256 서명 — 가짜는 받은 JWT 의 `iss` 만 본다) → `{ access_token, expires_in }`. `POST {base}/v1beta/properties/{id}:runReport` · `:runRealtimeReport`, 요청 `{ dateRanges, dimensions:[{name}], metrics:[{name}], limit, orderBys }`, 응답 `{ dimensionHeaders, metricHeaders, rows: [ { dimensionValues:[{value}], metricValues:[{value}] } ], rowCount }`. 차원 `date` · `sessionDefaultChannelGroup` · `sessionCampaignName` · `landingPage` · `pageReferrer` · `pagePath` · `newVsReturning`, 지표 `sessions` · `activeUsers` · `keyEvents:<이벤트>`. 실시간 차원 `unifiedScreenName` · `country`, 지표 `activeUsers`(실시간 API 에 유입 차원이 없을 수 있다 — 그래서 실시간 패널은 페이지 · 국가).
- kotlin 시험의 가짜(`FakeCloudflareApi` · `FakeGaApi`, `HttpServer` 루프백 — `PaddleTestKit` 의 `FakePaddleApi` 방식)와 react e2e 의 가짜(`fakeCloudflare.ts` · `fakeGa.ts` — `fakePaddle.ts` 방식)는 이 모양 하나를 따른다. 실측으로 모양이 바뀌면 두 곳을 같이 고친다.

---

## 워크트리 · 브랜치 · 합치는 순서

| 조각 | 레포 | 브랜치(갈래 시작점) | 묶음 |
|---|---|---|---|
| K1 | kotlin | `dashboard/k1-core` (`dashboard`) | 1 |
| K2 | kotlin | `dashboard/k2-db` (K1 확인 커밋) | 1 |
| R1 | react | `dashboard/r1-package` (`main`) | 1 |
| K3 | kotlin | `dashboard/k3-jdbc` (K1 확인 커밋) | 2 |
| K4 | kotlin | `dashboard/k4-cloudflare` (K3 확인 커밋) | 2 |
| R2 | react | `dashboard/r2-sources` (R1 확인 커밋) | 2 · 3 |
| K5 | kotlin | `dashboard/k5-ga` (K3 확인 커밋) | 3 |
| K6 | kotlin | `dashboard/k6-finish` (K2 · K4 · K5 를 합친 `dashboard`) | 4 |
| R3 | react | `dashboard/r3-finish` (R2 확인 커밋) | 4 |

- 순서: **K1 ∥ R1** → **K2 ∥ K3** → 묶음 1 확인(지휘: 견본 앱 + R1 화면을 진짜 백엔드로) → **K4 ∥ K5 ∥ R2** → **K6 ∥ R3** → R3 e2e(K6 합친 백엔드) → 지휘 끝 검사. 워크트리는 각 레포 `.claude/worktrees/dash-<조각>`.
- 같이 만지는 파일과 주인:

| 파일 | 주인 | 다른 조각 |
|---|---|---|
| `settings.gradle.kts` · `apps/workbench/build.gradle.kts` · `docs/modules/README.md` · `capabilities.json` | 모듈을 만드는 조각(K1 · K3 · K4 · K5)이 자기 줄 · 항목만 | 지휘가 합친 뒤 `perl scripts/build-capabilities.pl` |
| `apps/sample/build.gradle.kts` · `application.yml` | K2(dashboard 줄 · `admin.enabled` · `zone` · `currency`) | K3 · K4 · K5 는 자기 의존 한 줄 + 자기 블록만(파일 끝 `skeleton:` 아래 새 줄), K6 은 앱 통계 |
| `migrations.lock` | K2 · K3 | 지휘가 합칠 때 다시 잠금 |
| `ProjectAutoConfiguration.kt`(`SUPPORTED`) | K4 · K5 각 한 항목 | — |
| 루트 `build.gradle.kts`(`dbTestModules`) | K3 | — |
| `CHANGELOG.md` `[Unreleased]` | kotlin K6 · react R3 이 이번 일 전체 블록 | 다른 조각은 손대지 않는다 |
| react `apps/sample/src/routes/routes.tsx` · `layouts/RootLayout.tsx` · `apps/sample/package.json` | R1 | R2 · R3 은 자기 줄만 |
| react `packages/api-client/src/errorCodes.ts` | R1(`DASHBOARD.*` 넷) | — |

---

## 묶음 1 — 우리 DB 숫자만으로 끝에서 끝까지

### Task K1: `dashboard` 모듈 + 계약 + 관리 API (kotlin, 중)

**Files:**
- Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/dashboard/DashboardContracts.kt`(C2 의 platform 몫 — 타입만, 빈 없음)
- Create `modules/dashboard/`: `build.gradle.kts`(`api(platform)`, spring-security `compileOnly` — authz 와 같은 방식, `compileOnly(alert)`), `src/main/kotlin/dev/sumin/skeleton/dashboard/` — `DashboardProperties`(`skeleton.dashboard`), `DashboardPermissions`(`dashboard.read`), `DashboardAssembler`(기여 빈 → C1 응답, 직전 기간 · 간격 · 퍼널 비율), `DashboardCache`(키 = `ProjectContext.scopedKey` + 기여 key + 범위 + 간격, 키마다 한 번만 계산 — single-flight), `DashboardRangeParser`(날짜 → `DashboardRange`, 검증), `DashboardStorePorts.kt`(C2 의 dashboard 몫 — K3 이 구현), `web/DashboardAdminController`(C1 의 처음 두 경로), `DashboardErrors`, `DashboardAutoConfiguration` · `DashboardWebAutoConfiguration` + `.imports`
- Docs: `docs/modules/dashboard.md`(계약 · 카드 더하는 법 · 퍼널 바꾸는 법) · `docs/config/modules/dashboard.yml` · `docs/dashboard-http-contract.md`(C1 — 새 파일) · `docs/modules/README.md` 행 · `docs/modules/platform.md`(dashboard 계약 절) · `capabilities.json` 항목(짝 프런트 `@skeleton/dashboard`)

**먼저 쓸 실패 시험** (`modules/dashboard/src/test/kotlin/dev/sumin/skeleton/dashboard/`):
- [ ] `DashboardAccessTest` **권한**: `dashboard.read` 없는 로그인 → 세 경로 403 `COMMON.FORBIDDEN`, 익명 401, 있으면 200, `admin.enabled=false` → 404. (`OperatorAccess` 대역)
- [ ] `DashboardProjectsTest` **격리**: 프로젝트 둘(`skeleton.projects`), `ProjectContext.current()` 를 값으로 돌려주는 대역 기여 → A Host 응답에 A 값, 곧바로 B Host 에 B 값(캐시가 섞이지 않는다); 같은 프로젝트로 다섯 번 → 대역 호출 1회.
- [ ] `ModuleRegistrationRulesTest` · `ModuleDocumentationTest` · `CapabilitiesCatalogTest` · `ConfigPrefixDocumentationTest` 통과(가드).

**바뀐 범위 시험:**
```bash
./gradlew :modules:dashboard:test
./gradlew :modules:platform:test --tests '*ModuleRegistrationRulesTest' --tests '*ModuleDocumentationTest' --tests '*CapabilitiesCatalogTest' --tests '*ConfigPrefixDocumentationTest'
```

**끝 조건:** 위 통과. 보고에 C1 · C2 의 최종 모양(시그니처)을 적는다 — 바뀌었으면 R1 에 넘긴다.

---

### Task K2: 기본 기여 — 가입 · 활성 · 순매출 · 결제 · 퍼널 + 견본 앱 조립 (kotlin, 중) — 선행 K1

**Files:**
- `modules/account-jdbc/`: Create `…/account/jdbc/dashboard/AccountDashboardAutoConfiguration.kt`(`@ConditionalOnClass(name = DashboardAutoConfiguration 이름)`) — `account.signed-up` 지표(기간 안 `created_at`, `ERASED` 포함 — 가입한 사실은 남는다) + `DashboardCohortSource`. 마이그레이션 `accounts (created_at)` 인덱스.
- `modules/auth-session-jdbc/`: 같은 꼴 — `auth-session.active` 지표(기간 안 `last_used_at` 의 서로 다른 `account_id`; 직전 기간은 덮어쓰기 때문에 적게 나오므로 `comparable = false`(응답의 `previousValue` · `previousSeries` 가 `null`) — 화면은 「비교 불가」, 문서에 근사치라고 적는다. 지휘 결정 4) + 퍼널 단계 `auth-session.returned`(가입 다음 날 이후 쓴 세션이 있다). 마이그레이션 `auth_sessions (last_used_at)`.
- `modules/payment-jdbc/`: 같은 꼴 — `payment.net-revenue`(위 Review Focus 정의, `link = "/admin/payments"`) · `payment.paid-count`(기간 안 `paid_at` 주문 수) + 퍼널 단계 `payment.paid`(묶음 중 `paid_at` 이 있는 주문을 가진 계정). 마이그레이션 `payment_orders (paid_at)` · `payment_refunds (completed_at)`.
- 각 모듈 `docs/modules/<m>.md` 에 「대시보드 기여」 한 줄, `migrations.lock`.
- sample: `apps/sample/build.gradle.kts`(`:modules:dashboard`), `application.yml`(`skeleton.dashboard.admin.enabled: true` · `zone: Asia/Seoul` · `currency: KRW`), `docs/sample.md` 한 줄.

**먼저 쓸 실패 시험** (`modules/payment-jdbc/src/dbTest/…/DashboardRevenueTest.kt` — 두 방언):
- [ ] 결제 2건(KRW 10000 · 5000) → 순매출 KRW 15000, 결제 건수 2.
- [ ] 같은 기간 부분 환불 3000 → 12000; 다음 기간에 나머지 전액 환불 → 이번 기간 12000 그대로, 다음 기간 −7000.
- [ ] `origin=PROVIDER` 환불은 빼고, `FAILED` · `REQUESTED` · `REJECTED` 환불은 빼지 않는다.
- [ ] KRW 10000 + USD 990 → 두 줄 따로(합치지 않음), 시리즈는 `currency` 설정의 통화만.
- [ ] `PENDING` · `FAILED` · `EXPIRED` 주문은 세지 않는다; 기간 경계 `[from, to)`.

**바뀐 범위 시험:**
```bash
./gradlew :modules:payment-jdbc:postgresTest :modules:payment-jdbc:mysqlTest --tests '*DashboardRevenue*'
./gradlew :modules:account-jdbc:test :modules:auth-session-jdbc:test :modules:payment-jdbc:test
./gradlew :modules:platform:test --tests '*MigrationLockTest' --tests '*ModuleDocumentationTest'
./gradlew :apps:sample:test --tests '*ApplicationTests*' :apps:api:test
```

**끝 조건:** 위 통과, `dashboard` 없는 조립(스타터 `apps/api`)에서 기여 빈이 없다(`:apps:api:test` 의 기동 시험이 그대로 통과 — 새 단정 없이).

---

### Task R1: `@skeleton/dashboard` 패키지 + 견본 화면 (react, 대) — 계약 C1 만, K1 과 나란히

**Files:**
- Create `packages/dashboard/`: `package.json`(`@skeleton/api-client` · `@skeleton/ui` · `@skeleton/time`), `README.md`(API 표), `src/index.ts`, `src/api.ts`(`createDashboardApi(client, { basePath = '/admin/dashboard' })` — overview · ranking · realtime · layout), `src/types.ts`(C1), `src/hooks.ts`(`useDashboard(range, compare)` — `refetchInterval: 60_000`, 탭 숨김이면 멈춤), `src/range.ts`(오늘 · 7일 · 30일 · 직접 → `from`/`to`), `src/format.ts`(단위 · 통화 · 증감), `src/charts/TrendChart.tsx`(SVG 선 + 직전 기간 점선, 축 · 툴팁), `src/charts/FunnelBars.tsx`, `src/MetricTiles.tsx`(누르면 선택 — `aria-pressed`, 증감 · 출처 배지 · 신선도), `src/RankingCard.tsx`(`@skeleton/ui` `Tabs`, 빈 상태 「Cloudflare 토큰을 넣으면 보입니다」 — 상태별 문구 prop), `src/DashboardHeader.tsx`(기간 · 비교 · 자리(지금 N명 · 편집은 R2 · R3)), `src/DashboardView.tsx`(시안 E 순서, 폰 타일 2열), `src/labels.ts`(영어 기본 + 키별 이름 맵), 부품마다 `*.stories.tsx`(+ `play`) · `src/stories/fakeDashboard.ts`
- 레포 장부: `capabilities.json` 항목(`backend`: `dashboard` · `/api/v1/admin/dashboard`), `pnpm capabilities`, `docs/ui-catalog.md` 행, `tests/support/ssrFixtures.ts`, `tests/skeleton.repo.test.ts` 목록, CLAUDE.md · README 패키지 표, `packages/api-client/src/errorCodes.ts`(`DASHBOARD.*` 넷)
- sample: `apps/sample/package.json`(의존 한 줄), `src/routes/AdminDashboardPage.tsx`(`/admin/dashboard`, `RequirePermission('dashboard.read')`), `routes.tsx`, `RootLayout.tsx`(`AdminLinks` 첫 항목), i18n ko/en(카드 이름 · 빈 상태 · 단위)

**먼저 쓸 실패 시험:**
- [ ] 스토리 `play`(가짜 API): 타일을 누르면 그래프 제목이 그 지표로 바뀐다 · `NOT_CONNECTED` 카드는 안내 문구 · `previousValue: null` 타일은 「비교 불가」 · 돈 타일에 둘째 통화 줄 · 390px 에서 가로 넘침 없음(`scrollWidth ≤ clientWidth`).
- [ ] 회귀 시험은 없다(권한 · 돈은 백엔드가 막는다). `typecheck` · `ssr.safety` · `stories` · `capabilities` 가드 통과.

**바뀐 범위 시험:**
```bash
pnpm --filter @skeleton/dashboard test
pnpm --filter @skeleton/dashboard --filter sample typecheck
pnpm vitest run tests/ssr.safety.test.ts tests/stories.test.ts tests/capabilities.test.ts tests/skeleton.repo.test.ts tests/workspace.test.ts
pnpm test:stories -- packages/dashboard
```

**끝 조건:** 위 통과. 묶음 1 확인(지휘): K1 · K2 를 합친 견본 앱 + 이 화면으로 「키 없이 띄우면 DB 타일 · 퍼널은 숫자, CF · GA 카드는 빈 상태」(스펙 §7 첫 줄).

---

## 묶음 2 — Cloudflare

### Task K3: `dashboard-jdbc` + 적재 작업 (kotlin, 중) — 선행 K1, K2 와 나란히

**Files:**
- Create `modules/dashboard-jdbc/`: `JdbcDashboardDailyStore`(upsert — `SqlDialect`), `JdbcDashboardLayoutStore`, `DashboardLayoutErasure`(`AccountErasureListener` — 줄 삭제), AutoConfiguration, 마이그레이션 `V<ts>__dashboard.sql` 두 방언 — `dashboard_daily(day date, source, kind, dimension varchar(200), value bigint, updated_at, PK(day, source, kind, dimension))` · `dashboard_layouts(account_id pk, layout varchar(4000), updated_at)`(두 표를 한 번에 — 잠근 뒤 못 고친다), `src/dbTest`
- `modules/dashboard/`: `DashboardIngestRunner`(자체 주기 작업 — account 정리 · 세션 정리와 같은 방식: 기동 직후 한 번 + 매일 `ingest.at`(대시보드 시간대), 프로젝트마다(`ProjectRegistry.forEachProject`), 어제까지 `backfill-days` 일을 upsert; 실패는 로그 + `OwnerAlerts`(alert 가 있으면) — 기동은 막지 않는다). 지난 날짜 읽기 도우미(`DailySeries` — 오늘은 실시간 함수, 어제까지는 표).
- 루트 `build.gradle.kts`(`dbTestModules`), `settings.gradle.kts`, 문서 · 카탈로그(Global Constraints), `migrations.lock`, sample · workbench 의존 한 줄

**먼저 쓸 실패 시험:**
- [ ] dbTest `DashboardDailyStoreTest`(두 방언) **멱등**: 같은 행 두 번 upsert → 한 줄 · 같은 값; 값이 바뀌면 덮어쓴다; 다른 차원은 따로.
- [ ] `DashboardIngestRunnerTest` **멱등**: 대역 적재기(날마다 같은 숫자)로 두 번 돌려도 표가 같다; `backfill-days=3` 이면 어제 포함 3일; 적재기 하나가 던져도 다른 적재기는 돈다.

**바뀐 범위 시험:**
```bash
./gradlew :modules:dashboard-jdbc:test :modules:dashboard-jdbc:postgresTest :modules:dashboard-jdbc:mysqlTest :modules:dashboard:test --tests '*Ingest*'
./gradlew :modules:platform:test --tests '*MigrationLockTest' --tests '*ModuleDocumentationTest' --tests '*CapabilitiesCatalogTest'
```

**끝 조건:** 위 통과.

---

### Task K4: `dashboard-cloudflare` (kotlin, 중) — 선행 K3

**Files:**
- Create `modules/dashboard-cloudflare/`: `CloudflareAnalyticsClient`(C4 질의 — `ExternalHttpClient`, 별칭 묶음, 환산), `CloudflareIngestor`(`source = cloudflare`; kind `visits` · `pageviews` · `referrer` · `path` · `country` · `device`, 차원 상위 50, 경로는 쿼리 떼고), `CloudflareMetrics`(`cloudflare.visits` · `cloudflare.pageviews`) · `CloudflareRankings`(네 개) — 오늘은 직접 질의(`live-cache` 5분, 프로젝트 키 single-flight), 어제까지는 `DashboardDailyStore`(없으면 `NO_HISTORY`), 키가 없으면 `NOT_CONNECTED`. `CloudflareProperties`(프로젝트별 — `ProjectRegistry.scopedFrom`), AutoConfiguration, `src/test/…/FakeCloudflareApi.kt`
- `ProjectConfigKeys.SUPPORTED` 에 `dashboard-cloudflare.`, `docs/multi-project.md` 표 한 줄, 문서(토큰 만드는 법: Account / Account Analytics / Read) · 카탈로그(`secrets` 에 토큰), sample · workbench 의존 + yml 빈 블록, `docs/deploy.md` §7 비밀 줄

**먼저 쓸 실패 시험:**
- [ ] `CloudflareIngestTest` **멱등**: 가짜 CF + 메모리 저장소로 같은 날 두 번 → 같은 행, 환산(`count × sampleInterval`)이 맞다, 경로에 `?` 가 남지 않는다.
- [ ] `CloudflareProjectsTest` **격리**: 프로젝트 둘(사이트 태그 다름) — 가짜 CF 가 받은 요청의 `siteTag` 가 프로젝트별; 오늘 범위를 프로젝트마다 다섯 번 → 가짜 CF 요청 프로젝트당 1회; 키 없는 프로젝트는 `NOT_CONNECTED` 이고 요청 0회; 토큰이 오류 메시지 · 로그에 없다.

**바뀐 범위 시험:**
```bash
./gradlew :modules:dashboard-cloudflare:test
./gradlew :modules:platform:test --tests '*ModuleDocumentationTest' --tests '*CapabilitiesCatalogTest' --tests '*ConfigPrefixDocumentationTest' --tests '*ProjectRegistry*'
```

**끝 조건:** 위 통과. **M1(형 토큰이 오면, 지휘가 부른 developer 한 번)**: 실제 토큰으로 질의 하나를 돌려 C4 의 필드 이름 · 무료 데이터셋 · 조회 가능 기간 · 봇 기본값을 재고, 다르면 클라이언트 · 가짜 둘(kotlin · TS) · 스펙 §3.2 를 고친다. 안 나오는 차원은 그 탭을 뺀다(스펙 §3.5).

---

### Task R2: 출처 카드 채우기 + 분석 스크립트 + 실시간 (react, 중) — 선행 R1, K4 · K5 와 나란히

**Files:**
- `packages/dashboard/`: `RankingCard` 탭에 CF · GA 출처, 「전체 보기」(`Dialog` + `useRanking(key)`), 열 여러 개(채널 · 세션 · 전환), `RealtimeBadge.tsx`(헤더 「지금 N명」 — `useRealtime` 60초, 누르면 `RealtimePanel` 페이지 · 국가, 「GA · 동의한 방문자」 표기), 신선도 문구(「약 n분 전 기준」 · 「GA 기준 n시 집계」), 스토리
- `packages/marketing/src/analytics/`: `createAnalyticsLoader({ consent, gaMeasurementId?, cfBeaconToken? })` — 동의 「analytics」가 켜질 때만 gtag 를 붙이고, 끄면 `gtag('consent','update',{analytics_storage:'denied'})`; CF 비콘은 토큰이 있으면 붙인다(쿠키 없음). 부수 효과는 `start()` 호출 때만(SSR 안전). README · `index.ts` · ssrFixtures
- sample: `main.tsx`(env 두 개를 읽어 `start()`), `.env.example` 두 줄, `capabilities.json` 의 marketing 항목 갱신

**먼저 쓸 실패 시험:**
- [ ] `analytics.test.ts` **동의 전 미전송**: 동의 전 · 거절 → `googletagmanager` 스크립트가 문서에 없다; `acceptAll()` 뒤에만 측정 ID 가 실린 스크립트; 측정 ID 가 없으면 동의해도 없다.
- [ ] 스토리 `play`: 「전체 보기」가 열리고 100줄을 보여 준다; 실시간 배지를 누르면 패널.

**바뀐 범위 시험:**
```bash
pnpm --filter @skeleton/dashboard --filter @skeleton/marketing test
pnpm --filter @skeleton/dashboard --filter @skeleton/marketing --filter sample typecheck
pnpm vitest run tests/ssr.safety.test.ts tests/stories.test.ts tests/capabilities.test.ts
```

**끝 조건:** 위 통과.

---

## 묶음 3 — GA · 실시간

### Task K5: `dashboard-ga` (kotlin, 중) — 선행 K3, K4 와 나란히

**Files:**
- Create `modules/dashboard-ga/`: `GaTokenSource`(서비스 계정 JSON → RS256 JWT → 토큰, 만료 전 재사용 — 라이브러리 없이 `java.security`), `GaDataClient`(runReport · runRealtimeReport), `GaIngestor`(`source = ga`; kind `channel` · `campaign` · `landing` · `transition`(`pageReferrer → pagePath`, 쿼리 뗌) · `returning`, 전환은 `key-event` 가 있을 때만 열), `GaRankings`(다섯 — 오늘은 `NO_HISTORY` 대신 「GA 기준 n시 집계」: 오늘 칸을 비우고 어제까지만), `GaRealtime` + `web/DashboardRealtimeController`(C1 realtime — `dashboard` 의 컨트롤러가 아니라 이 모듈이 연다, 없으면 404 → 프런트는 배지를 숨긴다), `GaProperties`(프로젝트별), `src/test/…/FakeGaApi.kt`(토큰 + 두 보고서)
- `ProjectConfigKeys.SUPPORTED` 에 `dashboard-ga.`, `docs/multi-project.md` 한 줄, 문서(서비스 계정 만들기 → GA 속성에 「뷰어」 → base64 → `.env.local`) · 카탈로그 · `docs/deploy.md` §7, sample · workbench 의존 + yml 빈 블록

**먼저 쓸 실패 시험:**
- [ ] `GaIngestTest` **멱등**: 가짜 GA 로 같은 날 두 번 → 같은 행; `key-event` 없으면 전환 행이 없다.
- [ ] `GaProjectsTest` **격리**: 프로젝트 둘 — 가짜 GA 가 받은 경로의 속성 id 가 프로젝트별; 실시간을 프로젝트마다 다섯 번 → 프로젝트당 1회(60초 캐시); 키 없는 프로젝트는 `NOT_CONNECTED`, 요청 0회; 키 JSON 이 로그 · 오류에 없다.
- [ ] `DashboardRealtimeController` 권한: `dashboard.read` 없으면 403(K1 의 시험 도우미 재사용).

**바뀐 범위 시험:**
```bash
./gradlew :modules:dashboard-ga:test
./gradlew :modules:platform:test --tests '*ModuleDocumentationTest' --tests '*CapabilitiesCatalogTest' --tests '*ConfigPrefixDocumentationTest'
```

**끝 조건:** 위 통과. **M2(형 키가 오면)**: 실제 속성으로 보고서 하나 · 실시간 하나를 돌려 C4 의 GA 이름(특히 `keyEvents:<이벤트>`, 실시간 차원)을 재고 다르면 클라이언트 · 가짜 둘을 고친다.

---

## 묶음 4 — 편집 저장 · 앱 통계 · e2e · 카탈로그

### Task K6: 화면 설정 API · 견본 앱 통계 · 조합 · 문서 (kotlin, 중) — 선행 K2 · K4 · K5 합침

**Files:**
- `modules/dashboard/`: `web/DashboardLayoutController`(C1 layout — 자기 계정 것만, `DashboardLayoutStore` 없으면 404 `LAYOUT_UNAVAILABLE`, 4000자 · 모르는 카드 키는 400)
- sample: Create `apps/sample/src/main/kotlin/dev/sumin/skeleton/app/sample/dashboard/SampleDashboardConfiguration.kt` — `app.board-posts`(기간 안 게시판별 글 수) · `app.board-reactions`(반응 종류별 수), 읽기 전용 SQL(앱이 조립한 모듈 표를 읽는 예시 — 문서에 「앱 자기 표가 정석」이라 적는다)
- 조합: `scripts/new-project.sh`(`dashboard-cloudflare` · `dashboard-ga` 를 고르고 `dashboard-jdbc` 를 안 고르면 NOTES 권함 — authz 와 같은 방식), `scripts/test-new-project.sh` — `--quick` 단정 둘(`dashboard-cloudflare` 의 닫힘에 `dashboard` · `platform` 만 더해진다; `dashboard` 를 빼면 `dashboard*` 디렉토리 · 문서 행 · 카탈로그 항목이 남지 않는다), `--full` 조합 `11-dashboard` = `--modules dashboard,dashboard-jdbc,dashboard-cloudflare,dashboard-ga`(dbTest 두 방언)
- Docs: `docs/minimal-composition.md`(기동 필요 표 넷) · `docs/new-project-recipe.md`(유료 SaaS 작업 예에 `dashboard,dashboard-jdbc`) · `docs/sample.md` · `CHANGELOG.md` `[Unreleased]`(이번 일 전체 블록 — 새 모듈 넷, 기여 셋, 인덱스 마이그레이션, 프로젝트 키, react 짝) · `perl scripts/build-capabilities.pl`

**먼저 쓸 실패 시험:**
- [ ] `DashboardLayoutTest` **권한**: 남의 계정 설정을 읽거나 쓸 길이 없다(경로에 계정 id 가 없고 `Authentication.name` 만 쓴다 — A 가 저장한 뒤 B 로 읽으면 빈 기본값); `dashboard.read` 없으면 403.
- [ ] dbTest(K3 묶음에 하나 더): 계정 삭제(ERASED) 리스너가 설정 줄을 지운다.

**바뀐 범위 시험:**
```bash
./gradlew :modules:dashboard:test --tests '*Layout*' :modules:dashboard-jdbc:postgresTest :modules:dashboard-jdbc:mysqlTest
./gradlew :apps:sample:test --tests '*ApplicationTests*'
./gradlew :modules:platform:test --tests '*CapabilitiesCatalogTest' --tests '*ModuleDocumentationTest'
bash scripts/test-new-project.sh --quick
```

**끝 조건:** 위 통과, `perl scripts/build-capabilities.pl --check` 깨끗.

---

### Task R3: 편집 모드 · 앱 통계 · e2e · 문서 (react, 중) — 선행 R2, e2e 만 K6 합친 뒤

**Files:**
- `packages/dashboard/`: `DashboardEditor.tsx`(편집 → 카드 · 타일 `Switch` 끄기, 카드 ▲▼ 순서(폰 포함), 넓은 화면은 끌어 옮기기 추가, 「완료」로 저장), `useLayout`(서버 `GET/PUT layout`, 404 `LAYOUT_UNAVAILABLE` 이면 `localStorage` — 키에 계정 id), `AppStatsCard`(묶음 `app` 탭 카드), 스토리 `play`(끄기 → 완료 → 다시 열면 꺼져 있다)
- sample i18n(앱 통계 카드 이름), `docs/ui-catalog.md` 행, `capabilities.json` 갱신 · `pnpm capabilities`, `CHANGELOG.md` `[Unreleased]` 블록(새 패키지 · marketing 분석 · sample 라우트)
- e2e: Create `apps/sample/e2e/fakeCloudflare.ts` · `fakeGa.ts`(C4 모양, `GET /control` 로 받은 요청 확인, 테스트용 RSA 키를 만들어 서비스 계정 JSON 을 준다), `globalSetup.ts`(기본 켬 · `E2E_FAKE_ANALYTICS=0` 이면 끔, `SPRING_APPLICATION_JSON` 에 C3 키 + 주소), `dashboard.e2e.ts`

**먼저 쓸 실패 시험:**
- [ ] e2e `dashboard.e2e.ts`(한 여정): 운영자 로그인 → 메뉴 첫 항목 「대시보드」 → 신규 가입 타일 ≥ 1(시드 계정) → CF 방문 타일 = 가짜 값, 유입 카드 CF 탭에 가짜 `refererHost` → GA 탭에 가짜 채널 → 헤더 「지금 7명」 → 순매출 타일을 누르면 그래프 제목이 순매출 → (기존 `paymentSteps` 로) 사용자가 응원 결제 한 건 → 다시 불러오면 결제 건수 +1.

**바뀐 범위 시험:**
```bash
pnpm --filter @skeleton/dashboard test
pnpm --filter @skeleton/dashboard --filter sample typecheck
pnpm vitest run tests/stories.test.ts tests/capabilities.test.ts tests/ssr.safety.test.ts
pnpm --filter sample e2e -- dashboard     # K6 를 합친 kotlin 백엔드(scripts/sample-e2e-backend.sh)로
```

**끝 조건:** 위 통과.

---

## 끝 검사 (지휘)

- kotlin: K2 → K3 → K4 → K5 → K6 순으로 합친 뒤 `perl scripts/build-capabilities.pl` · `perl scripts/migrations-lock.pl --check` → `kverify`(전체 build · `test-new-project.sh --full` 의 `11-dashboard` 포함 · 배포 계약) 한 번.
- react: R1 → R2 → R3 합친 뒤 `pnpm test` · `pnpm typecheck` · `pnpm lint` · `pnpm test:stories` · `pnpm e2e:sample` 한 번.
- 리뷰: kotlin 묶음 끝 1회 · react 묶음 끝 1회(`code-reviewer`, Review Focus 위주, 시험 재실행 안 시킴). 확인(`qa`) 1회 — 스펙 §7 을 진짜 화면으로(390px · 1280px 캡처는 화면당 2장까지).
- M1 · M2 는 형이 키를 준 뒤 — 묶음 순서를 막지 않는다.

## 예산

| 묶음 | 조각 | 예상(조각별) | 벽시계 | 나란히 |
|---|---|---|---|---|
| 1 DB 끝에서 끝까지 | K1 · K2 · R1 | K1 2h · K2 2h · R1 3h | 약 4h | K1 ∥ R1, 그다음 K2 ∥ K3 ∥ R1(계속) — 동시 3명 |
| 2 Cloudflare | K3 · K4 · R2 | K3 1.5h · K4 2.5h · R2 2.5h | 약 3h(K3 은 묶음 1 과 겹침) | K4 ∥ K5 ∥ R2 — 동시 3명 |
| 3 GA · 실시간 | K5 | 2.5h | (묶음 2 와 겹침) | |
| 4 마무리 | K6 · R3 | K6 2h · R3 2.5h(+e2e 1h) | 약 3.5h | K6 ∥ R3 — 동시 2명 |
| 지휘 끝 검사 · 리뷰 2 · QA 1 | | | 약 2h | |

- 조각 9개(kotlin 6 · react 3) = developer 9회 + 실측 M1 · M2(각 0.5h, 키가 오면) + 리뷰 2 · QA 1. 동시 최대 3명(kotlin 2 + react 1), 전체 벽시계 약 12~13h.

## 정해야 할 것

없음. 계획 초안의 다섯(계약 위치 · 퍼널 묶음 전달 · 관리 API 기본값 · 활성 비교 · GA 실시간)은 2026-10-26 지휘가 추천안으로 정했고 스펙 맨 위 표에 있다.
