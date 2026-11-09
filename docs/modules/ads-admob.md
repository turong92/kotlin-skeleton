# ads-admob

AdMob **보상형 광고의 서버 측 확인(SSV)** 콜백을 받아 포인트를 쌓는다. 클라이언트가 「보상 줘」라고 부르는 길은 없다 — 광고를 끝까지 본 뒤 AdMob 이 서버로 직접 보내는 서명된 콜백만 포인트를 쌓는다.

`GET /api/v1/ads/admob/ssv`(공개, 로그인 없음 — 진위는 구글의 서명이다). 받은 쿼리 문자열을 고치지 않고 `&signature=` 앞까지(UTF-8)를 ECDSA-SHA256(DER, URL-safe base64) 으로, `key_id` 의 공개키로 확인한다. 공개키는 구글의 키 목록에서 읽어 24시간 넘게 쥐지 않고, 모르는 `key_id` 면 목록을 한 번 다시 읽는다(마지막으로 읽어 본 시각 — 실패 포함 — 뒤 1분 안에는 다시 읽지 않는다 — 아무 `key_id` 로 구글을 두들기지 못하게).
서명은 「구글이 보냈다」만 증명한다 — 남의 AdMob 계정 광고 단위가 이 주소를 콜백으로 지정해 포인트를 쌓을 수 있으므로, 설정 `ad-units` 에 있는 광고 단위만 받는다(비면 모든 콜백 거절). 콘솔의 광고 단위 ID(`ca-app-pub-…/123`)를 그대로 적으면 된다 — 구글의 콜백은 `/` 뒤 숫자만 보내므로 설정값과 콜백값을 둘 다 그 부분으로 맞춰 본다(숫자만 적어도 된다).
적립은 `PointWallet.earn(user_id, reward_amount, 유효기간, "ADMOB_SSV", transaction_id)` — 같은 `transaction_id` 는 한 번만 반영된다. `user_id` 는 앱이 광고를 부를 때 SSV 옵션으로 넘긴 계정 id 이고, 양은 AdMob 콘솔에서 정해 서명된 `reward_amount` 그대로다.

응답: 적립 · 이미 적립 → `200`(구글은 200 이 아니면 1초 간격으로 5번까지 다시 보낸다). 서명 불일치 · 허용 밖 광고 단위 · `user_id` · `transaction_id` · 양 없음 · 모르는 키 → `400`(지급 없음, 로그에는 사유 코드만 남기고 쿼리 값은 남기지 않는다). 공개키 목록을 못 읽음 · DB 오류 → `503`(구글이 다시 보낸다).
이 모듈은 계정 존재 여부를 모른다 — 모르는 `user_id` 도 서명과 광고 단위가 맞으면 그 계정 id 로 쌓는다(조작된 클라이언트는 SSV 옵션에 아무 값이나 넣을 수 있다 — 그 결과는 그 id 에 포인트가 쌓일 뿐 돈이 나가는 길은 아니다). `user_id` 가 128자를 넘으면 400 이다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:ads-admob"))` |
| 함께 오는 모듈 | `platform`, `point` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.ads-admob` — [docs/config/modules/ads-admob.yml](../config/modules/ads-admob.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 이고 지갑(`point` + `point-jdbc`)이 있고 `ad-units` 에 광고 단위를 적어야 일한다. |
| 부팅에 필요한 것 | 없음. 구글의 키 목록은 첫 콜백이 올 때 읽는다. |
| 교체 지점 | `AdmobKeySource`, `AdmobVerifierKeys`, `AdmobSsvVerifier`, `AdmobSsvController`, 공개 경로 빈 `admobSsvPublicEndpointContributor` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 (모바일 짝은 flutter-skeleton 의 광고 패키지 ads) |
| 테스트 | `modules/ads-admob/src/test` (시험용 EC 키 쌍으로 서명한 콜백 — 위조 · 허용 밖 단위 · 재전송 · 키 못 읽음) |

## 앱이 바꾸는 곳

- 켜기: `skeleton.ads-admob.enabled=true` + `ad-units` 에 콘솔의 광고 단위 ID. 환경변수로는 `<P>_ADS_ADMOB_ENABLED=true`, `<P>_ADS_ADMOB_AD_UNITS_0=ca-app-pub-…/…`.
- AdMob 콘솔: 보상형 광고 단위의 「서버 측 확인」을 켜고 콜백 주소를 `https://api.example.com/api/v1/ads/admob/ssv` 로 정한다(주소는 앱의 공개 주소). 앱은 광고를 부를 때 SSV 옵션의 `user_id` 에 로그인한 계정 id 를 넘긴다.
- 광고 단위가 이 주소의 콜백을 부를 수 있어야 하므로 이 경로는 로그인 없이 열린다(`auth` 의 기본 체인이 공개 경로 목록을 읽는다).
- 유효기간: `expires-in-days`(기본 없음 = 안 사라짐).
- 구글의 키를 다른 곳에서 읽으려면(시험 · 사설 미러) `AdmobKeySource` 빈을 둔다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
