# notification-legal

광고성 정보 게이트(`AdvertisingGate`, 모듈 [notification](notification.md))의 **동의 읽기 어댑터**이자 **동의 변경 통지 · 2년 재확인 안내**다 — 채널(푸시 · 메일 · 슬랙)과 무관하다. `AdvertisingConsentPolicy` 를 `legal` 의 동의 기록(append-only 사건)으로 구현한다.
`-jdbc` 와 같은 포트/어댑터 짝이다: `notification` 이 `legal` 을 몰라야 광고를 안 쓰는 앱이 `legal` 없이 알림을 쓰고, 규칙 시험이 가짜 정책으로 된다. 법률 자문이 아니다 — 광고 문서(`marketing` · `marketing-night`) 본문 · 전송자 연락처 · 법 해석은 앱(주인)과 법률 검토의 몫이다.

**동의 처리 결과 통지와 2년 재확인 안내도 여기서 선다 — 푸시 없이도 돈다**(정보통신망법 §50):
- `AdvertisingConsentNotifier`(`legal` 의 `ConsentChangeListener`): 동의 · 철회 · 재확인이 **어느 경로로 와도**(`legal` HTTP · 가입 · 앱 코드) 저장되면 곧바로 정보성 알림 `advertising.consent.changed` 를 그 사람에게 발행한다 — 전송자 명칭 · 처리 일시 · 무엇을(광고 / 야간) · 철회 방법. 광고 게이트 밖이라 광고 동의가 없는 사람에게도 간다.
- `AdvertisingReconfirmScanner`: `reconfirm-scan-interval`(기본 6시간)마다(프로젝트마다 `skeletonTaskScheduler`, 요청 구동 모드는 `OnRequestTask` `advertising-reconfirm`) 만료 `reconfirm-notice-before`(기본 30일) 전부터 정보성 알림 `advertising.consent.reconfirm` 을 **동의 주기당 한 번** 발행한다. 이미 만료된 동의 · 광고 동의가 없는 사람에게도 한 번 간다. 사용자가 `source: "reconfirm"` 으로 동의하면 새 주기가 시작된다.
- 둘 다 **알림 이벤트(`NotificationPublisher`)로 내보낸다**: 받은편지함 · SSE 는 늘 가지만 **그것은 전송매체가 아니라 앱 안 기록이다** - 법정 통지가 닿으려면 매체가 하나는 있어야 한다. (1) **푸시**: 앱이 두 종류(`advertising.consent.*`)를 `push.types` 에 넣는다(안 넣으면 받은편지함에만 쌓인다). (2) **전자우편**: `notification-mail` 이 같이 있으면 `AdvertisingNoticeMailer` 가 이 두 종류를 그 사람의 연락 주소(`ContactAddresses` - `account` 가 구현, 연락받을 이메일 → 계정 이메일)로 보낸다(`MailSender` · `ContactAddresses` 중 하나가 없으면 그 통지는 메일로 안 간다). 푸시도 메일도 없으면 시작할 때 **경고**를 남긴다(막지는 않는다 - 앱이 SMS 같은 자기 채널로 이 종류를 집어 갈 수 있다). 이벤트 id 는 (계정 · 종류 · 동의 시각)의 해시라 표시를 잃고 다시 보내도 받은편지함이 한 줄로 합친다.
- 「이미 안내했다」 표시는 `AdvertisingNoticeStore` 가 갖는다: `notification-jdbc`(표 `advertising_notices`)이거나 푸시 앱의 `notification-push-jdbc`(`push_advertising_notices` — 둘 다 있으면 푸시 쪽이 쓰여 이미 안내한 사람이 다시 안내받지 않는다). **표시 저장소가 없으면 시작이 실패한다** — 재확인 안내가 조용히 안 나가는 일이 없게.
- 문구는 한국어 기본이고 payload 에 구조화된 값(scope · action · senderName · 시각)이 같이 실린다 — 문구를 바꾸려면 `AdvertisingConsentNotifier` / 스캐너 빈을 교체한다. 재확인 스캔은 돌 길이 없으면 시작이 실패한다(`PeriodicRunCheck` - `scheduler` 모듈이거나 `skeleton.job-queue.mode=on-request`). 스캔은 동의한 지 `reconfirm-after − reconfirm-notice-before` 가 넘은 모든 사람을 쪽(500)으로 읽는다 — 아주 큰 서비스는 스캔 간격을 늘린다.

**꺼짐이 기본이다** — `skeleton.notification.advertising.types`(광고성 종류)가 비어 있으면 빈을 하나도 내지 않는다(이 모듈 없는 앱과 같다). 광고 종류를 설정했는데 이 모듈(정책 빈)이 없으면 시작이 실패하고 「이 모듈을 얹어라」고 말한다. 켜면 `legal` + `legal-jdbc` 가 같이 있어야 한다(동의 기록). 광고 종류를 정하면 `fallback-zone` · `sender-name` · **`body-suffix`(수신 거부 방법, 예: 「수신 거부: 설정 > 알림」 — §50④)** 가 필수다: 비면 `notification` 이 시작을 멈춘다.

