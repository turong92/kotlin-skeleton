# 배포 — 홈서버 배포 계약과 배포 가드

이 레포는 앱을 **이미지 하나 + 선언 한 장**으로 내놓는다. 컨테이너 · 네트워크 · DB · Redis · Caddy 사이트 · 터널 · 백업은 홈서버 플랫폼이
선언에서 만든다 — 그래서 이 프로젝트에는 운영용 compose · Caddyfile · cloudflared 설정 · 백업 선언이 **없고 두지도 않는다**.
(`docker-compose.yml` 은 로컬 개발용이다.)

| 만드는 쪽 | 하는 일 |
|---|---|
| 이 레포 | 계약을 지키는 `Dockerfile` · `deploy/app.yaml`(선언) · GHCR 이미지 워크플로 · 배포 가드 · 계약 증명 스크립트 |
| 플랫폼(홈서버 레포 `infra/modules/app-docker`) | 선언을 읽어 컨테이너 · DB · Redis · 비밀 · Caddy · 터널을 만든다 |

## 1. 배포 흐름 — tag 를 올리는 것이 배포다

```
git tag v0.1.1 && git push origin v0.1.1     # .github/workflows/image.yml → ghcr.io/<owner>/<name>-api:v0.1.1 · :sha-<커밋 12자>
deploy/app.yaml 의 tag: v0.1.1 로 올려 플랫폼에 적용   # 컨테이너가 그 이미지로 교체된다
```

태그는 불변이다 — `latest` 는 만들지도 쓰지도 않는다. 롤백은 `tag` 를 이전 값으로 되돌리는 것이다.

## 2. 선언 `deploy/app.yaml`

```yaml
name: ovation              # ^[a-z][a-z0-9-]{1,19}$
image: ghcr.io/<owner>/ovation-api
tag: v0.1.0                # 불변 태그
port: 8080
health: /actuator/health
env_prefix: OVATION        # ^[A-Z][A-Z0-9_]*$ — 프로젝트의 설정 접두사의 대문자
api_prefix: /api/v1
db: postgres               # postgres | mysql | none
redis: false
secrets: [JWT_SECRET]
env: { OVATION_ENV: prod, SPRING_PROFILES_ACTIVE: prod }
```

`scripts/new-project.sh` 가 찍을 때 이렇게 채운다 (rename 스크립트가 이름 · 접두사를 바꾼다):

| 칸 | 찍을 때 |
|---|---|
| `name` · `image` | `<config-prefix>` · `ghcr.io/OWNER/<config-prefix>-api` — **`OWNER` 는 직접 채운다**(소문자 계정 · 조직). 안 채우면 플랫폼의 image 검사가 거부해 조용히 틀린 곳에서 당기지 않는다 |
| `env_prefix` | 설정 접두사의 대문자 (`my-app` → `MY_APP`) |
| `db` | `--db mysql` 이면 `mysql`, 아니면 `postgres` |
| `redis` | `redis-*` 모듈을 골랐으면 `true` |
| `health` · `api_prefix` | `/actuator/health` · `/api/v1` |
| 모듈별 비밀 설명 | 고른 모듈의 줄만 남는다 (§7) |

`name` 은 2~20자여야 한다 — 설정 접두사가 더 길면 `new-project.sh` 가 알리고, 선언의 `name` 만 줄인다.
`web` · `domain` 은 주석으로 있다 (§8).

## 3. 계약과 이 레포가 지키는 방법

