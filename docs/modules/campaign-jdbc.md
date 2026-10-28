# campaign-jdbc

`campaign` 의 이벤트 저장 포트 `CampaignStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 넷: `campaigns`(종류 · 기간 · `config_json` text) · `campaign_attendance`(`(campaign_id, account_id, day)` 키 — 하루 한 번) ·
`campaign_referral_codes`(계정당 코드 하나, 코드 유니크) · `campaign_referrals`(`invitee_id` 키 — 초대받은 사람은 평생 한 번; 상태 `CLAIMED` · `QUALIFIED` · `CAPPED`).

규칙: 「새로 넣었나」는 갱신 행 수로 판정한다 — PostgreSQL `on conflict do nothing` / MySQL `insert ignore` 모두 이미 있으면 0 줄이다(공용 `SqlDialect.insertIgnore` 의 MySQL 판은 이미 있는 행에도 1 을 돌려줘 쓸 수 없다; 유니크 위반 예외는 PostgreSQL 에서 호출자 트랜잭션을 통째로 abort 시킨다) ·
같은 순간의 두 출석은 키가 한 줄만 받는다 · 초대자 상한 · 맞초대 판정은 `campaign_referral_codes` 줄을 정렬된 순서로 `for update` 잡고(`lockAccounts`) 잠금 읽기로 센다(집계는 PostgreSQL 이 `for update` 를 못 받아 줄을 읽어 센다) · 계정 탈퇴는 초대자 쪽 `campaign_referrals.inviter_id` 만 톰스톤으로 바꾸고 줄은 남긴다 · 초대 상태 전이는 `where status = 'CLAIMED'` 조건부 갱신 · 날짜는 `date` 열(`LocalDate`, 서비스 시간대 기준 — 변환하지 않는다) · 시각은 `Instant` · 설정은 `*_json text`(`json` 모듈의 `JsonCodec`).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:campaign-jdbc"))` |
| 함께 오는 모듈 | `campaign`, `reward`, `platform`, `persistence-jdbc`, `json` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `campaign` 과 `DataSource` · `db-*` 모듈이 있으면 저장소가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcCampaignStore` |
| 마이그레이션 | `modules/campaign-jdbc/src/main/resources/db/migration/postgresql`, `modules/campaign-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/rewards` |
| 테스트 | `modules/campaign-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [campaign](campaign.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
