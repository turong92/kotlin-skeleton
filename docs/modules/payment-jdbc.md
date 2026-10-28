# payment-jdbc

`payment` 의 결제 장부 저장 포트 `PaymentLedgerStore` 와 작업 포트 `PaymentTasks` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 이 모듈이 있어야 `payment` 의 장부(주문 만들기 · 승인 · 만료 · 조회 · 지급 · 환불 · 웹훅 수신함 · 사용자/운영자 HTTP · 탈퇴 고리)가 켜진다 —
없으면 `payment` 는 장부가 생기기 전과 같다. 표 4개: `payment_orders`(결제 한 번 = 한 줄 — 줄의 상태가 돈의 사실) · `payment_refunds`(환불 한 건) · `payment_events`(추가만 하는 이력) · `payment_webhook_events`(웹훅 수신함).
마이그레이션 `V20261009124513__payment_ledger` 가 네 표를 한 번에 만든다. 환불 줄의 `keep_subscription`(운영자가 「구독도 해지」를 끄고 보낸 환불 — 새 파일 `V20261020120000__payment_refunds_keep_subscription`, 기본값 `false` 라 옛 줄은 지금까지의 동작 그대로)은 별도 파일이 더한다. 구독은 새 파일 `V20261009134223__payment_subscriptions`(두 방언) 가 표 하나 `payment_subscriptions` 를 더한다 — 계정당 살아 있는 구독 하나(`active_key` 유니크), 빌링키는 암호문 열(`billing_token`, 지운 뒤 NULL), 구독 줄 락은 `select … for update` 이고 락 순서는 주문 → 구독. 설계와 근거: `docs/superpowers/specs/2026-10-08-payment-ledger-design.md` §4.3 · §5 · §6.

규칙: 락은 DB 의 **주문 줄 하나**다(`select … for update` 로 잠그고 새 문장으로 다시 읽은 값으로 판단 — PostgreSQL 은 기다리는 동안의 변경을 같은 문장에서 놓칠 수 있다) · 한 트랜잭션에는 주문 하나(범위는 가장 바깥 `inTransaction` **블록**이지 물리 트랜잭션이 아니다 — 앱의 바깥 트랜잭션에 합류해 블록을 여러 번 열면 블록마다 다른 주문을 잠글 수 있고, 락은 바깥이 끝날 때 풀리며 락 순서[주문 id 오름차순]는 호출자의 몫) ·
"하나만 있어야 한다"는 유니크 열이 지킨다(`dedupe_key` · `(provider, provider_payment_id)` · 환불의 `open_key` · `dedupe_key` · `(order_id, provider_refund_id)` · 웹훅의 `(provider, event_key)`) ·
금액은 `bigint` 통화 최소 단위(`check` 제약이 음수 · 결제액을 넘는 환불 합을 막는다) · MySQL 은 결제사가 준 값과 우리 키 열이 `utf8mb4_bin`(대소문자 구분) ·
**할인 합계 조회**: 빈 `DiscountTotals`(`platform` 의 읽기 전용 포트, `JdbcDiscountTotals`)가 할인 수단 모듈(예: `coupon` 의 운영 현황)에 확정(`CONFIRMED`) 할인 줄의 금액 합을 통화별로 답한다 - 복원(`RESTORED`) · 쥠(`RESERVED`) · 풀림(`RELEASED`)은 뺀다; 식별 목록은 1000 개씩 끊는다 ·
탈퇴(`TOMBSTONE`)는 **그 계정이 산 주문의 줄에서만** 계정 id 를 바꾼다 — 운영자로서 남의 주문에 남긴 `decided_by` · `requested_by` · `actor_id` 는 남는다(누가 환불을 결정했는가의 기록) ·
지워지는 개인정보 칸은 `payment_orders.card_issuer` · `card_last4` 둘뿐(그 밖의 열은 식별자 · 코드 · 금액 · 시각 · 수신함 원문 — `PaymentSchemaDbTest` 의 열 분류 시험이 새 열을 막는다). `order_name` 은 코드가 아니라 **앱 카탈로그가 정하는 문장**(분류 `LABEL`)이다 — 거기에 개인정보를 넣지 않는 것은 앱의 책임이다.

