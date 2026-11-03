# 예약(좌석 지정 · 선착순) + 앞단 대기열(가상 대기실) — 설계 초안

> 2026-11-02, 2026-11-03 좌석 지정으로 고침. 요청(형): "레이스 컨디션 해 볼까. 예약 시스템 같은거. … 예약 시스템 처럼 앞에 대기열도" → "좌석 직접 찍기까지" · "점유 했다가 릴리즈 하는 것까지도 하자".
> 형 원칙: 조립식 · 캡슐화 · 모듈 기본값 중립(꺼짐) · 업계 관행 · 진짜 문제만 막고 쓸데없는 옵션 금지 · 회귀 시험은 돈 · 보안 · 동시성 · 데이터 손실 · 공개 레포라 개인 흔적 없음.
> 대상: kotlin-skeleton(새 `booking` · `booking-jdbc` · `waiting-room` · `waiting-room-jdbc`, `apps/sample` — 기존 `payment` 은 손대지 않는다) + react-skeleton(새 `@skeleton/waiting-room` · `@skeleton/booking`, `apps/sample`). 기준: kotlin main `15c27e31`, react main `2f614f1`.
> 읽은 것: `docs/modules/{payment,catalog,coupon,campaign,idempotency,redis-core,redis-lock,redis-rate-limit,captcha-turnstile,scheduler,job-queue-jdbc,notification,notification-sse,notification-websocket}.md` · `docs/cloud-run-scale-to-zero.md` · `PaymentOrderListener` · `PaymentRefunds` · `InMemoryNotificationBroker` · `NotificationSseService` · react `packages/realtime`(`createSseClient`) · `apps/sample` 조립. 재고 · 좌석 개념은 어디에도 없다. 실시간 수단은 `notification-sse` · `notification-websocket` 둘인데 브로커는 **인메모리 하나뿐**(프로세스 안에서만 전달)이고, 레포에 Redis pub/sub 를 쓰는 곳은 아직 없다(`redis-core` 는 연결 · 템플릿 · 키 접두사만).
> 맨 끝 「결정됨」 절이 본문보다 우선한다. 본문은 결정 1~5 를 반영했다.

## 0. 관행 조사 → 권장안

| 물음 | 업계 | 권장 |
|---|---|---|
| 이중 판매 0 의 정본 | 좌석 지정은 **자리 줄 하나가 경쟁 단위** — 단일 DB 면 자리 줄 조건부 갱신(`… set status = 'HELD' where id = ? and status = 'AVAILABLE'`)이 표준. Redis 잠금 · `SETNX` 는 대형 트래픽 앞단 거르기이고 정본은 DB | **DB 자리 줄 조건부 갱신이 정본**, 두 방언 같은 SQL. 여러 자리는 **전부 아니면 전무**. 수량형과 달리 뜨거운 풀 줄이 없다. Redis 는 1차에 안 쓴다 |
| 잡는 시점 | 배치도에서 자리를 누르면 그 자리가 잡히고 남에게 「선택 불가」, 다시 누르면 풀림. 한 사람이 한 번에 잡는 자리 수 상한(행사가 정함) | 누르면 잡기 · 다시 누르면 놓기. 계정당 풀마다 **열린 보류 하나**(장바구니), 상한 `max_per_hold` 은 풀 만들 때 필수 |
| 보류 시간 | 티켓 사이트 수 분 ~ 10분, 타이머 하나가 **결제까지** 덮고 늘지 않는다. 다 되면 자동 취소 | 보류 **10분**(모듈 기본 — 안전 장치). 첫 자리부터 재고 자리를 더해도 · 결제창을 열어도 늘지 않는다 |
| 만료 회수 | 주기 작업 + 「막혔으면 그 자리에서 회수 후 재시도」 | 둘 다. 화면은 사건에 실린 만료 시각에 스스로 「열림」으로 바꾼다(§3) |
| 늦은 결제 | 보류 → 결제 → 확정, 늦게 들어온 결제는 다시 잡거나 **자동 환불** | 그대로(결정됨 2). 같은 보류에 두 번 결제되면 뒤의 것은 환불 |
| 배치도 실시간 반영 | 밀어주기(SSE · WebSocket) + 여러 대면 인스턴스 사이 중계(Redis pub/sub 가 흔하다). 중계는 놓칠 수 있으니(pub/sub 는 많아야 한 번) **다시 붙으면 전체 스냅샷**으로 맞추고, 사건에 자리별 버전을 실어 순서 뒤바뀜을 거른다. 어느 쪽이든 **찍는 순간 서버가 판정**한다 | **SSE**(결정됨 5) — 서버는 `notification-sse` 와 같은 방식으로 예약 전용 흐름을 따로 열고, 화면은 `@skeleton/realtime` 의 `createSseClient` 를 그대로 쓴다. 여러 대면 `redis-core` 위 pub/sub 중계. 다시 붙을 때마다 스냅샷. 폴링은 쓰지 않는다 |
| 중복 · 재시도 | 결제는 `Idempotency-Key`. 자리 잡기 · 놓기는 「이 자리를 내 보류에」라 본래 멱등 | 자리 찍기엔 멱등 키를 받지 않는다. 열린 보류 하나는 저장소 유니크가 정본 |
| 대기실 | Cloudflare Waiting Room(분당 입장 수 · 오픈 전 줄 · 오픈 때 **섞기**), Queue-it(오픈 전 줄은 오픈 순간 무작위, 그 뒤는 온 순서 · 서명된 통과 토큰), AWS Virtual Waiting Room(발급 번호 · 입장 번호 두 카운터) | 오픈 전 줄 + 오픈 때 섞기 + 그 뒤 온 순서, **분당 입장 수**만, 순번표 · 입장권은 서명 토큰, **로그인한 사람만**(결정됨 3) |

## 1. 범위

