# 한 프로세스에 프로젝트 여럿

부품(빈)은 한 벌, 프로젝트마다 다른 것은 **설정과 데이터**뿐이다. 앱 설정의 `skeleton.projects` 가 비어 있으면(기본) 아무 일도 일어나지 않고 앱은 서비스 하나다.

```yaml
skeleton:
  projects:
    acme:
      hosts: [example.com, api.example.com]   # 요청의 Host 로 프로젝트를 정한다 (ForwardedHeaderFilter 가 푼 뒤)
      schema: acme                          # DB 스키마 (MySQL 은 데이터베이스) — 프로젝트마다 하나
      datasource: { username: acme, password: ${ACME_DB_PASSWORD}, pool-max: 3 }   # 선택. 비우면 공통 spring.datasource.*
      config:                                  # skeleton.* 아래 키 — 공통 값 위에 덮어 같은 properties 클래스로 바인딩한다
        auth.jwt.secret: ${ACME_JWT_SECRET}
        account.mail.brand: Acme
```

- 모르는 Host → `421 COMMON.UNKNOWN_HOST`. `/health` · `/info` 는 프로젝트 없이 지나간다(헬스체크는 `localhost` 로 온다).
- 같은 Host 를 두 프로젝트가 쓰거나 스키마가 겹치면 기동 실패.
- 요청 밖(뒤 작업)은 `ProjectContext.runIn(project) { … }` 로만 프로젝트를 정한다. 문맥 없이 `current()` 는 `NoProjectContextException`.
- 프로젝트마다 한 번 만드는 부품: `ProjectRegistry.scopedFrom(prefix, Props::class.java) { project, props -> … }`.

## DB (프로젝트마다 스키마)

- **프로젝트마다 DB 계정이 필수**다(예외 없음 — 로컬 · 시험도). 아래 "DB 계정" 절. 서버 하나, 프로젝트마다 연결 풀 하나(`persistence-jdbc` 의 `ProjectRoutingDataSource`). 풀은 처음 쓸 때 열고(안 쓰면 연결 0), 최대 `datasource.pool-max`(기본 3), 최소 유지 0. 같은 서버 URL(`spring.datasource.*`)에 프로젝트의 계정으로 붙는다. PostgreSQL 의 기본 스키마는 **계정에 박힌 `search_path`** 이고(풀의 연결 단위 `SET` 에 기대지 않는다), MySQL 은 스키마 = 데이터베이스라 풀이 catalog 를 고른다.
- **프로젝트 문맥 없이 연결을 꺼내면 예외**(`NoProjectContextException`) — 기본 스키마로 가지 않는다. 트랜잭션이 열린 뒤 프로젝트가 바뀌면 문장을 만들 때 예외.
- 기동 때 `migration-flyway` 가 스키마마다 같은 스크립트를 돌린다(이력도 스키마마다, 하나라도 실패하면 기동 실패). 스키마가 없으면 Flyway 가 만든다 — DB 계정에 권한이 없으면 운영자가 미리 만든다. `public` 은 건드리지 않는다.
- `SqlDialectVerifier` 는 기동 때 연결을 열지 않고 URL 로 방언을 본다. `/health` 는 프로젝트별 풀을 하나씩 본다(Boot 의 라우팅 DataSource 지원).
- `status: closed` 인 프로젝트: 도메인은 `503 COMMON.PROJECT_CLOSED`, 풀 · 마이그레이션 · "프로젝트 둘 이상" 개수에서 빠진다(스키마는 안 적어도 된다).

## 설정을 프로젝트별로 (로그인 · 소셜 · 메일 · 파일 · 약관 · 캡차)

`config` 에 적은 `skeleton.*` 키는 공통 값 위에 덮여 **같은 properties 클래스**로 바인딩된다 (`ProjectRegistry.bind` / `scopedFrom`). 지금 프로젝트별로 갈리는 것(로그인 쪽 + 소셜 · 메일 · 파일 · 약관 · 캡차 · Redis · 웹소켓):