**읽기 질의와 인덱스** (HTTP 의 목록 · 「확인 필요」가 쓴다 — 인덱스를 못 타는 질의가 표가 커졌을 때의 병목이라 모양을 고정했다):
- 목록은 모두 **최신순 커서**이고 커서 조건은 MySQL 이 범위 접근을 쓰도록 `created_at < ? or (created_at = ? and id < ?)` 로 풀어 쓴다. 내 결제 내역 `listAccountOrders`(`PENDING` 제외, `idx_payment_orders_account` 를 거꾸로) · 운영자 주문 목록 `listOrdersForOperator(scope, filter, threshold, before, limit)`(범위 `ALL` · `REVIEW_REASON` · `STALE_CONFIRMING` · `UNFULFILLED` · `STALE_REFUND` · `UNAPPLIED_REFUND`[환불 줄은 `id in (select order_id from payment_refunds …)` 준조회라 필터 · 커서 · 개수가 같은 문장에서 걸린다] — 「확인 필요」는 서로 다른 인덱스를 타는 갈래마다 한 번씩 물어 서비스가 합친다; `filter` 의 `hideErased` 가 탈퇴 계정의 줄을 뺀다) · 환불 줄 목록 `listRefundsByStatus`(`idx_payment_refunds_status` 를 거꾸로) · 수신함 목록 `listWebhookEvents`(상태를 정하면 `(status, received_at, id)`, 안 정하면 PK `id` 순).
- `findOrdersByProviderPayment(providers, 번호)` 는 **`provider in (아는 결제사들)` 을 같이 걸어** `(provider, provider_payment_id)` 유니크 인덱스를 탄다(결제사 조건 없이 번호만 찾으면 못 탄다). `listOrdersByIds` · `listRefundsOfOrders` 는 목록 줄마다 묻지 않고 한 쪽을 한 번에 읽는다. 환불 줄에서 주문으로 거슬러 올라가는 「확인 필요」의 두 갈래는 환불 줄 전용 목록 메서드가 아니라 위 `listOrdersForOperator` 의 `STALE_REFUND` · `UNAPPLIED_REFUND` 범위다(준조회 한 문장).
- **새 인덱스 둘** — 「확인 필요」의 지급 · 회수 안전망 질의(`fulfilled_at is null and status = PAID` · `applied_at is null and status = SUCCEEDED` 는 소수 줄이다)를 위해: `idx_payment_orders_unfulfilled (fulfilled_at, status, created_at, id)` · `idx_payment_refunds_unapplied (applied_at, status, requested_at, id)`. 열 순서가 `status` 로 시작하지 않는 이유: 상태로 시작하면 옵티마이저가 상태별 목록 질의에도 이 인덱스를 골라 정렬을 따로 한다. 두 방언 같다.
- **PostgreSQL 쪽 「미지급」 · 「회수 안 됨」 질의는 걸름을 먼저 굳힌다.** 지급 · 회수 시각이 비어 있는 줄은 정상 상태에서 **0 줄**인데, 계획기는 상태와 시각의 상관을 몰라(확장 통계 `create statistics … (mcv)` 도 이 질의의 계획을 한 번도 바꾸지 못했다 — 다섯 분포 실측, 그래서 V 파일에 없다) 소수를 많게 어림하고 `idx_payment_orders_created` 를 거꾸로 훑으며 거른다(실측: 12만 줄에서 `Rows Removed by Filter: 120000`). 그래서 저장소는 PostgreSQL 에서 미지급 `PAID` 를 `with c as materialized (select … where fulfilled_at is null and …) select … from c where [커서] order by created_at desc, id desc limit ?` 로 보내 걸름이 `idx_payment_orders_unfulfilled` 범위로 먼저 평가되게 하고, 환불 갈래(`STALE_REFUND` · `UNAPPLIED_REFUND`)는 `id = any(array(select order_id from payment_refunds …))` 로 보내 환불 인덱스에서 모은 id 만 기본 키로 읽는다(비면 주문 표를 읽지 않는다). MySQL 은 같은 증상이 없어 그대로다(`PaymentIndexPlanDbTest` 의 「healthy ledger」 시험이 두 방언에서 잰다). 미지급이 정말 많은 장애 상황에서는 굳힌 집합을 쪽마다 다시 읽는다 — 그래도 읽는 것은 지급 인덱스 범위뿐이다.
- **탈퇴 고리(벌크)**: `eraseAccountOrders` 는 한 문장 update 다 — `card_issuer` · `card_last4` 를 NULL 로, `account_erased_at` 을 `coalesce` 로(이미 찍혀 있으면 그 시각을 지킨다). 이미 지워진 줄은 `where` 에서 걸러져 두 번째 호출은 0 줄을 바꾸고 `updated_at` 도 건드리지 않는다. `replaceAccountReferences`(`TOMBSTONE`)는 그 계정이 산 주문의 id 를 `order_id in (…)`(500개씩 끊어)으로 좁혀 이력 `actor_id` · 환불 줄 `requested_by` · `decided_by` 를 먼저 바꾸고 **주문 줄의 `account_id` 를 마지막에** 바꾼다 — 대상 주문은 주문 줄이 옛 id 를 가리키는 동안에만 찾을 수 있으므로 도중에 끊겨도 다시 부르면 이어진다. 운영자로서 남의 주문에 남긴 값은 `order_id` 로 좁히므로 남는다. 계정 모듈의 바깥 트랜잭션 안에서 불려도 된다(블록이 합류한다).

