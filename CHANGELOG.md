# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### 이식 안내 — 한 줄로 찍는 프로젝트 · 설정 캡슐화 · 인증 안전 기동 (2026-10-05)

하위 앱(rename-skeleton 으로 찍은 레포)이 위에서 아래로 따라 하면 된다. 새 프로젝트는 이제 `scripts/new-project.sh` 한 줄이다 (README "새 프로젝트 시작", `docs/minimal-composition.md` §5).

**A. 인증은 보호 프로필에서 안전하지 않게 뜨지 않는다 (`modules/auth`) — 동작이 바뀌는 변경.**
`skeleton.auth.protected-profiles`(기본 `[prod, staging]`) 중 하나가 활성이면 기동이 실패한다 (메시지가 손볼 속성 · 빈 이름을 적는다):
(a) `skeleton.auth.jwt.secret` 이 비었거나, 내장 기본값(`dev-local-jwt-secret-change-me-32-bytes`)이거나, 32바이트(HS256)보다 짧다 — `JwtTokenService` 가 만들어지기 전에 막는다.
(b) 내장 메모리 `AuthAccountRepository`(시드 `user/password`, `admin/password`)가 쓰인다 — 앱이 자기 `AuthAccountRepository` 빈을 둔다.
`local` · `dev` · `test` · 프로필 없음은 그대로다. 검증은 `ApplicationRunner` 에서 `SmartInitializingSingleton` 으로 옮겨 컨텍스트 refresh 안에서 실패한다 (컨텍스트 러너로 시험된다).
`prod`/`staging` 으로 도는 앱은 `AuthAccountRepository` 빈이 없으면 뜨지 않는다. `apps/workbench` 의 `BreakGlassIntegrationTest`(prod 프로필)는 자기 저장소를 넣도록 고쳤다.

**B1. 앱 yml 은 모듈 기본값과 다른 값만.** `apps/workbench` `application*.yml` 400줄 → 203줄. 기본값을 되풀이하던 `${SKELETON_X:기본값}` 자리표시자는 없앴다 — 느슨한 바인딩이 같은 환경변수 이름을 이미 준다.
달라진 환경변수 이름: `SKELETON_NOTIFICATION_WEBSOCKET_AUTH_ENABLED` → `SKELETON_NOTIFICATION_WEBSOCKET_AUTHENTICATION_ENABLED`, `SKELETON_NOTIFICATION_WEBSOCKET_SOCKJS_ENABLED` → `…_ENDPOINT_SOCK_JS_ENABLED`,
`…_TOPIC_PREFIX` / `…_USER_DESTINATION` / `…_BRIDGE_ENABLED` → `…_BROKER_NOTIFICATION_DESTINATION_PREFIX` / `…_BROKER_USER_NOTIFICATION_DESTINATION` / `…_BROKER_BRIDGE_ENABLED`, `SKELETON_PAYMENT_*_ROUTE_ENABLED` 는 `skeleton.payment.providers.<id>.enabled` 의 느슨한 이름, 벤더 표준 이름(`JWT_SECRET`, `AWS_PROFILE`, `TOSS_PAYMENTS_SECRET_KEY`, `STRIPE_SECRET_KEY`, …)은 그대로 별칭이 남아 있다.
모듈마다 **모든 키 + 기본값 + 한 줄 설명**을 `docs/config/modules/<module>.yml` 에 두었다 (27개) — 필요한 블록만 복사한다. `apps/workbench` `ModuleConfigSnippetsTest` 가 각 파일의 키가 모듈 `@ConfigurationProperties` 에 바인딩되고 값이 기본값과 같은지, 속성이 빠지지 않았는지 본다.

**B2. 모듈을 얹으면 켜진다 — 인프라 · 필수 설정 없이 못 뜨는 것만 예외.** 규칙과 모듈별 "뜨는 데 필요한 것" 표는 `docs/minimal-composition.md` §3. 감사 결과:
- `redis-lock`: 기동 때 Redis 에 붙던 것(Redisson 즉시 시작 + 기본 켜진 시작 점검)을 **지연 연결**로, 시작 점검은 **옵트인**(`skeleton.redis-lock.startup-check.enabled`, 기본 `false` — 운영에서는 켠다). 락을 끄지 않으므로 `@DistributedLock` 이 조용히 무시되지 않는다 (Redis 가 없으면 첫 사용 때 크게 실패). 워크벤치의 `local` · `dev` · `staging` · `prod` yml 은 점검을 켠다.
- `config-aws-ssm`: `dev` · `staging` · `prod` 에서 `paths` 가 비어 있으면 "SSM is enabled but paths is empty" 로 기동이 실패하던 것을, `paths`(또는 `credential-profile`)가 설정돼야 읽도록 바꿨다. 명시적 `enabled=true` + 빈 `paths` 는 그대로 실패. 실패 안내는 더 이상 `./gradlew :apps:api:bootRun` 을 적지 않는다.
- `scheduler`: `skeletonTaskScheduler` 가 아무 `TaskScheduler` 빈이 있으면 물러나서, `notification-websocket`(TaskScheduler 둘)과 함께 얹으면 레지스트라 주입이 모호해져 기동이 실패했다. 이제 이름(`skeletonTaskScheduler`)으로만 물러난다 — 앱이 스케줄러를 바꾸려면 **같은 이름의 빈**을 둔다 (이름 없는 `TaskScheduler` 빈으로는 더 이상 대체되지 않는다).
- 이미 안전하게 degrade 하던 `redis-core` · `redis-cache`(FAIL_OPEN) · `redis-rate-limit`(FAIL_OPEN) · `storage-s3`(버킷이 없으면 저장소 빈 없음) · `notification-websocket` · `event-kafka`(로깅 전송기) · `notification-mail` · `captcha-turnstile` · `payment-*` · `notification-slack` 는 동작을 안 바꿨다. 모듈마다 "모듈 + 선언된 의존만, 설정 없음, 인프라 없음" 컨텍스트가 뜬다는 `*BootWithoutConfigurationTest` 를 더했다. 워크벤치는 `redis-rate-limit`(인메모리 저장소 시험)과 `notification-websocket`(구독자가 늘어난다)만 끈 채 둔다.
- 주의: `notification-websocket` 은 얹으면 켜지고 엔드포인트(`/ws/notifications`, 허용 Origin `*`)는 `authentication.enabled=true` 전까지 열려 있다 — 기본값은 바꾸지 않았다.

