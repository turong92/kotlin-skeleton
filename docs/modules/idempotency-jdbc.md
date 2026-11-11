# idempotency-jdbc

`idempotency` 의 저장 포트 `IdempotencyStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 하나 — `idempotency_keys`(기본 키 = `<범위>:<Idempotency-Key>`, `expires_at` 색인).

규칙: 인스턴스가 여럿이거나 다시 떠도 **같은 키는 한 번만 실행되고 첫 응답(상태 · 헤더 · 본문 바이트)을 재생한다** ·
예약은 insert-ignore 하나가 이기고 예약마다 난수(`claim`)를 같이 넣어 「내가 이겼나」를 읽어 가린다(동시에 온 같은 키 중 하나만 시작) ·
지문이 다르면 충돌(409), 응답이 저장되기 전이면 진행 중(409) ·
만료(`skeleton.idempotency.ttl`, 기본 24시간)한 줄은 같은 키의 예약이 먼저 지우고, 나머지는 한 시간마다 청소한다(`scheduler` 가 있으면 `idempotency.purge`, 요청 구동 모드면 `OnRequestTask` `idempotency-purge`) ·
응답을 저장하기 전에 프로세스가 죽어도 그 키는 `idempotency` 모듈의 설정 `in-progress-lease`(기본 5분) 동안만 「진행 중」(409)이고, 그 뒤 첫 요청이 새로 시작한다(가장 오래 걸리는 명령보다 길게 — 짧으면 아직 도는 명령이 한 번 더 실행된다) · 키는 대소문자를 구분한다(MySQL 은 이진 정렬 칸).
stage · prod 에서 인메모리 저장소로 서면 기동 실패하는 가드(`idempotency`)는 `idempotency` 에 있고, 이 모듈을 넣으면 풀린다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:idempotency-jdbc"))` |
| 함께 오는 모듈 | `idempotency`, `persistence-jdbc`, `platform` |
| 컴파일 전용 | `scheduler` |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `idempotency` 와 `DataSource` · `db-*` 모듈이 있으면 저장소가 선다(인메모리 저장소 대신). |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `IdempotencyStore` |
| 마이그레이션 | `modules/idempotency-jdbc/src/main/resources/db/migration/postgresql`, `modules/idempotency-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/idempotency-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [idempotency](idempotency.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