| | 1차(끝에서 끝까지 한 벌) | 다음에 넓힐 것 |
|---|---|---|
| 자리 | 「풀」 = 회차 하나(이름 · 판매 시작/끝 · 한 번에 잡는 자리 상한 · 계정당 한도 · 통화). 자리 목록 = 구역 · 열 · 번호 · 등급 · 가격(없으면 무료). 배치도는 구역 → 열 → 번호 격자로 그린다 | 좌표 · 무대 그림이 있는 배치도 · 한 칸 띄우기 금지 규칙 · 반복 회차 자동 생성 · 대기자 명단 · 카탈로그 가격 연결 · 여러 통화 |
| 잡기 · 놓기 · 확정 | 찍어 잡기 · 다시 눌러 놓기 · 「나가기」로 모두 놓기 · 만료 자동 반환 · 무료 확정 · 결제 확정 · 취소 · 늦은 / 중복 결제 처리 | 자리별 부분 환불 · 양도 · 같은 계정의 같은 자리 다시 잡기 제한 |
| 배치도 반영 | SSE 사건 · 다시 붙으면 스냅샷 · 여러 대면 Redis pub/sub 중계 | 알림 브로커와 함께 쓰는 범용 실시간 중계 · 계정당 연결 수 상한 |
| 대기열 | 오픈 전 줄 · 섞기 · 순번 · 입장권 · 분당 속도 · 멈춤 · 새로고침 유지 · 계정 1인 1순번 | Redis 저장소 · 동시 인원 상한 · 「차례예요」 알림 |
| 운영 | 풀 만들기 · 자리 넣기 · 자리 막기/풀기 · 멈춤 · 현황 / 대기실 열기 · 속도 · 멈춤 · 닫기 · 현황 | 보류 강제 해제 · 내보내기 |

**비목표**: 배치도 그래픽 편집기 · 동적 가격 · 리셀 · 분산 트랜잭션 · 봇 완전 차단 · 익명 대기열(결정됨 3).
**반목표**: 자리 하나가 두 보류에 / 여러 자리 요청이 일부만 잡힌다 / 보류 하나의 자리가 두 번 풀린다 / 돈은 받았는데 자리도 환불도 없이 방치 / 같은 보류에 결제 둘이 받아진 채 방치 / 결제한 금액과 확정된 자리가 다르다 / **잡힌 채 영원히 안 풀린다**(만료를 늘리는 길이 하나라도 있다) / 새로고침에 순번이 바뀐다 / 위조 · 만료 · 남의 입장권으로 잡기 / **정확성이 대기열이나 화면 갱신에 기댄다**(둘 다 빼도 이중 판매 0) / 사건을 놓친 화면이 다시 붙어도 틀린 배치도로 남는다 / Redis 가 죽으면 한 대 안의 실시간 반영도 멈춘다 / 이 모듈을 안 붙인 앱의 동작이 바뀐다.

## 2. 모듈 경계 · 확장 지점

```
booking(-jdbc) ──api──▶ platform{TimeProvider, OperatorAccess, PermissionDeclaration, OnRequestTask}
booking ··compileOnly··▶ payment (결제 확정 · 환불), waiting-room (입장권 검사), job-queue-jdbc, redis-core (자리 사건 중계)
waiting-room(-jdbc) ──api──▶ platform
react @skeleton/booking ──▶ @skeleton/realtime (createSseClient)
```

`notification-sse` 를 그대로 쓰지 않고 같은 방식으로 따로 여는 이유: 알림 흐름은 토픽을 안 고른 연결(알림 종)이 모든 사건을 받으므로 자리 사건이 앱의 모든 알림 연결로 쏟아지고, 알림 브로커는 요청 스레드에서 구독자마다 바로 보낸다. 예약 흐름은 **풀 하나에만** 묶이고 대기실 입장권으로 문을 건다. 알림 쪽은 손대지 않는다.

| 확장 지점 | 자리 | 고른 이유 |
|---|---|---|
| `BookingPools` · `BookingHolds`(서버 API: 자리 잡기 · 놓기 · 확정 · 취소 · 현황) | `booking` | 앱이 자기 화면 · 도메인(공연 · 수업)에 붙인다 |
| `BookingPayments`(`itemFor(holdId, accountId)` · `onPaid` · `onRefunded`) | `booking` | `PaymentCatalog` · `PaymentOrderListener` 는 앱 빈 하나뿐이라 앱이 `itemType = "booking"` 으로 갈라 부른다(`catalog` 선례 그대로) |
| `BookingAdmission`(기본 = 늘 허용) | `booking`, 대기열이 있으면 `waiting-room` 이 구현 | 대기열 없이 작게 쓰는 모드 = 모듈을 안 붙인다. 정확성은 이 지점과 무관 |
| `WaitingRooms.requireEntry(scope, accountId, token)` | `waiting-room` | 예약이 아닌 앱도 자기 쓰기 경로 앞에 건다 |
| `BookingStore` · `WaitingRoomStore` | 각 모듈, 구현 `-jdbc` | 포트/어댑터 짝. Redis 저장소는 다음에 이 자리에 |
| `BookingSeatRelay`(기본 = 프로세스 안, `redis-core` 를 붙이면 Redis pub/sub) | `booking` | 한 대 · 여러 대가 같은 코드로 돈다. 다른 중계(메시지 큐 등)는 이 자리를 바꾼다 |

## 3. 자리 · 보류 (`booking-jdbc`, 두 방언, 시각 PG `timestamptz` · MySQL `datetime(6)` UTC)