**B3. 설정 접두사 표** (`docs/minimal-composition.md` §2) 에 빠져 있던 `skeleton.idempotency` · `skeleton.openapi` · `skeleton.config.aws.ssm` · `skeleton.migration` 을 채웠고, `modules/platform` `ConfigPrefixDocumentationTest` 가 `@ConfigurationProperties` 접두사 + `Binder` 로 읽는 접두사와 표를 비교한다 (표에 있어도 디렉토리가 없는 모듈은 건너뛰므로 모듈을 덜어 낸 프로젝트에서도 통과). 접두사 이름은 하나도 바꾸지 않았다.

**C. `scripts/new-project.sh <target-dir> <root-package> <config-prefix> <ClassPrefix> [--modules a,b,c] [--db postgresql|mysql] [--with-workbench]`.**
복사 → 모듈 닫힘(`project(":modules:x")` 의존) → 안 고른 모듈 · 설정 블록 · settings include · `dbTestModules` · 스타터 테스트의 부재 단언 정리 → `--modules` 의존성 한 줄 + `application.yml` 끝의 주석 설정 블록 → (`--db mysql`) 방언 · URL · compose · Testcontainers · 첫 마이그레이션 → `rename-skeleton.sh`.
검증: `scripts/test-new-project.sh` (`--quick` 은 `./gradlew check` 에 걸려 있고, `--full` 은 세 조합을 찍어 각각 `./gradlew build` — `.github/workflows/new-project.yml`).
`docker-compose.yml` 에 `profiles:` 뒤의 선택 서비스 `redis` · `kafka` · `mail`(Mailpit)을 더했다 (`docker compose up` 만으로는 뜨지 않는다). `.env.example` 은 모듈별 주석 구역으로 다시 짰다.
`./gradlew check` 의 루트 `base` 플러그인 + `newProjectChecks` 작업이 새로 생겼다.

### 이식 안내 — 스타터 / 워크벤치 분리 + 모듈은 스캔되지 않는다 (2026-10-05)

하위 앱(rename-skeleton 으로 찍은 레포)이 위에서 아래로 따라 하면 된다. 경로 · 패키지는 스켈레톤 기준 — 하위 앱은 자기 접두사로 읽는다.

**0. 무엇이 바뀌었나.** `apps/api` 는 이제 **스타터**(최소 조립, 모듈 7개)이고, 이전 `apps/api` 전체(모든 모듈 + 샘플 컨트롤러 + 통합 테스트)는 `apps/workbench` 로 갔다.
앱은 루트의 하위 패키지(`…app.api`, `…app.workbench`)로 옮겨 `@SpringBootApplication` 이 모듈 패키지를 스캔하지 않는다. 모듈은 빈을 AutoConfiguration 으로만 등록한다.
REST 경로 · 응답 모양은 그대로다 (`/api/v1/skeleton/**` — react-skeleton 워크벤치 UI 계약).

**1. 앱 패키지를 루트 하위로.** `@SpringBootApplication` 클래스와 앱 코드를 `dev.sumin.skeleton.app.<앱>` (rename 후 `<root>.app.<앱>`) 아래로 옮긴다.
루트 패키지에 두면 앱이 모듈 패키지까지 스캔한다. 하위 앱 중 `apps/api` 를 그대로 쓰는 쪽은 클래스 이동만 하면 되고, 모듈 쪽 수정은 없다.

```
dev/sumin/skeleton/KotlinSkeletonApplication.kt  →  dev/sumin/skeleton/app/api/ApiApplication.kt   (이름은 자유)
dev/sumin/skeleton/api/*                         →  dev/sumin/skeleton/app/api/*
src/test/.../dev/sumin/skeleton/**                →  src/test/.../dev/sumin/skeleton/app/api/**      (@SpringBootTest 는 테스트 패키지에서 위로 올라가며 @SpringBootApplication 을 찾는다)
```

**2. 스타터와 워크벤치 중 무엇을 따를지.** 기능이 몇 개인 서비스는 `apps/api` 를 출발점으로 두고 필요한 모듈을 한 줄씩 얹는다 (`docs/minimal-composition.md`).
모든 모듈을 계속 얹는 앱(데모)은 이전 `apps/api` 가 `apps/workbench` 로 옮겨졌으니 이름만 바꾼다. `settings.gradle.kts` 에 `include(":apps:workbench")`, Dockerfile · `docker-compose.yml` 은 계속 `:apps:api` (스타터)를 빌드한다.
`./gradlew newMigration` 의 기본 모듈은 계속 `apps/api`.

**3. 모듈 쪽에서 바뀐 것 (복사해 갱신하는 디렉토리 단위).**

