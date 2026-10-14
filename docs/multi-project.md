# 한 프로세스에 프로젝트 여럿

부품(빈)은 한 벌, 프로젝트마다 다른 것은 **설정과 데이터**뿐이다. 앱 설정의 `skeleton.projects` 가 비어 있으면(기본) 아무 일도 일어나지 않고 앱은 서비스 하나다.

```yaml
skeleton:
  projects:
    ovation:
      hosts: [ovation.fans, api.ovation.fans]   # 요청의 Host 로 프로젝트를 정한다 (ForwardedHeaderFilter 가 푼 뒤)
      schema: ovation                          # DB 스키마 (MySQL 은 데이터베이스) — 프로젝트마다 하나
      datasource: { username: ovation, password: ${OVATION_DB_PASSWORD}, pool-max: 3 }   # 선택. 비우면 공통 spring.datasource.*
      config:                                  # skeleton.* 아래 키 — 공통 값 위에 덮어 같은 properties 클래스로 바인딩한다
        auth.jwt.secret: ${OVATION_JWT_SECRET}
        account.mail.brand: Ovation
```

- 모르는 Host → `421 COMMON.UNKNOWN_HOST`. `/health` · `/info` 는 프로젝트 없이 지나간다(헬스체크는 `localhost` 로 온다).
- 같은 Host 를 두 프로젝트가 쓰거나 스키마가 겹치면 기동 실패.
- 요청 밖(뒤 작업)은 `ProjectContext.runIn(project) { … }` 로만 프로젝트를 정한다. 문맥 없이 `current()` 는 `NoProjectContextException`.
- 프로젝트마다 한 번 만드는 부품: `ProjectRegistry.scopedFrom(prefix, Props::class.java) { project, props -> … }`.

## DB (프로젝트마다 스키마)

- 서버 하나, 프로젝트마다 연결 풀 하나(`persistence-jdbc` 의 `ProjectRoutingDataSource`). 풀은 처음 쓸 때 열고(안 쓰면 연결 0), 최대 `datasource.pool-max`(기본 3), 최소 유지 0. 같은 서버 URL(`spring.datasource.*`)에 프로젝트의 스키마를 고른다(PostgreSQL `search_path`, MySQL 은 데이터베이스). 프로젝트별 DB 계정(`datasource.username/password`)을 주면 자기 스키마 밖은 DB 가 막는다.
- **프로젝트 문맥 없이 연결을 꺼내면 예외**(`NoProjectContextException`) — 기본 스키마로 가지 않는다. 트랜잭션이 열린 뒤 프로젝트가 바뀌면 문장을 만들 때 예외.
- 기동 때 `migration-flyway` 가 스키마마다 같은 스크립트를 돌린다(이력도 스키마마다, 하나라도 실패하면 기동 실패). 스키마가 없으면 Flyway 가 만든다 — DB 계정에 권한이 없으면 운영자가 미리 만든다. `public` 은 건드리지 않는다.
- `SqlDialectVerifier` 는 기동 때 연결을 열지 않고 URL 로 방언을 본다. `/health` 는 프로젝트별 풀을 하나씩 본다(Boot 의 라우팅 DataSource 지원).
- `status: closed` 인 프로젝트: 도메인은 `503 COMMON.PROJECT_CLOSED`, 풀 · 마이그레이션 · "프로젝트 둘 이상" 개수에서 빠진다(스키마는 안 적어도 된다).

## 로그인 쪽 설정을 프로젝트별로

`config` 에 적은 `skeleton.*` 키는 공통 값 위에 덮여 **같은 properties 클래스**로 바인딩된다 (`ProjectRegistry.bind` / `scopedFrom`). 지금 프로젝트별로 갈리는 것:

| 무엇 | 키 (config 아래) | 어떻게 |
|---|---|---|
| JWT 키 · 발급자 · TTL | `auth.jwt.*` | 프로젝트마다 `JwtTokenService` 부품. 토큰에 프로젝트 이름(`prj`)이 들어가고 받을 때 대조 — A 토큰은 B 에서 거절(키를 같이 써도). 잘못된 비밀은 기동에서 막는다 |
| 리프레시 쿠키 | `auth-session.cookie.*` | `RefreshTokenDelivery` 가 지금 프로젝트의 이름 · 경로 · 보안 속성을 쓴다 |
| CORS 허용 출처 | `web.cors.*` | 요청의 프로젝트가 고른다 (`skeleton.web.cors.enabled=true` 일 때) |
| 계정 메일 링크 주소 · 브랜드 · 제목 접두사 | `account.mail.*` | 내장 메일 틀은 프로젝트마다 한 번 만든다 (앱이 자기 `AccountMailTemplates` 빈을 두면 그것을 모두가 쓴다) |
| 시드 계정 | `account.seed.accounts[n].*` | 기동 때 프로젝트마다 그 스키마에 만든다 |

