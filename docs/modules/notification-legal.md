# notification-legal

광고성 정보 게이트(`AdvertisingGate`, 모듈 [notification](notification.md))의 **동의 읽기 어댑터**다 — 채널(푸시 · 메일 · 슬랙)과 무관하다. `AdvertisingConsentPolicy` 를 `legal` 의 동의 기록(append-only 사건)으로 구현한다.
`-jdbc` 와 같은 포트/어댑터 짝이다: `notification` 이 `legal` 을 몰라야 광고를 안 쓰는 앱이 `legal` 없이 알림을 쓰고, 규칙 시험이 가짜 정책으로 된다. 법률 자문이 아니다 — 광고 문서(`marketing` · `marketing-night`) 본문 · 전송자 연락처 · 법 해석은 앱(주인)과 법률 검토의 몫이다.

**이 모듈만으로는 동의 처리 결과 통지와 2년 재확인 안내가 없다** — 통지·재확인 안내는 지금 notification-push-legal(푸시 켜짐)에만 있다. 푸시 없는 앱은 직접 구현해야 한다(정보통신망법 §50).

**꺼짐이 기본이다** — `skeleton.notification.advertising.types`(광고성 종류)가 비어 있으면 빈을 하나도 내지 않는다(이 모듈 없는 앱과 같다). 광고 종류를 설정했는데 이 모듈(정책 빈)이 없으면 시작이 실패하고 「이 모듈을 얹어라」고 말한다. 켜면 `legal` + `legal-jdbc` 가 같이 있어야 한다(동의 기록).

**정책**: 광고 수신 동의(`consent-type`, 기본 `marketing`)가 **유효**할 때만 보낸다 — `legal` 상태가 `CURRENT`/`GRACE` **이고** 가장 최근 `AGREED` 사건 + `reconfirm-after`(기본 730일)가 아직 안 지났을 때. 미동의 · 철회 · 2년 만료 · 새 판 시행 뒤 유예 밖(`OUTDATED`)은 모두 「없는 동의」. 야간 수신은 **별도 문서**(`night-consent-type`, 기본 `marketing-night`)이고 광고 동의가 유효할 때만 유효하다(철회 한 번이면 광고 전체가 멈춘다 — 계산으로, 연쇄 쓰기 없이). 상태 계산(`LegalAdvertisingConsents`)은 `GET /api/v1/push/advertising`(`notification-push-legal`)과 같은 것을 쓴다.

**다른 채널이 부르는 법**: 이 모듈을 얹고 `skeleton.notification.advertising.*` 를 설정하면 `AdvertisingGate` 빈이 이 정책으로 판정한다. 광고성 메일 · 슬랙 등을 보내는 코드는 받는 사람마다 `gate.decide(accountId)` 를 부른다 — `SendNow` 면 보내고, `Skip` 이면 보내지 않고, `Hold(runAt)` 이면 그 시각 이후로 미룬다 — 그리고 제목 · 본문을 `gate.brand(title, body, 한도바이트)` 로 감싼다. 지금 광고 게이트는 푸시 채널에만 걸린다 — 광고성 메일 · 슬랙 등을 보내는 앱은 보내기 전에 이 부품을 불러야 한다(정보통신망법 §50).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-legal"))` |
| 함께 오는 모듈 | `notification`, `platform`, `legal` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (설정은 notification 모듈의 광고 설정에서 정한다) |
| 기본 동작 | 꺼짐. 광고성 종류(`skeleton.notification.advertising.types`)를 설정하고 `legal` 이 있으면 정책이 선다. |
| 부팅에 필요한 것 | `legal` 의 `ConsentService`(`legal` + `legal-jdbc`). 광고 설정(`fallback-zone` · `sender-name`)은 `notification` 이 검사한다. |
| 교체 지점 | `LegalAdvertisingConsents`, `legalAdvertisingConsentPolicy`(`AdvertisingConsentPolicy` 빈) |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/notification-legal/src/test` |

자세히: [notification](notification.md) · [notification-push](notification-push.md) · [legal](legal.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