| 모듈 | 변경 |
|---|---|
| `platform` | `TraceIdFilter`, `RequestLoggingFilter` 의 `@Component` 제거 — `PlatformWebAutoConfiguration` 의 `@Bean` 으로만 등록. `GlobalExceptionHandler` 는 `@RestControllerAdvice` 유지(Spring MVC 가 애너테이션으로만 찾는다) + 같은 `@Bean`. `ModuleRegistrationRulesTest` 추가(모든 모듈 `main` 소스 검사, `build.gradle.kts` 의 `tasks.test` 블록 포함) |
| `notification-websocket` | `NotificationWebSocketBrokerConfiguration` 의 `@Configuration` 제거 — AutoConfiguration 의 `@Import` 로만 들어온다 |
| `db-postgresql`, `db-mysql` | 중첩 `DataJdbcConversions` 의 `@Configuration` 제거 (`@Bean` 메서드가 있는 중첩 클래스는 AutoConfiguration 이 그대로 처리) |
| `auth`, `notification-sse` | 컨트롤러는 `@RestController` 유지(MVC 핸들러 표지는 대체할 수 없다 — 클래스 레벨 `@RequestMapping` 만으로는 404). 이미 AutoConfiguration 의 `@Bean` 으로 등록돼 있고, 테스트가 그것을 강제한다 |

**4. 어댑터 모듈이 계약 모듈을 `api` 로 노출한다.** 의존성 한 줄이면 된다. 앱 `build.gradle.kts` 에서 중복된 줄을 지워도 컴파일된다 (남겨도 무해).
`payment-toss`/`-stripe` → `payment`, `notification-jdbc`/`-sse`/`-slack`/`-websocket` → `notification`, `storage-s3` → `storage`, `auth-social` → `auth`,
`auth-social-google`/`-kakao`/`-naver` → `auth-social`, `redis-lock`/`-cache`/`-rate-limit` → `redis-core`.

**5. 확인.** `./gradlew build` 와 `scripts/rename-skeleton.sh dev.sumin.ovation ovation Ovation` 후 `./gradlew build` 를 복사본에서 돌려 통과를 확인했다.
`skeleton.notification.sse.enabled=false` 처럼 모듈을 끄면 이제 기동이 깨지지 않는다 (`apps/workbench` `ModuleDisabledIntegrationTest`).

### 이식 안내 — PostgreSQL 기본 + 방언 조립식 + Flyway 충돌 방지 (2026-10-01)

하위 앱(rename-skeleton 으로 찍은 레포)이 위에서 아래로 따라 하면 된다. 설계: `docs/superpowers/specs/2026-10-01-postgresql-flyway-design.md`.

**0. 기준.** 마지막 MySQL 전용 커밋은 `5cf377b`. MySQL 로 남을 앱은 1 단계에서 `db-mysql` 을 끼우면 동작이 같다.
아래 경로 · 이름은 스켈레톤 기준(`dev.sumin.skeleton`, `skeleton.*`) — 하위 앱은 자기 접두사로 읽는다.

**1. 모듈 조립 (`apps/<app>/build.gradle.kts`).**

```kotlin
implementation(project(":modules:db-postgresql"))     // 또는 :modules:db-mysql — 정확히 하나
implementation(project(":modules:migration-flyway"))   // 공통 :modules:migration 을 api 로 끌고 온다
implementation("org.springframework.boot:spring-boot-starter-flyway")
// 삭제: implementation("org.flywaydb:flyway-mysql"), runtimeOnly("com.mysql:mysql-connector-j")  — 방언 모듈이 가져온다
// 테스트: testcontainers-mysql → testImplementation("org.testcontainers:testcontainers-postgresql")
```

`settings.gradle.kts` 에 `include(":modules:db-postgresql")`, `include(":modules:db-mysql")`, `include(":modules:migration")`, `include(":modules:migration-flyway")`.
방언 모듈이 없거나 둘이거나 연결된 DB 와 다르면 `SqlDialectVerifier` 가 원인을 적고 기동을 실패시킨다.

**2. 새 파일 · 옮긴 파일 · 지운 파일.**

| 모듈 | 파일 | 비고 |
|---|---|---|
| persistence-jdbc | `SqlDialect.kt`, `SqlDialectVerifier.kt`, `SqlDialectAutoConfiguration.kt` | 새 파일. imports 에 `SqlDialectAutoConfiguration` |
| persistence-jdbc | `JdbcUtcAutoConfiguration.kt`, `META-INF/spring.factories` | **삭제** (MySQL 전용 → db-mysql) |
| db-postgresql (새 모듈) | `PostgresSqlDialect.kt`, `PostgresTimeConversions.kt`, `PostgresDialectAutoConfiguration.kt`, imports | 패키지 `…persistence.postgresql` |
| db-mysql (새 모듈) | `MySqlSqlDialect.kt`, `MySqlDialectAutoConfiguration.kt`, imports, `spring.factories` | 패키지 `…persistence.mysql` |
| db-mysql | `MySqlTimeConversions.kt` ← persistence-jdbc `UtcInstantConversions.kt` | object 이름만 바뀜 |
| db-mysql | `MySqlTimeZoneEnvironmentPostProcessor.kt` ← persistence-jdbc `JdbcTimeZoneEnvironmentPostProcessor.kt` | property source `skeleton-db-mysql-defaults` |
| persistence-jooq | `JooqTimeZoneEnvironmentPostProcessor.kt`, `META-INF/spring.factories` | **삭제** (같은 MySQL 세션 강제 — db-mysql 이 한다) |
| persistence-jooq | `db/skeleton-jooq-schema.sql` → `db/jooq-probe-mysql.sql`, 새 `db/jooq-probe-postgresql.sql` | 예시 DDL |
| migration (새 모듈, 공통) | `MigrationProperties`(`skeleton.migration`), `MigrationCleanGuardEnvironmentPostProcessor` (Flyway clean · Liquibase drop-first 포함 가드) | 패키지 `…migration` |
| migration-flyway (새 모듈, Flyway 구현) | `CleanOnValidationErrorMigrationStrategy`, `MigrationFlywayAutoConfiguration`, `MigrationFileRules` + `RepositoryMigrationsTest` | 패키지 `…migration.flyway`, `api(project(":modules:migration"))` |
| job-queue-jdbc | `JdbcJobRepository(jdbc, transactions, dialect: SqlDialect)` | 생성자 인자 추가, 생성 키는 `update(keys, "id")` |
| notification-jdbc | `JdbcNotificationInboxRepository(jdbc, jsonCodec, dialect: SqlDialect)` | 중복 삽입 = `SqlDialect.insertIgnore` |
| 루트 `build.gradle.kts` | `dbTestModules` 묶음 등록, `newMigration` 작업 | §6 · §7 |