| 계약 | 이 레포 | 증명 |
|---|---|---|
| 컨테이너 포트 8080, `0.0.0.0` 에서 듣는다, 호스트 포트 없음 | `server.address` 를 정하지 않는다(스프링 기본 = 모든 인터페이스). `EXPOSE 8080` 은 문서일 뿐 호스트에 열지 않는다 | `test-deploy-contract.sh`: 같은 네트워크의 다른 컨테이너가 `app:8080` 에 닿고, `netstat` 의 듣는 소켓이 모든 인터페이스 |
| 헬스체크는 **컨테이너 안에서** `wget -S -q -O /dev/null http://localhost:<port><health>` 를 돌려 2xx · 401 · 403 을 살아 있음으로 본다 (시작 유예 1분) | 런타임 이미지가 `eclipse-temurin:21-jre-alpine` — busybox `wget` 이 있다. slim · distroless 로 바꾸지 않는다 | 플랫폼의 `healthcheck_test` 와 같은 문장을 컨테이너 안에서 돌려 통과를 본다 |
| DB 는 `SPRING_DATASOURCE_URL` · `_USERNAME` · `_PASSWORD` 로 온다 (앱 접두사가 붙은 DB 변수는 없다) | 앱 yml 이 정확히 그 이름을 읽는다 (`${SPRING_DATASOURCE_URL:…}`) | 계약의 변수만 준 컨테이너가 DB 에 붙어 뜬다 |
| Redis 는 `<ENV_PREFIX>_REDIS_HOST` `_PORT` `_SSL_ENABLED` `_KEY_PREFIX` `_PASSWORD`; `redis: false` 면 `<ENV_PREFIX>_REDIS_LOCK_ENABLED=false` 하나 | 느슨한 바인딩이 `skeleton.redis.*` ← `SKELETON_REDIS_*`, rename 이 `SKELETON_` 를 프로젝트 접두사로 바꾼다 (§5) | `RedisEnvironmentVariablesTest` · `RedisLockEnvironmentVariablesTest` 가 **찍은 프로젝트의 빌드에서** 새 접두사로 돈다 |
| 로그는 stdout 만 | 파일 로깅 설정이 없다 (`logging.file.*` 없음) | `docker logs` 에 기동 로그가 있고 `docker diff` 에 새 `*.log` 가 없다 |
| 비밀은 이름만 선언하고 환경변수로 주입된다 | 비밀은 yml 에 값을 두지 않는다 — `JWT_SECRET` 별칭이 `skeleton.auth.jwt.secret` 으로 간다 | 보호 환경에서 기본 비밀로 뜨면 가드가 이름만 말하고 실패한다 (값 없음) |
| 쓰지 않는 모듈의 설정을 요구하지 않는다 | 모듈은 켜도 설정 없이 뜬다 (`docs/minimal-composition.md` §3). `Dockerfile` 은 `apps/workbench`(모든 모듈 데모) 빌드를 거부한다 | 기본 앱 기동 로그에 자격 증명 · 설정 요구가 없다 |

증명은 `scripts/test-deploy-contract.sh` (CI: `.github/workflows/deploy-contract.yml`):
앱마다 이미지를 빌드하고 일회용 `postgres:18` 과 함께 **계약의 환경변수만** 주어 띄운다. marina 가 도커를 가로채는 기계에서는 `MARINA_DIRECT=1`.

```bash
MARINA_DIRECT=1 scripts/test-deploy-contract.sh                # apps/api · apps/sample (이 프로젝트에 있는 앱)
MARINA_DIRECT=1 scripts/test-deploy-contract.sh --apps api --reuse
```

### 어느 앱을 배포하나

| 앱 | 빌드 | `health` | 비고 |
|---|---|---|---|
| `apps/api` (스타터) | `docker build -t … .` | `/actuator/health` | 인증이 모든 경로를 막으므로 헬스는 401 — 계약상 "살아 있음" |
| `apps/sample` | `docker build --build-arg APP=sample -t … .` | `/health` | management base-path 가 `/` 라 공개 경로로 열린다 (200) |
| `apps/workbench` | 거부 | — | 모든 모듈 데모: AWS SSM · S3 · Kafka 설정을 요구한다. 배포 대상이 아니다 |

이미지 워크플로는 `APP=api` 로 빌드한다. 샘플을 배포하려면 워크플로의 `build-args` 와 선언의 `health` 를 바꾼다.