- `booking_pools(id 'bkp_', name, opens_at, closes_at null, max_per_hold, per_account_limit null, currency null, hold_minutes 기본 10, waiting_room_id null, status ACTIVE|PAUSED|CLOSED, …)`
- `booking_seats(id 'bks_', pool_id, section, row_label, number, grade, price_amount null, status AVAILABLE|HELD|SOLD|BLOCKED, hold_id null, held_until null, version, …, unique(pool_id, section, row_label, number), index(pool_id))` — `held_until` 은 잡을 때 보류의 `expires_at` 을 옮겨 적는다(스냅샷 · 사건이 표 하나로 끝난다). `version` 은 자리 상태가 바뀔 때마다 +1(아래 모든 자리 갱신 문장이 `version = version + 1` 을 함께 한다) — 화면이 사건 순서 뒤바뀜을 거르는 데 쓴다. 풀 전체 카운터는 두지 않는다(뜨거운 줄이 생긴다).
- `booking_holds(id 'bkh_', pool_id, account_id, seat_count, amount null, status HELD|CONFIRMED|RELEASED|EXPIRED|CANCELED|UNFULFILLED, active_slot null, expires_at, checkout_started_at null, confirmed_order_id null, …, unique(pool_id, account_id, active_slot), index(status, expires_at))` — `active_slot` 은 `HELD` 일 때만 1 이고 나머지는 null. 두 방언 모두 null 은 유니크에 걸리지 않으므로 **계정당 풀마다 열린 보류 하나**가 부분 색인 없이 선다.
- `booking_hold_seats(hold_id, seat_id, pk)` — 보류가 가졌던 자리(늦은 결제의 다시 잡기 · 내 예약 목록). `booking_usage(pool_id, account_id, seats, pk)` — 계정당 한도를 조건부 갱신으로 센다(한도가 있을 때만).
- **불변식**(모든 시험이 끝에 확인): `HELD` 자리는 `HELD` 보류를, `SOLD` 자리는 `CONFIRMED` 보류를 가리키고 `AVAILABLE` · `BLOCKED` 자리의 `hold_id` 는 null. 열린 · 확정 보류마다 `seat_count` = 그 보류를 가리키는 자리 수 = 그 보류의 `booking_hold_seats` 줄 수. 사용량 = 계정의 열린 · 확정 보류 `seat_count` 합.
- **잠금 순서**: 늘 **보류 줄 → 사용량 줄 → 자리 줄(id 오름차순)**. 여러 자리를 한 문장으로 갱신하지 않는다 — 정렬한 id 로 한 줄씩(회수는 `select … where hold_id = ? order by id for update` 뒤 갱신). 그래서 {X, Y} 와 {Y, X} 를 동시에 잡아도 교착이 없다.
- **잡기** `addSeats(pool, account, seatIds)`: 한 트랜잭션 — ① 열린 보류를 찾거나 만든다(`active_slot = 1` 넣기가 유니크에 걸리면 있는 것을 읽는다) → `for update`. 만료 · 결제 시작 뒤면 `409 HOLD_EXPIRED` · `HOLD_LOCKED`, `seat_count + n > max_per_hold` 면 `LIMIT_REACHED`. ② 한도가 있으면 사용량 조건부 upsert(`seats + n <= limit`). ③ 자리마다 `update booking_seats set status = 'HELD', hold_id = ?, held_until = ? where id = ? and pool_id = ? and status = 'AVAILABLE'`. **하나라도 0줄이면 전부 되돌린다.** 막은 자리가 만료된 보류(`held_until < now`)의 것이면 그 보류를 따로 회수하고 한 번만 다시, 그래도 막히면 `409 BOOKING.SEAT_TAKEN {seatIds}`. ④ `booking_hold_seats` 넣기 · `seat_count + n`. 이미 내 보류에 있는 자리는 건너뛴다(멱등).
- **놓기**(다시 눌러 풀기) `removeSeat`: 내 열린 보류 잠금 → 결제 시작 전이면 `update booking_seats set status = 'AVAILABLE', hold_id = null, held_until = null where id = ? and hold_id = ? and status = 'HELD'` 가 1줄일 때만 `seat_count − 1` · 사용량 −1 · 보류 자리 줄 지우기. 이미 풀린 자리면 아무것도 안 한다. 자리가 0이 돼도 보류는 `HELD` 로 남아 **타이머가 다시 시작되지 않는다**.
- **모두 놓기**(「나가기」 · 「자리 다시 고르기」) `release`: `update booking_holds set status = 'RELEASED', active_slot = null where id = ? and status = 'HELD'` 가 1줄일 때만 그 보류의 자리를 모두 `AVAILABLE`, 사용량 −`seat_count`. 결제를 시작했어도 놓을 수 있다 — 그 뒤에 결제가 들어오면 늦은 결제다.
- **만료 회수**: 같은 모양으로 `… set status = 'EXPIRED' … where id = ? and status = 'HELD' and expires_at < now`. **보류 줄 전이가 1줄일 때만 자리가 돌아오므로** 회수 · 놓기 · 확정이 겹쳐도 한 번만 풀린다. 잡기가 막혔을 때 그 자리 회수 + 1분 주기 작업 `booking-hold-expire`(`scheduler` 가 있으면 주기, 요청 구동 모드면 `OnRequestTask`). 결제를 시작해도 만료를 늘리지 않는다 — **보류는 늘 `expires_at` 에 끝난다.**
- **확정**: 무료(그 보류 자리 모두 가격 없음)는 `confirm` 이 `HELD → CONFIRMED` · 자리 `SOLD`(만료 전만). 유료는 앱의 `PaymentCatalog` 가 `itemFor` 로 금액(= 자리 가격 합, 서버만 정한다)을 받아 주문을 만들고, 이때 `checkout_started_at` 을 적어 **자리를 고정**한다(그 뒤 더하기 · 빼기는 `409 HOLD_LOCKED` — 바꾸려면 모두 놓고 다시). `onPaid`: 보류가 `HELD` 면 `CONFIRMED` · `confirmed_order_id` · 자리 `SOLD`(만료 시각이 지났어도 아직 회수 전이면 그대로 확정). 이미 회수 · 놓기 된 보류면 `booking_hold_seats` 의 자리를 같은 조건부 갱신으로 **다시 잡고**(전부 아니면 전무), 못 잡으면 `UNFULFILLED` + 작업 `booking-refund`. 이미 **다른 주문으로** 확정된 보류면 이 주문은 `booking-refund`. 환불 작업은 트랜잭션 밖에서 `PaymentRefunds.refund("system:booking", 주문, "BOOKING_UNAVAILABLE", key = 주문 id)` — 키로 멱등. `onPaid` 는 주문 줄 락 안이라 DB 쓰기와 작업 넣기만 한다.
- **취소**: 무료 `CONFIRMED` 는 판매 끝 전까지 본인 취소(`CANCELED`, 자리 반환). 유료는 결제 환불이 정본 — `onRefunded` 의 전액 환불이 `CANCELED` + 자리 반환, 부분 환불은 자리 그대로.
- **자리 사건**: 자리 상태를 바꾼 트랜잭션은 **커밋 뒤**에 `BookingSeatRelay.publish(poolId, [{seatId, state: OPEN|TAKEN, heldUntil?, v}])` 를 부른다(잡기 · 놓기 · 모두 놓기 · 회수 · 확정 · 다시 잡기 · 취소 · 막기/풀기). 화면의 상태는 둘뿐이다 — `HELD` 는 `heldUntil` 이 붙은 `TAKEN`, `SOLD` · `BLOCKED` 는 붙지 않은 `TAKEN`. 롤백된 잡기는 사건이 없다. 보내기 실패는 업무를 실패시키지 않는다(로그 한 줄).
- **중계**: 기본 `LocalBookingSeatRelay` = 이 인스턴스의 그 풀 연결들에 넘긴다. 앱에 `redis-core` 가 있으면 `RedisBookingSeatRelay` 가 **이 인스턴스에는 바로 넘기고**, 채널 `RedisKeyPrefixer` 의 `booking:seats`(프로젝트 접두사 포함) 하나에 인스턴스 id 를 달아 발행한다 — 받는 쪽은 자기가 보낸 것을 건너뛴다. 그래서 Redis 가 죽어도 한 대 안의 반영은 멈추지 않는다. 인스턴스의 Redis 구독이 끊겼다 다시 붙으면 그사이 사건을 놓쳤을 수 있으므로 **그 인스턴스의 예약 흐름 연결을 모두 닫는다** → 화면이 다시 붙어 스냅샷으로 맞춘다. 보내기는 요청 스레드가 아니라 커밋 뒤 실행기 하나에서 한다(클릭 응답이 보는 사람 수에 묶이지 않게).
- **흐름** `GET …/seats/stream`(text/event-stream): 연결 때 로그인 · (대기실이 있으면) 입장권을 검사하고 그 풀 구독을 등록한 **뒤** 첫 사건 `ready` 를 보낸다. 사건 `seats`(위 모양). `notification-sse` 와 같은 방식 — 종료가 시작되면 웹 서버 종료 단계보다 먼저 연결을 닫고, 15초마다 주석 한 줄(심장박동, 프록시 유휴 끊김 막이), 연결 30분이면 서버가 닫는다(화면이 다시 붙는다). 두 값은 관행 값으로 고정하고 설정으로 열지 않는다.
- **스냅샷** `GET …/seats`: 자리 목록과 지금 상태를 한 번에 `{serverTime, seats: [{id, section, row, number, grade, price?, state, heldUntil?, v}]}`. **캐시하지 않는다** — 캐시된 옛 상태와 연결 전 사건 사이에 빈틈이 생긴다. 읽기는 연결 · 다시 연결 때만이라 보는 사람 수만큼이다.
- **화면이 맞추는 법**(`@skeleton/booking`): 흐름을 먼저 열고 `ready` 를 받으면 그동안 온 사건을 쥔 채 스냅샷을 읽고, 자리마다 **`v` 가 큰 쪽**을 남긴다 — 연결 전 사건은 스냅샷에, 연결 뒤 사건은 흐름에 있으므로 빠지는 것이 없다. 다시 붙을 때마다(`createSseClient` 의 `onOpen({reconnect: true})` · 끊김 감지 `idleTimeoutMs` 45초 · 숨김 뒤 다시 보임) 같은 순서를 되풀이한다. `TAKEN` 에 `heldUntil` 이 있으면 화면이 그 시각(스냅샷 `serverTime` 으로 시계 차를 고친 값)에 스스로 「열림」으로 바꾼다 — 회수 작업을 기다리지 않고 만료가 곧바로 보인다. 그 자리를 누르면 잡기가 그 자리에서 회수한다. `409 SEAT_TAKEN` 을 받으면 그 자리를 바로 「선택 불가」로 둔다.
- **연결 수 계산**: 배치도를 연 사람마다 열린 요청 하나다(DB 연결은 쥐지 않는다). 보는 사람은 대기실이 묶는다(분당 입장 × 입장 시간, 예 30 × 10분 = 300). Cloud Run 이면 필요한 인스턴스 ≈ 보는 사람 ÷ 동시성이고 요청 시간 한도는 연결 30분보다 길어야 한다 — `docs/cloud-run-scale-to-zero.md` 에 한 단락(최대 인스턴스 · 동시성 · 「여러 대면 `redis-core` 를 붙인다」).
- **운영자 자리 관리**: 넣기는 언제나(같은 구역 · 열 · 번호면 `409 BOOKING.SEAT_EXISTS`). 막기는 `AVAILABLE → BLOCKED`, 풀기는 `BLOCKED → AVAILABLE` 만 — 잡힌 · 팔린 자리는 `409 BOOKING.SEAT_IN_USE {seatIds}`(이미 팔린 것은 못 거둔다).