| 무엇 | 키 (config 아래) | 어떻게 |
|---|---|---|
| JWT 키 · 발급자 · TTL | `auth.jwt.*` | 프로젝트마다 `JwtTokenService` 부품. 토큰에 프로젝트 이름(`prj`)이 들어가고 받을 때 대조 — A 토큰은 B 에서 거절(키를 같이 써도). 잘못된 비밀은 기동에서 막는다 |
| 리프레시 쿠키 | `auth-session.cookie.*` | `RefreshTokenDelivery` 가 지금 프로젝트의 이름 · 경로 · 보안 속성을 쓴다 |
| CORS 허용 출처 | `web.cors.*` | 요청의 프로젝트가 고른다 (`skeleton.web.cors.enabled=true` 일 때) |
| 계정 메일 링크 주소 · 브랜드 · 제목 접두사 | `account.mail.*` | 내장 메일 틀은 프로젝트마다 한 번 만든다 (앱이 자기 `AccountMailTemplates` 빈을 두면 그것을 모두가 쓴다) |
| 시드 계정 | `account.seed.accounts[n].*` | 기동 때 프로젝트마다 그 스키마에 만든다 |
| 소셜 로그인 (Google · Kakao · Naver) | `auth-social.providers.<id>.{enabled, client-id, client-secret, redirect-uri, …}` | 프로젝트마다 제공자를 따로 만든다 — A 에서 켠 제공자는 B 의 `/auth/methods` · 로그인 · 연결에 없다 |
| X | `auth-social-x.*` | 프로젝트의 `client-id` 가 있을 때만 그 프로젝트에 X 가 있다 |
| 메일 발신자 | `notification-mail.from` | 프로젝트마다 **필수**(공통 값으로 내려가지 않는다 — 다른 서비스 이름의 주소로 나가면 안 된다). SMTP 접속(`spring.mail.*`)은 서버 공통, 프로젝트별 SMTP 는 지원하지 않는다 |
| 파일 저장소 (S3 · R2 · MinIO) | `storage-s3.{bucket, region, endpoint-override, credentials.*, presign.*, public-url.*, key-prefix}` | 프로젝트마다 S3 클라이언트 · 저장소를 만든다. 같은 버킷을 나눠 쓰면 `key-prefix` 가 서로 달라야 한다(같으면 기동 실패). 버킷이 없는 프로젝트는 저장소가 없다. 공개 URL 은 RAW, 또는 `crypto` 가 있으면 OPAQUE(프로젝트의 암호화 키로 토큰을 만든다 — 키 없는 프로젝트는 기동 실패) |
| 업로드 키 규칙 | `storage.web.{key-prefix, key-owner, key-secret}` | 발급 · 소유 확인이 지금 프로젝트의 규칙으로 돈다. OPAQUE 비밀은 프로젝트마다 달라야 한다(같으면 기동 실패) — A 의 키는 B 에서 소유로 인정되지 않는다 |
| 약관 · 동의 문서 | `legal.{location, default-locale, required, required-at-sign-up, previous-version-grace, facts.*, acknowledge-template}` | 문서 집합 · 규칙을 프로젝트마다 만든다(운영자 이름 같은 사실도 프로젝트의 것). 시작 검사와 DeployGuard 가 프로젝트마다 돈다. 동의 기록은 스키마마다 |
| 캡차 (Turnstile) | `captcha-turnstile.{secret-key, expected-hostname, expected-action, siteverify-url, timeout}` | 검증할 때마다 지금 프로젝트의 값. `enabled` 는 앱 전체 하나 — 켜면 서비스 중인 프로젝트 모두 비밀 키가 있어야 한다 |
| Redis 키 (한도 · 캐시 · 락) | (설정 없음) | `RedisKeyPrefixer` 가 프로젝트 안에서 `<key-prefix>:project:<id>:…` 로 만든다. 접속 정보는 서버 공통 |
| 알림 웹소켓 (STOMP) | (설정 없음) | 세션의 프로젝트 = 연결 요청의 Host. 사용자 이름(`Principal.name`)이 `<프로젝트>\|<계정 id>` 라 앱 핸들러가 받는 이름도 그 꼴이고, 사용자 알림이 프로젝트 사이에 섞이지 않고, 공개 토픽은 구독 선택자로 프로젝트마다 나뉜다 |
| (공통 값으로 내려가지 않는 것) | 약관 `legal.location` · `legal.facts.*`, 소셜 `auth-social.providers.<id>.{enabled, redirect-uri}`, X `client-id` · `client-secret` · `redirect-uri`, OIDC `providers.*` 전체, 메일 `from`, 계정 메일 `link-base-url` | 프로젝트가 자기 값만 읽는다 — 한 서비스 시절의 공통 값이 남아 있어도 값을 빠뜨린 프로젝트가 남의 약관 · 소셜 앱 · 주소를 물려받지 않는다(약관은 안 주면 TEMPLATE 문서라 stage · prod 가 막고, 소셜은 안 주면 꺼진다) |
| 범용 OIDC | `auth-social-oidc.providers.<코드>.*` | 프로젝트마다 제공자를 만든다 — discovery · JWKS 보관도 제공자 안이라 프로젝트끼리 섞이지 않는다 |
| 암호화 키 | `crypto.{primary-key-id, keys.<id>}` | `TextEncryptor` 가 지금 프로젝트의 키로 넘어간다(변환기 · `OpaqueUrlTokenCodec` · 빌링키 모두 이 빈을 지난다). 프로젝트끼리 **같은 키 바이트**면 기동 실패, 키 없는 프로젝트는 쓰는 순간 그 프로젝트 이름의 오류. 아래 "결제" |
| 결제사 키 (토스 · 카카오페이 · 네이버페이 · Paddle · Stripe) | `payment-toss.*` · `payment-kakaopay.*` · `payment-naverpay.*` · `payment-paddle.*` · `payment-stripe.*` | 프로젝트마다 그 프로젝트의 키를 든 결제사 게이트웨이(= 결제사 HTTP 클라이언트)를 따로 만든다 — A 에서 켠 결제사는 B 의 결제 화면 · `GET /config` 에 없다. 아래 "결제" |
| 결제 라우팅 · 웹훅 비밀 조각 | `payment.{default-provider, providers.*, routing.country-providers, webhook.path-token}` | 나라 → 결제사 지도 · 기본 결제사 · 켜고 끔 · 서명 없는 결제사(토스)의 웹훅 주소 비밀 조각이 프로젝트마다 |

**위 표의 키만 프로젝트 `config` 에 쓸 수 있다**(`ProjectConfigKeys.SUPPORTED`) — 그 밖의 키(`account.sign-up.enabled`, `legal.record.*`, `web.rate-limit.*`, `web.cors.enabled` …)는 조용히 무시되는 대신 기동 실패다. 프로젝트별 `auth-session.delivery` 도 받는다(`/auth/methods` 는 지금 프로젝트의 방식을 알린다).

