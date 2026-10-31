# notification-webpush

웹 푸시(VAPID) 전달 수단 — `notification-push` 의 `PushSender`(kind `WEBPUSH`)를 구현해 브라우저 구독으로 푸시를 보낸다.
표준 하나(RFC 8030 전달 · 8291 본문 암호화 `aes128gcm` · 8292 VAPID)라 Chrome · Firefox · Safari 16+ · Edge 가 모두 이 모듈 하나로 받는다. 암호화와 서명은 **JDK 암호화만** 쓴다(외부 라이브러리 없음) — RFC 8291 부록 A 시험 벡터가 한 바이트도 다르지 않게 나오는 것을 시험이 지킨다.

**꺼짐이 기본이다**(모듈 기본값 중립) — 앱 yml 이 켠다: `skeleton.notification.webpush.enabled=true` + VAPID 키 쌍 + `subject`. 알림이 실제로 가려면 `notification-push` 도 켠다.

**VAPID 키**는 `npx web-push generate-vapid-keys` 로 **한 번 만들어 고정**한다. 공개 키(`public-key`)는 비밀이 아니고 `GET /api/v1/push/config` 로 화면에 간다. 개인 키(`private-key`)는 **운영자 비밀**이라 환경변수로만 넣는다(로그 · 응답 · `toString` · 실패 이유 어디에도 나오지 않는다). **키를 바꾸면 기존 브라우저 구독이 모두 다음 방문까지 끊긴다** — 서버를 옮길 때 키를 들고 간다. 기기 줄의 `vapid_key_id` 가 지금 키와 다른 줄에는 보내지 않는다(화면이 다음 방문에 다시 구독).

**끝점 검사(SSRF)**: 서버가 남이 준 주소로 POST 하므로 등록할 때와 보낼 때 모두 — `https` · 포트 443 · userinfo 없음 · **허용 목록의 호스트**(기본 `fcm.googleapis.com` · `updates.push.services.mozilla.com` · `*.push.apple.com` · `*.notify.windows.com`)만 통과한다. 보낼 때는 호스트 이름을 풀어 **사설 · 루프백 · 링크로컬 · 공유 주소로 풀리면 거절**하고, **리다이렉트는 따라가지 않는다**. 거절하면 요청 자체가 나가지 않는다. 허용 목록에는 `http://host:port` 항목(개발 · e2e 가짜 푸시 서비스용)을 둘 수 있지만 그 출처 하나만 정확히 열 뿐이고, **보호 환경(stage · prod)에서는 기동이 실패한다**.

**판정**(`PushSender.send` → 코어 처리기): 2xx(표준은 201) 전달 · `404` · `410` 그 기기 줄만 지움 · `429`(`Retry-After` 초 단위) · `5xx` · 연결 실패 · 시간 초과 → 재시도 · **`400` · `401` · `403` · `413` 과 그 밖은 실패로 남긴다 — 지우지 않는다**(우리 VAPID 설정 실수가 구독 대량 삭제로 번지지 않게. 죽은 작업이 되어 `alert` 가 있으면 경보). 머리: `TTL`(남은 초) · `Urgency`(`HIGH` → `high`) · `Topic`(묶기 열쇠의 sha256 → base64url 앞 32자) · `Content-Encoding: aes128gcm`. 본문 JSON `{v:1, id, type, title, body, link, tag}`(한 레코드에 들어가는 3993바이트를 넘으면 실패).

**기동 검사**(`DeployGuard` `notification-webpush`): 켰는데 개인 키 · 공개 키 · `subject` 가 비었거나 읽을 수 없음 · 두 키가 한 쌍이 아님 · 견본용 시험 공개 키(`WebPushTestKeys.PUBLIC_KEY`, 개인 키 짝이 공개 문서에 있다) · 허용 목록의 `http://` 항목 · 아무것에도 맞지 않는 허용 항목이면 **보호 환경(`skeleton.env=stage|prod`)에서 기동 실패**, 그 밖에서는 경고만 남기고 센더를 세우지 않는다(기기 등록이 `400 PUSH.KIND_DISABLED`). 메시지에는 속성 · 환경변수 이름만 적고 값은 싣지 않는다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-webpush"))` |
| 함께 오는 모듈 | `notification-push`, `notification`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.notification.webpush` — [docs/config/modules/notification-webpush.yml](../config/modules/notification-webpush.yml) |
| 기본 동작 | 꺼짐. `skeleton.notification.webpush.enabled=true` 로 켠다. |
| 부팅에 필요한 것 | 켰다면 VAPID 키 쌍(`public-key` · `private-key`)과 `subject` — 보호 환경에서는 비었거나 · 한 쌍이 아니거나 · 시험 키면 기동 실패. 개인 키는 운영자 비밀(`<P>_NOTIFICATION_WEBPUSH_VAPID_PRIVATE_KEY`). 공개 키 `_VAPID_PUBLIC_KEY` · `_SUBJECT` 는 환경변수(`env:`). |
| 교체 지점 | `PushSender`(`webPushSender` 빈), `WebPushHttp`(전송 — 시험·프록시용), `DeployGuard`(`webPushDeployGuard` 빈) |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 — 짝 프런트 패키지(@skeleton/push)는 react-skeleton 에서 준비 중 |
| 테스트 | `modules/notification-webpush/src/test` |

자세히: [notification-push](notification-push.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
