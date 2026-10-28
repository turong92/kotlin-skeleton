# point-jdbc

`point` 의 저장 포트(`PointStore`)를 JDBC(PostgreSQL · MySQL)로 구현한다. 테이블 2 개: `point_balances`(계정마다 잔액 한 줄) · `point_entries`(원장 — 적립 묶음의 `remaining`, 차감 · 소멸 · 조정 줄, `unique(source_type, source_ref)`).
**돈 같은 영역의 불변식**을 이 모듈이 지킨다: 잔액 = 그 계정 적립 묶음의 `remaining` 합. 같은 계정의 쓰기는 잔액 줄을 잠그고(`select … for update`) 직렬로 돌아 동시 차감 둘이 같은 잔액을 두 번 쓰지 못한다(잔액 100 에 80 씩 둘 → 하나만). 음수 잔액은 표의 검사(`ck_point_balances_nonneg`)가 한 번 더 막는다. 같은 `(sourceType, sourceRef)` 는 유일 인덱스가 한 줄로 접는다(지급 건 재시도 · 동시 작업 · 같은 주문의 두 번 차감).
항목 표에는 잠금 읽기를 하지 않는다 — 새 키가 인덱스 끝 틈에 쌓여 계정이 다른 동시 쓰기끼리 틈 잠금으로 교착하기 때문이다. 그 대신 갱신은 모두 읽은 값을 조건으로 건 문장이고, 어긋나면 던져 롤백한다. MySQL(반복 읽기)에서 호출자의 바깥 트랜잭션이 낡은 스냅샷을 쥐고 같은 키를 동시에 처리하면 늦은 쪽이 한 번 던질 수 있다 — 돈은 두 번 움직이지 않고, 다시 시도하면 처음 줄을 돌려준다.
MySQL 의 계정 id · 출처 키는 이진 정렬이라 대소문자만 다른 두 키가 한 줄로 합쳐지지 않는다. 탈퇴 시 `AccountErasureListener`(`point`) 가 잔액과 모든 줄을 지운다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:point-jdbc"))` |
| 함께 오는 모듈 | `point`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (설정은 point 모듈의 것) |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 `PointStore` 를 등록한다 (앱이 같은 포트의 빈을 두면 그쪽이 쓰인다) |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마 (`spring.flyway.locations: classpath:db/migration/{vendor}`) |
| 교체 지점 | `PointStore` |
| 마이그레이션 | `modules/point-jdbc/src/main/resources/db/migration/postgresql`, `modules/point-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/rewards` |
| 테스트 | `modules/point-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`) — 소멸 임박 순 차감 · 잔액 초과 거절 · 같은 키 멱등 · 동시 차감 · 소멸 한 번 · 늦은 소멸 작업의 안전) |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