## 4. 대기열 (`waiting-room-jdbc`, 로그인한 사람만)

- `waiting_rooms(id 'wr_', scope_key unique, opens_at, prequeue_starts_at, entry_ttl, status, shuffled_at null, tail, …)` · `waiting_room_rates(room_id, from_at, per_minute)`(속도 구간 — 멈춤 = 0) · `waiting_room_tickets(id 'wrt_', room_id, account_id, position null, shuffle_key, created_at, unique(room_id, account_id), unique(room_id, position))`.
- **순번표 받기** `POST /waiting-rooms/{id}/tickets`: 같은 계정이면 **같은 순번표**(유니크). 오픈 전이면 번호 없이 줄에(무작위 `shuffle_key`), 오픈 뒤면 `tail + 1`.
- **섞기**: 오픈 시각 뒤 첫 요청이 방 줄을 조건부 갱신(`shuffled_at is null`)으로 이긴 쪽만 `row_number() over (order by shuffle_key)` 로 번호를 매긴다 — 타이머 없이 0대에서도 돈다(결정됨 1).
- **입장 번호(서빙)** = 오픈 시각부터 속도 구간을 적분한 값 — 시계와 DB 의 구간 표만으로 모든 인스턴스가 같은 값을 계산한다(NTP 전제, 결제와 같다). 내 번호 ≤ 서빙이면 입장, 입장 시각부터 `entry_ttl`(기본 10분) 동안 입장권을 다시 받을 수 있다. 지나면 `EXPIRED` → 「다시 줄 서기」(맨 뒤). 입장권은 **자리를 더할 때마다** 검사한다 — 입장 시간이 끝나면 새 자리는 못 잡고 이미 잡은 보류는 결제 · 확정할 수 있다.
- **상태 묻기** `GET /waiting-rooms/{id}/status`(헤더 `X-Ticket`): 순번표 토큰에 번호가 박혀 있어 **대부분 DB 를 안 읽는다**(방 정보는 인스턴스에 2초 캐시). 응답 `{state: PREQUEUE|WAITING|ADMITTED|EXPIRED|CLOSED, position, ahead, estimatedWaitSeconds, opensAt, pollAfterSeconds, entryToken?}`. 예상 시간 = 앞 사람 ÷ 지금 속도(멈춤이면 null). 묻는 간격은 서버가 앞 사람 수로 5 ~ 30초 + 흔들기.
- **토큰**: 순번표 · 입장권 모두 HMAC-SHA256 서명(키 id 포함 — 키 돌리기), 계정에 묶인다. 입장권은 방 · 계정 · 만료만 싣는다. 비밀 `SKELETON_WAITING_ROOM_TOKEN_KEYS_*` — 보호 환경에서 없거나 약하면 기동 실패(`DeployGuard`, 깨우기 비밀과 같은 기준).
- **새로고침**: 화면이 순번표 토큰을 방별 저장소에 둔다. 지워져도 다시 받으면 같은 순번표.
- **0대 · Redis**: 쓰기는 사람당 한 번(순번표), 묻기는 DB 없이 계산 → **Redis 없이 DB 하나로 충분**하고 타이머가 필요 없다. 0대 콜드스타트는 오픈 전 줄이 받아 준다 — `docs/cloud-run-scale-to-zero.md` 에 「오픈 전 줄 시작 시각에 미리 깨우기」 한 단락.

