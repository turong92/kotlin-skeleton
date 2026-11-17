# ads-web-rewarded

웹의 **보상형 광고(Google Ad Manager GPT)** 를 서버가 **광고 세션**으로 받아, 남용 한도 안에서 한 번만 보상한다. 웹에는 서버가 시청을 증명할 서명 콜백이 없어서(앱의 `ads-admob` 은 구글이 서명한 SSV 를 받는다) 보상의 근거가 브라우저의 `rewardedSlotGranted` 신호뿐이다 — 위조를 다 막을 수는 없으니 1회용 세션 · 최소 경과 시간 · 계정/IP 하루 상한으로 남용을 막는다. 앱마다 다른 것은 「보상을 받으면 무엇을 하나」 하나뿐이다: 포인트를 쌓거나(`reward-type: point`), 1회용 토큰을 받아 앱이 자기 경로에서 쓴다.

로그인한 호출자만(익명 401). 응답 봉투는 레포 표준(`{ value, meta }`).

- `POST /api/v1/ads/web-rewarded/sessions` `{ "purpose": "unlock-episode" }` — 광고를 띄우기 **전에** 부른다. 200 `{ sessionId("wrs_…"), purpose, expiresAt }`. 오늘 상한이 이미 찼으면 409 `ADS_WEB_REWARDED.DAILY_LIMIT`(광고를 띄우지 않는다). `purpose` 는 `^[a-z0-9][a-z0-9._-]{0,63}$`, 아니면 400.
- `POST /api/v1/ads/web-rewarded/sessions/{sessionId}/grant` — 브라우저에서 granted 가 온 뒤 부른다. 200 `{ sessionId, purpose, grantedAt, reward: {type, ref}|null, token|null, tokenExpiresAt|null }`. `reward-type` 을 정했으면 `reward` 만, 아니면 `token`(1회용 — 원문은 이 응답 한 번뿐, 저장은 SHA-256 해시) 과 `tokenExpiresAt` 만 차 있다. 에러: 404 `SESSION_NOT_FOUND`(없음 · 남의 세션) · 409 `SESSION_USED` · 409 `SESSION_EXPIRED` · 409 `TOO_EARLY`(세션은 열린 채) · 409 `DAILY_LIMIT` · 503 `REWARD_UNAVAILABLE`(지급기 · DB 오류 — 전부 되돌아가 세션은 열린 채, 카운터는 그대로, 다시 부르면 된다).

보상 요청은 한 트랜잭션 안에서 세션을 주인 조건으로 잠그고 → 이미 보상했는지 → 만료 → 최소 경과(`min-elapsed`) → 상한(원자적으로 「max 미만일 때만 +1」) → 세션을 보상됨으로 → 지급기(또는 토큰) 순서로 본다. 같은 세션의 동시 요청은 한 번만 지급되고, 상한 직전의 동시 요청은 하나만 통과한다. **상한 단위는 보상 횟수**(포인트 합이 아니다)이고 「하루」는 `day-zone` 의 자정이다(`platform` 의 `DayWindow`). 앱(AdMob)의 상한과 합산하지 않는다 — 따로 센다.

보상 처리는 platform 의 `RewardIssuer` 계약을 그대로 쓴다(`point` 가 이미 `type = "point"` 를 낸다). `reward-type` 을 정했는데 그 종류의 `RewardIssuer` 빈이 없거나 `reward-params` 를 지급기가 거절하면 **기동이 실패한다**. 토큰 모드의 토큰은 앱이 자기 엔드포인트에서 `WebRewardTokens.redeem(token, accountId, purpose)` 로 쓴다(HTTP 경로는 없다) — 없음 · 남의 것 · 다른 목적 · 이미 씀 · 만료면 null, 같은 토큰은 한 번만 성공한다. `grant` · `redeem` 은 호출자 트랜잭션 밖에서 부른다(안에서 부르면 세션 잠금 · 상한 되돌림이 호출자 트랜잭션에 섞인다). 계정이 지워지면 그 계정의 세션 줄과 계정 카운터를 지운다 — 이 고리는 저장소가 있으면 모듈이 꺼져 있어도 선다.

**보관 기한(선택)**: 세션 · 하루 카운터 줄은 쌓이기만 하므로 `retention`(기본 없음 = 지우지 않는다, 2일 이상)을 정하면 매일 그보다 오래된 줄을 멱등하게 지운다(여러 인스턴스가 겹쳐도 안전; `scheduler` 모듈이나 요청 구동 모드가 없으면 기동 실패). 아직 쓸 수 있는 토큰이 있는 세션은 남긴다. 저장소가 있으면 모듈이 꺼져 있어도 돈다.

**IP 상한은 `skeleton.web.client-ip.mode` 를 정해야 믿을 수 있다** — 정하지 않으면 전달 헤더를 호출자가 고를 수 있다. 정책 체크(구글의 보상 광고 정책 · 동의 창)와 브라우저 쪽 흐름은 react-skeleton 의 `packages/ads/README.md` 에 있다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:ads-web-rewarded"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | `scheduler` (보관 기한 지우기의 표시만 — 없으면 요청 구동 모드가 부른다. Spring Security 는 이 모듈의 호출자라 필수 의존이다) |
| 설정 접두사 | `skeleton.ads-web-rewarded` — [docs/config/modules/ads-web-rewarded.yml](../config/modules/ads-web-rewarded.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 이고 저장소(`ads-web-rewarded-jdbc`)가 있어야 일한다. 상한 없음, 보상 종류 없음(= 토큰 모드). |
| 부팅에 필요한 것 | `reward-type` 을 정했으면 그 종류의 `RewardIssuer` 빈(포인트면 `point` + `point-jdbc`). |
| 교체 지점 | `WebRewardStore`, `RewardIssuer`, `WebRewardService`, `WebRewardedController`, `webRewardErasure` |
| 마이그레이션 | 없음 (저장소 모듈 `ads-web-rewarded-jdbc` 가 가진다) |
| 프론트 짝 | `@skeleton/ads` |
| 테스트 | `modules/ads-web-rewarded/src/test` (메모리 저장소 — 1회용 · 최소 경과 · 만료 · 상한 · 토큰 · 기동 가드; 동시성은 `-jdbc` 의 dbTest) |

## 앱이 바꾸는 곳

- 켜기(포인트 적립): `skeleton.ads-web-rewarded.enabled=true` + `reward-type: point` + `reward-params: { amount: "1" }`. 환경변수로는 `<P>_ADS_WEB_REWARDED_ENABLED=true` 등.
- 토큰 모드(예: 1회 사용권): `reward-type` 을 비우고, 앱 엔드포인트에서 `WebRewardTokens.redeem(token, 로그인 계정, purpose)` 가 세션을 돌려주면 사용권을 준다.
- 상한: `account-daily-limit` · `ip-daily-limit`(기본 없음), 시간대 `day-zone`. 최소 시청 시간 `min-elapsed` 는 구글 기준 7초 — 영상 광고면 광고 길이에 맞춰 올린다.
- 다른 DB 는 `WebRewardStore` 를 구현해 빈으로 둔다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
