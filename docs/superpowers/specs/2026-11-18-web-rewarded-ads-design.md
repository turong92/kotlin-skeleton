# 웹 보상형 광고 — `ads-web-rewarded` (+ `-jdbc`) · `@skeleton/ads` 설계

## 왜

앱의 보상형 광고는 구글이 서명한 콜백(SSV → `ads-admob`)으로 보상한다. 웹(Google Ad Manager GPT 보상형)은 1차에 쓸 수 있는 서명 콜백이 없어서 보상의 근거가 브라우저의 `rewardedSlotGranted` 이벤트뿐이다. 그래서 서버가 이 신호를 **광고 세션**으로 받는다. 위조를 다 막을 수는 없으니 남용은 1회용 세션, 최소 경과 시간, 계정·IP 하루 상한으로 막는다. 앱마다 다른 것은 「보상을 받으면 무엇을 하나」 하나뿐이다. 한쪽은 포인트를 쌓고, 다른 쪽은 1회 사용권을 준다.

## 1차 범위 (처음부터 끝까지 도는 최소판)

- kotlin `modules/ads-web-rewarded`: 세션 열기와 보상 요청 두 엔드포인트, 1회용 토큰, 한도, 보상 처리 확장 지점. `modules/ads-web-rewarded-jdbc`: 저장소(PostgreSQL · MySQL).
- `platform` 에 「하루」 계산 `DayWindow` 를 두고, `ads-admob` 의 상한 계산도 이것을 쓴다.
- react `packages/ads`(`@skeleton/ads`): GPT 로더, 가짜 googletag, 흐름 컨트롤러, 훅, 동의 창 컴포넌트, README 의 정책 체크.

## 비목표

- 서버가 광고 시청을 증명하는 것(웹 SSV). 아래 「접근」 4 참조.
- 배너·전면 광고, 광고 네트워크를 갈아 끼우는 추상화(GPT 하나만 둔다), 실계정·심사, 동의 관리(CMP/TCF) 배너.
- 앱(AdMob)과 웹의 상한을 합산하는 것(「정해야 할 것」).
- 지난 세션 줄을 정리하는 작업. 보상 응답을 잃었을 때 되살리는 것(같은 세션의 두 번째 보상 요청은 409).

## 접근 (고른 것과 버린 것)

1. **보상 처리 확장 지점**: platform 의 기존 계약 `RewardIssuer` 를 그대로 쓴다(채택). point 는 이미 `PointRewardIssuer`(종류 `point`)를 내므로 point 쪽 코드는 새로 쓰지 않고, 1회 사용권도 같은 계약의 구현 하나가 된다. 새 `WebRewardHandler` 에 point 선택 의존(`compileOnly` + `noPointTest`)을 붙이는 안은 버렸다. 같은 일을 하는 계약이 둘이 되기 때문이다.
2. **상한 계산을 둘 자리**: `platform` `common/time/DayWindow`(채택). 두 광고 모듈이 이미 platform 에 의존하고, platform 이 `TimeProvider` 같은 시간 계약을 가진 곳이다. point 에 두는 안은 버렸다(사용권 앱은 point 없이 쓴다). 광고 공용 모듈을 새로 두는 안도 버렸다(함수 하나 때문에 모듈 하나는 과하다).
3. **1회용 토큰**: 무작위 32바이트(base64url)를 주고, 저장은 SHA-256 해시로만 한다(채택, `account` 의 1회용 토큰과 같은 관례). HMAC 서명 토큰은 버렸다. 1회용이라 어차피 DB 를 봐야 하고, 비밀 키가 배포 비밀 목록에 하나 더 붙기 때문이다.
4. **웹 SSV**: 2026-11 현재 Ad Manager 도움말(answer 9116812)에 「rewarded web 광고 단위에 SSV URL 을 두면 GET 콜백이 온다」는 문장이 생겼다. 하지만 GPT 에서 사용자 id 를 싣는 방법과 콜백 형식은 문서에 없다. 그래서 1차는 세션 방식으로 가고, 문서가 나오면 `ads-admob` 의 검증기를 재사용해 넓힌다.

## 흐름 (GPT 공식 흐름)

