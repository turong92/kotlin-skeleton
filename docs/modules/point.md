# point

포인트 **원장**: 적립(유효기간 선택) · 소멸이 가까운 묶음부터 차감 · 하루 한 번 소멸 · 운영자 조정. 보상 지급기(`RewardIssuer`, 종류 `point`)를 내서 이벤트(`campaign`)·`reward` 가 「포인트 n점」을 줄 수 있고, 단독으로도 돈다(`reward` 를 몰라도 된다).
쓰는 쪽(내부 서비스)은 서버 API `PointWallet` 하나만 안다 — 적립 `earn(accountId, amount, expiresAt, sourceType, sourceRef)` · 차감 `spend(accountId, amount, sourceType, sourceRef)` · 조정 `adjust` · 사용 취소(환원) `cancelSpend(accountId, spendSourceType, spendSourceRef, amount?, sourceType, sourceRef)` · 잔액 `balance`. **포인트 사용에는 HTTP 가 없다.** 둘 다 `(sourceType, sourceRef)` 로 멱등이다: 같은 지급 건(`GRANT`, 지급 건 id)·같은 주문을 두 번 처리해도 한 번만 반영되고(`earn` 은 null, `spend` 는 처음 줄을 돌려준다), **같은 키에 다른 요청**(적립 ↔ 차감, 다른 금액; 유효기간 차이는 보지 않는다)은 409 `POINT.SOURCE_CONFLICT` 로 거절되고(첫 줄을 돌려주면 호출자가 이번 요청이 반영됐다고 믿는다) 아무것도 바뀌지 않으며(직렬로 들어온 요청만 트랜잭션 밖에서 409 · 첫 줄로 답한다 - 동시 요청이나 옛 스냅샷 경로는 예외로 롤백되니 다시 부르면 409 · 첫 줄을 받는다), 잔액보다 큰 차감은 409 `POINT.INSUFFICIENT` 로 거절되며 아무것도 바뀌지 않는다(호출자 트랜잭션도 롤백 전용이 되지 않는다). 유효기간 기본값은 없다 — 보상 설정(`expiresInDays`) · 운영자 조정에서 고른다.
저장소(`PointStore`)는 이 모듈에 없다 — `point-jdbc` 를 붙이면 지갑 · 지급기 · 소멸 작업 · 계정 삭제 고리가 선다(저장소가 없으면 권한 선언 말고는 빈이 늘지 않는다). 소멸 작업은 `scheduler` 모듈이 있을 때 매일 03:30 에 돈다(없으면 앱이 `PointWallet.expireDue` 를 부른다) — 늦어도 안전하다: 유효기간이 지난 묶음은 잔액에서 이미 빠지고 쓸 수도 없다.

| 메서드 · 경로 | 하는 일 | 필요한 권한 |
|---|---|---|
| `GET /api/v1/points` | 내 잔액과 30일 안에 사라질 양(`expiringSoon`: `{amount, before}`, 없으면 null) | 로그인 |
| `GET /api/v1/points/history?cursor&size` | 내 내역, 최신순 커서 쪽매김 (`delta` · `kind` EARN/SPEND/EXPIRE/ADJUST/RESTORE · `reason` · `expiresAt` · `createdAt`) | 로그인 |
| `GET /api/v1/admin/points/{accountId}` | 한 계정의 잔액과 최근 내역 50줄 `{balance, entries[]}` | `point.manage` |
| `POST /api/v1/admin/points/{accountId}/adjustments` | `{delta, reason, expiresInDays?}` — 양수는 적립, 음수는 차감. 201 내역 줄 · 409 `POINT.INSUFFICIENT`. 운영 기록 `point.adjusted`(대상은 내역 줄 id 만) | `point.manage` |

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:point"))` (저장소는 `point-jdbc`) |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | `scheduler` (소멸 작업의 표시. Spring Security 도 컴파일 전용 — 없으면 HTTP 를 등록하지 않는다) |
| 설정 접두사 | `skeleton.point` — [docs/config/modules/point.yml](../config/modules/point.yml) |
| 기본 동작 | 조건부. 사용자 경로는 저장소가 있고 서블릿 웹 앱 + Spring Security 가 있으면 켜진다(`http.enabled=false` 로 끈다). 운영 경로는 꺼짐(`admin.enabled=false` → 404). 권한 선언 `point.manage` 는 모듈이 클래스패스에 있으면 늘 낸다(기본으로 받는 역할 없음 — 전권만) |
| 부팅에 필요한 것 | `PointStore` 빈(`point-jdbc`). 없어도 기동은 된다 |
| 교체 지점 | `PointStore`, `PointWallet`, `PointController`, `PointAdminController`, 지급기 빈 `pointRewardIssuer`, 계정 삭제 고리 `pointErasure` |
| 마이그레이션 | 없음 (`point-jdbc` 에 있다) |
| 프론트 짝 | `@skeleton/rewards` |
| 테스트 | `modules/point/src/test` (운영 권한 · 스위치 · 감사 기록 · 지급기 설정 검증) |