## 5. API (사용자 경로는 붙이면 켜짐 `skeleton.booking.http.enabled` · `skeleton.waiting-room.http.enabled`, 운영은 `…admin.enabled` 기본 꺼짐 → 404)

| 경로 | 하는 일 | 권한 |
|---|---|---|
| `GET /api/v1/booking/pools/{id}` | `{id, name, opensAt, closesAt, currency?, maxPerHold, perAccountLimit?, holdMinutes, waitingRoomId?, status, seats: {total, available}}` | 공개 |
| `GET /api/v1/booking/pools/{id}/seats` | 스냅샷 `{serverTime, seats: [{id, section, row, number, grade, price?, state: OPEN\|TAKEN, heldUntil?, v}]}` | 로그인 |
| `GET /api/v1/booking/pools/{id}/seats/stream` | SSE — `ready` 뒤 `seats` 사건 `{seats: [{id, state, heldUntil?, v}]}`, 대기실이 있으면 연결 때 `X-Entry-Token` | 로그인 |
| `POST /api/v1/booking/pools/{id}/hold/seats` `{seatIds}` | 대기실이 있으면 `X-Entry-Token` → 200 `{holdId, seats, expiresAt, amount?}` · `409 SEAT_TAKEN {seatIds}` · `LIMIT_REACHED` · `NOT_OPEN` · `HOLD_EXPIRED` · `HOLD_LOCKED` · `403 WAITING_ROOM.ENTRY_REQUIRED` | 로그인 |
| `DELETE /api/v1/booking/pools/{id}/hold/seats/{seatId}` | 다시 눌러 풀기 → 200 보류(같은 모양) · `409 HOLD_LOCKED` | 로그인 |
| `GET /api/v1/booking/holds/me` · `POST /holds/{id}/confirm` · `/release` · `/cancel` | 내 열린 보류 · 확정 목록 · 무료 확정(`409 HOLD_EXPIRED`) · 모두 놓기(`HELD` 만) · 확정 취소(무료 `CONFIRMED` 만). 남의 · 없는 보류는 같은 404 | 로그인 |
| 결제 | 기존 `POST /api/v1/payments/orders {itemType:"booking", itemRef: holdId}` | 로그인 |
| `POST /api/v1/waiting-rooms/{id}/tickets` · `GET …/status` | §4 | 로그인 |
| `/api/v1/admin/booking/pools` (`GET` · `POST {name, opensAt, closesAt?, maxPerHold, perAccountLimit?, currency?, holdMinutes?, waitingRoomId?}` · `PATCH {name, closesAt, status}` · `POST {id}/seats {seats:[{section,row,number,grade,price?}]}` · `POST {id}/seats/block {seatIds}` · `/unblock` · `GET {id}/stats`) | 현황 `{total, available, held, sold, blocked, unfulfilled}` | `booking.manage` · `booking.read` |
| `/api/v1/admin/waiting-rooms` (`POST {scopeKey, opensAt, prequeueMinutes, perMinute}` · `POST {id}/rate {perMinute}` · `POST {id}/close` · `GET {id}/stats`) | 현황 `{prequeue, issued, serving, perMinute, estimatedDrainSeconds}` | `waiting-room.manage` |

`release` 와 `cancel` 을 나눈 이유: 화면이 「나가기」로 보내는 놓기가 늦게 도착해도 이미 확정된 예약을 취소하지 않게. 스냅샷 · 흐름이 로그인인 이유: 캐시 없는 DB 읽기 · 열린 연결이라 익명에 열면 누구나 서버 자원을 붙잡는다.

## 6. 화면 (react)