- 계정 메일 작업(확인 코드 · 재설정 · 매직 링크)은 `account-task` 풀로 넘어가지만 제출한 요청의 프로젝트를 들고 간다(스레드가 물려받지는 않는다).
- **프로젝트 모드에서 `account.mail.link-base-url` 은 프로젝트마다 필수**다 — 공통 값으로 내려가지 않는다(다른 프로젝트 도메인으로 링크가 나갈 수 있다). stage · prod 가드가 프로젝트마다 본다.
- stage · prod 가드는 활성 프로젝트마다 시드 계정 · `log-links=ON`(계정), 쿠키 `secure=false`(auth-session)를 같은 규칙으로 본다 — 메시지에 `project '<id>'`.
- 앱이 자기 `AccountMailTemplates`/`AccountMailLayout` 빈을 두고 활성 프로젝트가 둘 이상이면 모든 프로젝트가 같은 문구 · 틀을 쓴다는 경고를 남긴다(기동 실패는 아님 — 의도일 수 있다).
- 약관 보존 기한 정리의 "마지막으로 비운 때"는 프로젝트마다 둔다.
- MVC 비동기(`Callable` · `DeferredResult` · `SseEmitter`)에서도 프로젝트가 유지된다(`ProjectFilter` 가 비동기 재진입에도 돌고, MVC 실행기에 `ProjectTaskDecorator`).

뒤 주기 작업 중 계정 삭제 유예 정리 · 세션 정리는 프로젝트를 차례로 돈다. 그 밖의 값(가입 정책 · 비밀번호 정책 · 한도 …)은 아직 공통이다.


## 프로젝트 전용 API (`@ProjectApi`)

```kotlin
@ProjectApi("acme") annotation class AcmeApi          // 프로젝트 모듈이 한 번 만든다
@RestController @RequestMapping("/api/v1/cheers") @AcmeApi class CheerController { … }
```

- 문자열은 `skeleton.projects.<이름>` 의 이름. 다른 프로젝트 도메인으로 부르면 404 (`RequestMappingHandlerMapping` 의 요청 조건 — 컨트롤러 안 if 가 아니다). 두 프로젝트가 **같은 경로**를 써도 기동 충돌이 없다.
- 목록에 없는 이름이 붙은 컨트롤러는 어느 도메인에서도 열리지 않고 기동 로그에 경고(실패 아님 — 빌드에는 있지만 이 서버가 안 켠 프로젝트일 수 있다).
- **목록이 비면 표시는 무시되고 전부 열린다.** 한 프로젝트를 떼어 혼자 띄워도 코드 수정이 없다 — 단 같은 경로를 쓰는 다른 프로젝트의 컨트롤러가 같은 빌드에 있으면 한 서비스에서는 모호하다(견본은 그래서 `@Profile("multi")`).
- 프로젝트 모듈끼리는 서로의 코드 · 표를 부르지 않는다(떼어 낼 수 있게).

## 뒤 작업 · 메모리 상태

- **잡 큐가 있을 때의 계정 삭제 유예 정리**도 프로젝트마다 그 문맥에서 임대를 잡고 정리 잡을 넣는다(프로젝트마다 try/catch). 인메모리 임대도 프로젝트마다 따로.
- **잡 큐 일꾼**(`job-queue-jdbc`)은 서비스 중인 프로젝트를 차례로 돌며 프로젝트마다 그 스키마의 `jobs` 를 집는다. 한 번 돌 때 프로젝트마다 한 묶음(`batch-size`)씩이라 일감이 많은 프로젝트가 다른 프로젝트를 굶기지 않고, 한 프로젝트의 오류(DB 포함)는 그 프로젝트에서 끝나고 다음으로 간다. 끝난 줄 정리(retention)도 프로젝트마다. 일감을 넣을 때의 프로젝트는 넣은 스키마로 정해진다. 일꾼은 여전히 스레드 하나다 — 느린 핸들러는 모든 프로젝트의 다음 회차를 늦출 뿐 굶기지는 않는다. 자세히: [job-queue-jdbc](job-queue-jdbc.md).
- **배포 때 옛 일꾼이 새 일감을 DEAD 로 만들지 않는다**(한 서비스도 해당): 일꾼은 처리기를 아는 잡 종류만 집는다. 모르는 종류는 시도도 쓰지 않고 남아 다른 버전 일꾼이 집으며, `unhandled-type-grace`(기본 1일)가 지나도 아무도 안 집으면 그때 DEAD + 로그 + 죽은 잡 알림.
- **프로세스 메모리 상태는 프로젝트마다 따로** 센다(키에 프로젝트를 붙인다, `ProjectContext.scopedKey`): 인메모리 요청 한도(전체 한도 · 계정 흐름 · 로그인 시도 · 게시판 · 세션 회전), 멱등 키의 범위, 알림 브로커 구독 · 인메모리 받은편지함, 경보의 접기(저장소 키) · 직접 전송 간격 · 5xx 몰림 집계. 경보 메시지에는 프로젝트 id 가 실려 받는 쪽(웹훅 · 메일)이 어느 서비스 것인지 안다. 프로젝트가 없으면 키는 그대로다. 알림은 **같은 프로젝트에서 발행한 것만** 그 프로젝트의 구독(SSE 연결 …)에 간다 — 프로젝트 밖에서 구독한 서버 전체 리스너(슬랙 전달 등)는 모든 프로젝트의 알림을 듣고, 프로젝트 없이 발행한 알림은 그 리스너만 듣는다. **슬랙 전달(`notification-slack`, `notification-events` 기본 켜짐)은 모든 프로젝트의 알림 — 받는 사람 id · 본문 · 필드 — 을 한 채널로 보낸다**(제목과 필드에 프로젝트 id 가 붙는다). 기동 때 경고가 나오며, 한 채널로 모이면 안 되면 `notification-events: false` 로 끈다. 서버 여러 대가 공유하는 한도(Redis)는 키에 프로젝트가 들어간다(위 표). 멱등 · 알림의 Redis 저장소는 이 저장소에 없다.
- **다른 스레드로 넘어가는 일은 제출한 쪽의 프로젝트를 들고 간다**: `async` 실행기(`AsyncContextTaskDecorator` 가 MDC · 인증과 함께 프로젝트도), MVC 비동기 실행기(앱의 `TaskDecorator` 가 있으면 함께 감싼다), 경보 전송(가상 스레드), `skeletonTaskScheduler` 에 예약한 일. 스레드가 프로젝트를 물려받지는 않는다. `@SkeletonScheduled` 작업은 프로젝트 밖에서 돈다 — 프로젝트마다 일하려면 `ProjectRegistry.forEachProject { … }` 로 돌고, 문맥 없이 DB 를 쓰면 예외다(섞이지 않는다).

