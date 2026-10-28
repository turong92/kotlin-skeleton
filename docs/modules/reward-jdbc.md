# reward-jdbc

`reward` 의 지급 건 저장 포트 `RewardGrantStore` 와 작업 포트 `RewardTasks` 를 JDBC(PostgreSQL · MySQL)로 구현하고, 일꾼을 `job-queue-jdbc` 위에 얹는다.
표 하나 `reward_grants`(`id` · `account_id` · `reward_type` · `params_json` · `source_type` · `source_key` · `status` · `result_ref` · `last_error` · `created_at` · `issued_at`) —
`(source_type, source_key)` 유니크가 같은 조건의 두 번 판정을 한 건으로 접는다. 상태 전이는 모두 `where status = …` 조건부 갱신이다.

규칙: 넣기는 `SqlDialect.insertIgnore` 이고 **넣었는지는 내가 만든 id 가 있는지로 본다**(MySQL 은 이미 있는 행에도 갱신 행 수 1 을 돌려준다; 유니크 위반 예외는 PostgreSQL 에서 호출자 트랜잭션을 통째로 abort 시킨다) ·
건 잠금은 `select … for update` 로 `PENDING` 일 때만 읽는다 — 같은 건 작업 둘이 동시에 와도 둘째는 비어 돌아와 지급은 한 번이다 · `params_json` 은 `*_json text`(`json` 모듈의 `JsonCodec`).

작업 어댑터: `reward.issue` 작업 하나가 지급 건 하나(내용 `{"grantId":"…"}`). 넣기는 **호출자 트랜잭션 안에서만**(밖이면 던진다) — 건 줄이 롤백되면 작업도 사라진다. 처리기(`RewardJobHandler`)는 트랜잭션으로 감싸지 않는다(일꾼이 건을 잠그는 트랜잭션을 직접 연다).
영구 실패(`RewardPermanentFailure`)는 재시도 없이 바로 DEAD. **죽은 작업의 고리**(`RewardDeadListener`): 큐가 작업을 DEAD 로 만드는 모든 길에서 건을 `FAILED` 로 적는다(이미 `PENDING` 이 아니면 그대로) — 운영자가 「다시 지급」으로 되살린다. 큐가 꺼져 있으면(`skeleton.job-queue.enabled=false`) 시작이 실패한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:reward-jdbc"))` |
| 함께 오는 모듈 | `reward`, `platform`, `persistence-jdbc`, `job-queue-jdbc`, `json` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (지급 작업의 최대 시도 수는 `reward` 의 설정에서 정한다) |
| 기본 동작 | 켜짐. `reward` 와 `DataSource` · `db-*` 모듈이 있으면 저장소 · 작업 · 일꾼 처리기가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, `job-queue-jdbc` 의 스키마, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcRewardGrantStore`, `JdbcRewardTasks`, `RewardJobHandler`, `RewardDeadListener` |
| 마이그레이션 | `modules/reward-jdbc/src/main/resources/db/migration/postgresql`, `modules/reward-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/rewards` |
| 테스트 | `modules/reward-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [reward](reward.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
