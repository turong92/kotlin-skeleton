# waiting-room

대기열(가상 대기실) — 몰리는 순간(티켓 오픈 · 선착순 신청)의 입장을 **줄 세워 분당 N명씩** 들여보낸다. **로그인한 사람만** 줄을 선다: 1인 1순번 = 계정 1개 1순번이다(익명 · 캡차 모드와 익명 확장점은 없다 — 계정 없이 쓰는 앱이 대기열을 원하면 그 앱이 자체로 만든다).
저장은 포트(`WaitingRoomStore`)이고 DB 는 `waiting-room-jdbc` 가 붙인다 — 저장소가 없으면 서비스도 서지 않는다. 예약(`booking`)을 몰라도 쓴다: 어느 쓰기 경로 앞에든 `WaitingRooms.requireEntry` 한 줄로 문을 건다.

**줄이 서는 법**: 대기실 = 범위 키(`scopeKey`, 앱이 정하는 이름) · 오픈 시각 · 분당 입장 수(**만들 때 필수, 기본값 없음** — 서버 크기 · 행사 성격을 모듈이 모른다) · 오픈 전 줄이 시작하는 때(오픈 30분 전) · 입장 시간(10분).
- **오픈 전 줄**(시작 ~ 오픈): 순번표를 받아도 번호가 없다 — 일찍 온다고 유리하지 않다. **오픈 시각 뒤 첫 요청**이 오픈 전 줄을 무작위로 섞어 1..n 번호를 매긴다(방마다 단 한 번 — 동시에 몰려도 한 번, 타이머가 없어 인스턴스 0대에서도 돈다). 오픈 뒤 온 사람은 온 순서로 맨 뒤 번호.
- **같은 계정은 같은 순번표**: 몇 번을 요청해도(동시 포함) 방마다 하나. 입장 시간이 지난 번호로 다시 요청하면 그때만 맨 뒤 번호를 새로 받는다.
- **입장 번호(서빙)** = 오픈 시각부터 속도 구간을 적분한 값(내림). 분당 100 이면 오픈 60초 뒤 정확히 100번까지 입장한다. 시계와 DB 의 구간 표만으로 정해지므로 모든 인스턴스가 같은 값을 계산한다(NTP 전제). 속도는 운영 경로로 바꾼다(0 = 멈춤, 멈춘 동안은 늘지 않는다).
- **입장**: 내 번호 <= 서빙이면 입장, 입장 시각부터 입장 시간 동안 입장권(`entryToken`)을 받는다. 지나면 `EXPIRED` → 다시 서면 맨 뒤.
- **토큰**(HMAC-SHA256, 키 id 포함): 순번표 · 입장권 모두 계정에 묶인다. 입장권은 방 · 계정 · 만료만 싣는다. **위조 · 변조 · 만료 · 다른 방 · 다른 계정의 토큰은 거절**하고 순번표를 입장권 자리에 쓸 수도 없다(종류도 서명 안에 있다).

**서버 API**: `WaitingRooms` — `create(NewWaitingRoom)` · `get` · `findByScope` · `setRate` · `close` · `stats` · `issueTicket(roomId, accountId)` · `status(roomId, accountId, ticket)` · **`requireEntry(roomId, accountId, entryToken)`**(아니면 `403 WAITING_ROOM.ENTRY_REQUIRED` — 토큰만 보며 DB 를 읽지 않는다). 예약 풀의 `waitingRoomId` 는 이 대기실의 `id` 다(`BookingAdmission` 대기열 구현이 `requireEntry` 를 부른다).
닫은 방은 새 순번표를 주지 않고 묻기에 `CLOSED` 로 답한다 — 이미 낸 입장권은 자기 만료까지 통한다.

**사용자 경로**(서블릿 웹 앱 + Spring Security 가 있을 때, 기본 켜짐 `skeleton.waiting-room.http.enabled`; 모두 로그인한 사람만 — 공개 경로 없음). 응답은 `{value}` 봉투, `Cache-Control: no-store`:

| 메서드 · 경로 | 하는 일 |
|---|---|
| `POST /api/v1/waiting-rooms/{id}/tickets` | 순번표 받기 → 아래 모양의 답. 오픈 전 줄 시작 전이면 `409 WAITING_ROOM.NOT_OPEN`(`data.opensAt`, `data.prequeueStartsAt`) |
| `GET /api/v1/waiting-rooms/{id}/status` (헤더 `X-Ticket`) | 내 자리 묻기 → 같은 모양의 답. 순번표 토큰에 번호가 있으면 DB 를 읽지 않는다 |