## 아직 프로젝트를 모르는 기능

Kafka(`event-kafka`) 만 남았다 — **빌드에 들어 있고 서비스 중인 프로젝트가 둘 이상**이면 기동이 실패한다(한 곳, `ProjectUnawareFeatures`). 해결: 모듈을 빼거나 나머지를 `status: closed` 로. 모듈이 프로젝트를 알게 되면 표에서 뺀다.
소셜 로그인 · 메일 발신 · 파일 · 약관 · 캡차 · Redis · 알림 웹소켓 · 암호화 · 결제는 위 표대로 프로젝트를 안다. 한계(프로젝트가 알지 못하는 부분):

- **프로젝트별 SMTP · Redis · 웹소켓 허용 출처는 없다** — 접속 정보(`spring.mail.*` · `skeleton.redis.*` · `notification-websocket.endpoint.*`)는 서버 공통이다. 메일은 발신자(`from`)만 프로젝트별이다.
- **캡차 `enabled` 는 앱 전체 하나**다 — 켜면 서비스 중인 프로젝트 모두 비밀 키가 있어야 한다.
- 버킷을 정하지 않은 프로젝트는 저장소가 없고, 그 프로젝트가 `/api/v1/storage` 를 부르면 프로젝트 이름이 담긴 오류다.
- 프로젝트 밖에서(뒤 작업 · 스케줄러) 저장소 · 약관 · 캡차 · 메일 발신자를 부르면 `NoProjectContextException` 이다 — `ProjectRegistry.forEachProject` 로 돈다. Redis 키는 예외라 프로젝트 밖이면 서버 전체 키가 된다(`<key-prefix>:…`).

### 암호화(`crypto`) — 프로젝트별 키

DB 는 스키마 · 계정으로 갈려 공유 키가 데이터를 섞지는 않지만, (1) 키 하나가 새면 모든 프로젝트의 암호문이 열리고 (2) 프로젝트를 떼어 혼자 띄울 때 공유 키를 같이 넘겨야 하고 (3) `storage-s3` 의 `OPAQUE` 공개 URL 토큰을 B 도메인이 풀 수 있어서 **프로젝트마다 키를 둔다**(구현됨).

- `skeleton.projects.<id>.config.crypto.{primary-key-id, keys.<id>}` 를 공통 `skeleton.crypto.*` 위에 덮어 프로젝트마다 `AesGcmTextEncryptor` 를 만들고, `TextEncryptor` 빈(`ProjectRoutedTextEncryptor`)이 부를 때 지금 프로젝트의 것으로 넘긴다. Spring Data 변환기 · `OpaqueUrlTokenCodec` 이 이 빈을 지나므로 따로 고칠 곳이 없다. 암호문 봉투의 키 id 는 프로젝트 안에서만 뜻이 있다 — 같은 id(`local`)를 둘이 써도 A 의 암호문은 B 의 키로 풀리지 않는다.
- **서비스 중인 프로젝트끼리 같은 키 바이트를 쓰면 기동 실패**(메시지에 프로젝트 · 키 id). 공통 `skeleton.crypto.keys` 만 두고 프로젝트가 둘이면 같은 키라 이 검사에 걸린다 — 프로젝트마다 자기 키를 준다. 닫힌 프로젝트는 세지 않는다.
- 어느 프로젝트에도 키가 없으면 모듈은 passive(예전과 같다). 키가 없는 프로젝트가 암호화를 쓰면 그 프로젝트 이름이 담긴 `CryptoException`, 프로젝트 문맥 없이 부르면 `NoProjectContextException`(뒤 작업은 `forEachProject`).
- `storage-s3` 의 프로젝트 모드 `OPAQUE` 공개 URL 은 이 코덱으로 풀렸다 — 프로젝트의 키가 없으면 그 프로젝트 이름의 기동 실패.

## 결제 (`payment*`)

