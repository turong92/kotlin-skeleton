# dashboard-cloudflare

대시보드에 **접속 숫자**를 얹는다: 방문 · 페이지뷰 타일(`cloudflare.visits` · `cloudflare.pageviews`)과 순위 탭 넷(유입 `cloudflare.referrers` · 많이 본 페이지 `cloudflare.paths` · 국가 `cloudflare.countries` · 기기 `cloudflare.devices`). 숫자는 Cloudflare **Web Analytics(RUM)** 가 센다 — 쿠키 없음, 정적 페이지 포함, 우리 서버 부담 없음(주황 구름이면 Cloudflare 가 비콘을 자동으로 넣는다).
읽기는 GraphQL Analytics API(`rumPageloadEventsAdaptiveGroups`)이고, 표본 추출된 값은 `sampleInterval` 로 환산한다. **화면 요청은 Cloudflare 를 직접 부르지 않는다**: 어제까지는 새벽 적재 작업이 `dashboard-jdbc` 의 일별 표에 옮겨 둔 값을 읽고(`DAILY`), 오늘은 서버 캐시(`live-cache`, 기본 5분, 프로젝트마다 한 번)를 거쳐 직접 질의한다(`CACHED`). 운영자가 열 명이어도 외부 호출은 캐시 주기당 한 번이다. 표가 없는 앱(`dashboard-jdbc` 없음)에서 지난 날이 걸린 기간은 `NO_HISTORY`, 키가 비면 `NOT_CONNECTED`(기동은 막지 않는다), 외부 호출이 실패하면 `ERROR`(그 카드만).
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
| 테스트 | `modules/dashboard-cloudflare/src/test` (가짜 Cloudflare — 적재 멱등 · 환산 · 경로 쿼리 제거 · 프로젝트 격리 · 캐시 한 번 · 토큰 미노출) |

## 앱이 바꾸는 곳

- **토큰 만들기**: Cloudflare 대시보드 → My Profile → API Tokens → Create Token → Custom, 권한 **Account / Account Analytics / Read** 하나(읽기 전용). 계정 id 는 대시보드 주소의 `dash.cloudflare.com/<계정 id>`, 사이트 태그는 Web Analytics 의 사이트 화면. 토큰은 환경변수 `SKELETON_DASHBOARD_CLOUDFLARE_API_TOKEN`(배포 선언의 `secrets:`)로 넣고 커밋하지 않는다.
- **프로젝트 모드(`skeleton.projects`)**: 계정 id · 사이트 태그 · 토큰을 `skeleton.projects.<id>.config.dashboard-cloudflare.{account-id, site-tag, api-token}` 에 프로젝트마다 적는다. **공통 값을 물려받지 않는다** — 셋 중 하나라도 프로젝트 `config` 에 없으면 그 프로젝트의 카드는 `NOT_CONNECTED` 다(남의 사이트 숫자가 이 프로젝트 화면에 나오지 않게). 캐시와 일별 표도 프로젝트마다 따로다.
- 프런트에서 비콘을 직접 넣는 경우(주황 구름을 안 거치는 배포)만 `VITE_CF_BEACON_TOKEN` — 프록시를 거치면 Cloudflare 가 자동으로 넣으므로 채우지 않는다(둘 다 붙으면 두 번 센다).
- **확인 못 한 것**: GraphQL 필드 이름 · 무료 계정에서 열리는 데이터셋 · 조회 가능 기간 · 봇 기본값은 계획 C4 의 추측이다. 실제 토큰으로 질의 하나를 돌려 재고 이 모듈의 클라이언트와 가짜를 맞춘다(안 나오는 차원은 그 탭을 뺀다).

## Decisions and rejected alternatives

**어제까지는 표, 오늘만 직접.** Cloudflare 는 원본을 짧게 보관하고(표본 축소) 질의 한도(5분당 300)가 있다. 화면마다 부르면 한도와 지연이 운영자 수에 비례한다. 버린 것: *화면 요청마다 직접 질의*.

**프로젝트는 자기 키만.** 공통 설정에서 계정 id · 토큰을 물려받으면 프로젝트 B 의 화면에 A 사이트의 방문이 나온다. 물려받지 않고 연결 안 됨으로 두는 쪽이 조용히 틀린 숫자를 보이는 쪽보다 낫다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