작업 어댑터는 `job-queue-jdbc` 에 싣는다 — 자체 폴링 워커를 만들지 않는다. 작업 넣기는 **장부 트랜잭션과 같은 연결**을 쓰므로 줄이 롤백되면 작업 줄도 사라지고(장부 트랜잭션 밖이면 던진다),
`PaymentTaskRunner.types`(앱이 더한 `PaymentTaskHandler` 빈 포함) 마다 큐 처리기 하나가 빈 정의로 등록된다. 처리기는 트랜잭션으로 감싸지 않는다(결제사를 부르므로). 시작할 때 처리기 없는 종류가 있으면 멈춘다.
영구 실패(`PaymentTaskPermanentException`)는 재시도 없이 바로 죽는다(`JOB_DEAD` 경보는 `alert` 의 몫).
**죽은 작업의 고리**: 큐가 작업을 DEAD 로 만드는 **모든 길**(재시도를 다 씀 · 영구 실패 · 처리기 없음 · 워커가 죽어 멈춘 채 시도를 다 씀 — 이 길은 처리기를 거치지 않는다)에서 이 모듈의 `JobDeadListener` 빈(`PaymentJobDeadListener`)이
우리 작업의 대상 id 를 꺼내 `PaymentTaskHandler.onDead(run)` 을 부른다(`alert` 의 `JOB_DEAD` 경보와 나란히). 조회 작업은 줄이 아직 `CONFIRMING` 이고 표시가 없으면 `CONFIRM_UNRESOLVED`,
환불 정산 작업은 환불이 아직 `PENDING` 이면 주문에 `REFUND_MISMATCH`, 지급 작업은 주문이 아직 미지급(`PAID` · `PARTIALLY_REFUNDED` 이고 `fulfilled_at` 이 NULL)이면 `FULFILMENT_UNRESOLVED`,
회수 작업은 환불 줄이 `SUCCEEDED` 인데 `applied_at` 이 NULL 이면 `REFUND_APPLY_UNRESOLVED` 를 찍는다 — 「확인 필요」 목록은 경보를 끈 앱 · `alert` 없는 앱에서도 이 표시로 채워진다. 만료 작업만 찍을 것이 없다(줄은 아직 `PENDING` 이라 돈이 움직인 적이 없다).
검토 이유 칸은 하나라 우선순위가 있다: 돈이 어긋난 이유(`AMOUNT_MISMATCH` · `ORDER_MISMATCH` · `REFUND_MISMATCH` · `MODE_MISMATCH` · `CONFIRM_UNRESOLVED`)가 지급 쪽 이유(`FULFILMENT_UNRESOLVED` · `REFUND_APPLY_UNRESOLVED`)를 덮어쓰고(이력에 `REPLACED_<앞선 이유>`) 반대는 안 되며, 같은 급끼리는 먼저 찍힌 것이 남는다.
지급 쪽 이유의 출구는 운영자의 「결제사와 맞추기」다 — 미지급 주문에 지급 작업을, 미회수 `SUCCEEDED` 환불에 회수 작업을 다시 넣고(한 번의 맞추기에 한 번) 이 두 이유를 푼다.
고리는 큐 워커 스레드에서 **트랜잭션 밖**으로 불리고(자기 트랜잭션을 연다), 던져도 큐는 로그만 남기고 계속 돈다. 앱이 같은 종류의 `JobHandler` 를 직접 두면(워커가 말없이 하나만 고른다) 시작이 멈춘다.