경계: `migration` 에는 도구와 무관한 것만 둔다 (설정 `skeleton.migration`, DB 를 지우는 설정의 프로필 가드). 파일 규칙 검사(`MigrationFileRules` — 위치 · 방언 폴더 짝 · 중복 · 타임스탬프)와 `newMigration` 도 개념상 공통이지만 지금은 Flyway 하나뿐이라 `migration-flyway` · 루트 Gradle 에 두고, `migration-liquibase` 가 생길 때 공통으로 끌어올린다. outOfOrder · baseline · repair 는 Flyway 전용.

**3. 설정 · 속성.**

| 키 | 값 | 어디서 |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/app` (쿼리 파라미터 없음) | 앱 yml, `.env.example` |
| `spring.flyway.locations` | `classpath:db/migration/{vendor}` | 앱 yml — **필수** (모듈이 방언별 폴더를 씀) |
| `spring.flyway.out-of-order` | `true` | 앱 yml — 선택 (apps/api 의 취향. 스켈레톤 모듈은 Flyway 기본값을 바꾸지 않는다) |
| `spring.flyway.validate-migration-naming` | `true` | 앱 yml — 선택 |
| `skeleton.migration.clean-on-validation-error` | 기본 `false`, `application-local.yml` 에서 `true` | 새 속성 |
| `skeleton.migration.clean-allowed-profiles` | 기본 `[local]` | 새 속성 |
| Gradle `-Pskeleton.jooq.dialect` | `postgresql`(기본) \| `mysql` | persistence-jooq 코드 생성 |
| (삭제) Hikari `connectionTimeZone`, `forceConnectionTimeZoneToSession`, `preserveInstants` | — | db-mysql 을 끼울 때만 자동으로 들어간다 |

docker-compose: `postgres:18` 서비스(`127.0.0.1:5432`, 볼륨 `/var/lib/postgresql`), `mysql:8.4` 는 `profiles: [mysql]`.

**4. 새 시간 규칙.** "SQL 파라미터는 `Instant` 대신 UTC `LocalDateTime`" 은 **폐기**. 두 DB 에 공통으로 맞는 바인딩 타입이 없다
(실측 2026-10-01, JVM 서울, `2026-03-01T00:30:00Z`):

| 바인딩 | PG `timestamptz` | MySQL `datetime(6)` |
|---|---|---|
| UTC `OffsetDateTime` / `Timestamp.from` | 정확 | JVM 벽시계(09:30)로 저장 |
| `Instant` | 드라이버 거부 | — |
| UTC `LocalDateTime` | **−9h** | 정확 |

- JdbcClient / NamedParameterJdbcTemplate: 쓰기 `dialect.instantParam(instant)`, 읽기 `dialect.readInstant(rs, "col")`. `Instant` · `Timestamp` · `LocalDateTime` 을 직접 바인딩하지 않는다.
- Spring Data JDBC: 방언 모듈이 `JdbcCustomConversions` 를 등록 (자체 빈을 만들면 `PostgresTimeConversions.all` / `MySqlTimeConversions.all` 포함).
- 칼럼: 시점 `timestamptz`, 달력 날짜 `date`, 벽시계(`ZonedMoment.local`) `timestamp`. `docs/time.md`.

**5. jOOQ 코드 생성 (`apps/<app>/build.gradle.kts`, PG).** `docs/persistence-jooq.md` 전문.

```kotlin
database {
    name = "org.jooq.meta.extensions.ddl.DDLDatabase"
    properties {
        property { key = "scripts"; value = "src/main/resources/db/migration/postgresql" }
        property { key = "sort"; value = "flyway" }
        property { key = "unqualifiedSchema"; value = "none" }
        property { key = "defaultNameCase"; value = "lower" }
    }
    forcedTypes {
        forcedType { name = "INSTANT"; includeExpression = "(?i:.*_at)"; includeTypes = "(?i:timestamp.*with.*time.*zone)" }
    }
}
```

`UtcInstantConverter` · `parseIgnoreComments` 는 MySQL 경로용 (`parseIgnoreComments` 는 PG 에 켜 둬도 무해 — persistence-jooq 는 방언과 무관하게 켠다). `jsonb` 는 jOOQ 에서 `JSON` 으로 생성된다.
여러 폴더를 합칠 땐 `scripts` 에 쉼표 목록이 안 되므로 `Sync` 작업으로 한 디렉토리에 모은다 (persistence-jooq `collectModuleDdl`).

**6. Flyway — 버전 · outOfOrder · clean · 가드.** `docs/schema-management.md` 전문.

- 위치 · 이름: `src/main/resources/db/migration/<vendor>/V<UTC yyyyMMddHHmmss>__<snake_case>.sql`. 만들기: `./gradlew newMigration -Pname=add_x [-Pmodule=apps/api] [-Pvendor=postgresql]`.
- 검사: `modules/migration-flyway` `RepositoryMigrationsTest` 가 `./gradlew build` 에서 형식 · vendor 폴더 · 레포 전체 중복 버전 · 두 vendor 짝 불일치를 막는다 (`tasks.test { systemProperty("skeleton.repoRoot", rootDir.absolutePath) }`).
- outOfOrder 는 앱의 선택 — apps/api 는 모든 환경 `true` 로 yml 에 적는다 (모듈은 기본값을 안 바꿈). 켠다면 규칙: **마이그레이션은 서로 독립 — 다른 브랜치의 미적용 마이그레이션에 기대지 않는다.**
- 로컬 clean: `local` 프로필에서 `skeleton.migration.clean-on-validation-error=true` 면 체크섬 불일치 · 적용 파일 사라짐(최신 적용분보다 이른 것)일 때 clean 후 재적용. 미적용(pending) 파일, 앱의 `ignore-migration-patterns`(기본 `*:future` — 다른 브랜치가 적용한 더 늦은 파일)에 걸리는 것은 밀지 않는다. 앱이 `out-of-order=true` 로 돈다는 전제 (Flyway 12.4 에 `cleanOnValidationError` 가 없어 `FlywayMigrationStrategy` 로 구현).
- 가드 (`migration` 공통 모듈): 허용 프로필 밖에서 위 옵션, `spring.flyway.clean-disabled=false`, `spring.liquibase.drop-first=true` 중 하나라도 켜지면 기동 실패 (활성 프로필 없음 = `default`, 허용 안 됨).
- **재명명** (이미 MySQL 에 적용된 DB 는 이력과 어긋난다 → 새 DB 로 시작하거나 `flyway_schema_history` 를 손으로 맞춘다):

| 전 | 후 |
|---|---|
| job-queue `db/migration/V2026091001__skeleton_jobs.sql` | `db/migration/{postgresql,mysql}/V20260910010000__skeleton_jobs.sql` |
| notification `db/migration/V2026061701__notification_inbox.sql` | `db/migration/{postgresql,mysql}/V20260617010000__skeleton_notification_inbox.sql` |
| 앱 `db/migration/V1__init.sql` (주석뿐) | 삭제 |
| 앱 테스트 `db/migration/V9000__utc_probe.sql` | `db/migration/postgresql/V20260101000000__utc_probe.sql` |

**7. Testcontainers.** `org.testcontainers.postgresql.PostgreSQLContainer(DockerImageName.parse("postgres:18"))`, 의존성 `testcontainers-postgresql`.
방언을 타는 모듈(job-queue-jdbc, notification-jdbc)은 루트 `dbTestModules` 로 `postgresTest` · `mysqlTest` 두 묶음을 갖는다 —
공통 소스 `src/dbTest/{kotlin,resources}`, 묶음별 `src/<suite>/kotlin` (컨테이너 정의), 둘 다 `check` 에 걸림. 한 묶음엔 방언 모듈 하나만.

**8. MySQL → PostgreSQL SQL 차이 (하위 앱이 다시 써야 할 것).**

| MySQL | PostgreSQL |
|---|---|
| `insert … on duplicate key update c = values(c)` | `insert … on conflict (key) do update set c = excluded.c` |
| 중복 무시 (`insert ignore`, 예외 잡아 삼키기) | `on conflict (key) do nothing` = `SqlDialect.insertIgnore`. **예외 삼키기 금지** — PG 는 실패한 문장이 트랜잭션 전체를 abort |
| `LAST_INSERT_ID()`, `GeneratedKeyHolder` 그대로 | `returning id` 또는 `update(keys, "id")` (키 칼럼 지정 안 하면 모든 칼럼이 키로 온다) |
| `datetime(6)` | 시점 `timestamptz`, 벽시계 `timestamp` |
| `bigint auto_increment` | `bigint generated by default as identity` |
| `engine=`, `charset=`, `collate` | 삭제 |
| `create table (…, index idx (a, b))` | `create index if not exists idx on t (a, b)` |
| `bigint unsigned` | `bigint` (+ 필요하면 `check (x >= 0)`) |
| 식별자 대소문자 | 따옴표 없으면 소문자로 접힘 — 대문자 이름은 `"Name"` 으로만 |
| `update/delete … limit n` | `where id in (select id … limit n)` |
| `json` | `jsonb` (jOOQ 생성 타입은 `JSON`) |
| `tinyint(1)` | `boolean` |
| `ifnull(a, b)` | `coalesce(a, b)` |
| `concat(a, b)` | 되지만 `a || b` 권장 (null 이면 결과 null 주의) |
| `@@session.time_zone`, `database()` | `show timezone`, `current_schema()` |
| `information_schema.statistics` (인덱스) | `pg_indexes` |

**9. 동작 수정.** notification-jdbc 가 같은 알림을 같은 트랜잭션에서 두 번 저장하면 PG 에서 트랜잭션이 깨지던 문제
(`DuplicateKeyException` 삼키기) → `insertIgnore`. 두 DB 테스트(`saving the same event twice inside one transaction…`)로 고정.

### 이식 안내 — 마이그레이션 규칙 검사 범위 · 앱 기동 규칙 순서 (2026-10-01, Ovation 이식에서 발견)

`9593d55` 를 이미 옮긴 앱이 따라 하면 된다. 아래 두 가지 모두 Ovation(`7161171` · `0440714`)에서 먼저 고치고 확인한 것.

**1. `MigrationFileRules` 가 레포 안 워크트리 · 레포 · `node_modules` 를 훑지 않게.** `9593d55` 는 `Files.walk` 로 루트 아래를 다
훑고 나서 경로에 `build` 등이 있는지 걸렀다. 그래서 (가) 레포 안에 다른 git 워크트리가 있으면 — Claude Code 가 만드는
`.claude/worktrees/<이름>/` 등 — 그 안의 마이그레이션 사본이 「duplicate version」으로 `./gradlew build` 를 깨고,
(나) `node_modules` 안에 읽을 수 없는 폴더가 있으면 `AccessDeniedException` 으로 깨진다.

| 파일 | 바꿀 것 |
|---|---|
| `modules/migration-flyway/…/MigrationFileRules.kt` | `Files.walk` → `Files.walkFileTree` (`sqlFiles(root)`). `preVisitDirectory` 에서 루트가 아니고 (`SKIP_DIRS` 에 있거나 `dir/.git` 이 있으면) `SKIP_SUBTREE` — 들어가지도 않는다. `SKIP_DIRS` 에 `.claude` 추가 |
| `modules/migration-flyway/build.gradle.kts` | `tasks.test` 입력 `fileTree` 의 `exclude` 에 주요 건너뛸 곳을: `"**/build/**", "**/node_modules/**", ".claude/**", "**/.git/**"` |
| `MigrationFileRulesTest` | 테스트 셋 추가: 중첩 `.git` 이 있는 폴더 · `.claude/worktrees` 는 훑지 않음, `node_modules` 안 읽기 금지 폴더로 들어가지 않음 |

**2. 앱이 따로 둔 기동 규칙은 Flyway 빈보다 먼저.** 스켈레톤 가드(`MigrationCleanGuardEnvironmentPostProcessor`)는
`EnvironmentPostProcessor` 라 빈보다 먼저 돈다 — 코드 변경 없음. 하지만 앱이 그 위에 **보통 빈**으로 기동 규칙을 더하면
(예: Ovation `DeployGuards` — stage · prod 에선 허용 프로필과 무관하게 clean 설정 거부) Spring 이 Flyway 빈을 먼저 만들 수
있고, 그러면 Flyway 빈을 받는 `FlywayMigrationInitializer` 의 `migrate()`(로컬 clean 전략 포함)가 DB 를 지운 **뒤에야** 규칙이 기동을 막는다
(Ovation 실측: jOOQ → Flyway 가 먼저 생성). 둘 중 하나:

- 규칙이 `Environment` 만 읽으면 `EnvironmentPostProcessor` 로 만든다 (`META-INF/spring.factories`).
- 빈으로 두려면 static `BeanFactoryPostProcessor` 로 모든 `Flyway` 빈이 규칙 빈에 기대게 한다 (Ovation 방식,
  `ApplicationContextRunner` 에 Flyway 설정을 먼저 등록하고 「규칙으로 기동 실패 + Flyway 빈 미생성」을 확인하는 테스트로 고정):

```kotlin
companion object {
    @JvmStatic
    @Bean
    fun flywayAfterDeployGuards(): BeanFactoryPostProcessor = FlywayAfterDeployGuards()
}
private class FlywayAfterDeployGuards :
    AbstractDependsOnBeanFactoryPostProcessor(Flyway::class.java, "deployGuardsChecked")   // 규칙 빈 이름 = @Bean 메서드 이름
