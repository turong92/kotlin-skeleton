# ads-web-rewarded-jdbc

`ads-web-rewarded` 의 저장 포트(`WebRewardStore`)를 JDBC(PostgreSQL · MySQL)로 구현한다. 테이블 2 개: `ads_web_rewarded_sessions`(광고 세션 — 열 때 한 줄, 보상 요청이 `granted_at` 을 한 번 채운다. 토큰 모드의 토큰은 SHA-256 해시만 둔다) · `ads_web_rewarded_daily_counts`(하루 보상 카운터 — `scope`(`ACCOUNT` · `IP`) · `subject` · `day_start` 가 키).

**돈 같은 영역의 불변식**을 이 모듈이 지킨다. 같은 세션의 보상 요청은 세션 줄을 잠그고(`select … for update`) 직렬로 돌아, 동시 요청 여덟 개가 와도 한 번만 지급되고 나머지는 `SESSION_USED` 다. 하루 상한은 「없으면 0 줄을 만들고(insert ignore) → `granted < max` 일 때만 +1」 하는 갱신이라 상한 직전에 서로 다른 세션 둘이 동시에 와도 하나만 통과하고 카운터는 정확히 max 다. 토큰은 「해시 · 계정 · 목적이 맞고 안 쓰였고 만료 전」인 줄만 갱신하는 한 문장이라(갱신 행 수 1 이 곧 성공) 같은 토큰은 한 번만 쓰인다. 지급기나 DB 오류가 나면 세션 · 카운터 쓰기가 전부 되돌아가 세션은 열린 채로 남고 다시 요청하면 지급된다 — **지급기와 이 표가 같은 DB(같은 트랜잭션)여야** 이 보장이 선다(`reward` 모듈과 같은 전제). 계정 삭제 고리(`ads-web-rewarded` 의 `webRewardErasure`)는 그 계정의 세션과 계정 카운터를 지우고 IP 카운터는 남긴다. 지난 세션 · 카운터 줄을 지우는 작업은 아직 없다(다음에 넓힐 것).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:ads-web-rewarded-jdbc"))` |
| 함께 오는 모듈 | `ads-web-rewarded`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (설정은 ads-web-rewarded 모듈의 것) |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 `WebRewardStore` 를 등록한다 (앱이 같은 포트의 빈을 두면 그쪽이 쓰인다). 경로는 `ads-web-rewarded` 의 `enabled=true` 일 때 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마 (`spring.flyway.locations: classpath:db/migration/{vendor}`) |
| 교체 지점 | `WebRewardStore` |
| 마이그레이션 | `modules/ads-web-rewarded-jdbc/src/main/resources/db/migration/postgresql`, `modules/ads-web-rewarded-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/ads` |
| 테스트 | `modules/ads-web-rewarded-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`) — 같은 세션 동시 보상은 한 번 · 상한 직전 동시 보상은 하나만 · 지급기 실패 되돌림 · 토큰 1회 · 다른 계정 · 목적 · 만료 거절 · 계정 삭제) |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