## 4. 배포 환경 스위치와 배포 가드

### 스위치 `skeleton.env`

`skeleton.env` = `local` | `stage` | `prod` (환경변수 `SKELETON_ENV` — 찍은 프로젝트에서는 `<ENV_PREFIX>_ENV`, 선언의 `env:` 에서 준다).

- **정하지 않으면 중립이다.** 아무 가드도 이 스위치로는 막지 않는다. 스프링 프로필 기본값은 건드리지 않는다.
- 모르는 값(`production` 같은 오타)은 **항상 기동 실패** — 오타가 가드를 조용히 끄지 않게 한다. 메시지에 값은 싣지 않는다.
- `skeleton.deploy.require-env=true` 면 비었을 때도 기동 실패 ("스위치를 꼭 정한다" — 옵트인, 기본은 꺼짐).
- **스프링 프로필과의 관계**: 스위치는 프로필을 켜지 않고, 프로필은 스위치를 켜지 않는다. 둘이 어긋나면(`prod` 인데 프로필 `prod` 가 꺼짐, 또는 그 반대) 기동은 되고 **경고**만 한다 —
  Ovation 처럼 어긋남을 실패로 보고 싶으면 앱이 `DeployGuard` 하나를 둔다. 프로필로 보호하던 가드(auth `protected-profiles`, migration `clean-allowed-profiles`)는 **자기 프로필 목록으로 그대로** 동작하고, 스위치가 stage · prod 면 프로필과 상관없이 같은 규칙이 **더** 선다.

### `DeployGuard` — 모듈이 기여하는 기동 규칙

```kotlin
class StorageDeployGuard(private val properties: StorageProperties) : DeployGuard {
    override val name = "storage"
    override fun problems(context: DeployContext): List<String> =        // 서면 안 되는 것 — 하나라도 있으면 기동 실패
        if (context.protectedEnv && properties.bucket.isBlank()) listOf("SKELETON_STORAGE_S3_BUCKET is not set") else emptyList()
    override fun warnings(context: DeployContext): List<String> = emptyList()   // 서지만 알아 둘 것 — WARN 로그
}
// AutoConfiguration 의 @Bean + @ConditionalOnMissingBean 으로 등록하면 DeployGuardRunner 가 모아 돌린다
```

- `context.protectedEnv` = 스위치가 stage · prod. 프로필로 보호하던 규칙은 `context.protectedBy(자기 프로필 목록)`. 자기 환경 판단은 가드 몫이다.
- **메시지는 환경변수 · 속성 이름만** 말하고 값은 싣지 않는다.
- `DeployGuardRunner` 는 모든 빈이 만들어진 직후(웹 서버가 열리기 전)에 돌고, 실패하면 `DeployGuardFailureAnalyzer` 가 스택 트레이스 대신 가드별로 고칠 변수를 나열한 화면을 낸다.
- **액추에이터 없이 목록 보기**: 기동 로그 INFO 한 덩어리가 환경과 가드 목록을 보인다.

  ```
  deploy guards: env=prod, 3 guard(s)
    - deploy-env: ok
    - auth: ok
    - migration-clean: ok
  ```

  스위치가 비면 `env=unset (set SKELETON_ENV=local|stage|prod to opt in …)`.

| 가드 | 모듈 | 규칙 (스위치 stage · prod 일 때 또는 기존 프로필 규칙) |
|---|---|---|
| `deploy-env` | platform | `require-env` 인데 스위치가 비면 실패. 스위치와 프로필 `prod` 가 어긋나면 경고 |
| `auth` | auth | JWT 비밀이 비었거나 · 내장 기본값이거나 · 32바이트 미만 / 내장 시드 계정 저장소 사용 / dev-login 켬 / break-glass 허용 계정 없음 → 실패. 이전 `AuthStartupValidator` 와 같은 규칙이다 (이제 문제를 모두 나열한다) |
| `migration-clean` | migration | `clean-on-validation-error` · `spring.flyway.clean-disabled=false` · `spring.liquibase.drop-first=true` 가 허용 프로필 밖에서 켜지면 실패. **DB 에 닿기 전**에 막으려고 차단은 `EnvironmentPostProcessor` 가 하고, 가드 빈은 같은 규칙을 목록에 보인다 |

