# point

포인트 **원장**: 적립(유효기간 선택) · 소멸이 가까운 묶음부터 차감 · 하루 한 번 소멸 · 운영자 조정. 보상 지급기(`RewardIssuer`, 종류 `point`)를 내서 이벤트(`campaign`)·`reward` 가 「포인트 n점」을 줄 수 있고, 단독으로도 돈다(`reward` 를 몰라도 된다).
쓰는 쪽(내부 서비스)은 서버 API `PointWallet` 하나만 안다 — 적립 `earn(accountId, amount, expiresAt, sourceType, sourceRef)` · 차감 `spend(accountId, amount, sourceType, sourceRef)` · 조정 `adjust` · 잔액 `balance`. **포인트 사용에는 HTTP 가 없다.** 둘 다 `(sourceType, sourceRef)` 로 멱등이다: 같은 지급 건(`GRANT`, 지급 건 id)·같은 주문을 두 번 처리해도 한 번만 반영되고(`earn` 은 null, `spend` 는 처음 줄을 돌려준다), 잔액보다 큰 차감은 409 `POINT.INSUFFICIENT` 로 거절되며 아무것도 바뀌지 않는다(호출자 트랜잭션도 롤백 전용이 되지 않는다). 유효기간 기본값은 없다 — 보상 설정(`expiresInDays`) · 운영자 조정에서 고른다.
저장소(`PointStore`)는 이 모듈에 없다 — `point-jdbc` 를 붙이면 지갑 · 지급기 · 소멸 작업 · 계정 삭제 고리가 선다(저장소가 없으면 권한 선언 말고는 빈이 늘지 않는다). 소멸 작업은 `scheduler` 모듈이 있을 때 매일 03:30 에 돈다(없으면 앱이 `PointWallet.expireDue` 를 부른다) — 늦어도 안전하다: 유효기간이 지난 묶음은 잔액에서 이미 빠지고 쓸 수도 없다.

| 메서드 · 경로 | 하는 일 | 필요한 권한 |
|---|---|---|
| `GET /api/v1/points` | 내 잔액과 30일 안에 사라질 양(`expiringSoon`: `{amount, before}`, 없으면 null) | 로그인 |
| `GET /api/v1/points/history?cursor&size` | 내 내역, 최신순 커서 쪽매김 (`delta` · `kind` EARN/SPEND/EXPIRE/ADJUST · `reason` · `expiresAt` · `createdAt`) | 로그인 |
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
- 켜기: `skeleton.point.admin.enabled=true`, 운영자에게 `point.manage` 를 역할로 준다(`skeleton.authz.roles.<역할>: [point.manage]`).
- 차감 순서(국룰): 소멸이 가까운 묶음부터, 유효기간 없는 묶음은 맨 뒤. 소멸은 남은 양만 `EXPIRE` 줄로 남긴다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