```

규칙 빈에 `@DependsOn` 을 붙이는 건 반대 방향이라 소용없고, 자체 `FlywayMigrationStrategy` 에 넣으면 스켈레톤 로컬 clean
전략(`@ConditionalOnMissingBean`)을 대체해 버린다. `docs/schema-management.md` 「Guard」 절.


### Changed
- Spring Boot 4.0.5 → **4.1.1**, Kotlin 2.2.21 → **2.3.21** (Boot-managed: jOOQ 3.21.7, MySQL Connector/J 9.7.0, Testcontainers 2.0.5, Spring Security 7.1.1). One source change: `JwtTokenService` treats a missing `sub` claim as authentication failure (subject is nullable in Spring Security 7.1)
- `storage-s3`: static key-pair credentials (`credentials.access-key-id` / `secret-access-key`) for R2/MinIO with fail-fast on half-specified pairs; `region: auto` supported; `UploadObjectRequest.cacheControl` / `contentDisposition` passed to `PutObject`; `StorageService.deleteAll` (S3: `DeleteObjects` in batches of 1000). `docs/storage-s3.md` R2 section

### Added
- `storage-s3`: `skeleton.storage-s3.presign.endpoint-override` — a separate endpoint for presigned URLs (browser-facing) when the server reaches S3 through a compose service name such as `http://s3:8333`; unset keeps the shared `endpoint-override`. Requested from the Ovation local SeaweedFS setup. `docs/storage-s3.md` "Local container"
- `modules/persistence-jooq`: jOOQ with code generation from a DDL file (`DDLDatabase`, no DB at build), `UtcInstantConverter` (`*_at` → `Instant`, UTC-fixed), `JooqAuditRecordListener`, UTC session defaults; Testcontainers MySQL test with JVM zone forced to Seoul. `docs/persistence-jooq.md`
- `modules/job-queue-jdbc`: MySQL table retry queue — `JobQueue.enqueue`, `JobHandler` by type, `FOR UPDATE SKIP LOCKED` claiming, exponential backoff, `max-attempts` → DEAD, `PermanentJobFailureException`, stale RUNNING recovery, no Redis; 6 Testcontainers tests. `docs/job-queue-jdbc.md`
- `modules/notification-mail`: SMTP `MailSender` on top of `spring.mail.*`, off by default. `docs/notification-mail.md`
- `modules/captcha-turnstile`: `TurnstileVerifier` via platform outbound HTTP, hostname/action checks, off by default. `docs/captcha-turnstile.md`
- Schema without Flyway: `spring.flyway.enabled=false` + `spring.sql.init.mode=always` + `schema.sql`, proven by `SchemaSqlInitIntegrationTest`; migration path back to Flyway in `docs/schema-management.md`
- HTML pages next to the API: `apps/api` `PagesController` + page-scoped `HtmlPageErrorAdvice` + `PublicEndpointContributor`, proven by `HtmlPageCoexistenceIntegrationTest` (public `text/html`, HTML errors instead of JSON envelope, `/api/**` still protected)
- `scripts/rename-skeleton.sh`: rewrites root package, `skeleton.*` config prefix, `SKELETON_*` env placeholders and `Skeleton*` class/file names; `docs/minimal-composition.md` with a generated module → config-prefix table and the no-Redis defaults (rate limit, idempotency, scheduler lock)
- `modules/time`: global-time capability — `TimeContext` (account preference → `X-Time-Zone`/`Accept-Language` → `skeleton.time.default-*`), `ZonedMoment` (local time + IANA zone as source of truth, derived `at`; DST gap/overlap policy documented and tested), `TimeFormatter.dual` (event zone + viewer zone, `GMT+9`-style labels), `CountryTimeZones` generated from tzdata `zone.tab` with representative defaults for multi-zone countries, `UserTimePreferences` SPI. 12 tests
- `modules/persistence-jdbc`: `JdbcTimeZoneEnvironmentPostProcessor` forces the MySQL session to UTC via Hikari driver properties (`connectionTimeZone`, `forceConnectionTimeZoneToSession`), and `UtcInstantConversions` writes `Instant`/`LocalDate`/`LocalDateTime` as `JdbcValue` literals and reads `LocalDateTime` as UTC — measured against Connector/J: it converts `Timestamp`/`Date` parameters by the JVM zone but returns `DATETIME` as a wall-clock `LocalDateTime`, so a non-UTC JVM shifted instants by hours and moved `LocalDate` by a day. `apps/api` proves the round trip with the JVM default zone set to `Asia/Seoul`
- `docs/time.md`: the three temporal kinds and how to store/format each