한계: 러너는 모든 빈이 만들어진 **뒤**에 돈다 — Flyway 마이그레이션이 가드보다 먼저 실행될 수 있다. DB 를 지우는 설정만은 위의 이른 차단이 막는다.

## 5. 환경변수 접두사가 흐르는 길

`env_prefix` 는 프로젝트의 설정 접두사의 대문자다. 스프링 느슨한 바인딩이 `<prefix>.redis.ssl.enabled` 를 `<PREFIX>_REDIS_SSL_ENABLED` 로 받고, rename 스크립트가 코드 · yml · 문서 · 테스트의 `skeleton.` · `SKELETON_` 를 프로젝트 접두사로 바꾼다.

| 플랫폼이 넣는 변수 | 스켈레톤 | 프로젝트(`ovation`) | 설정 키 |
|---|---|---|---|
| `<ENV_PREFIX>_REDIS_HOST` | `SKELETON_REDIS_HOST` | `OVATION_REDIS_HOST` | `redis.host` |
| `<ENV_PREFIX>_REDIS_PORT` | `SKELETON_REDIS_PORT` | `OVATION_REDIS_PORT` | `redis.port` |
| `<ENV_PREFIX>_REDIS_SSL_ENABLED` | `SKELETON_REDIS_SSL_ENABLED` | `OVATION_REDIS_SSL_ENABLED` | `redis.ssl.enabled` |
| `<ENV_PREFIX>_REDIS_KEY_PREFIX` | `SKELETON_REDIS_KEY_PREFIX` | `OVATION_REDIS_KEY_PREFIX` | `redis.key-prefix` |
| `<ENV_PREFIX>_REDIS_PASSWORD` | `SKELETON_REDIS_PASSWORD` | `OVATION_REDIS_PASSWORD` | `redis.password` |
| `<ENV_PREFIX>_REDIS_LOCK_ENABLED` (`redis: false`) | `SKELETON_REDIS_LOCK_ENABLED` | `OVATION_REDIS_LOCK_ENABLED` | `redis-lock.enabled` |
| (선언의 `env:`) | `SKELETON_ENV` | `OVATION_ENV` | `env` |

- **prod 프로필이 SSL 을 조용히 강제하지 않는다.** `apps/workbench` 의 `application-prod.yml` 은 `redis.ssl.enabled: true`(관리형 Redis 전제)지만 **환경변수가 프로필 yml 을 이긴다** — 사설 네트워크의 평문 Redis 는 `<ENV_PREFIX>_REDIS_SSL_ENABLED=false` 로 켠다 (`ProdRedisSslEnvironmentTest` 가 진짜 yml 과 진짜 환경변수 모양으로 증명). `apps/api` · `apps/sample` 에는 prod yml 이 없어 기본 `false` 다.
- 접두사가 틀리면(`env_prefix` 가 프로젝트의 설정 접두사와 다르면) 변수는 아무 데도 닿지 않고 조용히 무시된다 — 그래서 `env_prefix` 는 `new-project.sh` 가 채운다.
- 환경변수 이름 규칙: 점은 밑줄, 대시는 밑줄 또는 삭제(`skeleton.redis-lock.enabled` ← `SKELETON_REDIS_LOCK_ENABLED` 도 `SKELETON_REDISLOCK_ENABLED` 도 받는다).

## 6. GHCR 이미지 워크플로 `.github/workflows/image.yml`

