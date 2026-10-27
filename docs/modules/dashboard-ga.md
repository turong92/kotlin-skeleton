# dashboard-ga

대시보드에 **흐름**을 얹는다: 순위 탭 다섯 — 유입 채널(`ga.channels`) · 캠페인(`ga.campaigns`) · 첫 페이지(`ga.landing-pages`) · 페이지 이동(`ga.page-transitions`, `참조 → 경로`) · 새/재방문(`ga.returning`, 「New」 · 「Returning」 두 줄) — 과 헤더의 **현재 방문자**(`GET /api/v1/admin/dashboard/realtime`: 최근 30분 활성 사용자 · 지금 보는 페이지 · 국가). 핵심 이벤트 이름(`key-event`)을 주면 채널 · 캠페인 · 첫 페이지에 「Conversions」 열이 생긴다.
GA 는 쿠키에 동의한 방문자만 센다(프런트가 동의 전에는 gtag 를 띄우지 않는다). 방문 수 전체는 Cloudflare 몫이고, 이 모듈은 「어디서 왔고 어디로 갔나」다.
읽기는 GA4 Data API v1beta(서비스 계정 키 → RS256 JWT → 접근 토큰, 라이브러리 없이 `java.security`). **화면 요청은 GA 를 직접 부르지 않는다**: 순위는 새벽 적재 작업이 `dashboard-jdbc` 의 일별 표에 옮겨 둔 값만 읽는다(`DAILY`, 신선도는 마지막 적재 시각). GA 일반 보고서는 몇 시간 늦게 확정되므로 **오늘은 적재도 질의도 하지 않는다** — 오늘 칸은 비우고 어제까지만 보인다(「GA 기준 n시 집계」). 현재 방문자만 직접 질의하고 서버 캐시(`realtime-cache`, 기본 60초, 프로젝트마다 한 번)를 거친다(`CACHED`) — 실패도 60초 캐시해 장애 중에 폴링마다 Google 을 다시 부르지 않는다. 새/재방문의 사용자 수는 날마다 적재한 값을 더한 것이라 여러 날 온 사람이 여러 번 센다(비율은 근사치). 표가 없는 앱(`dashboard-jdbc` 없음)에서 지난 날이 걸린 기간은 `NO_HISTORY`, 키가 비면 `NOT_CONNECTED`(기동은 막지 않는다), 외부 호출이 실패하면 `ERROR`(그 카드만).
일별 표에는 **집계 숫자만** 담는다 — URL 은 쿼리 문자열과 조각을 떼고 요청 로그와 같은 경로 마스킹 규칙(`LogMaskingContributor` 로 더한 주소 속 비밀 조각 규칙, 없으면 그대로)을 거친 것, 빈 차원은 `(none)`, 상위 50개. 날짜는 GA 속성의 시간대 기준이므로 속성과 대시보드 시간대를 맞춰 둔다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:dashboard-ga"))` |
| 함께 오는 모듈 | `dashboard`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.dashboard-ga` — [docs/config/modules/dashboard-ga.yml](../config/modules/dashboard-ga.yml) |
| 기본 동작 | 켜짐. 속성 id 와 서비스 계정 키가 모두 있어야 연결된다. 비어 있으면 카드는 `NOT_CONNECTED`, 적재기는 아무것도 하지 않는다. 현재 방문자 경로는 `skeleton.dashboard.admin.enabled=true` + 서블릿 웹 앱 + Spring Security 일 때만 서고 권한 `dashboard.read` 가 필요하다 |
| 부팅에 필요한 것 | 없다 (키 없이 기동한다). 지난 기간을 보려면 `dashboard-jdbc` |
| 교체 지점 | `GaDataClient`, `GaTokenSource`, `GaSettings` |
| 마이그레이션 | 없음 (표는 `dashboard-jdbc`) |
| 프론트 짝 | `@skeleton/dashboard` (HTTP 계약: [dashboard-http-contract.md](../dashboard-http-contract.md)) |
| 테스트 | `modules/dashboard-ga/src/test` (가짜 Google — JWT 서명 확인 · 적재 멱등 · 전환 열 · URL 쿼리 제거 · 프로젝트 격리 · 실시간 캐시 한 번 · 키 미노출 · 현재 방문자 권한) |

## 앱이 바꾸는 곳

- **서비스 계정 만들기**: Google Cloud 콘솔 → 프로젝트 선택 → API 및 서비스에서 「Google Analytics Data API」 사용 설정 → IAM 및 관리자 → 서비스 계정 만들기 → 키 추가(JSON). 그 계정의 이메일을 GA 관리 → 속성 액세스 관리에 **「뷰어」**로 추가한다(읽기 전용). 키 JSON 을 base64 한 한 줄(`base64 < key.json | tr -d '\n'`)을 `SKELETON_DASHBOARD_GA_CREDENTIALS`(배포 선언의 `secrets:`, 로컬은 `.env.local`)로 넣고, 속성 id(GA 관리 → 속성 설정의 숫자)를 `SKELETON_DASHBOARD_GA_PROPERTY_ID` 로 넣는다. 키는 커밋하지 않는다.
- **전환**: GA 의 핵심 이벤트(key event) 이름을 `key-event` 로 주면 「Conversions」 열이 생긴다. 비우면 열이 없다.
- **프로젝트 모드(`skeleton.projects`)**: 속성 id 와 키를 `skeleton.projects.<id>.config.dashboard-ga.{property-id, credentials}` 에 프로젝트마다 적는다. **공통 값을 물려받지 않는다** — 둘 중 하나라도 프로젝트 `config` 에 없으면 그 프로젝트의 카드는 `NOT_CONNECTED` 다. 토큰 캐시 · 실시간 캐시 · 일별 표도 프로젝트마다 따로다.
- 프런트: `VITE_GA_MEASUREMENT_ID`(측정 ID) 를 주면 쿠키 동의 「분석」 뒤에 gtag 가 뜬다 — 이 모듈은 읽기만 한다.
- **확인 못 한 것**: 차원 · 지표 이름(특히 핵심 이벤트 지표 `keyEvents:<이벤트>`, 페이지 이동의 `screenPageViews`, 실시간 차원 `unifiedScreenName` · `country`)은 계획 C4 의 추측이다. 실제 속성으로 보고서 하나 · 실시간 하나를 돌려 재고 이 모듈의 클라이언트와 가짜를 맞춘다.

## Decisions and rejected alternatives

**순위는 표에서만, 오늘은 비운다.** GA 일반 보고서는 몇 시간 뒤에 확정돼 오늘 숫자가 계속 바뀌고 모양도 달라진다(처리 중 값). 화면마다 부르면 한도(속성당 시간당 호출 수)와 지연이 운영자 수에 비례한다. 버린 것: *오늘 칸을 직접 질의*.

**프로젝트는 자기 속성과 키만.** 공통 설정에서 속성 id · 키를 물려받으면 프로젝트 B 의 화면에 A 속성의 숫자가 나온다. 연결 안 됨으로 두는 쪽이 조용히 틀린 숫자를 보이는 쪽보다 낫다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
