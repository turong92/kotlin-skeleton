# notification-mail

SMTP 로 메일을 보내는 `MailSender` 를 준다 (`spring.mail.*` 위에).
`enabled=true` · `spring.mail.host` · 발신자(`from`)를 정하기 전에는 꺼져 있다.

**광고 종류의 메일**(선택 연동 — `notification` 모듈이 같이 있을 때, 연동 코드는 notification 모듈 쪽 `AdvertisingMailSender` 에 있다): `skeleton.notification.advertising.types` 를 정하면 앱에 있는 모든 `MailSender` 빈(바꿔 낀 것도)이 감싸져, `MailMessage.notificationType` 이 광고 종류인 메일은 푸시와 같은 게이트를 지난다 — 동의가 없으면 보내지 않고(`accepted=false`), 야간이고 야간 동의가 없으면 보내지 않고 `heldUntil`(다음 08시)을 돌려준다(메일 보내기에는 큐가 없다 — 그 시각 이후 다시 보내는 것은 부르는 쪽 몫). 보내는 것은 제목에 `(광고)` · 본문(text · html) 끝에 `body-suffix`(수신 거부 방법)가 붙는다. 동의는 계정마다 보므로 광고 메일은 `accountId` 가 필수이고 받는 사람은 한 명이어야 한다(아니면 거절). `notificationType` 이 없는 메일은 정보성이라 그대로 나간다 — 광고인지 표시하는 것은 보내는 코드의 몫이다. 광고 종류가 없으면 아무것도 바뀌지 않는다. 규칙은 notification-legal 모듈 문서에 있다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-mail"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.notification-mail` — [docs/config/modules/notification-mail.yml](../config/modules/notification-mail.yml) |
| 기본 동작 | 꺼짐. `enabled=true` + `spring.mail.host` + `from` 으로 켠다. |
| 부팅에 필요한 것 | 없음. 켤 때 SMTP 서버가 필요하다. |
| 교체 지점 | `MailSender` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/notification-mail/src/test` |

프로젝트 여럿([multi-project](../multi-project.md)): 발신자 `from` 은 프로젝트마다(`skeleton.projects.<id>.config.notification-mail.from`) **필수**이고 공통 값으로 내려가지 않는다(다른 서비스 이름의 주소로 나가면 안 된다) — 없으면 기동 실패. 브랜드(메일 본문의 서비스 이름 · 링크 주소)는 계정 메일이면 `account.mail.*`, 직접 만드는 메일이면 앱이 정한다. SMTP 접속(`spring.mail.*`)은 서버 공통이다 — 프로젝트별 SMTP 는 범위 밖.

자세히: [메일 상세](../notification-mail.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