- **트리거는 `v*` 태그 푸시 하나뿐** — 브랜치 푸시 · PR 은 이미지를 올리지 않는다. 올라가는 태그는 `v<버전>` 과 `sha-<커밋 12자>` 두 개, **`latest` 는 없다**.
- 태그 모양(`vMAJOR.MINOR.PATCH[-suffix]`)을 검사하고, **이미 있는 태그는 덮어쓰지 않고 실패한다** (불변).
- 이미지 이름은 `ghcr.io/<소유자(소문자)>/<선언의 name>-api` — 선언의 `image:` 와 같아야 한다.
- **스켈레톤 레포 자체에서는 아예 돌지 않는다**: 잡 조건 `github.event.repository.is_template != true` — 이 레포는 GitHub 템플릿이고, 스켈레톤의 버전 태그(`v1.x`, CHANGELOG)는 앱 릴리스가 아니라서 그 태그가 앱 이미지를 publish 하면 안 된다. "태그일 때만" 으로 충분하지 않은 이유다.
  `Use this template` · `new-project.sh` 로 만든 프로젝트는 템플릿이 아니라서 `v*` 태그마다 돈다.
- 권한은 `packages: write` 뿐이다 (`GITHUB_TOKEN`). 패키지를 홈서버가 당기려면 패키지를 공개로 두거나 플랫폼에 읽기 토큰을 둔다 (플랫폼 몫).

## 7. 모듈별 비밀과 빠졌을 때의 동작

`secrets:` 는 **플랫폼이 무작위 값을 만들어 넣는** 비밀이다. 그래서 이 목록에는 무작위여도 되는 것(JWT 비밀)만 넣는다.
서드파티가 주는 키(결제 · SMTP · R2 · OAuth)는 값을 사람이 정하므로 이 목록으로 만들 수 없다 — 선언의 `env:` 에 값을 적으면 평문이다. 어떻게 넣을지는 플랫폼과 정할 일이다 (§9 질문).
`<P>` = `env_prefix`.

| 모듈 | 환경변수 | 빠지면 |
|---|---|---|
| auth | `JWT_SECRET` (별칭) 또는 `<P>_AUTH_JWT_SECRET` — **선언의 `secrets:`** | 보호 환경(`<P>_ENV=stage\|prod` · 프로필 `prod\|staging`)에서 **기동 실패**: 비었음 / 내장 기본값 / 32바이트 미만 |
| auth | `<P>_AUTH_BREAK_GLASS_SECRET` · `_ALLOWED_ACCOUNT_IDS` (break-glass 를 켰을 때만) | 비밀이 비면 기동 실패 · 허용 계정이 비면 보호 환경에서 기동 실패 |
| account | `<P>_ACCOUNT_MAIL_LINK_BASE_URL` (메일 링크가 여는 프론트 주소 — 비밀 아님, 선언의 `env:` 에 적어도 된다) · 선택 `<P>_ACCOUNT_BOOTSTRAP_ADMIN_EMAIL` | 보호 환경에서 **기동 실패** (`DeployGuard` `account`): 주소가 비었음 · 메모리 계정 저장소(`account-jdbc` 를 얹는다) · 메일 발송 길 없음(`notification-mail`) · 시드 계정 · 링크 로그 켬. 첫 관리자 이메일은 확인된 로그인 때 ADMIN 을 준다 |
| auth-session | (없음 — 설정만) | 보호 환경에서 메모리 세션 저장소(`auth-session-jdbc` 를 얹는다) · 쿠키 전달인데 `cookie.secure=false` 면 기동 실패 |
| auth-social-google / -kakao / -naver | `<P>_AUTH_SOCIAL_PROVIDERS_<X>_ENABLED=true` · `_CLIENT_ID` · `_CLIENT_SECRET` | 켰는데 비면 기동 실패 (`client id/secret must not be blank`) |
| captcha-turnstile | `<P>_CAPTCHA_TURNSTILE_ENABLED=true` · `_SECRET_KEY` | 검증기 빈이 없다 — 기동은 되고, 주입받는 곳이 있으면 그 빈 이름으로 실패 |
| payment-toss / -stripe | `<P>_PAYMENT_<X>_ENABLED=true` · `_SECRET_KEY` | 제공자 빈이 없다 — 결제 라우팅이 그 제공자를 못 찾는다 |
| storage-s3 | `<P>_STORAGE_S3_BUCKET` · `_ENDPOINT_OVERRIDE` · `_CREDENTIALS_ACCESS_KEY_ID` · `_CREDENTIALS_SECRET_ACCESS_KEY` | 버킷이 비면 저장소 빈이 없다. 키가 비면 AWS 기본 자격 증명 체인으로 가 **첫 사용에서** 실패(기동은 된다) |
| notification-mail | `<P>_NOTIFICATION_MAIL_ENABLED=true` · `_FROM` · `SPRING_MAIL_HOST` · `_USERNAME` · `_PASSWORD` | host · from 이 비면 발송기 빈이 없다. 비밀번호가 틀리면 첫 발송에서 실패 |
| notification-slack | `<P>_NOTIFICATION_SLACK_ENABLED=true` · `_WEBHOOK_URL` | 아무것도 보내지 않는다 |
| alert | `<P>_ALERT_WEBHOOK_URL` | 경보가 로그로만 남는다 (기동은 된다) |
| crypto | `<P>_CRYPTO_PRIMARY_KEY_ID` · `<P>_CRYPTO_KEYS_<ID>` (base64 AES 키) | 키가 없으면 암호화 빈이 없다 |
| redis-* | 플랫폼이 `<P>_REDIS_*` 를 넣는다 (`redis: true`) | §5 |
| config-aws-ssm | AWS 자격 증명 | **고르지 않는다** — 홈서버에는 AWS 가 없고, `paths` 를 정하면 부팅에서 자격 증명을 찾는다 |