**결제사 키 · 웹훅 비밀은 프로젝트마다, 장부는 스키마마다.** 장부 표 · 수신함 · 작업은 프로젝트의 스키마(풀 · DB 계정)에서 돌고 뒤 작업 일꾼이 프로젝트를 차례로 돈다(위 "뒤 작업"). 목록이 비면 지금과 같다 — 결제사 모듈의 옛 게이트웨이 빈이 그대로 선다.

- **결제사 모듈이 프로젝트 모드를 따르는 한 가지 모양**: `PaymentGatewaySource`(payment) 빈 하나. `create(settings)` 가 프로젝트마다 불리고, 모듈은 `settings.bind("skeleton.payment-<결제사>", MyProps::class.java)` 로 **그 프로젝트의 키**를 묶어 게이트웨이를 만들거나(켜지 않았거나 비밀이 비었으면) `null` 을 돌려준다. 라우터(`PaymentProviderRouter.forProjects`)가 프로젝트마다 게이트웨이 목록을 만들어 두고, 부를 때 지금 프로젝트의 것으로 넘긴다. 토스 · Paddle · Stripe 가 이 모양이고, 새 결제사 모듈(네이버페이 · 카카오페이)도 `@Conditional(ProjectsConfigured)` 소스 빈 하나(+ 옛 빈에 `@Conditional(ProjectsNotConfigured)`)면 된다. 결제사 HTTP 풀(`ExternalHttpClient`)은 서버 공통이지만 키는 요청마다 그 프로젝트의 게이트웨이가 싣는다.
- **한 프로젝트에서 켠 결제사는 다른 프로젝트에 없다** — 결제 화면의 결제사 목록 · `GET /config` · 주문 만들기 · 웹훅 경로(404) 모두 지금 프로젝트의 라우터를 지난다.
- **웹훅 식별 = 도메인 + 프로젝트별 비밀값.** 웹훅 경로(`/api/v1/payments/webhooks/{provider}[/{token}]`)도 다른 요청처럼 Host 로 프로젝트가 정해진다(모르는 도메인은 421). 서명 있는 결제사(Paddle)는 **그 프로젝트의** 웹훅 비밀값으로 서명을 확인해 A 의 비밀값으로 서명한 웹훅이 B 도메인으로 오면 `401`, 서명 없는 결제사(토스)는 그 프로젝트의 `payment.webhook.path-token` 이 아니면 `404`. 수신함 줄 · 처리 작업 · 인라인 반영(`webhook.inline`)은 그 프로젝트의 스키마에서 돈다. 보호 환경 가드(약한 비밀 조각 · 토스 키 모드)는 프로젝트마다 돌고 메시지에 `project '<id>'`.
- **프로젝트마다 다른 것 / 공통인 것(판단)**
  - *프로젝트별*: 결제사 키 · 켜고 끔(`payment-*.*`), 결제사 라우팅(`payment.default-provider` · `providers.*` · `routing.country-providers` — 프로젝트마다 파는 나라가 다르다), 웹훅 비밀 조각, 암호화 키. 프로젝트가 다르면 계약 · 정산 · 키가 다르기 때문이다.
  - *공통*: 만료 · 조회 · 환불 시각(`checkout-ttl` · `reconcile.*` · `refund.*`), 웹훅 본문 상한 · 요청 한도, 운영자 경로 · 역할, 탈퇴 처리, 구독 정책(갱신 재시도 · 플랜 변경 방식 · 해지·환불 정책 · 일할 시간대). 인프라 정책이라 서버가 하나로 정하고, 프로젝트 `config` 에 쓰면 기동 실패라 조용히 무시되지 않는다. (프로젝트마다 갈라야 할 만큼 필요해지면 `ProjectConfigKeys` 에 키를 더하고 소비하는 곳을 `router.properties()` 로 읽게 한다 — 나라 지도 · 비밀 조각이 그렇게 읽는다.)
  - *앱 코드*: **플랜 · 금액 · 지급 훅**(`SubscriptionPlans` · `PaymentCatalog` · `PaymentOrderListener` · `SubscriptionRenewalGuard` · `SubscriptionUsageCheck`)은 앱이 구현하는 빈이다. 호출은 그 프로젝트 문맥 안에서 일어나므로 구현이 `ProjectContext.current()` 로 프로젝트를 보고 가른다. 한 빈이 프로젝트마다 다른 플랜을 내는 모듈 쪽 라우팅은 아직 없다.
