# auth-session-jdbc

`auth-session` 의 저장을 DB 에 둔다 — `auth_sessions`(기기 · IP · 마지막 사용 · 철회) 와 `auth_refresh_tokens`(토큰 해시, `used_at` 이 비면 지금 쓸 수 있는 토큰).
한 토큰을 두 번 쓰지 못하게 하는 것은 조건부 UPDATE 한 문장이다 — 16 스레드가 같은 토큰을 내밀어도 정확히 하나만 이긴다 (두 DB 에서 시험).

- **일별 활동 기록**: 표 `auth_account_activity` — 하루에 한 줄 `(account_id, day)`. 로그인과 토큰 새로고침 때 `auth-session` 이 남긴다(같은 계정이 같은 날 이미 기록됐으면 저장소를 부르지 않고, 저장소가 `insert … on conflict do nothing` 이라 여러 인스턴스가 써도 한 줄). **인증 경로라 쓰기가 실패해도 로그인은 막지 않는다**(로그에는 예외 종류만). 개인 값은 계정 id 뿐(IP · 기기 · 시각 없음)이고 계정이 지워질 때 `AccountErasureListener` 가 같이 지운다. 보존 기간은 `skeleton.auth-session.activity.retention`(기본 400일 — 대시보드가 거슬러 볼 수 있는 길이), 날짜를 세는 시간대는 `skeleton.auth-session.activity.zone`(비면 앱 시간대). 이 표가 없으면(저장소가 메모리) 기록은 아무것도 하지 않는다. 기록이 실패하면(DB 가 죽었거나 `schema.sql` 앱이 표를 안 넣었거나) 30초 동안 다시 부르지 않고 WARN 한 줄만 남긴다(로그인은 그대로).
  **업그레이드 직후**: 기록은 마이그레이션을 적용한 날부터 쌓이므로 직전 기간이 비어 있으면 증감이 튄다. 원하면 세션의 마지막 사용일로 한 번 **근사 백필**한다(세션당 마지막 날 하나뿐이라 모자란다 — 새 기록이 쌓이면 맞아진다). 날짜 시간대는 `activity.zone` 으로 바꾼다: PostgreSQL `insert into auth_account_activity (account_id, day) select distinct account_id, (last_used_at at time zone 'Asia/Seoul')::date from auth_sessions on conflict do nothing;` · MySQL `insert ignore into auth_account_activity (account_id, day) select distinct account_id, date(convert_tz(last_used_at, '+00:00', '+09:00')) from auth_sessions;` 개인정보 처리방침의 보유 항목에 「서비스를 이용한 날짜(계정별)」를 적는다.
- **대시보드 기여**: `dashboard` 모듈이 클래스패스에 있으면 활성 이용자 타일(`auth-session.active`, 기간 안 일별 활동 기록의 서로 다른 계정 수)과 퍼널 단계 `auth-session.returned`(가입한 날 이후의 날에 활동한 적이 있다)를 기여한다. **정확하다** — 세션의 마지막 사용 시각을 덮어쓰는 방식이 아니라서 지난 기간도 같은 정의로 세고 직전 기간과 비교한다(`comparable = true`). 시간별 그래프는 날 단위 기록이라 그날의 합계 한 점이다. 기간은 대시보드 시간대의 날짜로 자르므로 `activity.zone` 과 `dashboard.zone` 을 같게 둔다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-session-jdbc"))` |
| 함께 오는 모듈 | `auth-session`, `auth`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcSessionStore` |
| 마이그레이션 | `modules/auth-session-jdbc/src/main/resources/db/migration/postgresql`, `modules/auth-session-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth-session-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [auth-session](auth-session.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