## 8. 정적 프론트엔드

같은 호스트에서 정적 프론트를 서빙하려면 선언에 `web` 과 `api_prefix` 를 함께 둔다 (`web` 만 있으면 플랫폼이 거부한다).
Caddy 가 `<api_prefix>/*` 만 백엔드로 보내고 나머지는 `data/apps/<name>/<web>` 의 정적 번들을 서빙한다 (`/index.html` 폴백 — SPA).

- **프론트 빌드 산출물 디렉토리 이름이 `web:` 과 같아야 한다.** react-skeleton(Vite)의 기본은 `dist`.
- 번들을 그 디렉토리에 놓는 일은 플랫폼 밖이다 (플랫폼 문서 참고).
- 같은 origin 이라 CORS 는 필요 없다. 백엔드 경로는 `api_prefix`(`/api/v1`) 밑에 있어야 한다 — 그 밖의 경로는 정적 번들로 간다 (`apps/sample` 의 `/health` 처럼 prefix 밖인 경로는 도메인으로 닿지 않고, 컨테이너 안 헬스체크로만 쓰인다).
- **SSR(서버 렌더링) 프론트는 이 선언으로 안 된다** — `web:` 은 정적 파일 서빙뿐이다. SSR 은 자기 선언(자기 이미지 · 포트)이 따로 필요하다.

## 9. 알려진 한계 · 플랫폼에 묻는 것

- 서드파티 비밀을 넣는 길이 선언에 없다 (§7). `secrets:` 는 무작위 값만 만든다.
- 스타터의 내장 시드 계정은 `prod` 에서 거부된다 — 첫 배포 전에 앱이 자기 `AuthAccountRepository` 빈을 둬야 한다 (일부러 그렇다).
- 플랫폼의 이름 검증은 `<ENV_PREFIX>_REDIS_*` 만 막고 `_REDIS_SSL_ENABLED` · `_REDIS_LOCK_ENABLED` 는 선언 `env:` 에 쓰도록 둔다 — 같은 이름을 쓰면 주입이 뒤에서 덮는다.