### Changed
- `apps/api` datasource URL no longer needs `connectionTimeZone`/`forceConnectionTimeZoneToSession` parameters
- Testcontainers MySQL pinned to `mysql:8.4` (was `mysql:latest`, i.e. 9.x) to match `docker-compose.yml`
- Dockerfile builder image `gradle:8.11` → `eclipse-temurin:21-jdk` (the wrapper downloads Gradle anyway; the image tag was misleading); `.dockerignore` added
- Springdoc OpenAPI UI: `/api/v1/docs`, `/api/v1/docs/ui`
- `HelloControllerIntegrationTest`: `X-Request-Id` → `X-Trace-Id` 전파, 표준 `ApiError`, 요청 로그 traceId 흐름 검증
- W3C `traceparent` 기반 trace context: traceId는 전체 플로우로 승계, 각 BE 요청은 새 spanId 생성
- 로그 correlation 패턴에 `traceId`, `spanId`, `parentSpanId` 모두 출력
- 에러 응답과 응답 헤더에 `spanId` 포함
- Standard success response envelopes: single `{value, meta}`, list `{values, meta}`, page `{values, pagination, meta}`
- `Response` helper and envelope DTOs: `BasicResponse`, `DataResponse`, `ListResponse`, `PageResponse`, and `CursorResponse`
- Standard REST operation contracts: `201 Created` with `Location`, `202 Accepted`, `204 No Content`, and reusable `PageQuery`
- Web platform capability: public endpoint registry for `permitAll`, forwarded/security headers, CORS scaffold, rate-limit scaffold, and WebClient-based outbound HTTP facade
- Module-composed OpenAPI docs: platform contributes standard schemas/trace/error responses, auth contributes bearer JWT security, and auth-social contributes its functional route docs
- Standard request validation errors via Jakarta Bean Validation and `ApiError.errors[]`
- `modules/auth` stateless auth capability:
  - `POST /api/v1/auth/login` password login and `GET /api/v1/auth/me`
  - HS256 JWT issue/authenticate with `CurrentPrincipal`
  - local/dev header login via `X-Dev-Account-Id`, `X-Dev-Username`, `X-Dev-Email`
  - production break-glass access via secret, reason, and account allowlist
  - overridable Spring Boot auth auto-configuration defaults for app-specific repositories and security chains