**정책**: 광고 수신 동의(`consent-type`, 기본 `marketing`)가 **유효**할 때만 보낸다 — `legal` 상태가 `CURRENT`/`GRACE` **이고** 가장 최근 `AGREED` 사건 + `reconfirm-after`(기본 730일)가 아직 안 지났을 때. 미동의 · 철회 · 2년 만료 · 새 판 시행 뒤 유예 밖(`OUTDATED`)은 모두 「없는 동의」. 야간 수신은 **별도 문서**(`night-consent-type`, 기본 `marketing-night`)이고 광고 동의가 유효할 때만 유효하다(철회 한 번이면 광고 전체가 멈춘다 — 계산으로, 연쇄 쓰기 없이). 상태 계산(`LegalAdvertisingConsents`)은 `GET /api/v1/push/advertising`(`notification-push-legal`)과 같은 것을 쓴다.

**채널이 부르는 법**: 이 모듈을 얹고 `skeleton.notification.advertising.*` 를 설정하면 `AdvertisingGate` 빈이 이 정책으로 판정한다.
- **푸시**는 채널이 알아서 게이트를 부른다([notification-push](notification-push.md)).
- **메일**은 `notification-mail` 이 같이 있으면 `MailSender` 가 알아서 건다 — `MailMessage.notificationType` 이 광고 종류이면 받는 사람 한 명(`accountId` 필수)마다 `decide` 를 불러 동의가 없으면 보내지 않고, 보내는 것은 제목에 `(광고)` · 본문(text · html) 끝에 전송자 명칭(`sender-name`) · `body-suffix` 를 단다. **야간 규칙은 메일에 없다** - 법 제50조 제3항 단서의 「대통령령으로 정하는 매체」가 전자우편이다(정보통신망법 시행령 제61조 제2항): 야간(21~08시)에도 광고 동의만 있으면 보내고 야간 별도 동의를 보지 않는다. 광고 본문에는 전송자 연락처도 있어야 하니 앱이 본문에 싣는다(모듈은 연락처 설정을 모른다)([notification-mail](notification-mail.md)).
- 슬랙 등 **다른 채널**로 광고를 보내는 앱은 받는 사람마다 `gate.decide(accountId)` 를 직접 부르고(`SendNow` 면 보내고 · `Skip` 이면 보내지 않고 · `Hold(runAt)` 이면 그 시각 이후로 미룬다) 제목 · 본문을 `gate.brand(title, body, 한도바이트)` 로 감싼다.
- 받은편지함 · SSE · 웹소켓은 전송이 아니라 앱 안 기록 · 표시라 게이트 밖이다. 그래서 앱이 광고 종류를 `NotificationPublisher` 로 직접 발행하면 받은편지함 제목에는 `(광고)` 가 안 붙는다 - 제목 · 본문을 `gate.brand(...)` 로 감싸 발행한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-legal"))` |
| 함께 오는 모듈 | `notification`, `platform`, `legal` |
| 컴파일 전용 | `notification-mail` |
| 설정 접두사 | 없음 (설정은 notification 모듈의 광고 설정에서 정한다) |
| 기본 동작 | 꺼짐. 광고성 종류(`skeleton.notification.advertising.types`)를 설정하고 `legal` 이 있으면 정책 · 통지 · 재확인 스캔이 선다. |
| 부팅에 필요한 것 | `legal` 의 `ConsentService`(`legal` + `legal-jdbc`), 재확인 표시 저장소 `AdvertisingNoticeStore`(`notification-jdbc` 또는 `notification-push-jdbc`), 알림 발행기(`notification`), 스캔을 돌릴 길(`scheduler` 모듈 또는 `skeleton.job-queue.mode=on-request`). 광고 설정(`fallback-zone` · `sender-name` · `body-suffix`)은 `notification` 이 검사한다. |
| 교체 지점 | `LegalAdvertisingConsents`, `legalAdvertisingConsentPolicy`(`AdvertisingConsentPolicy` 빈), `AdvertisingConsentNotifier`, `AdvertisingReconfirmScanner`, `advertisingReconfirmSchedule`, `advertisingReconfirmTask`, `AdvertisingNoticeMailer` |
| 마이그레이션 | 없음 (표는 `notification-jdbc`) |
| 프론트 짝 | 없음 |
| 테스트 | `modules/notification-legal/src/test` |

자세히: [notification](notification.md) · [notification-mail](notification-mail.md) · [notification-push](notification-push.md) · [legal](legal.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