**여러 인스턴스 시험** (`dbTest` 의 `MultiInstanceDbTest` · `MultiInstanceJobClaimDbTest` · `LedgerCluster`): 한 프로세스 · 한 조립 안의 16 스레드 시험은 "프로세스 안 상태(자바 락 · 메모리 맵 · 싱글턴이 든 상태)에 기대지 않는다"를 증명하지 못한다. 그래서 같은 DB 를 보는 **독립된 백엔드 셋**(각자 연결 풀 · 트랜잭션 관리자 · 저장소 · 서비스 묶음 · 작업 큐 워커 · 시계 · 지급 훅 — 클러스터가 만들 때 객체 동일성을 단정한다)이 같은 일을 같은 순간에 한다: 같은 주문 승인 · 같은 결제 키를 두 주문에 · 승인 ↔ 만료 · 같은 직접 환불 · 다른 키의 동시 환불 · 같은 웹훅 배달 · 작업 워커 여러 대 · 웹훅 주문 단위 상한. 공유하는 것은 DB 와 결제사 흉내 하나뿐이다(결제사는 바깥에 하나라 호출 수를 세려면 함께 봐야 한다). 이긴 쪽이 늘 DB 의 락 · 유니크라는 증거로, `lockOrder` 의 `for update` 를 빼 보면 같은 주문 승인 · 같은 환불 등이 빨강이 된다. Spring 컨텍스트 여럿이 아니라 손으로 조립한 묶음이다(자동설정 연결은 `PaymentJdbcAutoConfigurationTest` 가 보고, 컨텍스트 셋은 폴링 스레드가 비결정적이라 워커를 시험이 직접 돌릴 수 없다).
**이 시험이 재지 못하는 것**: 진짜 두 프로세스(한 JVM 이라 `static`/`object` 의 전역 상태는 여전히 공유된다 — 결제 모듈 main 에는 정합이 기대는 JVM 전역 가변 상태가 없음을 전수 검색으로 확인했다), 네트워크 분할 · DB 장애 조치 · 연결 풀 고갈, 인스턴스마다 시계가 다른 경우(마감 · 정산 대기는 **판단하는 백엔드의 시계**로 정해지므로 1분 빠른 한 대는 다른 대가 아직 받아 주는 승인을 거절하고 만료 · 정산 대기를 일찍 끝낸다 — 전용 시험은 두지 않았다. 인스턴스 시계는 NTP 로 맞춘다). 웹훅 주문 단위 상한은 세고 넣는 사이의 경합으로 **상한을 동시 요청 수만큼까지 넘길 수 있다**(잰 값은 CHANGELOG). 알려진 것: MySQL 에서 큐 워커 둘이 동시에 짧은 큐를 집으면 `job-queue-jdbc` 의 집기 트랜잭션이 교착으로 되돌려질 수 있다(작업은 남아 다음 폴링이 집는다 — 재현 시험은 두지 않았다).

