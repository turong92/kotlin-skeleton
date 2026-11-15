# notification-push-legal

광고성 푸시의 **동의 상태 읽기 어댑터**다(`GET /api/v1/push/advertising`). 판정(동의 없이는 안 보냄 · 야간 보류 · 제목 `(광고)` · 수신 거부 문구)은 채널과 무관한 부품 `AdvertisingGate`([notification](notification.md))가 갖고, 정책 구현(`legal` 동의 기록 읽기) · **동의 변경 통지 · 2년 재확인 안내**는 채널과 무관하게 [notification-legal](notification-legal.md)이 한다 — 이 모듈은 그 둘을 `api` 로 끌어오고(의존성 한 줄로 충분), 푸시 앱의 설정 화면이 읽는 동의 상태 경로를 더한다. 푸시 코어는 `legal` 을 몰라 광고를 안 쓰는 앱이 `legal` 없이 푸시를 쓴다.
법률 자문이 아니다 - 광고 문서(`marketing` · `marketing-night`) 본문 · 전송자 연락처 · 법 해석은 앱(주인)과 법률 검토의 몫이다. 정보통신망법 §50 관행을 기계장치로 옮긴 것이다.

**꺼짐이 기본이다** - `skeleton.notification.advertising.types`(광고성 종류)가 비어 있으면 이 모듈은 빈을 하나도 내지 않는다(이 모듈 없는 앱과 같다). 광고 종류를 설정했는데 정책 빈(`notification-legal`)이 없으면 시작이 실패하고 「이 모듈을 얹어라」고 말한다 -
동의 확인 없이 광고가 나가는 구성이 조용히 생기지 않게. 켜면 `legal` + `legal-jdbc` 가 같이 있어야 한다(동의 기록).

**무엇을 하나**
- **정책** `AdvertisingConsentPolicy`(구현은 [notification-legal](notification-legal.md)): 광고 수신 동의(`consent-type`, 기본 `marketing`)가 **유효**할 때만 보낸다 - `legal` 상태가 `CURRENT`/`GRACE` **이고** 가장 최근 `AGREED` 사건 + `reconfirm-after`(기본 730일)가 아직 안 지났을 때. 미동의 · 철회 · 2년 만료 · 새 판 시행 뒤 유예 밖(`OUTDATED`)은 모두 「없는 동의」. 야간(21~08시) 수신은 **별도 문서**(`night-consent-type`, 기본 `marketing-night`)이고 광고 동의가 유효할 때만 유효하다(철회 한 번이면 광고 전체가 멈춘다 - 계산으로, 연쇄 쓰기 없이).
- **동의 변경 통지 · 재확인 안내**(`advertising.consent.changed` · `advertising.consent.reconfirm`)는 [notification-legal](notification-legal.md)이 알림 이벤트로 낸다 - 푸시 없이도 돈다. 두 종류가 단말에 가려면 앱이 `push.types` 에 넣는다(안 넣으면 받은편지함에만 쌓인다).
- **`GET /api/v1/push/advertising`**: 설정 화면이 읽는 동의 상태(모양: [푸시 HTTP 계약](../push-http-contract.md)). 동의 · 철회 · 재확인은 새 경로가 아니라 `legal` 의 기존 경로(`/api/v1/legal/consents`)다 - 증거가 한 곳에 쌓인다.

**알아 둘 것**
- 광고 게이트는 푸시 채널과 `notification-mail`(광고 종류로 표시된 메일)에 걸린다. 받은편지함 저장은 전송이 아니라 앱 안 기록이다.
- 광고 문서(`marketing`)의 새 판은 **동의 범위가 바뀔 때만** 올린다 - 올리면 유예 밖의 모든 사람이 다시 동의해야 광고를 받는다(안전 쪽).
- 받는 사람 선별(누구에게 광고를 보낼지)은 앱 몫이고 이 게이트는 안전망이다. 동의 조회는 받는 사람 한 명당 읽기 한 번이다.
- 시간대는 앱이 구현한 `AdvertisingRecipientZones`(예: 계정 프로필의 시간대) → 없으면 `fallback-zone`(필수, 시스템 기본 시간대는 쓰지 않는다)이다.
- 옛 `AdvertisingConsentNotifier` · `AdvertisingReconfirmScanner`(패키지 `notification.push.legal`)는 `notification-legal`(패키지 `notification.legal`)로 옮겼다 - 빈 이름은 그대로다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-push-legal"))` |
| 함께 오는 모듈 | `notification-push`, `notification-legal`, `notification`, `platform`, `legal` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (설정은 notification 모듈의 광고 설정에서 정한다) |
| 기본 동작 | 꺼짐. 푸시를 켜고(`enabled`) 광고성 종류(`skeleton.notification.advertising.types`)를 설정하면 동의 상태 경로가 선다. |
| 부팅에 필요한 것 | `legal` 의 `ConsentService`(`legal` + `legal-jdbc`), 재확인 표시 저장소(`notification-push-jdbc`), 알림 발행기(`notification`). 광고 설정(`fallback-zone` · `sender-name` · `body-suffix`)은 `notification`, `ttl` ≥ 12시간은 푸시 코어가 검사한다. |
| 교체 지점 | `AdvertisingController` (정책 · 통지 · 재확인 스캔은 notification-legal 모듈) |
| 마이그레이션 | 없음 (표는 `notification-push-jdbc`) |
| 프론트 짝 | `@skeleton/push` (광고 동의 칸 AdvertisingSettings — 부분 경로 advertising. 광고 문서 화면은 @skeleton/legal) |
| 테스트 | `modules/notification-push-legal/src/test` |

자세히: [notification-push](notification-push.md) · [legal](legal.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
