# booking-jdbc

`booking` 의 저장 포트 `BookingStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 여섯 — `booking_pools`(풀) · `booking_seats`(자리 한 칸 = 경쟁 단위, 상태 · `hold_id` · `held_until` · `version`) · `booking_holds`(보류, `active_slot` 유니크로 계정당 풀마다 열린 보류 하나) · `booking_hold_seats`(보류가 가졌던 자리) · `booking_usage`(계정이 풀에서 쥔 자리 수) · `booking_orders`(결제된 주문이 보류에 어떻게 맞춰졌나 - 주문당 한 줄, 「주문 하나에 한 번만 효과」 · 환불 작업 하나의 근거). 계정 id 에는 외래 키가 없다.

**이중 판매 0 의 정본**이 여기 있다. 두 방언이 같은 SQL 이다:
- 자리 잡기는 자리 줄 조건부 갱신(`status = 'AVAILABLE'` 일 때만)이고 여러 자리는 한 트랜잭션에서 전부 아니면 전무(하나라도 0줄이면 통째로 롤백).
- 모든 쓰기 트랜잭션의 첫 문장은 그 계정의 **사용량 줄 잠금**이다 — 한 계정의 보류 쓰기가 여기서 직렬이 되어 열린 보류를 찾거나 만들 때 유니크 충돌 · 틈 잠금(MySQL)이 끼지 않는다. 그 뒤 보류 줄 전이(조건부 갱신) → 자리 줄은 **id 오름차순, 한 문장에 한 줄**. 그래서 {X, Y} 와 {Y, X} 를 동시에 잡아도 교착이 없다.
- 보류 줄 전이(`HELD → 닫힘`)가 1줄일 때만 자리가 돌아와 회수 · 놓기 · 확정이 겹쳐도 한 번만 풀린다.
- 결제 쓰기(`settlePaid` · `cancelRefunded`; `priceHold` 는 읽기뿐)도 같은 규칙이다: 첫 문장이 사용량 줄 잠금, 보류 줄은 잠금 읽기, 다시 잡을 자리는 id 오름차순으로 한 줄씩 잠가 **전부 `AVAILABLE` 일 때만** 바꾼다(부분만 잡히지 않아 업무 실패로 예외를 던지지 않는다 — 결제 트랜잭션에 참여해도 롤백 표시가 안 선다). 환불 작업은 같은 트랜잭션에서 `BookingRefundQueue` 로 넣는다.
- 불변식: `HELD` 자리는 `HELD` 보류를, `SOLD` 자리는 `CONFIRMED` 보류를 가리키고 열린 · 확정 보류의 `seat_count` = 그 보류를 가리키는 자리 수 = `booking_hold_seats` 줄 수. 사용량 = 계정의 열린 · 확정 보류 `seat_count` 합. 동시성 시험이 끝마다 확인한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:booking-jdbc"))` |
| 함께 오는 모듈 | `booking`, `persistence-jdbc`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 저장소가 서고, 그러면 `booking` 의 서비스가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcBookingStore` |
| 마이그레이션 | `modules/booking-jdbc/src/main/resources/db/migration/postgresql`, `modules/booking-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/booking-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [booking](booking.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
