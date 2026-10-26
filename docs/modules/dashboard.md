# dashboard

관리자 대시보드의 **조립기**: 다른 모듈이 빈으로 기여한 카드(숫자 타일 · 순위 탭 · 퍼널)를 모아 한 화면 분량의 JSON 으로 돌려준다 — 숫자 타일 + 그래프 시리즈(직전 기간과 겹쳐 보기), 탭 달린 순위 카드, 같은 사람 묶음 퍼널. 계산은 짧은 캐시(프로젝트마다)를 지나므로 운영자 열 명이 동시에 열어도 기여는 캐시 주기당 한 번 불린다. 이 모듈에는 카드가 하나도 없다 — `account-jdbc` · `auth-session-jdbc` · `payment-jdbc` 가 자기 숫자를 기여하고, 앱이 자기 카드를 더한다.
기본은 꺼짐이다(`skeleton.dashboard.admin.enabled=false` → 404). `dashboard` 를 안 붙인 앱은 아무것도 달라지지 않는다. 프런트용 HTTP 계약: [dashboard-http-contract.md](../dashboard-http-contract.md).

| 메서드 · 경로 | 하는 일 | 필요한 권한 |
|---|---|---|
| `GET /api/v1/admin/dashboard?from&to&compare` | 기간의 타일 · 시리즈 · 순위(상위 10) · 퍼널 | `dashboard.read` |
| `GET /api/v1/admin/dashboard/rankings/{key}?from&to&limit` | 순위 하나를 더 길게(「전체 보기」, `limit` ≤ 500) | `dashboard.read` |

