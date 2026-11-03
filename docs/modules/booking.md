# booking

예약(좌석 지정 · 선착순) — 회차 하나(풀)의 배치도에서 자리를 직접 찍어 **잡고**, 다시 눌러 **놓고**, 시간이 지나면 **자동으로 돌아오고**, 확정한다. 이중 판매 0 이 이 모듈의 약속이다.
저장은 포트(`BookingStore`)이고 DB 는 `booking-jdbc` 가 붙인다 — 저장소가 없으면 서비스도 서지 않는다.

**풀 · 자리 · 보류**: 풀 = 회차(이름 · 판매 시작/끝 · 한 번에 잡는 자리 상한 `maxPerHold` — **만들 때 필수, 기본값 없음** · 계정당 한도(선택) · 통화(선택) · 보류 시간 기본 10분 · 대기실 id(선택)).
자리 = 구역 · 열 · 번호 · 등급 · 가격(없으면 무료) — 상태 `AVAILABLE · HELD · SOLD · BLOCKED`, 바뀔 때마다 `version` 이 오른다. 보류 = 한 계정이 한 풀에서 잡고 있는 자리 묶음(계정당 풀마다 **열린 보류 하나**).
금액은 통화의 최소 단위 정수이고 서버만 정한다(자리 가격의 합).

**서버 API**(앱이 자기 화면 · 도메인에 붙인다): `BookingPools`(풀 만들기 · 자리 넣기 · 막기/풀기 · 스냅샷) · `BookingHolds`(자리 더하기 · 하나 놓기 · 모두 놓기 · 무료 확정 · 취소 · 만료 회수 · 내 보류). 자리 상태를 바꾼 호출은 **커밋 뒤에** 자리 사건을 한 번 중계한다.
- **잡기** — 여러 자리는 **전부 아니면 전무**. 하나라도 못 잡으면 통째로 되돌려지고 `409 BOOKING.SEAT_TAKEN {seatIds}`(못 잡은 자리 전부). 이미 내 보류에 있는 자리는 건너뛴다(멱등). 막은 자리가 **보류 시간이 지났는데 아직 회수 전**이면 그 보류를 그 자리에서 회수하고 한 번만 다시 시도한다.
- **놓기** — 다시 눌러 풀기. 자리가 0이 돼도 보류는 남고 **타이머가 다시 시작되지 않는다**. **모두 놓기** — 「나가기」 · 「자리 다시 고르기」. `HELD` 가 아닌 보류에는 아무 일도 안 한다(늦게 도착한 놓기가 확정된 예약을 건드리지 않는다).
- **보류 시간** — 첫 자리를 잡을 때 정해지고 자리를 더해도 늘지 않는다. 다 되면 자동 반환. **새로고침 · 탭 닫기에는 놓지 않는다**(놓는 길은 다시 누르기 · 나가기 · 시간 끝).
- **만료 회수** — 1분 주기 작업(`scheduler` 가 있으면 `booking.hold-expire`, 요청 구동 모드면 `OnRequestTask` `booking-hold-expire`) + 잡기가 막혔을 때 그 자리 회수. 주기가 늦어도 자리는 안 막힌다. 보류 줄 전이가 1줄일 때만 자리가 돌아와 회수 · 놓기 · 확정이 겹쳐도 한 번만 풀린다.
- **무료 확정 · 취소** — 자리 모두 가격이 없는 보류는 `confirm`(만료 전만) 으로 `CONFIRMED` · 자리 `SOLD`. 무료 확정은 판매 끝 전까지 본인이 `cancel` 한다. 가격이 붙은 자리의 결제 확정은 아직 연결되어 있지 않다(`409 BOOKING.PAYMENT_REQUIRED`).

**사용자 경로**(서블릿 웹 앱 + Spring Security 가 있을 때, 기본 켜짐 `skeleton.booking.http.enabled`; 풀 정보만 공개, 나머지는 로그인 — 남의 · 없는 보류는 같은 404). 응답은 모두 `{value}` 봉투(목록은 `{values}`):

| 메서드 · 경로 | 하는 일 |
|---|---|
| `GET /api/v1/booking/pools/{id}` | 풀 `{id, name, opensAt, closesAt?, currency?, maxPerHold, perAccountLimit?, holdMinutes, waitingRoomId?, status, seats: {total, available}}` (공개) |
| `GET …/pools/{id}/seats` | 스냅샷 `{serverTime, seats: [{id, section, row, number, grade, price?, state: OPEN\|TAKEN, heldUntil?, v}]}` — **캐시하지 않는다**(`Cache-Control: no-store`) |
| `GET …/pools/{id}/seats/stream` | `text/event-stream` — 첫 사건 `ready`, 뒤이어 `seats` `{seats: [{id, state, heldUntil?, v}]}`. 대기실이 있으면 `X-Entry-Token` |
| `POST …/pools/{id}/hold/seats {seatIds}` | 자리 잡기 → `{holdId, poolId, status, seats, expiresAt, amount?}`. 대기실이 있으면 `X-Entry-Token` |
| `DELETE …/pools/{id}/hold/seats/{seatId}` | 다시 눌러 풀기 → 같은 모양의 보류 |
| `GET …/holds/me` | 내 열린(만료 전) · 확정 보류 목록 |
| `POST …/holds/{id}/confirm` · `/release` · `/cancel` | 무료 확정 · 모두 놓기 · 확정 취소 |