답: `{state: PREQUEUE|WAITING|ADMITTED|EXPIRED|CLOSED, position?, ahead?, estimatedWaitSeconds?, opensAt, pollAfterSeconds, entryToken?, ticket?}`.
`ahead` = 내 앞에서 아직 입장 못 한 사람 수 · `estimatedWaitSeconds` = 지금 속도로 입장까지(멈춤이면 없음) · `pollAfterSeconds` = 다시 물을 간격(앞 사람이 많을수록 길게 5 ~ 30초, 흔들기 포함) · `entryToken` 은 `ADMITTED` 일 때만 · `ticket` 은 순번표 토큰이 새로 만들어졌을 때만(받을 때, 오픈 뒤 번호가 처음 알려졌을 때) — **있으면 저장해 둔 것을 이것으로 바꾼다**(새로고침해도 지워지지 않게 방별로 보관 — 지워져도 다시 받으면 같은 순번표).
입장권은 예약 같은 쪽 요청의 `X-Entry-Token` 헤더에 싣는다. 오류: `403 WAITING_ROOM.TICKET_INVALID`(없는 · 위조 · 남의 순번표) · `409 WAITING_ROOM.CLOSED` · `404 WAITING_ROOM.ROOM_NOT_FOUND` · 익명 `401`.

**운영 경로**(`skeleton.waiting-room.admin.enabled=true` 일 때만, 꺼져 있으면 404 · 권한 `waiting-room.manage` — 기본 역할 없이 전권만): `POST /api/v1/admin/waiting-rooms {scopeKey, opensAt, perMinute, prequeueMinutes?, entryMinutes?}` · `POST …/{id}/rate {perMinute}` · `POST …/{id}/close` · `GET …/{id}/stats` → `{prequeue, issued, serving, perMinute, estimatedDrainSeconds}`. 같은 `scopeKey` 는 `409 WAITING_ROOM.ROOM_EXISTS`.

**서명 키**: `skeleton.waiting-room.token.key`(환경변수 `<P>_WAITING_ROOM_TOKEN_KEY`) 하나. **보호 환경(stage · prod)에서 키가 없거나 약하면(24자 미만 · 서로 다른 글자 8개 미만) 기동 실패**, 그 밖에서는 경고다(깨우기 비밀과 같은 기준). 키가 없으면 시작 때 만든 무작위 키로 서명하므로 재시작과 다른 인스턴스에서는 토큰이 통하지 않는다. 토큰에는 키 id(비밀의 지문)가 들어 있다.
키 돌리기: 새 키를 `key` 로, 옛 키를 `previous-key`(`<P>_WAITING_ROOM_TOKEN_PREVIOUS_KEY`, 검증에만 쓴다)로 옮겨 배포 → 입장 시간이 지나 옛 입장권이 다 끝나면 `previous-key` 를 뺀다. 뺀 뒤 옛 키로 서명된 순번표는 `403 TICKET_INVALID` 가 되는데, 화면이 순번표를 다시 받으면(`POST …/tickets`) 같은 순번표(같은 번호)가 새 키로 서명되어 돌아온다.

**0대(scale-to-zero)**: 쓰기는 사람당 한 번(순번표), 묻기는 DB 없이 계산 → Redis 없이 DB 하나로 충분하고 타이머가 필요 없다. 콜드스타트는 오픈 전 줄이 받아 준다 — [오픈 전 줄 시작 시각에 미리 깨우기](../cloud-run-scale-to-zero.md#대기열-오픈-전-줄-시작-시각에-미리-깨우기).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:waiting-room"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.waiting-room` — [docs/config/modules/waiting-room.yml](../config/modules/waiting-room.yml) |
| 기본 동작 | 조건부 — 저장소(`waiting-room-jdbc`)가 있으면 서비스가 서고, 서블릿 앱이면 사용자 경로도 켜진다. 운영 경로는 꺼짐. 대기실을 만들기 전에는 아무 일도 없다. |
| 부팅에 필요한 것 | `TimeProvider` 빈(platform)과 `WaitingRoomStore` — `waiting-room-jdbc` 가 낸다(없으면 서비스가 서지 않는다). 보호 환경에서는 서명 키. |
| 교체 지점 | `WaitingRoomStore`, `WaitingRoomTokens`, `WaitingRooms`, `WaitingRoomController`, `WaitingRoomAdminController` |
| 마이그레이션 | 없음 (`waiting-room-jdbc` 가 가진다) |
| 프론트 짝 | 없음 |
| 테스트 | `modules/waiting-room/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
