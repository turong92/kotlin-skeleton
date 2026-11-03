# video-jdbc

`video` 의 저장 포트 `VideoStore` · `VideoWorkerStore` 를 JDBC(PostgreSQL · MySQL)로 구현하고, 후속 작업 포트 `VideoFollowUps`(`video.finalize` · `video.wake`)를 `job-queue-jdbc` 에 얹는다. 표 셋을 한 번에 만든다.

- `videos` — 영상 한 줄: 주소용 무작위 `id`(char 22, **대소문자 구분** — MySQL 은 이진 정렬) · 올린 계정(`owner_account_id`, 외래 키 없음) · 앱이 정하는 `purpose` · `visibility` · 주 상태 · 올린 파일 키 · 멀티파트 id · 마지막 올리기 움직임 · `src_*`(일꾼 probe 결과) · 원본 단 · 호환 · 사다리 트랙 · 포스터 · 진행률 · 시도 · 임대 · 실패 코드. 색인 `(status, lease_until)` · `compat_status` · `ladder_status` · `(owner_account_id, purpose)`.
- `video_workers` — 일꾼 토큰(SHA-256 해시만) · 허용 차선 · 폐기 · 마지막 본 시각. 일꾼 API 가 쓴다.
- `video_wake_runs` — 클라우드 작업 깨우기 기록. `VideoWakeRuns` 포트로 읽고 쓴다 - 쓰는 쪽은 `video-cloudrun` 이다.

상태를 바꾸는 쓰기는 모두 **한 문장의 조건부 갱신**(`update … where id = ? and status = ?`)이다 — 같은 영상을 동시에 넘기려는 호출 중 하나만 `true` 를 받는다. 일꾼이 일을 집는 것은 한 트랜잭션의 `select … for update skip locked` → 임대 갱신이라 두 일꾼이 같은 영상을 집지 못하고(두 방언 같은 문법), 후속 작업은 호출자의 트랜잭션 안에서 큐에 들어가 상태 변경과 함께 커밋되거나 함께 사라진다. 지우기(`markDeleted`)는 상태를 `DELETED` 로 바꾸면서 일꾼이 쥔 임대(`lease_*` · `lane` · 진행률)도 한 문장으로 비운다 — 지운 영상에는 일꾼의 heartbeat · 결과가 더는 받아들여지지 않고 임대 만료 청소도 다시 줄 세우지 않는다. 지운 뒤 객체 정리(`video.finalize` 의 `DELETED`)는 `video` 모듈의 몫이다. 시각은 `SqlDialect` 로 묶는다(UTC, 마이크로초).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:video-jdbc"))` |
| 함께 오는 모듈 | `video`, `job-queue-jdbc`, `persistence-jdbc`, `platform`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 `VideoStore` · `VideoWorkerStore` · `VideoTransactions` 가 서고, 작업 큐(`JobQueue`)가 있으면 `VideoFollowUps` 와 `video.finalize` · `video.wake` 처리기가 선다 (`video` 는 `skeleton.video.enabled=true` 일 때만 이것을 쓴다). |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcVideoStore`, `JdbcVideoWorkerStore`, `JobQueueVideoFollowUps` |
| 마이그레이션 | `modules/video-jdbc/src/main/resources/db/migration/postgresql`, `modules/video-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/video-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [video](video.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
