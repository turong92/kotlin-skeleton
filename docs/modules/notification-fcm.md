# notification-fcm

모바일 푸시(FCM HTTP v1) 전달 수단 — `notification-push` 의 `PushSender`(kind `FCM`)를 구현해 안드로이드 · iOS 앱의 기기 토큰으로 푸시를 보낸다(iOS 는 FCM 이 APNs 로 넘긴다). 서비스 계정 서명(JWT bearer)과 요청은 **JDK 만** 쓴다(외부 라이브러리 없음).

**꺼짐이 기본이다**(모듈 기본값 중립) — 앱 yml 이 켠다: `skeleton.notification.fcm.enabled=true` + `credentials`(Firebase 서비스 계정 키 JSON 의 base64). 알림이 실제로 가려면 `notification-push` 도 켠다. 서비스 계정은 Firebase 콘솔에서 만든다([real-provider-setup.md](../real-provider-setup.md) 「푸시 알림 키」). 프로젝트 id 는 그 JSON 의 `project_id` 를 쓴다(따로 적지 않는다).

**서비스 계정은 운영자 비밀이다.** `credentials` 는 환경변수(`<P>_NOTIFICATION_FCM_CREDENTIALS`)로만 넣는다. 서비스 계정 · 접근 토큰 · 기기(FCM) 토큰은 로그 · 응답 · `toString` · 실패 이유(죽은 작업의 `last_error`) 어디에도 나오지 않고, 구글이 오류에 적어 보낸 설명 문장도 싣지 않는다.

**요청 주소는 고정이다**: 토큰은 `https://oauth2.googleapis.com/token`(JSON 안의 `token_uri` 는 읽지 않는다), 보내기는 `https://fcm.googleapis.com/v1/projects/{project_id}/messages:send`. 프로젝트 id 는 `[a-z][a-z0-9-]{3,62}` 만 통과해 요청 경로에 다른 것이 끼지 못하고, 리다이렉트는 따라가지 않는다. 접근 토큰은 만료 5분 전까지 다시 쓴다.

**등록 검사**: 토큰은 1~4096자에 공백 · 제어 문자 없음, `platform` 은 `android` · `ios`.

**판정**(`PushSender.send` → 코어 처리기): `200` 전달 · `UNREGISTERED` · 오류의 `fieldViolations` 가 `message.token` 을 가리키는 `INVALID_ARGUMENT` → 그 기기 줄만 지움 · `QUOTA_EXCEEDED` · `UNAVAILABLE` · `INTERNAL` · `429`(`Retry-After` 초 단위) · `5xx` · 연결 실패 · 시간 초과 → 재시도 · **그 밖은 실패로 남긴다 — 지우지 않는다**: 토큰을 가리키지 않는 `INVALID_ARGUMENT`(메시지 모양 문제), `SENDER_ID_MISMATCH`(토큰이 다른 프로젝트 것이라는 뜻이지만, 다른 프로젝트의 서비스 계정을 잘못 넣어도 모든 토큰이 이 답을 받는다 — 설정 실수가 기기 줄 대량 삭제로 번지지 않게 지우지 않는다), `THIRD_PARTY_AUTH_ERROR`(APNs 키 문제), `401` · `403` · `404`(우리 자격 · 프로젝트 문제 — 지우면 모든 기기 줄이 사라진다). 접근 토큰을 못 얻어도 같다(구글이 서비스 계정을 거절하면 실패, 일시 장애면 재시도). 죽은 작업이 되어 `alert` 가 있으면 경보.

**메시지 모양**: `notification{title, body}` + `data{eventId, type, link}` + `android{collapse_key, ttl(남은 초), priority, notification.tag}` + `apns.headers{apns-collapse-id(묶기 열쇠의 sha256 base64url, 64바이트 이하), apns-expiration, apns-priority}`. `HIGH` 긴급도는 `priority: HIGH` · `apns-priority: 10`.

**기동 검사**(`DeployGuard` `notification-fcm`): 켰는데 `credentials` 가 비었거나 읽을 수 없으면(base64 · JSON · `type: service_account` · `client_email` · `project_id` · PKCS#8 RSA 키) **보호 환경(`skeleton.env=stage|prod`)에서 기동 실패**, 그 밖에서는 경고만 남기고 센더를 세우지 않는다(기기 등록이 `400 PUSH.KIND_DISABLED`). 메시지에는 속성 · 환경변수 이름만 적고 값은 싣지 않는다. 프로젝트 모드에서는 FCM 자격이 하나뿐이다(프로젝트마다 다른 Firebase 앱은 지원하지 않는다).

**진짜 발송 시험**은 `FCM_TEST_CREDENTIALS`(서비스 계정 JSON 의 base64) · `FCM_TEST_TOKEN`(기기 토큰) 환경변수가 둘 다 있을 때만 돈다(`FcmRealSendTest`). 평소에는 건너뛴다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-fcm"))` |
| 함께 오는 모듈 | `notification-push`, `notification`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.notification.fcm` — [docs/config/modules/notification-fcm.yml](../config/modules/notification-fcm.yml) |
| 기본 동작 | 꺼짐. `skeleton.notification.fcm.enabled=true` 로 켠다. |
| 부팅에 필요한 것 | 켰다면 서비스 계정 `credentials` — 보호 환경에서는 비었거나 읽을 수 없으면 기동 실패. 운영자 비밀(`<P>_NOTIFICATION_FCM_CREDENTIALS`). |
| 교체 지점 | `PushSender`(`fcmSender` 빈), `FcmHttp`(전송 — 시험·프록시용), `DeployGuard`(`fcmDeployGuard` 빈) |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 — 모바일 앱이 기기 토큰을 PUT /api/v1/push/devices/{installationId} 로 올린다 |
| 테스트 | `modules/notification-fcm/src/test` |

자세히: [notification-push](notification-push.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