1. 사용자가 앱의 「광고 보고 …」 버튼을 누르면 `POST …/sessions` 가 간다. 상한에 걸렸으면 409 이고 광고는 띄우지 않는다.
2. `googletag.defineOutOfPageSlot(adUnitPath, OutOfPageFormat.REWARDED)` 를 부른다. 결과가 null 이면 「광고 없음」이다. 아니면 `addService(pubads())` → `enableServices()` → `display(slot)` 순서로 부른다.
3. `rewardedSlotReady` 가 오면 보상 내용을 적은 동의 창을 띄운다. 사용자가 [보기]를 누르는 클릭 핸들러 안에서만 `event.makeRewardedVisible()` 를 부른다. 소리는 이 손짓 뒤에만 난다. 동의 창을 두는 이유: ready 는 첫 클릭보다 늦게 와서, 그때는 브라우저의 일시적 사용자 활성이 끝나 있을 수 있다.
4. `rewardedSlotGranted` 가 오면 `POST …/sessions/{id}/grant` 를 보낸다. `payload`(종류·양)는 클라이언트 값이라 서버가 쓰지 않는다.
5. `rewardedSlotClosed` 가 오면 `googletag.destroySlots([slot])` 를 부른다. granted 없이 닫혔으면 보상을 잃은 것(forfeited)이다. 같은 슬롯의 `slotRenderEnded` 가 `isEmpty` 면 「광고 없음」이다. 다음 광고는 사용자가 다시 눌러야만 시작한다.

## API 계약 (고정 — 두 줄기가 이것만 본다)

로그인 필요(호출자 = `Authentication.name`, 익명은 401). 모듈이 꺼져 있으면 404. 응답 봉투는 레포 표준(`{ value, meta }`, 에러 `ApiError`)이다.

| 메서드 · 경로 | 요청 | 200 `value` | 에러 |
|---|---|---|---|
| `POST /api/v1/ads/web-rewarded/sessions` | `{ "purpose": "unlock-episode" }` — 필수, `^[a-z0-9][a-z0-9._-]{0,63}$` | `{ "sessionId": "wrs_01J…", "purpose": "unlock-episode", "expiresAt": "2026-11-18T03:15:00Z" }` | 400 `COMMON.VALIDATION_FAILED` · 409 `ADS_WEB_REWARDED.DAILY_LIMIT` |
| `POST /api/v1/ads/web-rewarded/sessions/{sessionId}/grant` | 본문 없음 | `{ "sessionId": "wrs_01J…", "purpose": "unlock-episode", "grantedAt": "2026-11-18T03:00:31Z", "reward": { "type": "point", "ref": "pt_01J…" }, "token": null, "tokenExpiresAt": null }` | 404 `ADS_WEB_REWARDED.SESSION_NOT_FOUND`(없음 · 남의 세션) · 409 `ADS_WEB_REWARDED.SESSION_USED` · 409 `ADS_WEB_REWARDED.SESSION_EXPIRED` · 409 `ADS_WEB_REWARDED.TOO_EARLY` · 409 `ADS_WEB_REWARDED.DAILY_LIMIT` · 503 `ADS_WEB_REWARDED.REWARD_UNAVAILABLE` |

- `reward-type` 을 정했으면 `reward` 만 차 있다. 정하지 않았으면(토큰 모드) `reward: null` 이고 `token`(1회용)과 `tokenExpiresAt` 이 차 있다.
- 세션 열기는 200 이다(201 이 아니다). 세션을 조회하는 경로가 없어 `Location` 을 줄 곳이 없다.
- 토큰을 쓰는 HTTP 경로는 두지 않는다. 앱이 자기 엔드포인트에서 `WebRewardTokens.redeem(token, accountId, purpose)` 를 부른다. 결과가 null 이면 쓸 수 없는 토큰이다(없음 · 남의 것 · 다른 목적 · 이미 씀 · 만료). 같은 토큰은 한 번만 성공한다.

## 서버 규칙

