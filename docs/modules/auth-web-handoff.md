# auth-web-handoff

**앱에서 웹으로 넘어갈 때 쓰는 한 번 쓰는 로그인 코드.** 로그인한 앱이 코드를 받아 주소의 `#` 조각에 실어 바깥 브라우저로 열면, 웹이 코드를 한 번 바꿔 로그인 세션을 받는다. 메일 링크 로그인(`auth-magic-link`)과 **같은 토큰 장치**(`OneTimeTokens`: 256비트 난수 · 해시만 저장 · 원자적 한 번 · 만료 · 용도 구분)를 쓰고, 세션은 다른 로그인과 같은 길(`AuthTokenResponseFactory` — 막힌 계정 · 2단계 확인 · 세션 열기)로 열린다. 스키마 · `account` 변경 없음.

**기본 꺼짐**(`skeleton.auth-web-handoff.enabled=false`) — 앱 yml 이 켠다. 코드를 내는 쪽은 `WebHandoffIssuer`(`platform`)를 아는 모듈이다 — 지금은 `payment-iap` 의 `POST /api/v1/payments/iap/web-checkout-link`.

| 메서드 · 경로 | 하는 일 | 누가 |
|---|---|---|
| `POST /api/v1/auth/web-handoff/redeem` `{ code }` | 코드 → 토큰 응답 (쓸 수 없는 코드는 `410 ACCOUNT.TOKEN_INVALID`) | 공개 |

**새어 나가도 피해가 작게**: 수명은 기본 2분 · 한 번만 · 같은 계정에 새 코드를 내면 열린 코드가 닫힘. 코드는 **지금 열려 있는 세션에 묶인다** — 낼 때 호출한 액세스 토큰의 `sid` 가 열린 세션인지(`SessionInspector.openedAt`) 보고(닫힌 세션 · `sid` 없는 토큰은 401), 그 `sid` 를 코드에 담아 바꿀 때 다시 본다. 그래서 훔친 15분짜리 액세스 토큰도 그 세션이 닫히면(개별 로그아웃 · 모든 기기 로그아웃 · 비밀번호 재설정 · 정지 · 탈퇴) 새 세션을 열지 못하고, 이미 받아 둔 코드도 바꾸는 순간 410 으로 거절된다. 계정의 세션을 한꺼번에 닫는 사건(`SESSIONS_REVOKED`)이 오면 남은 코드를 바로 닫기도 한다. 세션 장치(`auth-session`)가 없으면 앱이 뜨지 않는다. 정지 · 탈퇴 대기 계정은 코드가 있어도 일반 로그인과 같이 거절됨. 저장은 해시만이고 코드는 로그 · 예외 메시지에 싣지 않는다(요청 본문은 `toString` 이 가려 둔다). 코드는 **주소의 `#` 조각**에 실어 줘야 한다 — 조각은 서버 · 프록시 접근 로그와 Referer 에 가지 않는다. 웹은 읽자마자 `history.replaceState` 로 주소에서 지운다.

**웹 쪽 규칙(react `WebHandoffGate`)**: 이미 다른 계정으로 로그인한 브라우저에서는 묻지 않고 계정을 바꾸지 않는다 — 「나가고 앱의 계정으로 계속할까요?」를 확인한 뒤에만 로그아웃하고 코드를 바꾼다(남이 만든 코드 링크로 내 브라우저가 그 사람 계정에 로그인되는 일을 막는다). 코드는 조각에서만 읽고 라우터 위치에서 바로 지운다.

조심: 코드는 「앱이 이미 로그인했다」는 증명이라 2단계 확인이 켜진 계정은 웹에서 2단계를 한 번 더 거친다(`AuthTokenResponseFactory` 그대로). 단일 인스턴스 메모리 토큰 저장소(`account-jdbc` 없이)는 재시작하면 열린 코드가 사라진다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-web-handoff"))` |
| 함께 오는 모듈 | `account`, `auth`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.auth-web-handoff` — [docs/config/modules/auth-web-handoff.yml](../config/modules/auth-web-handoff.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 면 `account` 위에 redeem 경로 하나와 세션 사건 듣는 쪽이 선다. |
| 부팅에 필요한 것 | `account` 의 부팅 조건 + `SessionInspector` 빈 — 세션 장치 `auth-session` 을 같이 얹어야 한다(따라오지 않는다). 없으면 앱이 뜨지 않는다 |
| 교체 지점 | `WebHandoff`, `WebHandoffController`, `webHandoffSessionEventListener` |
| 마이그레이션 | 없음 (`account_tokens` 를 쓴다 — 용도 `web_handoff`) |
| 프론트 짝 | `@skeleton/auth` (웹 결제 화면이 주소의 #handoff 조각을 읽어 redeem 을 부른다 - WebHandoffGate) |
| 테스트 | `modules/auth-web-handoff/src/test` |

## 변이 확인 기록

검사를 하나씩 빼 보고 시험이 빨개지는지 손으로 확인했다(2026-11-04).

| 뺀 검사 | 빨개진 시험 |
|---|---|
| 세션을 한꺼번에 닫을 때 열린 코드 닫기(`closeAll`) | `closing every session of the account … closes the open code` |
| 새 코드를 내면 열린 코드 닫기(계정 단위 주인) | `issuing a new code for the account closes the one still open` (+ 위 시험) |
| 코드 수명(`ttl`) | `an expired code is dead` |

한 번만 쓰기 · 해시 저장 · 로그 무노출은 이 모듈의 시험(`WebHandoffWebTest`)이 보지만 변이로는 확인하지 않았다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
