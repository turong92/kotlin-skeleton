# audit-jdbc

운영자 행동 기록 `OperatorAuditLog`(`platform`)를 DB 에 남기고(PostgreSQL · MySQL), 운영자가 조회하는 API 와 보관 기한을 더한다. `platform` 의 기본은 아무것도 남기지 않는 `NoOpOperatorAuditLog` 인데, 이 모듈을 붙이면 그 자리를 이 구현이 대신한다(앱이 같은 타입의 빈을 두면 이쪽이 물러난다 — 그때는 조회 · 보관 기한도 없다).

**한 줄**: 누가(`actor_id`) · 언제(`occurred_at`) · 무엇을(`action`, `<모듈>.<대상>.<과거형>`) · 대상(`target_type` · `target_id`) · 결과(`result`: `SUCCESS` · `DENIED` · `FAILED`) · 어디서(`ip` · `user_agent`) · 덧붙임(`detail`, 문자열 맵의 JSON). 값은 id 만이다 — 이메일 · 이름 · 본문 · 사유 원문 · 토큰은 호출자가 싣지 않는다(`OperatorAction` 의 규칙). 접속 IP · 기기 정보는 호출자가 넘기지 않는다: 지금 처리 중인 요청에서 읽고(IP 는 `skeleton.web.client-ip` 규칙 — [client-ip](../client-ip.md)), 요청 밖(작업 · 시작 때)이면 비운다.

- **더하기만 한다**: 표 `operator_audit_log` 의 트리거가 막는 것 — 고치기(항상), 지우기(기록한 지 **30일이 안 된 줄**), PostgreSQL 은 표 비우기(`TRUNCATE`)까지(MySQL 은 `TRUNCATE` 에 트리거를 걸 수 없다 — 앱 계정에 `DROP` · `TRUNCATE` 권한을 주지 않는다). `legal-jdbc` 의 동의 기록과 같은 방식이다. 앱 코드에도 고치거나 지우는 길이 없다(조회 API 는 읽기 전용). 한계: 트리거는 앱의 실수 · 버그를 막지, 데이터베이스 관리자 권한을 가진 사람을 막지 못한다 — 그 권한은 앱 계정과 나눈다.
- **행동과 같은 트랜잭션**: 기록은 부르는 쪽의 쓰기 트랜잭션에 합류한다. 행동이 롤백되면 줄도 사라지고, 커밋되면 함께 남는다 — 기록 없는 행동도, 행동 없는 기록도 남지 않는다. 거절 · 실패(`DENIED` · `FAILED`)를 남기려는 호출자는 롤백되는 트랜잭션 **밖에서** 부른다(`OperatorAction.result`, 기본 `SUCCESS`).
- **조회**: `GET /api/v1/admin/audit-logs` — `skeleton.audit.admin.enabled=true` 일 때만(꺼짐이 기본 → 404). 권한 `audit.read`(기본 역할 없음 — 전권 `*` 만 가진다, 역할 관리에서 나눠 준다. 익명 401 · 권한 없음 403). 필터: `from`(포함) · `to`(미포함, ISO-8601 시각) · `actorId` · `targetType` · `targetId`. 새것부터, 커서(`cursor` = 앞 쪽 응답의 `cursor.nextCursor`)와 `size`(1~100, 기본 50). `from` 이 `to` 보다 늦거나 `size` 가 범위 밖이면 400. 줄에는 운영자 id · 접속 IP · 기기 정보가 있으니 꼭 필요한 운영자에게만 준다. 이 조회 자체는 기록하지 않는다.
- **보관 기한** `skeleton.audit.retention`(예: `365d`): **기본 없음 — 아무것도 지우지 않는다.** 정하면 그보다 오래된 줄을 하루 한 번 1000줄씩 끊어 지운다(로그에는 건수만). **30일 미만이면 기동 실패** — 트리거가 30일 안의 줄을 지우지 못하게 하는 같은 값이다. 돌릴 길이 없으면(`scheduler` 모듈도 요청 구동 모드도 없다) 조용히 안 지우는 대신 기동 실패. 개인정보(IP · 기기 정보)가 든 줄을 영영 두지 않으려면 정하고, 증빙이 우선이면 비워 둔다 — 어느 쪽이 맞는지는 서비스가 정한다(법정 보관 기간은 서비스의 몫).
- **이 레포에서 부르는 곳**: 운영 경로가 있는 모듈의 쓰기 행동 — 역할 변경(`authz`), 시험 계정 표시(`account`: `account.test-mark.set` · `cleared`), 포인트 조정 · 쿠폰 · 문의 처리 · 예약 · 카탈로그 · API 키 · 공지 따위. 새 운영 행동을 만들면 `OperatorAuditLog.record(OperatorAction(...))` 를 쓰기 트랜잭션 안에서 한 줄 부른다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:audit-jdbc"))` |
| 함께 오는 모듈 | `platform`, `persistence-jdbc` |
| 컴파일 전용 | `scheduler` |
| 설정 접두사 | `skeleton.audit` — [docs/config/modules/audit-jdbc.yml](../config/modules/audit-jdbc.yml) |
| 기본 동작 | 켜짐. 줄을 남기고, 조회 API 와 보관 기한은 꺼져 있다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마 (`spring.flyway.locations: classpath:db/migration/{vendor}`). **MySQL**: 마이그레이션이 트리거를 만들므로 binlog 가 켜져 있으면 `log_bin_trust_function_creators=1`(또는 마이그레이션 계정에 `SUPER`)이 필요하다(`legal-jdbc` 와 같다). 보관 기한을 정했다면 `scheduler` 모듈이나 요청 구동 모드. |
| 교체 지점 | `JdbcOperatorAuditLog`, `AuditOrigin`, `AuditRetention` |
| 마이그레이션 | `modules/audit-jdbc/src/main/resources/db/migration/postgresql`, `modules/audit-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/audit-jdbc/src/test` · `modules/audit-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [platform](platform.md) 「운영자 권한」 · [account](account.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
