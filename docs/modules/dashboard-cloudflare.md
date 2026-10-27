# dashboard-cloudflare

대시보드에 **접속 숫자**를 얹는다: 방문 · 페이지뷰 타일(`cloudflare.visits` · `cloudflare.pageviews`)과 순위 탭 넷(유입 `cloudflare.referrers` · 많이 본 페이지 `cloudflare.paths` · 국가 `cloudflare.countries` · 기기 `cloudflare.devices`). 숫자는 Cloudflare **Web Analytics(RUM)** 가 센다 — 쿠키 없음, 정적 페이지 포함, 우리 서버 부담 없음(주황 구름이면 Cloudflare 가 비콘을 자동으로 넣는다).
읽기는 GraphQL Analytics API(`rumPageloadEventsAdaptiveGroups`)이고, 숫자는 Cloudflare 가 돌려준 추정값 그대로 쓴다(표본 간격을 다시 곱하지 않는다 — 실측, 아래). **화면 요청은 Cloudflare 를 직접 부르지 않는다**: 어제까지는 새벽 적재 작업이 `dashboard-jdbc` 의 일별 표에 옮겨 둔 값을 읽고(`DAILY`), 오늘은 서버 캐시(`live-cache`, 기본 5분, 프로젝트마다 한 번)를 거쳐 직접 질의한다(`CACHED`). 운영자가 열 명이어도 외부 호출은 캐시 주기당 한 번이다. 실패한 호출도 60초 동안 캐시해(장애 중에 폴링마다 다시 부르지 않는다) 그 사이에는 카드가 `ERROR` 로 답한다. 오늘 직접 질의한 경로도 적재분과 같이 쿼리 문자열을 뗀다. 표가 없는 앱(`dashboard-jdbc` 없음)에서 지난 날이 걸린 기간은 `NO_HISTORY`, 키가 비면 `NOT_CONNECTED`(기동은 막지 않는다), 외부 호출이 실패하면 `ERROR`(그 카드만).
일별 표에는 **집계 숫자만** 담는다 — 경로는 쿼리 문자열과 조각을 뗀 것, 빈 유입은 `(none)`, 차원은 상위 50개.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:dashboard-cloudflare"))` |
| 함께 오는 모듈 | `dashboard`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.dashboard-cloudflare` — [docs/config/modules/dashboard-cloudflare.yml](../config/modules/dashboard-cloudflare.yml) |
| 기본 동작 | 켜짐. 계정 id · 사이트 태그 · 토큰이 모두 있어야 연결된다. 비어 있으면 카드는 `NOT_CONNECTED`, 적재기는 아무것도 하지 않는다 |
| 부팅에 필요한 것 | 없다 (키 없이 기동한다). 지난 기간을 보려면 `dashboard-jdbc` |
| 교체 지점 | `CloudflareAnalyticsClient`, `CloudflareSettings` |
| 마이그레이션 | 없음 (표는 `dashboard-jdbc`) |
| 프론트 짝 | `@skeleton/dashboard` (HTTP 계약: [dashboard-http-contract.md](../dashboard-http-contract.md)) |
| 테스트 | `modules/dashboard-cloudflare/src/test` (가짜 Cloudflare — 적재 멱등 · 추정값 그대로 · 경로 쿼리 제거 · 자기 사이트 이동(방문 0) 제외 · 프로젝트 격리 · 캐시 한 번 · 토큰 미노출) |

## 앱이 바꾸는 곳

- **토큰 만들기**: Cloudflare 대시보드 → My Profile → API Tokens → Create Token → Custom, 권한 **Account / Account Analytics / Read** 하나(읽기 전용). 계정 id 는 대시보드 주소의 `dash.cloudflare.com/<계정 id>`, 사이트 태그는 Web Analytics 의 사이트 화면. 토큰은 환경변수 `SKELETON_DASHBOARD_CLOUDFLARE_API_TOKEN`(배포 선언의 `secrets:`)로 넣고 커밋하지 않는다.
- **프로젝트 모드(`skeleton.projects`)**: 계정 id · 사이트 태그 · 토큰을 `skeleton.projects.<id>.config.dashboard-cloudflare.{account-id, site-tag, api-token}` 에 프로젝트마다 적는다. **공통 값을 물려받지 않는다** — 셋 중 하나라도 프로젝트 `config` 에 없으면 그 프로젝트의 카드는 `NOT_CONNECTED` 다(남의 사이트 숫자가 이 프로젝트 화면에 나오지 않게). 캐시와 일별 표도 프로젝트마다 따로다.
- 프런트에서 비콘을 직접 넣는 경우(주황 구름을 안 거치는 배포)만 `VITE_CF_BEACON_TOKEN` — 프록시를 거치면 Cloudflare 가 자동으로 넣으므로 채우지 않는다(둘 다 붙으면 두 번 센다).
- **실측(2026-10-27, 무료 계정, 방문 수십 건 규모의 사이트)**: `rumPageloadEventsAdaptiveGroups` 가 무료에서 열리고 질의 모양(계정 범위 `accounts(filter: { accountTag })`, 변수 `$filter` 의 `siteTag` · `datetime_geq` · `datetime_lt`)과 필드(`count` · `sum { visits }` · `avg { sampleInterval }` · `dimensions { date datetimeHour refererHost requestPath countryName deviceType }`)가 그대로 맞는다. 알아낸 것:
  - **`count`(페이지뷰) · `sum.visits`(방문)는 이미 추정값**이다 — `sampleInterval` 이 10 인 줄의 값은 모두 10 의 배수(표본 하나가 `count: 10`). 다시 곱하면 10 배로 부푼다. 표본 간격은 질의 범위가 클수록 커진다(하루 질의는 1, 한 달 질의는 10) — 그래서 하루씩 적재하는 쪽이 정확하고 큰 범위의 직접 질의는 거칠다.
  - **자기 사이트 안의 이동은 `refererHost` 가 자기 호스트이고 `visits` 가 0** 이다(`count` 만 있다). 유입 순위는 방문 기준이라 방문 0 줄을 빼고, 방문으로 정렬해(`sum_visits_DESC`) 상위 50 칸을 내부 이동이 차지하지 않게 한다. 리퍼러 없음(직접 방문)은 빈 문자열이라 「(direct)」 로 적는다. 국가는 코드(`KR`), 기기는 `desktop` · `mobile`.
  - 시계열은 `datetimeHour`(`…T17:00:00Z`)로 시간별이 된다. 한 달 전까지 질의가 된다(더 오래된 보관 기간은 사이트가 새것이라 재지 못했다).
  - 하루치 적재 합계 = 같은 하루를 직접 질의한 합계, 4일 합 = 일별 합 = 시간별 합 (페이지뷰 382 · 방문 124 로 대조).
  - **경로에 공유 링크 토큰이 들어갈 수 있다**(`/e/<토큰>` 같은 공개 열람 주소). 일별 표와 「많이 본 페이지」 에 그대로 보이므로 그런 주소가 있는 앱은 운영자만 보는 화면임을 알아 둔다.

## Decisions and rejected alternatives

**어제까지는 표, 오늘만 직접.** Cloudflare 는 원본을 짧게 보관하고(표본 축소) 질의 한도(5분당 300)가 있다. 화면마다 부르면 한도와 지연이 운영자 수에 비례한다. 버린 것: *화면 요청마다 직접 질의*.

**프로젝트는 자기 키만.** 공통 설정에서 계정 id · 토큰을 물려받으면 프로젝트 B 의 화면에 A 사이트의 방문이 나온다. 물려받지 않고 연결 안 됨으로 두는 쪽이 조용히 틀린 숫자를 보이는 쪽보다 낫다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