`from` · `to` 는 대시보드 시간대의 날짜(양 끝 포함), 둘 다 없으면 오늘. 하루면 시간별, 92일까지 일별, 그 위는 주별, 최대 366일. 잘못된 기간은 400 `DASHBOARD.RANGE_INVALID`, 없는 순위는 404 `DASHBOARD.RANKING_NOT_FOUND`. 카드 하나가 터지거나 키가 없어도 그 카드만 `status`(`ERROR` · `NOT_CONNECTED` · `NO_HISTORY`)로 말하고 응답 전체는 200 이다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:dashboard"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | `alert` (있으면 적재 실패를 알린다) |
| 설정 접두사 | `skeleton.dashboard` — [docs/config/modules/dashboard.yml](../config/modules/dashboard.yml) |
| 기본 동작 | 꺼짐. `skeleton.dashboard.admin.enabled=true` 이고 서블릿 웹 앱 + Spring Security 가 있을 때만 컨트롤러가 선다. 켜도 권한 `dashboard.read`(전권 역할은 늘 가진다)가 있어야 부른다. 권한 선언 `dashboard.read` 는 모듈이 클래스패스에 있으면 켜지 않아도 낸다 |
| 부팅에 필요한 것 | 없다. 기여 빈이 없으면 카드가 비어 있을 뿐이다 |
| 교체 지점 | `DashboardAssembler`, `DashboardCache`, `DashboardRangeParser`, `DashboardAdminController`, 기여 계약 빈(`DashboardMetric` · `DashboardRanking` · `DashboardFunnel` …) |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/dashboard` (HTTP 계약: [dashboard-http-contract.md](../dashboard-http-contract.md)) |
| 테스트 | `modules/dashboard/src/test`, `modules/dashboard/src/noOptionalTest` (`spring-security-core` 가 없을 때) |

## 앱이 바꾸는 곳

- 켜기: `skeleton.dashboard.admin.enabled=true`, 보는 사람에게 `dashboard.read` 를 역할로 준다(`skeleton.authz.roles.<역할>: [dashboard.read]` 또는 `authz-jdbc` 의 역할 화면).
- `skeleton.dashboard.zone` · `currency` · `cache.db` — [dashboard.yml](../config/modules/dashboard.yml). 여러 통화가 섞이는 돈 타일은 합치지 않고 통화마다 한 줄로 돌려주며 `currency` 통화가 맨 앞이다.
- **카드 더하기**: 계약 타입은 `platform` 의 `dev.sumin.skeleton.common.dashboard` 에 있다(모듈이 `dashboard` 를 컴파일에 끌어오지 않고 기여하려고). 빈으로 두면 끝이다.
  - 타일: `DashboardMetric` — `key` · `label` · `unit` · `source` · `order` · `value(range)` · `series(range, interval)`. `comparable = false` 면 직전 기간을 계산하지 않는다.
  - 순위 탭: `DashboardRanking` — `group`(`traffic` · `pages` · `geo` · `app`) · `columns` · `top(range, limit)`.
  - 기여는 예외 대신 `DashboardResult(status = NOT_CONNECTED | NO_HISTORY | ERROR)` 로 말하게 한다. 던져도 그 카드만 `ERROR` 가 되고 로그에는 예외 종류만 남는다 — 메시지에 비밀이 있을 수 있어서다.
  - 기여하는 쪽 모듈의 자동 설정은 `@ConditionalOnClass(name = ["dev.sumin.skeleton.dashboard.DashboardAutoConfiguration"])` 로 등록한다. 기여 모듈이 `dashboard` 를 `compileOnly` 로도 끌어오지 않아야 `new-project.sh` 가 대시보드 소스를 안 따라 보낸다.
  - 프로젝트 모드(`skeleton.projects`)에서는 `value`/`series`/`top` 이 요청의 프로젝트 문맥 안에서 불린다 — 자기 프로젝트의 숫자만 돌려준다. 캐시 키가 프로젝트마다 따로다.
- **퍼널 비용**: 묶음은 많아야 `funnel.max-cohort`(5만)+1 명만 읽고(`members(range, limit)`), 넘으면 그 퍼널만 `TOO_LARGE` 다. 결과는 5분 캐시된다(`cache.funnel`). 구현은 `limit` 을 질의에 걸어야 한다.
- **그래프는 질의 한 번**: `series` 는 칸마다 질의하지 않는다 — 범위의 (시각, 값)을 한 번 읽어 `DashboardBuckets.series` 로 칸에 나눈다. 돈 타일의 `series` 는 조립기가 정해 넘기는 통화(`value[0].currency`) 한 통화만 돌려준다.
- **퍼널 바꾸기**: 기본은 `DashboardCohortSource` 빈 하나(묶음: 기간 안에 들어온 계정)와 `DashboardFunnelStep` 빈들(`order` 순, 단계마다 「묶음 중 닿은 사람 수」)이다. 앱이 `DashboardFunnel` 빈을 두면 그것이 이긴다. 묶음은 계정 id 목록을 메모리로만 넘기고 저장하지 않으며, 단계 구현은 id 를 1000 개씩 끊어 질의한다. 비율은 묶음 크기 기준이다.
- 저장 포트(`DashboardDailyStore` · `DashboardIngestor` · `DashboardLayoutStore`)는 이 모듈이 타입만 가진다 — 구현은 `dashboard-jdbc`(일별 집계 · 화면 설정), 외부 출처의 적재기(`DashboardIngestor` 빈)는 각 출처 모듈이 낸다. 이 모듈에는 메모리 구현이 없다.
- **일별 적재 작업**(`DashboardIngestRunner`, `skeleton.dashboard.ingest`): 저장소와 적재기 빈이 있을 때만 일한다 — 기동 직후 한 번, 그다음 매일 `ingest.at`(대시보드 시간대)에 어제부터 `backfill-days` 일을 다시 적재한다. 프로젝트 모드에서는 프로젝트마다. 적재기 하나가 실패해도 다른 적재기는 돌고 기동은 막지 않으며, 실패는 로그와(`alert` 가 있으면 출처당 하루 한 번) 알림으로 알린다 — 예외 메시지는 싣지 않는다. 여러 인스턴스가 같은 시각에 돌 수 있지만 같은 날을 다시 써도 같은 행(upsert)이라 결과가 같다.
- **지난 날짜 읽기**(`DailySeries`): 외부 출처 카드가 기간을 「표에서 읽는 어제까지」와 「오늘(실시간)」로 가를 때 쓴다.

## Decisions and rejected alternatives

**기여 계약 타입은 `platform` 에.** `account-jdbc` 가 `dashboard` 를 컴파일에 걸면 `new-project.sh` 가 스타터로 찍는 모든 프로젝트에 대시보드 소스를 끌고 간다. authz 의 `PermissionDeclaration` 과 같은 선례다. 버린 것: *기여 모듈이 `compileOnly(dashboard)`*.

**캐시는 조립기에, 키는 프로젝트마다.** 화면 요청이 DB · 외부 API 를 직접 두드리지 않게 한다. 같은 키가 동시에 와도 계산은 한 번(single-flight). 오류 결과는 담지 않아 다음 요청이 다시 시도한다.

**스켈레톤 중립의 예외 하나 — 인덱스.** 기여 모듈의 범위 인덱스(`accounts.created_at` · `auth_sessions.last_used_at` · `payment_orders.paid_at` · `payment_refunds.completed_at`)는 마이그레이션이라 `dashboard` 를 안 붙인 앱에도 들어간다. 특히 `auth_sessions.last_used_at` 은 토큰 갱신마다 바뀌는 열이라 그 인덱스 유지 비용이 생긴다. 이 예외를 빼면 기여 질의가 표 전체를 훑는다.

**관리 API 는 기본 꺼짐.** 스켈레톤은 사용법을 고르지 않는다(authz · payment · account 와 같다) — 켜는 것은 앱 설정이다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
