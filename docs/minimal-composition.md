# Minimal composition — picking a few modules

`apps/api` deliberately includes **every** module to prove they coexist, so its `application.yml` carries
settings for all of them. A real service keeps only what it needs. This page is the recipe.

## 1. Pick modules

Everything depends on `platform`. Everything else is optional. A typical small service:

```kotlin
// apps/<app>/build.gradle.kts
dependencies {
    implementation(project(":modules:platform"))
    implementation(project(":modules:auth"))              // JWT, dev-login, break-glass
    implementation(project(":modules:persistence-jdbc"))  // audit timestamps + UTC-safe time types
    implementation(project(":modules:time"))              // viewer zone/locale, ZonedMoment
    implementation(project(":modules:job-queue-jdbc"))    // MySQL retry queue (no Redis)
    implementation(project(":modules:storage-s3"))        // R2 / S3
    implementation(project(":modules:scheduler"))         // annotation-driven jobs (Noop lock by default)

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    runtimeOnly("com.mysql:mysql-connector-j")
}
```

Keep only those lines in `settings.gradle.kts` `include(...)` as well; delete the module directories you
do not include (or leave them — unincluded directories are ignored by Gradle).

Your app may live in any package (`dev.sumin.app1`, `com.acme.shop`): modules register their own controllers,
filters and advice through `AutoConfiguration.imports`, never through your component scan. `apps/api`
`ModuleSelfRegistrationIntegrationTest` boots the modules under a root configuration that scans nothing to keep it
that way.

## 2. Delete the settings of modules you dropped

Each module owns exactly one configuration prefix. Remove the matching block from `application*.yml`.
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
| Retry queue | `job-queue-jdbc` — MySQL `FOR UPDATE SKIP LOCKED`, safe with several instances | — |
| Notifications | in-memory broker | `notification-jdbc`, `-sse`, `-websocket`, `-slack`, `-mail` |
| Events | `event-kafka` off unless enabled | — |

## 4. Rename into your project

```bash
scripts/rename-skeleton.sh dev.sumin.ovation ovation Ovation
./gradlew build
```

Rewrites the root package (`dev.sumin.skeleton` → `dev.sumin.ovation`), config prefix (`skeleton.*` →
`ovation.*`), env-var placeholders (`SKELETON_*` → `OVATION_*`), and class/file names (`Skeleton*` →
`Ovation*`), including `META-INF/spring/*.imports`. Verified by running the script on a copy of this repo
and building it.
