# kotlin-skeleton

Kotlin + Spring Boot 백엔드 스켈레톤. 새 API 프로젝트 시작점.

## Module Layout

This skeleton uses coarse-grained Gradle modules.

```text
apps/
  api                 # executable Spring Boot app

modules/
  platform            # web, errors, trace/logging, shared infrastructure
  auth                # stateless auth, JWT, dev-login, break-glass access
  auth-social         # optional social-login extension for auth
```

Use modules as capability choices:

- `apps/api` composes the runnable application.
- `modules/platform` is the shared foundation for most apps.
- `modules/auth` is included when the app needs authentication. Its default beans are Spring Boot auto-configuration defaults, so an app can replace `AuthAccountRepository`, `SecurityFilterChain`, token service, or filters with its own beans.
- `modules/auth-social` is included when the app needs social login. Its default beans are also auto-configuration defaults, so provider clients, account links, provisioning policy, and the social auth handler can be replaced.

Fine-grained details such as JWT, password login, OAuth, or dev login live as packages inside their capability modules unless they grow into provider-level integrations.

## Auth Capability

`modules/auth` provides stateless backend auth defaults:

- `POST /api/v1/auth/login` issues a JWT bearer token.
- `GET /api/v1/auth/me` returns the authenticated `CurrentPrincipal`.
- JWT claims use `sub=accountId`, `username`, `email`, `roles`, `iss`, `iat`, and `exp`.
- Successful auth responses use the shared success envelope, and auth failures return the shared `ApiError` shape with trace/span fields.

Development seed accounts:

| accountId | username | email | password | roles |
| --- | --- | --- | --- | --- |
| `acc_user` | `user` | `user@example.com` | `password` | `USER` |
| `acc_admin` | `admin` | `admin@example.com` | `password` | `USER`, `ADMIN` |

Example:

```bash
curl -s http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"password"}'
```

Local/dev header login can be enabled only for `local` or `dev` profiles:

```yaml
skeleton:
  auth:
    dev-login:
      enabled: true
```

Supported headers are `X-Dev-Account-Id`, `X-Dev-Username`, and `X-Dev-Email`. Roles are always loaded from `AuthAccountRepository`; role headers are ignored.

Break-glass access is disabled by default and is intended for audited emergency access:

```yaml
skeleton:
  auth:
    break-glass:
      enabled: true
      secret: ${BREAK_GLASS_SECRET}
      allowed-account-ids:
        - acc_admin
```

Requests must include `X-Break-Glass-Secret`, `X-Break-Glass-Reason`, and `X-Break-Glass-Account-Id`. In `prod` and `staging`, startup validation requires a nonblank secret and allowlist. Keep YAML thin; inject secrets through environment variables.

## Auth Social Capability

`modules/auth-social` is an optional social-login extension for `modules/auth`.

- `POST /api/v1/auth/social/{provider}/login` exchanges a frontend-provided authorization code through an enabled provider.
- The `value` response payload is the same as password login: bearer token, expiration, and `CurrentPrincipal`.
- Provider identity maps to an internal account through `OAuthAccountLinkRepository`.
- Roles always come from `AuthAccountRepository`, not provider profile data.
- Google, Kakao, and Naver live under `auth-social/providers/*` when real provider clients are added. They are not separate Gradle modules yet.

First-slice tests use a fake provider. Real provider credentials should be supplied through environment variables:

```yaml
skeleton:
  auth-social:
    providers:
      google:
        enabled: true
        client-id: ${GOOGLE_OAUTH_CLIENT_ID}
        client-secret: ${GOOGLE_OAUTH_CLIENT_SECRET}
```

## 스택

- Kotlin 2.2 / JDK 21
- Spring Boot 4.0
- Spring Data JDBC + Flyway
- MySQL 8.4
- Gradle (Kotlin DSL)

## 빠른 시작

```bash
# 로컬 DB만 띄우기
docker compose up -d mysql

# 앱 실행 (호스트 JDK 사용)
./gradlew bootRun
```

`http://localhost:8080/health` → `{"status":"UP"}`

풀스택 컨테이너 기동:

```bash
docker compose up -d
```

## REST 컨벤션

- 기본 API 네임스페이스: `/api/v1/*` (컨트롤러에서 `@RequestMapping("/api/v1/...")`)
- 헬스체크: `/health` (Spring Boot Actuator)
- 성공 응답은 `modules/platform` 의 envelope DTO를 사용한다.
  - 단건: `ApiResponse.value(dto)` → `{ "value": ..., "meta": ... }`
  - 목록: `ApiResponse.list(items)` → `{ "values": [...], "meta": ... }`
  - 페이지: `ApiResponse.page(items, pagination)` → `{ "values": [...], "pagination": ..., "meta": ... }`
- `meta` 에는 현재 요청의 `traceId`, `spanId`, `timestamp` 가 들어간다.
- 에러 응답은 기존 `ApiError` shape를 유지한다.

## 사용법

이 레포는 **GitHub Template**. 새 프로젝트 시작:

1. GitHub 레포 페이지 → **Use this template** 버튼
2. 또는 `gh repo create <name> --template sumin/kotlin-skeleton --private`