- `modules/auth-social` optional social-login capability with provider-neutral OAuth contracts, account-link resolution, fake-provider integration tests, and `/api/v1/auth/social/{provider}/login`
- Optional social OAuth provider client modules: `modules/auth-social-google`, `modules/auth-social-kakao`, and `modules/auth-social-naver`
- `modules/idempotency` optional command endpoint protection with `@IdempotentOperation`, required `Idempotency-Key`, request fingerprinting, replay headers, and replaceable `IdempotencyStore`
- `ExternalHttpClient.postForm(...)` and per-call `baseUrl(...)` overrides for OAuth/payment-style external APIs
- `modules/notification` optional notification contracts with a replaceable in-memory broker
- `modules/notification-sse` optional Spring MVC SSE delivery through `GET /api/v1/notifications/sse`
- Persistence audit timestamp modules:
  - platform `TimeProvider` auto-configuration and `BaseAuditTimestamps` for UTC `Instant` + MySQL `DATETIME(6)` precision
  - optional `modules/persistence-jpa` with JPA `AuditTimestamps` and `BaseJpaEntity`
  - optional `modules/persistence-jdbc` with JDBC `AuditTimestamps`, `JdbcAuditable`, and audit callback auto-configuration

### Fixed
- 방언 모듈을 안 끼운 앱이 `NoSuchBeanDefinitionException: SqlDialect` 만 보던 경우에도 "db-postgresql 또는 db-mysql 을 끼워라" 안내가 나온다 (`SqlDialectFailureAnalyzer`, persistence-jdbc `spring.factories`). `db-postgresql` / `db-mysql` 자동설정은 Boot 의 Data JDBC 자동설정보다 먼저 돌도록 `beforeName` 으로 명시 (이름 순서에 기대지 않음). `newMigration` 작업 그룹 이름을 `migration` 으로 (rename 후 `skeleton` 이 남던 것)
- Copying a module migration into `schema.sql` broke jOOQ codegen (reported from Ovation): `DDLDatabase` cannot parse MySQL inline `index` clauses, and a separate `create index` is not idempotent on MySQL 8.4. `job-queue-jdbc` and `notification-jdbc` migrations now wrap their index clauses in `/* [jooq ignore start] */ … /* [jooq ignore stop] */` (comma inside the span), `persistence-jooq` sets `parseIgnoreComments=true` and generates code from those migrations on every build, and `SchemaSqlInitIntegrationTest` runs a `schema.sql` containing the `skeleton_jobs` DDL twice and checks the indexes. `docs/persistence-jooq.md` documents the pattern
- `scripts/rename-skeleton.sh` (reported from the Ovation rename): the YAML root key `skeleton:` was left as is, so every `<prefix>.*` block in `application*.yml` was silently ignored after a rename (e.g. `storage-s3.enabled=false` lost, module booted enabled); `.env.example` kept `SKELETON_*` while the yml switched to the new prefix; visible defaults kept the skeleton name (OpenAPI title/description, `skeleton-job-queue` thread, `skeleton-code-enum` Jackson module, `skeleton-async-`/`skeleton-scheduler-` prefixes, Kafka headers, Redis key prefix, JWT issuer, SSM paths, `skeleton-jooq-schema.sql`). `CountryTimeZones` looked up its TSV by the absolute path `/dev/sumin/skeleton/time/…` while the resource directory moved with the package (3 `modules:time` tests failed after a rename) — it now loads the resource relative to its own class. The script rewrites all of the above (plus the slash-form `dev/sumin/skeleton` in `.py`/`.sh`) and ends with a leftover scan that fails the run if any `skeleton` trace remains
- Modules no longer depend on the app component-scanning `dev.sumin.skeleton`: `platform` registers `TraceIdFilter`, `RequestLoggingFilter` and `GlobalExceptionHandler` via `PlatformWebAutoConfiguration`, `auth` registers `AuthController` in `AuthAutoConfiguration` (all `@ConditionalOnMissingBean`, so scanning apps keep their beans). Found by assembling a monorepo app in `dev.sumin.app1`: auth endpoints were 404 and responses had no trace id. Guarded by `ModuleSelfRegistrationIntegrationTest`, which boots a root configuration that scans nothing. `CodeEnumOpenApiCustomizer` no longer filters DTOs by the `dev.sumin.skeleton` package prefix (it skips JDK/Kotlin/Spring/Jackson types instead), so code-enum descriptions appear for DTOs in any package
- 매핑되지 않은 API 경로를 `500`이 아니라 표준 `404 ApiError`로 응답
- break-glass/dev-login authentication is no longer overwritten by a later bearer-token filter when both headers are present