- **빌링키 암호화는 프로젝트의 암호화 키로**(위 `crypto`). 빌링 능력이 있는 결제사(토스)를 켠 프로젝트에 암호화 키가 없으면 카드를 받은 뒤가 아니라 **기동 때** 그 프로젝트 이름으로 실패한다.
- **결제사 계정 · 키는 프로젝트마다 따로 — 기동이 강제한다.** 공통 `skeleton.payment-*.*` 는 프로젝트로 내려가지만(공통 위에 덮는다), 결제사를 켠 프로젝트가 **비밀 값(토스 `secret-key`, 카카오페이 `secret-key` · `cid-secret`, 네이버페이 `client-secret`, Paddle `api-key` · `webhook-secret`, Stripe `secret-key`)을 자기 `config` 에 적지 않고 공통에서 물려받으면 기동 실패**다(그러면 그 프로젝트 주문이 다른 프로젝트의 상점 계정으로 결제되고 웹훅이 엉뚱한 스키마로 간다). **서비스 중인 두 프로젝트의 비밀 값이 같아도 기동 실패**(상속이든 일부러든 같다). 서명 없는 결제사(토스)의 웹훅 주소 비밀 조각 `payment.webhook.path-token` 도 같은 규칙. 이 검사는 결제사 이름을 나열하지 않는다 — 결제사 모듈의 `PaymentGatewaySource.secretKeys`(비밀 값의 설정 키 목록)가 선언한 것을 라우터가 본다(새 결제사 모듈은 그 목록만 채우면 된다). 닫힌 프로젝트와 그 결제사를 켜지 않은 프로젝트는 보지 않는다. 공통에 결제사 키가 있으면 지우고 프로젝트마다 적는다(프로젝트가 몇 개든 상속은 막힌다). 비밀은 아니지만 짝인 `payment-toss.client-key` · `payment-paddle.client-token` · 네이버페이 `payment-naverpay.client-id` · `payment-naverpay.chain-id` · `payment-naverpay.return-url` · 카카오페이 `payment-kakaopay.cid`(가맹점 코드) · `payment-kakaopay.return-url`(구매자가 돌아올 프로젝트 화면 - 물려받으면 구매자가 다른 프로젝트 화면으로 돌아간다) 와 토스 `payment-toss.easy-pays`(상점 계약 MID 가 프로젝트마다 다르다)도 물려받으면 기동 실패다(`PaymentGatewaySource.ownKeys` — 결제창은 공통 상점의 클라이언트 키로 열리는데 승인은 이 프로젝트 시크릿 키로 시도되어 결제가 계속 실패한다). 같은 값 검사는 비밀 값에만 한다.
- **앱이 직접 둔 `PaymentGateway` · `TextEncryptor` 빈은 모든 프로젝트가 같이 쓴다**(한 계정의 키 · 하나의 암호화 키) — 프로젝트 모드에서는 기동 경고, 보호 환경(`skeleton.env=stage|prod`)에서는 기동 실패(DeployGuard `payment` · `crypto`). 프로젝트마다 갈리게 하려면 앱 빈 대신 `PaymentGatewaySource` · 프로젝트별 `crypto.keys` 를 쓴다. (예전에는 이런 앱이 프로젝트가 둘이면 `ProjectUnawareFeatures` 가드로 기동 실패했다.)
- **결제사가 우리 키를 거절했다는 경보**(`PaymentProviderCredentialAlert`)는 게이트웨이를 만들 때 잡아 둔 프로젝트의 문맥으로 불린다 — 결제사 응답 스레드에는 프로젝트가 없어서 그러지 않으면 경보가 사라지거나 프로젝트가 빠진다.
- 프로젝트 모드에서는 앱이 `paymentWebhookDeployGuard` 라는 이름으로 덮어쓴 가드가 쓰이지 않는다 — 프로젝트 모드의 가드 빈 이름은 `paymentProjectWebhookDeployGuard`(덮으려면 그 이름으로).
- 한계: `SubscriptionPlans`/`PaymentCatalog` 같은 앱 빈을 프로젝트마다 둘 수 있는 라우팅은 없다.

### 프로젝트 종료 절차 — 결제 쪽 (`status: closed` 로 바꾸기 전과 후)

`closed` 는 도메인을 503 으로 막고 DB 풀 · 뒤 작업 순회에서 그 프로젝트를 뺀다 — 결제는 돈이 걸려 있어서 **순서가 있다**. 건너뛰면 결제사가 돈을 받았는데 장부가 모르거나, 웹훅이 영영 안 닿는다.

1. **새 결제 막기** (프로젝트는 아직 `active`) — 앱의 `PaymentCatalog.quote` 가 이 프로젝트의 상품을 `PaymentItemNotPurchasableException` 으로 거절하고 `SubscriptionPlans.list()` 가 이 프로젝트에서 비도록 바꿔 배포한다. 결제사 설정(`payment-*.enabled=false` · `payment.providers.<id>.enabled=false`)은 쓰지 않는다 — 둘 다 `router.gateway` 를 막아 환불 · 웹훅 처리 작업까지 멈추고, 이미 열린 결제창의 승인 · 웹훅 경로까지 닫혀 결제된 돈을 장부가 못 따라온다. 결제창이 이미 열린 주문은 `checkout-ttl`(기본 30분) 안에 끝나거나 만료된다.
2. **남은 구독 해지 · 환불** — 운영자 경로(`admin.enabled`)에서 구독을 해지하고(결제사가 갱신하는 Paddle 은 결제사 쪽 해지까지 확인) 환불할 주문을 환불한다. 운영자 목록의 「확인 필요」(`CONFIRMING` 이 오래됨 · 환불 `PENDING` · 지급/회수 미완)가 비고, 갱신 대기 구독이 0 이 될 때까지 기다린다.
3. **웹훅 마지막 처리** — 결제사 콘솔에서 이 프로젝트 도메인의 웹훅 수신(토스 웹훅 등록 · Paddle notification destination)을 끈 뒤, 이 프로젝트 스키마의 결제 작업(`payment.*` 종류)이 모두 끝났는지(대기 · 재시도 0, `DEAD` 는 사람이 확인) 본다. **이 단계가 끝나기 전에 `closed` 로 바꾸지 않는다** — `closed` 가 되면 그 프로젝트의 일꾼 순회가 멈춰 남은 작업이 영영 안 돈다.
4. **`status: closed`** — 도메인이 503, 이후 늦게 오는 결제사 재전송은 503 이라 결제사가 재시도하다 포기한다(3 에서 끈 뒤라 정상).
5. **키 회수** (이 순서) — ① 결제사 키(토스 시크릿 키 · Paddle API 키와 웹훅 비밀값 · Stripe 키)를 각 결제사 콘솔에서 폐기하고 설정(`payment-*.*` · `payment.webhook.path-token`)에서 지운다. ② 장부 스키마는 법정 보존 기간 동안 읽기 전용으로 남기고(DB 계정에서 쓰기 권한을 거둔다), 그 안의 암호문(빌링키)은 이미 ①로 결제사에서 무효라 풀어도 쓸모가 없다. ③ `crypto.keys` 는 보존 기간이 끝나 스키마를 지울 때 함께 폐기한다 — 암호문이 남아 있는 동안 키를 먼저 지우면 장부의 빌링키 칸을 읽을 수 없게 된다(결제 이력 자체는 평문이라 읽힌다).