**스키마 관리**: Flyway 가 모듈 마이그레이션을 적용한다. `schema.sql` 모드에서는 PostgreSQL 판 · MySQL 판 모두 `create table if not exists` 와 표 안의 인덱스라 두 번 돌아도 된다 (MySQL 판의 `/* [jooq ignore start] */` 표식은 jOOQ 코드 생성용 — MySQL 은 그대로 읽는다).
별도 작업 표는 없다 — 작업 줄은 `job-queue-jdbc` 의 `jobs` 표(같은 스키마)에 들어간다.

- **대시보드 기여**(모드·되돌림 규칙 포함): `dashboard` 모듈이 클래스패스에 있으면 순매출(`payment.net-revenue`) · 결제 건수(`payment.paid-count`) 타일과 퍼널 단계 `payment.paid`를 기여한다. 순매출 = 기간 안 `paid_at` 의 `paid_amount` 합 − 기간 안 `completed_at` 의 성공 환불 합, **통화별로 따로**(합치지 않음) — 부분 환불 · 결제사 쪽 취소(`origin=PROVIDER`)도 빼고 실패 · 대기 · 거절 환불은 안 뺀다. 그래프는 타일 첫 통화 한 통화다. 인덱스 `idx_payment_orders_paid`(마이그레이션이라 `dashboard` 를 안 붙인 앱에도 들어간다) · `idx_payment_refunds_completed`. **지금 끼운 결제사 키의 모드(`TEST` · `LIVE` · `UNKNOWN`)와 같은 모드로 만든 주문만 센다** — 테스트 키로 시작했다가 실키로 바꾼 앱의 테스트 매출이 남지 않는다(환불은 주문과 조인해 같은 기준). 켜져 있지 않은 결제사의 주문은 `LIVE` 만 센다. 값은 지금 상태 기준이다: 차지백 환불이 이겨 `REVERSED` 로 바뀌면 그 환불이 완료됐던 기간의 순매출이 소급해서 올라간다(되돌림 시각의 기간이 아니다). 그래프는 범위당 질의 둘(결제 · 환불)로 읽고 조립기가 정한 통화(타일 첫 통화) 한 개만 그린다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:payment-jdbc"))` |
| 함께 오는 모듈 | `payment`, `platform`, `persistence-jdbc`, `job-queue-jdbc`, `crypto` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 저장소 · 작업 포트를 등록한다 (앱이 같은 타입의 빈을 두면 그쪽이 쓰인다). 큐가 꺼져 있으면(`skeleton.job-queue.enabled=false`) 시작이 실패한다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, `job-queue-jdbc` 의 `jobs` 표와 이 모듈의 마이그레이션이 적용된 스키마 (`spring.flyway.locations: classpath:db/migration/{vendor}`). |
| 교체 지점 | `PaymentLedgerStore`, `PaymentTasks`, `DiscountTotals` |
| 마이그레이션 | `modules/payment-jdbc/src/main/resources/db/migration/postgresql`, `modules/payment-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/payment` |
| 테스트 | `modules/payment-jdbc/src/test`, `modules/payment-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`) — 저장소 계약 · 16 스레드 유니크 경합 · `check` 제약 · 열 분류 · 심은 값 스캔 · 작업 넣기 롤백 · 서비스 경주 · 독립된 백엔드 셋의 여러 인스턴스 시험) |

자세히: [payment](payment.md) · [재시도 큐 상세](../job-queue-jdbc.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