## v1.2.0 - Multi-module foundation

- Converted the backend skeleton to a coarse-grained Gradle multi-module layout.
- Added `apps/api` as the executable Spring Boot application.
- Added `modules/platform` for shared web/error/observability infrastructure.
- Added `modules/auth` as the authentication capability module foundation.
- Kept the existing trace-aware `/api/v1/hello` behavior and integration tests.

## [1.1.1] - 2026-04-20

### Added
- `RequestLoggingFilter`: 요청 시작/종료를 `→` `←` pair 로그로 출력 (같은 traceId로 묶임)
- `application.yml` `connectionTimeZone=UTC` — DB 타임존 JVM과 무관하게 UTC 고정
- `application.yml` Flyway MySQL 버전 경고 억제 (ERROR 레벨)

### Changed
- traceId 관리는 `TraceIdFilter` (커스텀) 유지 결정 — Spring Boot 4 + Micrometer Brave autoconfig 가 우리 환경에서 안정적으로 붙지 않아서. 단일 서비스 PoC 에선 차이 없고, 멀티 언어(Python/JS) 환경에서 오히려 단순 (X-Request-Id 는 누구나 다룸)
- 로그 correlation 패턴은 `[traceId]` 만 출력 (app 이름은 기본 APPLICATION_NAME 자리에서 한 번만)

## [1.1.0] - 2026-04-20

### Added
- **traceId 기반 관찰가능성**: `TraceIdFilter`가 요청마다 MDC `traceId` 심고 응답 헤더 `X-Trace-Id`로 반환. 클라이언트 `X-Request-Id` 헤더 오면 승계
- **표준 에러 응답**: `ApiError` (RFC 7807 Problem Details 변형 + traceId + timestamp), `GlobalExceptionHandler` 가 전역 예외 캐치
- **도메인 예외 베이스**: `ApplicationException` — 상속해서 throw하면 HTTP status + title이 자동 매핑
- **로깅 패턴**: 모든 로그 라인에 `[appName,traceId]` 프리픽스 출력
- **패키지 구조 정립**: `api/`, `domain/`, `infra/`, `common/`, `config/` 경계와 책임 CLAUDE.md에 명시
- **샘플 `HelloController`** (`/api/v1/hello`) — 컨벤션 시연용

## [1.0.0] - 2026-04-20

### Added
- Spring Boot 4.0 + Kotlin 2.2 + JDK 21 기본 구성
- MySQL 드라이버, Flyway, Spring Data JDBC
- Spring Boot Actuator (`/health` 노출)
- Testcontainers 테스트 지원
- 멀티 스테이지 Dockerfile
- `docker-compose.yml` 로컬 dev 환경 (app + MySQL)
- GitHub Actions CI 워크플로