## 찍은 프로젝트에서 프로젝트 여럿 켜기

`scripts/new-project.sh` 로 찍은 프로젝트에도 이 문서 · 견본 · 스크립트가 그대로 따라온다(`apps/api/src/main/resources/application-multi.yml`, `scripts/dev-multi.sh`, `scripts/project-account.sql`, `ProjectHello`). 켜는 순서:

1. **모듈 고르기** — 위 표의 모듈만 두 프로젝트 이상과 함께 쓸 수 있다. Kafka 를 골랐다면 프로젝트는 하나만 서비스 중이어야 한다. 결제 · 암호화는 프로젝트마다 자기 키를 준다(위 "결제").
2. **설정** — `application-multi.yml` 을 앱 설정으로 옮겨 `skeleton.projects.<id>.{hosts, schema, datasource, config}` 를 채운다. 찍으면 접두사가 바뀌므로(`skeleton.` → 새 접두사) 키는 `<접두사>.projects` 가 된다. `config` 에는 위 표의 키만 쓸 수 있고(그 밖은 기동 실패), 비밀 값은 환경변수로 넣는다("환경변수로만 넣기"). 공통 결제사 키(sample 의 `payment-toss.secret-key` · `easy-pays` 등)는 지우고 프로젝트마다 적는다 — 공통에서 물려받으면 기동 실패다.
   프로젝트마다 필수인 값: `account.mail.link-base-url`, `notification-mail.from`(메일을 쓸 때), 캡차를 켰다면 `captcha-turnstile.secret-key`, `storage.web.key-secret`(OPAQUE 일 때, 서로 달라야 한다), `storage.web.guest.secret`(비회원 업로드를 켰을 때, 서로 달라야 한다), 버킷을 나눠 쓰면 `storage-s3.key-prefix`, 암호화를 쓰면 `crypto.keys.<id>`(서로 다른 키 바이트), 결제를 쓰면 `payment-<결제사>.*` · `payment.webhook.path-token`(서명 없는 결제사).
3. **DB 계정 · 스키마** — 프로젝트마다 `scripts/project-account.sql` 로 만든다(운영) 또는 `scripts/dev-multi.sh`(로컬).
4. **도메인** — 로컬은 `*.localhost`. X 로그인은 `localhost` 콜백을 거부하므로 한 프로젝트의 hosts 에 `127.0.0.1` 을 넣어 그 주소로 연다.
5. **확인** — 기동 로그에서 서비스 중인 프로젝트마다 소셜 · 저장소 · 약관 검사가 통과하는지 본다(잘못된 설정은 메시지에 `project '<id>'` 가 붙은 기동 실패다).

## 견본: `multi` 프로필

`apps/api` 에서 `--spring.profiles.active=local,multi` → `a.localhost` · `b.localhost` (hosts 파일 없이 `*.localhost` 가 127.0.0.1). 스키마 `proj_a` · `proj_b`, 시드 `user@a.localhost` · `user@b.localhost` (password), 같은 경로 `/api/v1/project-hello` 가 도메인마다 다른 컨트롤러. 기본 `local` 프로필은 지금처럼 한 서비스다.

```
curl http://a.localhost:8080/api/v1/project-hello   # project a
curl http://b.localhost:8080/api/v1/project-hello   # project b
```

## 프런트가 맞출 것 (이번 범위 밖)

- 브라우저가 보내는 **Host 가 곧 프로젝트**다. 프런트 개발 서버(Vite)가 `/api` 를 프록시할 때 원래 Host(`a.localhost:5173`)를 그대로 전달해야 한다(`changeOrigin: false` — 포트는 서버가 무시한다). 운영은 Caddy 가 `X-Forwarded-Host` 를 넘긴다(`ForwardedHeaderFilter` 가 푼 뒤 판정).
- CORS 출처 · 쿠키 이름은 프로젝트의 `config` 에 있다. 메일 링크 주소(`account.mail.link-base-url`)도 프로젝트마다.

## 분리

목록에 그 프로젝트만 남기고(나머지 삭제 또는 `closed`) 같은 DB 의 그 스키마에 붙이면 혼자 뜬다 — `MultiProjectSeparationRehearsalTest` 가 함께 띄워 만든 계정으로 혼자 띄운 서버에서 로그인되는 것을 확인한다.

## 환경변수로만 넣기

프로젝트 값은 환경변수만으로도 묶인다(`config` 는 Map 이다) — 시험으로 확인했다(`ProjectRegistryTest`):

