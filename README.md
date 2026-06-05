# kotlin-skeleton

Kotlin + Spring Boot 백엔드 스켈레톤. 새 API 프로젝트 시작점.

## Module Layout

This skeleton uses coarse-grained Gradle modules.

```text
apps/
  api                 # executable Spring Boot app

modules/
  platform            # web, errors, trace/logging, shared infrastructure
  auth                # authentication capability contracts and future login support
```

Use modules as capability choices:

- `apps/api` composes the runnable application.
- `modules/platform` is the shared foundation for most apps.
- `modules/auth` is included when the app needs authentication.

Fine-grained details such as JWT, password login, OAuth, or dev login live as packages inside `modules/auth` unless they grow into provider-level integrations.

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

## 사용법

이 레포는 **GitHub Template**. 새 프로젝트 시작:

1. GitHub 레포 페이지 → **Use this template** 버튼
2. 또는 `gh repo create <name> --template sumin/kotlin-skeleton --private`