- **세션 열기**: 상한이 정해져 있으면 오늘 보상 수(계정, IP)를 읽기만 해서, 이미 다 찼으면 409 를 준다. 광고를 띄우기 전에 거절하려는 것이다. IP 키는 platform `ClientIps.of(request).limitKey` 다(IPv6 는 /64). 세션 id 는 `SortableIds` 접두사 `wrs_` 를 쓴다.
- **보상 요청**: 한 트랜잭션 안에서 다음 순서로 본다. 세션을 주인 조건으로 잠근다(없으면 404) → 이미 보상했으면 `SESSION_USED` → `now ≥ expiresAt` 이면 `SESSION_EXPIRED` → `now < openedAt + min-elapsed` 이면 `TOO_EARLY`(세션은 열린 채로 둔다) → 정해진 상한마다 「max 미만일 때만 +1」을 원자적으로 하고, 실패하면 `DAILY_LIMIT` → 세션을 GRANTED 로 바꾼다 → `reward-type` 이 있으면 `RewardIssuer.issue(RewardGrantView(id = sessionId, accountId, type, params = reward-params, sourceType = "ADS_WEB_REWARDED", sourceKey = sessionId))` 를 같은 트랜잭션에서 부르고 `resultRef` 를 남긴다. 없으면 토큰을 만들어 해시와 만료를 남긴다.
- 지급기가 던지거나 DB 오류가 나면 전부 되돌린다. 세션은 열린 채로, 카운터는 그대로 남고 503 을 준다. 지급기와 세션 표가 **같은 DB** 라는 전제는 `reward` 모듈과 같다.
- 에러는 트랜잭션 밖에서 던진다. 결과 값을 돌려받은 뒤에 던지는 point 의 방식을 따른다.
- **상한 단위는 보상 횟수**다(포인트 합이 아니다). 양은 `reward-params` 로 고정되므로 「하루 n편」으로 읽힌다. 「하루」는 `DayWindow.containing(now, day-zone)` 이고, 카운터 키는 그 `from` 이다.
- IP 상한은 `skeleton.web.client-ip.mode` 를 정해야 믿을 수 있다(정하지 않으면 전달 헤더로 고를 수 있다). 모듈 문서에 적는다.
- 계정이 지워지면(`AccountErasureListener`) 그 계정의 세션 줄과 계정 카운터를 지운다.
- `reward-type` 을 정했는데 그 종류의 `RewardIssuer` 빈이 없거나 `validate(reward-params)` 가 실패하면 기동이 실패한다.

## 설정 `skeleton.ads-web-rewarded` (기본은 중립)

```yaml
skeleton:
  ads-web-rewarded:
    enabled: false              # true 면 두 경로가 선다 (꺼짐 → 404). 저장소(ads-web-rewarded-jdbc)가 있어야 한다
    session-ttl: 15m            # 세션을 열고 보상 요청까지 허용하는 시간
    min-elapsed: 7s             # 세션을 열고 이보다 빨리 온 보상 요청은 거절 — 구글: 표시 광고는 7초 이상 보여야 보상. 영상만 쓰면 광고 길이로 올린다
    # account-daily-limit: 10   # 계정 하루 보상 횟수. 비우면(기본) 없음
    # ip-daily-limit: 30        # IP 하루 보상 횟수. 비우면(기본) 없음
    day-zone: UTC               # 「하루」의 기준 시간대(IANA) — ads-admob 과 같은 뜻
    # reward-type: point        # 보상 즉시 이 종류의 RewardIssuer 를 부른다. 비우면(기본) 1회용 토큰을 돌려준다
    reward-params: {}           # 지급기에 넘길 params — point 면 amount(필수) · expiresInDays(선택)
    token-ttl: 10m              # 토큰 모드에서 토큰을 쓸 수 있는 시간
```

검증 규칙: 시간은 모두 0보다 커야 한다. `min-elapsed < session-ttl`. 상한은 0 이상이다. `day-zone` 은 IANA 이름이어야 한다. 포인트를 쌓는 앱의 설정은 `reward-type: point` + `reward-params: { amount: "1" }` 이다.

## 확장 지점 (developer 는 이것만 만든다)

| 자리 | 왜 |
|---|---|
| `RewardIssuer`(기존, platform) · 기본은 point 의 `PointRewardIssuer` | 보상 처리가 앱마다 다르고, 두 번째 구현(1회 사용권)이 예정되어 있다 |
| `WebRewardStore` 포트 · `-jdbc` 구현 | 레포 관례(core / `-jdbc`). 다른 DB 는 앱이 구현한다 |
| `WebRewardTokens.redeem` | 토큰 모드 앱이 자기 경로에서 소비한다 |
| 서비스 · 컨트롤러 빈 `@ConditionalOnMissingBean` | 레포 관례 |
| react: `loadGoogletag` 주입 · API `basePath` | 진짜/가짜 GPT 전환, 모듈 경로를 바꾼 앱 |

만들지 않는 것: 광고 네트워크 추상화, 목적(purpose)별 설정, 보상 양을 계산하는 훅.

## react `@skeleton/ads`