**위 표의 키만 프로젝트 `config` 에 쓸 수 있다**(`ProjectConfigKeys.SUPPORTED`) — 그 밖의 키(`account.sign-up.enabled`, `legal.facts.*`, `web.rate-limit.*`, `web.cors.enabled` …)는 조용히 무시되는 대신 기동 실패다. 프로젝트별 `auth-session.delivery` 도 받는다(`/auth/methods` 는 지금 프로젝트의 방식을 알린다).

- 계정 메일 작업(확인 코드 · 재설정 · 매직 링크)은 `account-task` 풀로 넘어가지만 제출한 요청의 프로젝트를 들고 간다(스레드가 물려받지는 않는다).
- **프로젝트 모드에서 `account.mail.link-base-url` 은 프로젝트마다 필수**다 — 공통 값으로 내려가지 않는다(다른 프로젝트 도메인으로 링크가 나갈 수 있다). stage · prod 가드가 프로젝트마다 본다.
- stage · prod 가드는 활성 프로젝트마다 시드 계정 · `log-links=ON`(계정), 쿠키 `secure=false`(auth-session)를 같은 규칙으로 본다 — 메시지에 `project '<id>'`.
- 앱이 자기 `AccountMailTemplates`/`AccountMailLayout` 빈을 두고 활성 프로젝트가 둘 이상이면 모든 프로젝트가 같은 문구 · 틀을 쓴다는 경고를 남긴다(기동 실패는 아님 — 의도일 수 있다).
- 약관 보존 기한 정리의 "마지막으로 비운 때"는 프로젝트마다 둔다.
- MVC 비동기(`Callable` · `DeferredResult` · `SseEmitter`)에서도 프로젝트가 유지된다(`ProjectFilter` 가 비동기 재진입에도 돌고, MVC 실행기에 `ProjectTaskDecorator`).

뒤 주기 작업 중 계정 삭제 유예 정리 · 세션 정리는 프로젝트를 차례로 돈다. 그 밖의 값(가입 정책 · 비밀번호 정책 · 한도 …)은 아직 공통이다.


## 프로젝트 전용 API (`@ProjectApi`)

```kotlin
@ProjectApi("ovation") annotation class OvationApi          // 프로젝트 모듈이 한 번 만든다
@RestController @RequestMapping("/api/v1/cheers") @OvationApi class CheerController { … }
```

- 문자열은 `skeleton.projects.<이름>` 의 이름. 다른 프로젝트 도메인으로 부르면 404 (`RequestMappingHandlerMapping` 의 요청 조건 — 컨트롤러 안 if 가 아니다). 두 프로젝트가 **같은 경로**를 써도 기동 충돌이 없다.
- 목록에 없는 이름이 붙은 컨트롤러는 어느 도메인에서도 열리지 않고 기동 로그에 경고(실패 아님 — 빌드에는 있지만 이 서버가 안 켠 프로젝트일 수 있다).
- **목록이 비면 표시는 무시되고 전부 열린다.** 한 프로젝트를 떼어 혼자 띄워도 코드 수정이 없다 — 단 같은 경로를 쓰는 다른 프로젝트의 컨트롤러가 같은 빌드에 있으면 한 서비스에서는 모호하다(견본은 그래서 `@Profile("multi")`).
- 프로젝트 모듈끼리는 서로의 코드 · 표를 부르지 않는다(떼어 낼 수 있게).

## 아직 프로젝트를 모르는 기능

뒤 작업 일꾼(job-queue) · 결제 · 소셜 · 메일 발신 · 파일 · 알림 · 경보 · 멱등 · Redis · 캡차 · 암호화 · Kafka · 스케줄러 · async 가 **빌드에 들어 있고 서비스 중인 프로젝트가 둘 이상**이면 기동이 실패한다(한 곳, `ProjectUnawareFeatures`). 해결: 모듈을 빼거나 나머지를 `status: closed` 로. 모듈이 프로젝트를 알게 되면 표에서 뺀다.

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
