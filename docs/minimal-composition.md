# Minimal composition — picking a few modules

Two apps ship with the skeleton:

| App | What it is | Modules |
|---|---|---|
| `apps/api` | the **starter** — the minimal recipe below, built and booted by its own tests | `platform`, `auth`, `persistence-jdbc`, `db-postgresql`, `migration-flyway`, `time` |
| `apps/workbench` | the **demo** — every module together, with sample endpoints under `/api/v1/skeleton/**` | all of them |

A real service starts from `apps/api` and keeps only what it needs. This page is the recipe.

## 1. Start from the starter, add a module = one dependency line

`apps/api/build.gradle.kts` is the recipe:

```kotlin
// apps/<app>/build.gradle.kts
dependencies {
    implementation(project(":modules:platform"))
    implementation(project(":modules:auth"))              // JWT, dev-login, break-glass
    implementation(project(":modules:persistence-jdbc"))  // audit timestamps, SqlDialect
    implementation(project(":modules:db-postgresql"))     // exactly one db-* module: driver, Flyway support, SqlDialect
    implementation(project(":modules:migration-flyway"))  // + common migration: clean guard, opt-in local clean, naming check
    implementation(project(":modules:time"))              // viewer zone/locale, ZonedMoment

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
```

Need more? Add the line, and a yml block only if you want to change something (modules ship their defaults):

```kotlin
    implementation(project(":modules:job-queue-jdbc"))    // DB retry queue (no Redis)
    implementation(project(":modules:storage-s3"))        // R2 / S3 — brings :modules:storage along (api dependency)
    implementation(project(":modules:scheduler"))         // annotation-driven jobs (Noop lock by default)
    implementation(project(":modules:notification-sse"))  // brings :modules:notification along
```

```yaml
skeleton:
  storage-s3:
    enabled: true
    bucket: my-bucket
```

Adapter modules expose the contract they implement with `api(project(...))`, so one line is enough:
`payment-toss`/`payment-stripe` → `payment`, `notification-jdbc`/`-sse`/`-slack`/`-websocket` → `notification`,
`storage-s3` → `storage`, `auth-social` → `auth`, `auth-social-google`/`-kakao`/`-naver` → `auth-social`,
`redis-lock`/`-cache`/`-rate-limit` → `redis-core`. You never edit a module to compose it.

Keep only the modules you use in `settings.gradle.kts` `include(...)` as well; delete the module directories you
do not include (or leave them — unincluded directories are ignored by Gradle). `apps/workbench` can be deleted once
you no longer need it (the React skeleton's workbench UI is its only client).

**Your app lives in a sub-package of the root** (`dev.sumin.skeleton.app.api`, after the rename `dev.sumin.ovation.app.api`)
so `@SpringBootApplication` scans only your code. Modules are never component-scanned: each registers its
controllers, filters and advice through its `AutoConfiguration` (`META-INF/spring/…AutoConfiguration.imports`), and a
module switched off by `skeleton.<module>.enabled=false` leaves no stray bean behind. Two tests keep it that way:
`modules/platform` `ModuleRegistrationRulesTest` (no `@Component`/`@Configuration` on module `main` classes; MVC handler
markers only when an autoconfiguration `@Bean` registers the class) and `apps/workbench` `ModuleDisabledIntegrationTest`.

What the starter proves: `apps/api` tests boot the context with only these modules — no Redis, Kafka, S3, mail or JPA on
the classpath — serve `GET /api/v1/hello`, log in with the seed user and return the standard 404 `ApiError`.

## 2. Delete the settings of modules you dropped

The starter's yml carries no module blocks. If you started from `apps/workbench` instead, each module owns exactly one configuration prefix: remove the matching block from `application*.yml`.
Unknown `skeleton.*` keys are ignored by Spring Boot, but `skeleton.config.validation` (platform) can be
configured to require keys, so keep the file honest.

| 모듈 | 설정 접두사 |
|---|---|
| `async` | `skeleton.async` |
| `async-notification` | `skeleton.async-notification` |
| `auth` | `skeleton.auth` |
| `auth-social` | `skeleton.auth-social` |
| `captcha-turnstile` | `skeleton.captcha-turnstile` |
| `crypto` | `skeleton.crypto` |
| `event-kafka` | `skeleton.event-kafka` |
| `job-queue-jdbc` | `skeleton.job-queue` |
| `migration` (+ `migration-flyway`) | `skeleton.migration` |
| `notification-mail` | `skeleton.notification-mail` |
| `notification-slack` | `skeleton.notification.slack` |
| `notification-sse` | `skeleton.notification.sse` |
| `notification-websocket` | `skeleton.notification-websocket` |
| `payment` | `skeleton.payment` |
| `payment-stripe` | `skeleton.payment-stripe` |
| `payment-toss` | `skeleton.payment-toss` |
| `platform` | `skeleton.config.validation` |
| `platform` | `skeleton.http` |
| `platform` | `skeleton.observability.links` |
| `platform` | `skeleton.redaction` |
| `platform` | `skeleton.web` |
| `redis-cache` | `skeleton.redis-cache` |
| `redis-core` | `skeleton.redis` |
| `redis-lock` | `skeleton.redis-lock` |
| `redis-rate-limit` | `skeleton.redis-rate-limit` |
| `scheduler` | `skeleton.scheduler` |
| `storage` | `skeleton.storage` |
| `storage-s3` | `skeleton.storage-s3` |
| `time` | `skeleton.time` |

## 3. What stays without Redis, Kafka, AWS

| Capability | Default without the optional module | Optional module |
|---|---|---|
| Rate limit (`skeleton.web.rate-limit`) | `InMemoryFixedWindowRateLimitStore` (per instance) | `redis-rate-limit` |
| Idempotency | `InMemoryIdempotencyStore` (per instance) | (Redis store: bring your own `IdempotencyStore`) |
| Scheduler lock | `NoopSkeletonScheduledLockManager` — runs locally, fine for a single instance | `redis-lock` |
| Retry queue | `job-queue-jdbc` — `FOR UPDATE SKIP LOCKED` (PostgreSQL / MySQL), safe with several instances | — |
| Notifications | in-memory broker | `notification-jdbc`, `-sse`, `-websocket`, `-slack`, `-mail` |
| Events | `event-kafka` off unless enabled | — |

## 4. Rename into your project

```bash
scripts/rename-skeleton.sh dev.sumin.ovation ovation Ovation
./gradlew build
```

Rewrites the root package (`dev.sumin.skeleton` → `dev.sumin.ovation`, also the slash form in resource paths and scripts), config prefix (`skeleton.*` → `ovation.*`,
including the YAML root key `skeleton:`), env-var placeholders (`SKELETON_*` → `OVATION_*`, `.env.example` too),
class/file names (`Skeleton*` → `Ovation*`, bean names `skeleton*` → `ovation*`), and name strings (`kotlin-skeleton`
→ `ovation` for `spring.application.name`, JWT issuer, Redis key prefix, SSM paths, OpenAPI title; `skeleton-*` →
`ovation-*` for thread-name prefixes, the Jackson module, Kafka headers, the AWS profile, `skeleton-jooq-schema.sql`).
It ends with a leftover scan and fails if any `skeleton` trace remains — a build cannot catch a leftover `skeleton:`
YAML root key, which silently disables every `ovation.*` setting in that file. Left alone on purpose: the
`skeleton_jobs` table (bound to module SQL), the sample API paths `/api/v1/skeleton/**` (the react-skeleton
workbench calls them), and `kotlin-skeleton` in Markdown titles. Verified by running the script on a copy of this
repo and building it.