- 의존은 `@skeleton/api-client` · `@skeleton/ui`, peer 는 `react`. GPT 타입은 쓰는 부분만 패키지 안에 둔다(외부 타입 패키지 없음). 스크립트는 `https://securepubads.g.doubleclick.net/tag/js/gpt.js` 다.
- 상태: `idle → opening → loading → ready → playing → granting → rewarded`. 끝 상태는 `rewarded` · `forfeited`(granted 없이 닫힘) · `unavailable`(광고 없음) · `limited`(`DAILY_LIMIT`) · `failed` 이고, `ready` 에서 `cancel()` 하면 `idle` 로 돌아간다.
- `watch()` 는 `ready` 이고 사용자 활성(`navigator.userActivation.isActive`, 없는 브라우저는 통과)이 있을 때만 `makeRewardedVisible()` 를 부른다. 컨트롤러는 스스로 `start()` 를 부르지 않는다(자동 연속 재생 금지). 보상 요청은 세션 하나에 한 번이다.
- 가짜 모드는 `createFakeGoogletag({ outcome: 'granted' | 'closed' | 'no-fill' | 'load-error', watchMs = 8000 })` 다. googletag 의 쓰는 부분만 흉내 내고 미디어를 만들지 않으니 소리가 나지 않는다. 앱이 로컬·시험에서 `loadGoogletag` 로 끼운다. 기본 8초는 서버 `min-elapsed` 7초보다 길게 잡은 값이다. 스토리와 테스트는 진짜 GPT 를 싣지 않는다.

## 정책 체크 (`packages/ads/README.md` 에 둔다)

구글의 「보상을 주는 광고 단위 정책」과 rewarded web 요건을 따른다. 광고를 띄울 때마다 해야 할 행동과 받을 보상을 미리 알린다. 사용자가 그 광고를 직접 고를 때만 띄운다(한 번 동의로 계속 보는 것은 금지). 자동 연속 재생을 하지 않는다. 닫기·건너뛰기가 가능해야 하고, 보상형이 아닌 광고에 보상 문구를 붙이지 않는다. 보상은 반드시 준다. 뷰포트 meta(`width=device-width, initial-scale=1`)가 있어야 하고, Ad Manager 의 「Block non-instream video ads」 보호를 꺼야 한다.

## 테스트 (깨지면 사고가 나는 것만)

- 서버: 같은 세션의 동시 보상 요청은 한 번만 성공한다. 상한 직전에 두 세션이 동시에 요청하면 하나만 성공한다. 지급기가 실패하면 세션은 열린 채, 카운터는 그대로다. 너무 이른·만료된·쓴·남의 세션은 거절한다. 토큰은 한 번만 쓰이고 다른 계정·목적이면 쓰이지 않는다. 지급기가 없는 설정은 기동이 실패한다. `DayWindow` 의 서울 자정 경계.
- 웹: 사용자 활성 없는 `watch()` 는 광고를 띄우지 않는다. granted 가 두 번 와도 보상 요청은 한 번이다. 닫힘만 오면 보상 요청이 없다. 끝난 뒤 저절로 다시 시작하지 않는다. `DAILY_LIMIT` 이면 슬롯을 만들지 않는다. 닫히면 슬롯을 지운다.

## 다음에 넓힐 것

웹 SSV(구글 문서가 나오면), 지난 세션·카운터 정리 작업, 보상 응답을 잃었을 때 다시 받기, 앱·웹 합산 상한(정하면), 미리 불러 두기(preload).

## 정해야 할 것

1. **앱(AdMob)과 웹의 하루 상한을 합칠까?** 포인트 원장 앱의 요구에 「같은 하루 상한 설정」이 있는데, 지금 원장의 상한은 출처(`ADMOB_SSV`)별로 센다.
   - (추천) 따로 센다 — 1차 그대로. `ads-admob` 은 포인트 합, 웹은 보상 횟수를 센다. point 와, 지금 다른 손이 넣고 있는 상한 작업을 건드리지 않는다. 대신 둘 다 10 이면 하루 최대 20편이다.
   - 합친다 — 계정 하루 n편이 앱·웹 통틀어 한 번 지켜진다. point 의 `PointEarnLimit` 에 「같이 셀 출처 목록」을 더하고, 웹은 point 모드에서 `earnWithin` 으로 그 목록을 세야 한다. point·ads-admob 을 고치는 작업이 하나 더 붙는다.