## 앱이 바꾸는 곳

- 보상으로 주기: 이벤트 설정의 보상 한 줄 `{type: "point", params: {amount: "100", expiresInDays: "365"}}` (`expiresInDays` 는 생략하면 안 사라진다). 설정을 저장할 때 `amount`(1 이상 정수) · `expiresInDays`(1~36500)를 `RewardIssuer.validate` 가 본다.
- 쓰기: `PointWallet.spend(accountId, amount, "ORDER", orderId)` — 같은 주문 id 로 다시 불러도 한 번만 빠진다. 모자라면 `PointInsufficientException`(409 `POINT.INSUFFICIENT`)을 던지며, 호출자 트랜잭션 안에서 잡고 계속해도 된다.
- 사용 취소(환원): `PointWallet.cancelSpend(accountId, "ORDER", orderId, amount, "ORDER_CANCEL", cancelKey)` — `amount` 가 null 이면 아직 안 되돌린 전부, 부분 취소를 여러 번 해도 된다. 포인트는 그 차감이 뺀 **원래 묶음에 원래 유효기간으로** 돌아가고(유효기간이 늦은 묶음부터), 그 사이 유효기간이 지난 묶음 몫은 바로 `EXPIRE` 줄로 닫는다 — 사용자 내역에는 `RESTORE`(+) 줄과 `EXPIRE`(−) 줄이 남는다. 자기 `(sourceType, sourceRef)` 로 멱등이고, 같은 키에 다른 요청(다른 차감 · 다른 금액)은 409 `POINT.SOURCE_CONFLICT`. 원 차감이 없으면(적립 키 · 모르는 키 · `point_spend_allocations` 가 생기기 전의 옛 차감) 404 `POINT.SPEND_NOT_FOUND`, 취소 합계가 원 차감을 넘으면 409 `POINT.CANCEL_EXCEEDED` — 모두 아무것도 바뀌지 않고 트랜잭션 밖에서 던진다(호출자 트랜잭션이 롤백 전용이 되지 않는다). 운영자 조정으로 뺀 `ADJUST` 줄은 되돌릴 수 없다. `amount` 를 null 로 다시 부르는 것은 금액을 대조하지 않는다(같은 차감을 가리키면 처음 줄을 돌려준다) - 취소 키마다 한 가지 뜻으로만 쓴다(명시 금액이면 늘 명시 금액, 전부면 늘 null). 오류는 `earn` · `spend` 와 같은 단서를 따른다: 직렬로 들어온 요청만 트랜잭션 밖에서 던지고, 동시 요청 · 옛 스냅샷 경로 · 다른 계정의 차감(`IllegalStateException`)은 예외로 롤백되니 다시 부르면 된다.
- **작업 기록(`PointOperations`, 선택)**: 지갑 위의 층이다 — `spend/earn/cancelSpend(actor, accountId, …, sourceType, sourceRef)` 가 `PointOperationResult{operation, entry?}` 를 돌려주고, 작업(= 키 하나)마다 **접수 영수증 · 시도 · 결과 전이**를 `point_operations` · `point_operation_attempts` 에 남긴다(행위자 · 종류 · 금액 · 접수 · 시도 · 완료 시각 · `OK`/`POINT.*` 결과 코드 · 원 작업 연결; 토큰 · 결제 키 · 본문 · 사유 칸은 없다). 상태: `ACCEPTED → PROCESSING → SUCCEEDED | FAILED(확정) | UNKNOWN(불명) → ABANDONED`, 보상은 원 작업의 `compensation`(NONE/REQUESTED/DONE/FAILED). 확정 실패(`INSUFFICIENT` · `CANCEL_EXCEEDED` …)는 던지지 않고 `FAILED` 로 돌려주며, 같은 키에 다른 요청은 409 `POINT.SOURCE_CONFLICT` 이고 기록은 그대로다. **경계**: **활성 트랜잭션 안에서 부르면 `IllegalStateException` 으로 거절한다**(합류하면 잠근 줄 때문에 자기 교착, 커넥션 둘, 낡은 스냅샷이 따라온다) — 다른 쓰기와 한 트랜잭션으로 묶어야 하면 `PointWallet` 을 쓴다(기록 없이). 영수증과 시도는 각자 짧은 트랜잭션으로 부수효과 전에 커밋한다 — 원장 트랜잭션이 실패해도 영수증과 실패 시도는 남고, 영수증을 못 쓰면 원장에 닿기 전에 던진다. 성공 표시(취소면 원 작업의 보상 완료 `DONE` 까지)는 원장 쓰기와 **같은 트랜잭션**이다 — 그 트랜잭션이 롤백되면 성공으로 남지 않는다(작업은 불명으로 남아 조회가 원장으로 가른다). `actor` 는 호출한 **서비스 이름**이다(64자까지, 개인 식별값을 넣지 않는다). 응답을 못 받은 예외(타임아웃 · 커밋 전 끊김)는 확정 실패가 아니라 `UNKNOWN`. **불명 건 조회**: 영수증과 같은 트랜잭션에서 `job-queue-jdbc` 작업(`point.op.resolve`)이 예약돼(`skeleton.point.operations.reconcile.first-delay` 기본 60s, `max-attempts` 기본 12) 원장(`entryOf`)으로 가른다 — 줄이 요청과 맞게 있으면 `SUCCEEDED`, 시도를 다 쓰면 `ABANDONED`, **포기한 뒤에 줄이 나타나도 `resolve` 를 부르면 성공으로 바로잡는다**(조회 작업은 포기하면 끝나므로 작업 id 를 아는 쪽이 부른다). 같은 키 재시도(영수증을 이번에 만들지 않은 호출)가 시도를 열면 조회 작업을 다시 건다 — 영수증만 쓰고 죽은 작업도 이 길로 되살아난다. 조회 처리기는 트랜잭션 밖에서 돈다(잡 처리를 트랜잭션으로 감싸지 않는다). 큐가 없으면 앱이 `resolve(operationId)` 를 부른다. 보관 기간 기본값은 없고(앱이 정한다) 계정을 지우면 `pointErasure` 가 원장 줄과 함께 지운다. 쓰기를 이 층으로 강제하지 않는다 — 지갑을 직접 불러도 되고, 이 층은 권장이다.
- **앱의 보상 흐름(차감 뒤 작업이 최종 실패할 때)**: ① 작업 키(예: 주문 id)로 `operations.spend` — 같은 키라 다시 불러도 한 번만 빠지고 시도만 늘어난다. ② 작업이 확정되면 끝. ③ 작업이 **최종** 실패(재시도를 다 썼거나 취소됨)하면 그 키에서 이은 취소 키(예: `"$orderId:cancel"`)로 `operations.cancelSpend` — 원 작업과 연결되고(`originOperationId`), **원 차감이 원장에 있다고 확인된 건에만** 보상한다: 원 작업이 성공이 아니면 먼저 `resolve` 로 원장을 보고, 그래도 줄이 없으면(`UNKNOWN`/`ABANDONED`) 취소는 `POINT.ORIGIN_UNRESOLVED` 의 `UNKNOWN` 으로 기다리며 원장에 아무것도 쓰지 않는다(원 작업이 확정 실패면 되돌릴 것이 없어 `FAILED`). ④ 응답을 모르면(타임아웃 · 재시작 · 예외) **같은 키로 다시 부른다** — 멱등이라 두 번 차감 · 환원되지 않는다. ⑤ 대기 중인 작업은 `job-queue-jdbc`([job-queue-jdbc.md](job-queue-jdbc.md))의 잡으로 두고, 잡이 최종 실패(DEAD)하면 `JobDeadListener.onDead` 가 불린다 — 거기서 `cancelSpend` 를 **직접 부르지 말고** 취소 키를 담은 환원 잡을 새로 넣는다(듣는 쪽이 던지면 워커가 로그만 남기고 끝난다; 새 잡은 재시도되고 취소 키가 같아 두 번 돌려주지 않는다). 지갑(`PointWallet`)만 쓰는 앱도 같은 순서를 쓴다 — 기록과 `UNKNOWN` 확인만 빠진다.
- 켜기: `skeleton.point.admin.enabled=true`, 운영자에게 `point.manage` 를 역할로 준다(`skeleton.authz.roles.<역할>: [point.manage]`).
- 차감 순서(국룰): 소멸이 가까운 묶음부터, 유효기간 없는 묶음은 맨 뒤. 소멸은 남은 양만 `EXPIRE` 줄로 남긴다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