- `@skeleton/waiting-room`: `createWaitingRoomApi` · `WaitingRoomGate`(두 모양 — **페이지 앞**: 순번이 오기 전엔 대기 화면, **버튼 앞**: `useWaitingRoom(id).enter()` 가 입장권을 기다렸다 돌려준다) · 대기 화면(오픈 카운트다운 · 「내 앞에 N명」 · 예상 시간 · 진행 막대 · 「새로고침해도 순서는 그대로예요」) · `createEntryHeaders` · `@skeleton/waiting-room/admin` 의 `AdminWaitingRoom`(속도 입력 · 멈춤/다시 · 닫기 · 숫자 현황 5초 갱신).
- `@skeleton/booking`:
  - `SeatMap` — 구역마다 열 줄 · 번호 순 자리 단추, 등급 색 범례, 세 상태(선택 가능 · 내 자리 · 선택 불가). 상태는 `useSeatFeed(poolId)` 가 맞춘다(§3 「화면이 맞추는 법」 — `@skeleton/realtime` 의 `createSseClient` 위, 다시 붙을 때마다 스냅샷, 자리별 `v`, `heldUntil` 타이머). 연결이 끊긴 동안은 배치도 위에 「다시 연결하는 중」 한 줄. 누르면 잡기 · 내 자리를 누르면 놓기, 응답이 올 때까지 그 단추만 잠근다. `409 SEAT_TAKEN` 이면 그 자리를 바로 「선택 불가」로 바꾸고 「다른 분이 먼저 잡았어요」. 단추마다 읽히는 이름(「A구역 3열 12번 R석, 선택 불가」). 그림 편집 · 확대 축소는 없다.
  - `HoldPanel` — 잡은 자리 · 금액 · 남은 시간(0 이면 버튼 잠김 · 「시간이 지나 자리가 풀렸어요」) · 무료 확정 또는 결제 자리(앱이 `@skeleton/payment` 결제창을 끼운다 — 패키지끼리 import 하지 않는다) · 결제 시작 뒤엔 「자리 다시 고르기」(= 모두 놓기). 결제창을 닫으면 보류를 그대로 두고 다시 결제할 수 있다.
  - `BookingLeaveButton` 성격의 「나가기」 = 모두 놓기. **새로고침 · 탭 닫기에는 놓지 않는다** — 결제사 리다이렉트도 페이지를 떠나는 것이라 그때 놓으면 결제 중에 자리가 풀린다. 탭을 닫은 보류는 타이머가 푼다.
  - `MyBookings` · `@skeleton/booking/admin` 의 `AdminBookingPools`(풀 만들기 · 자리 넣기 — 「구역 · 열 범위 · 번호 범위 · 등급 · 가격」 입력을 화면이 자리 목록으로 펼친다 · 막기/풀기 · 멈춤 · 현황).
- 문구 ko/en `labels` prop, 부품마다 스토리 + `play`, 눌리는 것은 눌리게, SSR 안전.

## 7. 견본 앱 · 시험

- 견본(kotlin + react `apps/sample`): 풀 「워크숍 20석」(무료, 한 등급, 한 번에 2석) · 「소극장 공연 120석」(유료 R · S 두 등급, 한 번에 4석, `cheer/` 카탈로그가 `itemType` 으로 가른다), 둘 다 대기실(분당 30). 씨앗은 로컬만.
- **e2e 한 여정**(계정 둘, 브라우저 문맥 둘): 오픈 전 대기 화면 → 오픈(가짜 시계 앞당기기) → 둘 다 입장 → A 가 두 자리를 찍는다 → B 의 배치도에 그 두 자리 「선택 불가」(새로고침 없이 수 초 안) → B 가 그중 하나를 눌러 「다른 분이 먼저 잡았어요」 → A 가 하나를 다시 눌러 풀기 → B 의 배치도에 다시 열림 → B 가 잡는다 → A 무료 확정 → 새로고침해도 「확정」.
- **부하 · 동시성 시험(돈 · 동시성 — 반드시)**, `booking-jdbc` `dbTest`(PostgreSQL · MySQL 둘 다, 시작 신호를 한꺼번에 여는 걸쇠):
  1. 같은 자리 하나에 **서로 다른 계정 1000 동시** → 잡은 보류 정확히 하나. 자리 100 풀에 1000 계정이 무작위 1 ~ 3자리 묶음 → 어느 자리도 두 보류에 없고 일부만 잡힌 보류가 없다, 불변식 성립.
  2. 엇갈린 순서: {X, Y} 와 {Y, X} 를 각 200 동시 → 교착 0(오류 0), 각 자리 주인 하나. 같은 계정 50 동시로 서로 다른 자리(`max_per_hold` 2) → 정확히 2, 열린 보류 하나. 같은 자리 잡기 · 놓기를 한 계정이 100 번 섞어 동시 → 끝 상태와 `seat_count` · 사용량이 맞는다.
  3. 만료 회수 두 벌 ∥ 그 자리 새 잡기 ∥ 모두 놓기 ∥ 늦은 `onPaid` → 자리가 두 번 풀리지 않는다, 불변식 성립. 늦은 결제 + 다른 사람이 잡음 → `UNFULFILLED` + 환불 작업 하나(두 번 불려도 하나). 같은 보류에 주문 둘이 결제 → 하나 확정, 하나 환불 작업. 결제 시작 뒤 자리 더하기 · 빼기 → `HOLD_LOCKED`.
  4. 같은 DB 를 보는 독립 컨텍스트 둘로 1번을 반반(여러 대).
  5. **시험이 진짜 막는지**: 조건(`status = 'AVAILABLE'`)을 뺀 판에서 1번이, 「전부 아니면 전무」 되돌리기를 뺀 판에서 1번 묶음 판이 빨개지는 것을 한 번씩 확인하고 결과를 진행 기록에 남긴다(결제 B10 선례).
  6. HTTP: 견본 `RANDOM_PORT` + 가상 스레드 1000 개가 실제 HTTP 로 같은 10자리를 찍는다 → 200 은 10, 나머지 `409 SEAT_TAKEN`, 5xx 0. p50 · p95 를 기록만 한다(단정하지 않는다).