```
SKELETON_PROJECTS_A_HOSTS_0=a.example.com
SKELETON_PROJECTS_A_SCHEMA=a
SKELETON_PROJECTS_A_DATASOURCE_POOLMAX=5
SKELETON_PROJECTS_A_CONFIG_AUTH_JWT_SECRET=…
SKELETON_PROJECTS_A_CONFIG_ACCOUNT_MAIL_LINKBASEURL=https://a.example.com
SKELETON_PROJECTS_A_CONFIG_ACCOUNT_SEED_ACCOUNTS_0_EMAIL=…
```

함정: 환경변수 이름의 `_` 는 `.` 로 바뀌므로 **이름 안에 하이픈이 든 마디는 하이픈 없이** 쓴다 — `link-base-url` → `LINKBASEURL`, `pool-max` → `POOLMAX`, `auth-session` → `AUTHSESSION` (`..._CONFIG_AUTHSESSION_COOKIE_NAME`). `LINK_BASE_URL` 로 쓰면 `link.base.url` 이라 다른 키다 — `auth_session` 처럼 지원 접두사를 벗어나는 것은 기동 실패로 걸리지만 `account.mail.` 아래의 오타는 조용히 안 먹는다(그래도 링크 주소는 프로젝트마다 필수라 prod 가드가 잡는다). 파일 하나로 받는 길은 열지 않았다(필요 없었다).

## DB 계정 (프로젝트 모드에서 필수)

PgBouncer 트랜잭션 모드(PlanetScale 기본, 포트 **6432**)는 트랜잭션마다 실제 연결을 바꿔 끼워 연결 단위 `SET search_path` 가 따라가지 않고, 계정을 같이 쓰면 실제 연결이 프로젝트끼리 돌려 쓰여 A 의 요청이 B 의 스키마에 붙을 수 있다.
그래서 **프로젝트마다 계정을 나누고 계정에 기본 스키마를 박는다**(`ALTER ROLE … SET search_path`) — PgBouncer 가 계정별로 풀을 나누고, 붙을 때마다 그 스키마가 걸린다. (PlanetScale: 앱의 일반 연결은 6432(PgBouncer), `LISTEN` 같은 세션 기능이 필요한 연결만 5432 직접.)

기동 때(마이그레이션 뒤, 활성 프로젝트마다) 앱이 확인하고 어기면 **기동 실패**(메시지에 프로젝트 id)한다 — `ProjectAccountsVerifier`:
1. 두 프로젝트가 같은 DB 계정이면 실패 — `datasource.username` 이 없어 공통 `spring.datasource` 계정으로 떨어지는 것도 같은 계정이다.
2. (PostgreSQL) 풀 설정이 아니라 **새 연결**의 `current_schema()` 가 자기 스키마가 아니면 실패 — 계정 기본값 누락. (계정 이름이 스키마 이름과 같으면 PostgreSQL 기본값 `"$user"` 가 우연히 맞을 수 있다 — 그래도 `ALTER ROLE` 을 박는 것이 규칙이다.)
3. 그 계정이 다른 활성 프로젝트 스키마에 `USAGE` 가 있으면 실패 (`has_schema_privilege`).
- MySQL: 1, 3 만 가능한 범위로 본다(다른 프로젝트 데이터베이스의 표가 보이면 실패). 2 에 해당하는 것은 없다 — 풀이 catalog 를 고르고, PgBouncer 같은 연결 바꿔 끼우기 문제가 없는 구성을 전제한다. 한계다.
- 확인은 프로젝트의 **실제 풀 연결**로 한다. 프로젝트 모드에서 공통 `spring.datasource.hikari` 에 `schema` · `connection-init-sql` · `data-source-properties.currentSchema`/`options` 가 있으면(연결마다 스키마를 덮어쓴다) 기동 실패.
- 이 확인은 모든 빈이 만들어진 직후 돈다. 약관 기동 검사(`legalStartupCheck`)도 같은 때 돌고 둘의 순서는 빈 등록 순서에 기댄다(어느 쪽이 먼저든 계정이 틀리면 기동은 실패하고 메시지만 달라진다).
- 프로젝트 목록이 비면(한 서비스) 아무 검사도 없다.

### 운영에서 만들기 (관리자 계정으로, 프로젝트마다 한 번 — 멱등)

`scripts/project-account.sql` 이 정본이다(로컬 `scripts/dev-multi.sh` 와 시험이 같은 틀을 쓴다). 중괄호 두 겹 자리(계정 · 비밀번호 · 스키마)를 바꿔 해당 데이터베이스에서 돌린다:

```sql
do $$
begin
  if not exists (select from pg_roles where rolname = '<계정>') then
    create role "<계정>" login password '<비밀번호>';
  end if;
end
$$;
alter role "<계정>" with login password '<비밀번호>';
create schema if not exists "<스키마>" authorization "<계정>";
alter schema "<스키마>" owner to "<계정>";
alter role "<계정>" set search_path = "<스키마>";
```

그 뒤 `datasource.username/password` 에 계정을 적는다. 다른 프로젝트 스키마에는 권한을 주지 않는다. 로컬은 `scripts/dev-multi.sh` 가 Docker Postgres 에 `proj_a` · `proj_b` 를 만들고(`public` 은 건드리지 않는다, 비밀번호는 로컬 전용 `<계정>_dev_pw`) `multi` 프로필로 띄운다.