오류(모두 `BOOKING.` 접두): `409 SEAT_TAKEN`(`data.seatIds`) · `LIMIT_REACHED`(한 번에 잡는 상한 · 계정당 한도) · `NOT_OPEN`(판매 전 · 끝 · 멈춤) · `HOLD_EXPIRED` · `HOLD_LOCKED`(결제를 시작한 보류의 자리 바꾸기) · `HOLD_EMPTY` · `PAYMENT_REQUIRED` · `NOT_CANCELABLE` · `SEAT_EXISTS` · `SEAT_IN_USE`(`data.seatIds`), `404 POOL_NOT_FOUND · HOLD_NOT_FOUND · SEAT_NOT_FOUND`. 대기실 입장권이 없으면 `403 WAITING_ROOM.ENTRY_REQUIRED`(문 구현이 던진다).

**배치도 반영**(SSE): 서버는 `notification-sse` 와 같은 방식으로 예약 전용 흐름을 따로 연다 — 연결은 풀 하나에만 묶이고, 구독을 등록한 **뒤에** `ready` 를 보낸다. 화면은 흐름을 먼저 열고 `ready` 를 받은 뒤 스냅샷을 읽고, 자리마다 **`v` 가 큰 쪽**을 남긴다(연결 전 변화는 스냅샷에, 연결 뒤 변화는 흐름에 있어 빠지는 것이 없다). 다시 붙을 때마다 같은 순서를 되풀이한다.
화면의 상태는 둘뿐이다 — `HELD` 는 `heldUntil` 이 붙은 `TAKEN`, `SOLD` · `BLOCKED` 는 붙지 않은 `TAKEN`. `heldUntil` 시각에 화면이 스스로 「열림」으로 바꾼다. 종료가 시작되면 웹 서버 종료 단계보다 먼저 열린 연결을 모두 닫고(30초를 기다리지 않는다), 15초마다 주석 한 줄(심장박동), 연결 30분이면 서버가 닫는다(화면이 다시 붙고 스냅샷으로 맞춘다) — 두 값은 고정이다.
중계 기본 `LocalBookingSeatRelay` 는 **이 인스턴스의 그 풀 연결들**에만 넘기고, 보내기는 요청 스레드가 아니라 실행기 하나에서 한다. **여러 대로 돌리면 다른 인스턴스에서 일어난 변화는 연결이 다시 붙을 때(최대 30분)까지 안 보인다** — 여러 대를 잇는 중계(Redis pub/sub)는 `BookingSeatRelay` 자리를 바꿔 끼운다. 놓친 사건은 정확성에 영향이 없다: 찍는 순간 DB 가 판정하고 늦게 본 자리를 누르면 `409` 로 바로 고쳐진다.

**정확성은 DB 가 정한다**: 정본은 자리 줄 조건부 갱신(`… set status = 'HELD' … where id = ? and status = 'AVAILABLE'`, 바뀐 줄이 1일 때만 내 것)이고 대기열 · 화면 갱신 · 문(`BookingAdmission`)을 모두 빼도 이중 판매는 0 이다. `BookingAdmission` 의 기본은 **늘 허용**이다(대기열을 붙이면 입장권 검사가 이 자리에 선다).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:booking"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | `scheduler` |
| 설정 접두사 | `skeleton.booking` — [docs/config/modules/booking.yml](../config/modules/booking.yml) |
| 기본 동작 | 조건부 — 저장소(`booking-jdbc`)가 있으면 서비스가 서고, 서블릿 앱이면 사용자 경로도 켜진다. 문은 늘 허용, 중계는 이 인스턴스 안. |
| 부팅에 필요한 것 | `TimeProvider` 빈(platform)과 `BookingStore` — `booking-jdbc` 가 낸다(없으면 서비스가 서지 않는다). |
| 교체 지점 | `BookingStore`, `BookingAdmission`(문), `BookingSeatRelay`(중계), `BookingPools`, `BookingHolds`, `BookingController`, `BookingSeatStreamService`, `BookingExpiryJob`, `bookingHoldExpiryTask` |
| 마이그레이션 | 없음 (`booking-jdbc` 가 가진다) |
| 프론트 짝 | 없음 |
| 테스트 | `modules/booking/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