- **배치도 반영(실시간 — 반드시)**:
  7. 사건: 잡기 · 놓기 · 모두 놓기 · 회수 · 확정 · 막기마다 커밋 뒤 사건 하나, 롤백된 잡기는 사건 0, 같은 자리 `v` 는 늘 오른다.
  8. 여러 대: Redis(Testcontainers) 를 나눠 보는 독립 컨텍스트 둘에 흐름 연결 각 100 → 시험 6 모양의 찍기 폭주를 두 대에 반반 → 끝에 **모든 연결의 「스냅샷 + 받은 사건」 결과가 DB 와 자리마다 같다**.
  9. 놓침: 한 인스턴스의 Redis 구독을 끊고 그사이 자리를 바꾼 뒤 다시 붙임 → 그 인스턴스의 연결이 닫히고, 다시 붙은 쪽이 스냅샷으로 DB 와 같아진다. Redis 를 아예 내려도 한 대 안의 사건은 계속 온다.
  10. 빈틈: 사건이 스냅샷 읽기와 겹치게 일부러 끼운 판(스냅샷 직전 · 직후 커밋)에서 화면 맞추기 결과가 DB 와 같다(react 쪽 시험 — 사건 순서 뒤섞기 포함).
  11. 종료: 열린 흐름이 있어도 서버 종료가 30초를 기다리지 않는다(`notification-sse` 선례).
- 대기열(가짜 시계): 분당 100 에서 60초 뒤 정확히 100 입장 · 멈춤 구간 · 섞기를 동시에 200 요청이 불러도 한 번 · 번호가 1..n 빈틈 없는 순열 · 위조 · 만료 · 다른 계정의 토큰 거절 · 같은 계정 순번표 하나 · 입장 시간이 지난 계정은 자리를 더하지 못하고 있던 보류는 확정된다.
- 조립: 대기열 없이 예약만(`noOptionalTest`) · 결제 없이 무료 풀만 · 이 모듈 없는 `apps/api` 그대로 · `MigrationLockTest` · `CapabilitiesCatalogTest` · `ModuleDocumentationTest`.
- react: 대기 화면 상태별 스토리 `play` · 묻는 간격을 서버 값대로 · 저장소 복원 · `SeatMap` 세 상태 스토리 · `useSeatFeed` 맞추기(`ready` 전 사건 쥐기 · `v` 작은 사건 버림 · 다시 붙으면 스냅샷 다시 · `heldUntil` 에 스스로 열림 · 시계 차 보정) · 끊긴 동안 「다시 연결하는 중」 · `409` 뒤 그 자리 즉시 「선택 불가」 · 응답 전 같은 단추 두 번 눌러도 요청 하나 · 보류 타이머 0 에서 버튼 잠김 · 결제 시작 뒤 자리 단추 잠김 · api 경로 기록 시험.

## 8. 내가 정한 것 (관행대로 — 바꾸려면 말해 줘)

1. 정본은 DB 자리 줄 조건부 갱신, 여러 자리는 전부 아니면 전무, Redis 는 1차에 안 쓴다(대기열 · 자리 모두).
2. 보류 10분 · 입장권 10분 · 오픈 전 줄 30분 전부터 — 안전 장치라 모듈 기본, 풀 · 방마다 덮는다. 보류 타이머는 첫 자리부터 재고 늘지 않는다.
3. 분당 입장 수와 **한 번에 잡는 자리 상한**은 만들 때 필수(기본값 없음 — 서버 크기 · 행사 성격을 모듈이 모른다. 상한이 없으면 한 사람이 다 잡는다).
4. **결제를 시작해도 보류를 늘리지 않는다**(초안의 「결제창이 열린 보류는 결제가 끝날 때까지 회수 안 함」을 버렸다). 버려진 결제창이 자리를 `checkout-ttl`(30분) 동안 묶고, 배치도 표시가 결제 표를 읽어야 해서다. 마지막 순간의 결제는 다시 잡기 → 실패면 자동 환불이 받는다. 그래서 `payment` 에 `openOrdersFor` 를 더하지 않는다.
5. 결제를 시작하면 자리를 고정한다(금액과 확정 자리가 어긋나지 않게). 바꾸려면 모두 놓고 다시 고른다.
6. 유료 취소는 결제 환불이 정본(전액만 자리 반환). 같은 보류에 두 번 결제되면 뒤의 것은 자동 환불.
7. 계정당 한도 기본 없음(중립).
8. 대기열은 예약과 따로 쓰는 모듈, 예약은 대기열을 몰라도 맞다. 입장권은 자리를 더할 때마다 검사 — 대기실을 단 풀에서는 입장 시간이 「같은 자리 놓았다 다시 잡기」로 버티는 시간의 상한이 된다(대기실 없는 풀은 막지 않는다).
9. 배치도 반영(결정됨 5 를 이렇게 만든다): 예약 전용 SSE 흐름을 `notification-sse` 와 같은 방식으로 따로 연다(알림 흐름은 손대지 않는다 — §2) · 중계는 `redis-core` 가 있으면 저절로 Redis pub/sub, 없으면 프로세스 안(설정 스위치 없음) · 이 인스턴스엔 Redis 를 거치지 않고 바로 · 자리별 `version`(풀 카운터 없음) · 스냅샷은 캐시하지 않음 · 만료는 화면이 `heldUntil` 에 스스로 연다 · 심장박동 15초 · 연결 30분 고정. 정확성은 찍는 순간 DB 가 정하고, 늦게 본 자리를 누르면 `409` 로 바로 고쳐진다. 여러 대인데 `redis-core` 를 안 붙이면 다른 인스턴스의 변화는 다시 붙을 때(최대 30분)까지 안 보인다 — 모듈 문서에 굵게 적는다.
10. 새로고침 · 탭 닫기에는 놓지 않는다 — 놓는 길은 다시 누르기 · 「나가기」(결제창을 닫고 나가는 경우 포함) · 타이머 셋.

## 9. 작업 계획

| 키 | 조각 | 선행 | 크기 | 예상 |
|---|---|---|---|---|
| K1 | `booking` · `booking-jdbc`: 표 다섯(V 두 방언) · 잡기 · 놓기 · 모두 놓기 · 무료 확정 · 취소 · 만료 회수(주기 · 그 자리) · 스냅샷 · SSE 흐름 · 사건 · `LocalBookingSeatRelay` · 사용자 API · 오류 코드 · 동시성 시험 1 · 2 · 3(결제 빼고) · 4 · 5 · 7 · 11 · 모듈 문서 · 설정 문서 · 카탈로그(새 네 모듈 자리를 한 번에) | — | 대 | 8h |
| K2 | `waiting-room` · `-jdbc`(로그인만): 표 셋 · 순번표 · 섞기 · 서빙 계산 · 토큰(키 돌리기 · DeployGuard) · 상태 API · `requireEntry` · 운영 API · 시험 · 0대 문서 단락 | — | 대 | 5h |
| K3 | 결제 · 운영 · 중계: `BookingPayments`(금액 · 자리 고정) · 늦은 / 중복 결제 · `booking-refund` 작업 · 동시성 시험 3(결제) · 예약 운영 API(풀 · 자리 넣기 · 막기 · 현황) · 권한 선언 · OpenAPI · `BookingAdmission` 대기열 구현 · `RedisBookingSeatRelay`(구독 재연결 시 연결 닫기) · 시험 8 · 9 | K1 · K2 | 대 | 5.5h |
| K4 | 견본: 조립(`redis-core` 포함) · `cheer/` 카탈로그 분기 · 씨앗(두 풀 자리) · yml · HTTP 부하 시험 6 · `docs/sample.md` · 0대 문서 연결 수 단락 · CHANGELOG | K3 | 중 | 3h |
| R1 | `@skeleton/waiting-room`: api · Gate 두 모양 · 대기 화면 · 헤더 · 운영 화면 · 스토리 · 카탈로그 | §5 표(가짜 API) | 중 | 3h |
| R2 | `@skeleton/booking`: api · `useSeatFeed`(`createSseClient` · 스냅샷 맞추기 · 시험 10) · `SeatMap`(세 상태) · `HoldPanel` · 나가기 · `MyBookings` · 운영 화면(자리 범위 입력 · 막기) · 스토리 · 카탈로그 | R1 | 대 | 6h |
| R3 | react 견본 조립 · e2e 한 여정(계정 둘) | R2 · K4 합친 뒤 | 중 | 3.5h |

- 묶음: ① K1 ∥ K2 ∥ R1 → ② K3 ∥ R2 → ③ K4 → R3 → 끝 검사(지휘: kotlin 바뀐 모듈만 한 번 · react 전체 + `e2e:sample` 한 번, 리뷰 kotlin 1 · react 1, qa 1 — 화면당 캡처 2장까지). 동시 최대 3.
- developer 7 + 리뷰 2 + qa 1 = 에이전트 10. 벽시계 대략 20h(K1 8h → K3 5.5h → K4 3h → R3 3.5h 가 긴 줄).
- 같이 만지는 파일: `settings.gradle.kts` · `capabilities.json` · `docs/modules/README.md` 는 K1 만(네 모듈 자리), 견본 yml · CHANGELOG 는 K4 만. `docs/cloud-run-scale-to-zero.md` 는 K2(대기열 단락) 뒤에 K4(연결 수 단락) — 묶음이 달라 겹치지 않는다. `payment` · `notification*` · `redis-core` · `@skeleton/realtime` 은 아무 조각도 고치지 않는다(쓰기만).
- 계약이 바뀌면 kotlin 조각이 보고에 적고 지휘가 react 조각에 넘긴다. 스펙과 계획이 어긋나면 스펙이 이긴다.

참고(관행 조사 출처): [Cloudflare Waiting Room 이벤트(오픈 전 줄 · 섞기 · 분당 입장)](https://developers.cloudflare.com/api/terraform/resources/waiting_rooms) · [Pulumi 의 Cloudflare 대기실 이벤트 항목](https://www.pulumi.com/registry/packages/cloudflare/api-docs/waitingroomevent/) · [Queue-it 오픈 전 줄(오픈 때 무작위, 그 뒤 온 순서)](https://queue-it.com/pre-queue/) · [Queue-it 토큰 SDK](https://github.com/queueit/QueueToken.V1.JAVA).

## 결정됨 (2026-11-02)

형과 정했다. 이 절이 본문보다 우선한다.

1. 오픈 전 순서 — 형이 관행을 물어 설명한 뒤 **A**: 오픈 순간 무작위로 섞고, 오픈 뒤 온 사람은 온 순서(대기열 전문 서비스 관행).
2. 결제 — 형: "콘서트 예약 방식으로 가야지" → **A**: 대기열 → 입장 → 자리 잡기(카운트다운 보류) → 결제 → 시간 초과 자동 반환, 늦은 결제는 다시 잡거나 자동 환불. 좌석 배치도에서 직접 고르기 여부는 형 답 대기(기본은 수량형).
3. 익명 대기열 — 형: "로그인만", "로긴 한 사람 위주로 해줘야 하지 않을까" → **B: 로그인한 사람만**. 익명 모드 · 캡차 기반 1인 1순번은 만들지 않는다(1인 1순번 = 계정 1개 1순번).
   - 덧붙임(2026-11-03): 계정 없이 쓰는 앱이 대기열을 원하면 그 앱이 자체로(브라우저 토큰 + 오픈 직전 서버 미리 깨우기) 만든다. 스켈레톤 대기열은 계정 주체 그대로 두고 익명 확장점을 미리 만들지 않는다.
4. 좌석 — 형: 「좌석 직접 찍기까지」(2026-11-03). 배치도에서 자리 하나하나를 고르고, 자리 단위로 잡기 경쟁(같은 자리 동시 클릭은 한 명만)을 한다.
   - 형: 「점유 했다가 릴리즈 하는 것까지도 하자」 — 자리를 찍으면 그 자리가 잡히고(다른 사람에게 바로 「선택 불가」로 보임), 놓는 길은 셋: 본인이 다시 눌러 풀기 · 결제창을 닫고 나가기 · 보류 시간 끝(자동 반환). 풀린 자리는 다른 사람 배치도에 곧바로 다시 열린다.
5. 배치도 실시간 반영 — 형(2026-11-03): **SSE**(기존 `notification-sse` · `@skeleton/realtime` 재활용 또는 같은 방식). 서버 여러 대면 다른 인스턴스로의 전달은 Redis(`redis-core`) pub/sub. 끊겼다 다시 붙으면 배치도 전체 스냅샷을 다시 받아 맞춘다. 판정은 항상 DB. 폴링 안은 쓰지 않는다.
