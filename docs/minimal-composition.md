# Minimal composition — picking a few modules

Two apps ship with the skeleton:

| App | What it is | Modules |
|---|---|---|
| `apps/api` | the **starter** — the minimal recipe below, built and booted by its own tests | `platform`, `auth`, `persistence-jdbc`, `db-postgresql`, `migration-flyway`, `time` |
| `apps/workbench` | the **demo** — every module together, with sample endpoints under `/api/v1/skeleton/**` | all of them |

A real service starts from `apps/api` and keeps only what it needs. This page is the recipe. To stamp a project in one command
(copy, pick modules, rename): `scripts/new-project.sh` — see §5.

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

## 2. Settings: copy a block, delete a block

The starter's yml carries no module blocks, and neither should yours: modules ship their defaults, so an app yml holds only the
values that differ. `docs/config/modules/<module>.yml` lists **every key with its default and a one-line comment** for each module that has
a prefix — copy the block you need, keep the keys you change. `apps/workbench` ModuleConfigSnippetsTest keeps these files honest (every
key binds to the module's `@ConfigurationProperties`, every value equals the default, no property is missing).

If you started from `apps/workbench` instead, each module owns exactly one configuration prefix: remove the matching block from
`application*.yml`, or reduce the file to what differs from the snippets. Unknown `skeleton.*` keys are ignored by Spring Boot, but
`skeleton.config.validation` (platform) can be configured to require keys, so keep the file honest.

Relaxed binding gives every key an environment variable (`skeleton.redis.key-prefix` → `SKELETON_REDIS_KEY_PREFIX`), so yml placeholders
like `${SKELETON_REDIS_HOST:localhost}` are never needed; only vendor-standard names (`JWT_SECRET`, `AWS_PROFILE`, `STRIPE_SECRET_KEY`)
need an alias. `.env.example` lists the variables per module in commented sections. A test (`modules/platform` `ConfigPrefixDocumentationTest`)
fails when this table and the prefixes found in module code differ.

| 모듈 | 설정 접두사 |
|---|---|
| `async` | `skeleton.async` |
| `async-notification` | `skeleton.async-notification` |
| `auth` | `skeleton.auth` |
| `auth-social` | `skeleton.auth-social` |
| `captcha-turnstile` | `skeleton.captcha-turnstile` |
| `config-aws-ssm` | `skeleton.config.aws.ssm` |
| `crypto` | `skeleton.crypto` |
| `event-kafka` | `skeleton.event-kafka` |
| `idempotency` | `skeleton.idempotency` |
| `job-queue-jdbc` | `skeleton.job-queue` |
| `migration` | `skeleton.migration` |
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
| `platform` | `skeleton.openapi` |
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

## 3. Adding a module turns it on — except when it would not boot

**Rule.** Adding a module turns its capability on with its defaults. The exception is anything that would fail or hang boot without
external infrastructure or required config: that must either **degrade safely** (boot, and fail or fall back at first use) or **stay
off until configured**. A module never needs a yml block just to let the app start, and every infrastructure-dependent module has a
test proving a context with only that module (and its declared dependencies) boots with no configuration and no infrastructure
(`*BootWithoutConfigurationTest`; `ApplicationContextRunner`, no Redis / Kafka / S3 / SMTP running).

| Module | On when added? | Requires to boot | Needed only for its feature |
|---|---|---|---|
| `platform` | yes | nothing | — |
| `auth` | yes | nothing in `local` / `dev` / `test` / no profile; in `prod` / `staging` (`skeleton.auth.protected-profiles`): `skeleton.auth.jwt.secret` ≥ 32 bytes and your own `AuthAccountRepository` bean | — |
| `auth-social` | yes (no provider until enabled) | nothing | provider keys |
| `auth-social-google` / `-kakao` / `-naver` | off until `skeleton.auth-social.providers.<x>.enabled=true` | nothing | client id / secret |
| `async` | yes | nothing | — |
| `async-notification` | yes | `notification` (declared dependency) | — |
| `captcha-turnstile` | off until `enabled=true` + `secret-key` | nothing | Turnstile secret, internet |
| `config-aws-ssm` | loads only once `paths` are set (dev / staging / prod) or `credential-profile` is set | nothing | AWS credentials when it loads (`fail-fast` decides) |
| `crypto` | beans only when `skeleton.crypto.keys` has a key | nothing | AES keys |
| `db-postgresql` / `db-mysql` | yes — exactly one | a reachable database matching the dialect (`SqlDialectVerifier` names the mismatch) | — |
| `event-kafka` | yes, as a logging sender | nothing | `enabled=true` + a `KafkaOperations` bean + Kafka |
| `idempotency` | yes (in-memory store) | nothing | — |
| `job-queue-jdbc` | yes | a `DataSource` + one `db-*` module (its `skeleton_jobs` migration is picked up by `spring.flyway.locations: classpath:db/migration/{vendor}`) | — |
| `json` | yes | nothing | — |
| `migration` / `migration-flyway` | yes | Flyway on the classpath (`spring-boot-starter-flyway`) | — |
| `notification` | yes (in-memory broker) | nothing | — |
| `notification-jdbc` | yes | a `DataSource` + one `db-*` module (+ its migration, as above) | — |
| `notification-mail` | off until `enabled=true` + `spring.mail.host` + `from` | nothing | an SMTP server |
| `notification-slack` | wired; sends nothing until `enabled=true` + `webhook-url` | nothing | webhook URL, internet |
| `notification-sse` | yes | nothing | — |
| `notification-websocket` | yes (in-process simple broker; the endpoint is open until `authentication.enabled=true`) | a servlet web app | — |
| `payment` | yes (routing only) | nothing | a provider module |
| `payment-toss` / `payment-stripe` | off until `enabled=true` | nothing | secret key, internet |
| `persistence-jdbc` | yes | a `DataSource` + one `db-*` module | — |
| `persistence-jpa` | yes | a `DataSource` + JPA starter | — |
| `persistence-jooq` | yes | a `DataSource` (code generation needs no database) | — |
| `redis-core` | yes | nothing — the connection is lazy | a Redis server at first command |
| `redis-lock` | yes — the Redisson client connects lazily; **startup check is opt-in** (`startup-check.enabled=true`, recommended in production) | nothing | a Redis server at first lock; without it `@DistributedLock` fails loudly |
| `redis-cache` | yes — `FAIL_OPEN`: cache errors are ignored | nothing | a Redis server for hits |
| `redis-rate-limit` | yes — replaces the in-memory store, used only when `skeleton.web.rate-limit.enabled=true`; `FAIL_OPEN` | nothing | a Redis server when rate limiting is on |
| `scheduler` | yes (no-op lock, single instance; its own `skeletonTaskScheduler`, replaced by a bean of the same name) | nothing | `redis-lock` for several instances |
| `storage` | yes | nothing | — |
| `storage-s3` | yes — no bucket, no `PresignedStorage` bean | nothing | bucket (+ endpoint / keys for R2 · MinIO) |
| `time` | yes | nothing | — |

What changed to make this true (2026-10-05 audit of every `matchIfMissing = true` and `enabled: false` pairing): `redis-lock` used to
connect to Redis during boot (Redisson eager start + a startup check on by default) — now lazy, with the check opt-in;
`config-aws-ssm` used to fail every `dev` / `staging` / `prod` boot that had the module but no `paths` — now it loads only once configured;
`scheduler` used to back off when any other module defined a `TaskScheduler` (`notification-websocket` defines two), which left its registrar
with an ambiguous injection — it now owns a `skeletonTaskScheduler`. The others (`redis-cache`, `redis-rate-limit`, `storage-s3`,
`notification-websocket`, `event-kafka`) already degrade safely; the workbench yml only restated switches.

## 4. What stays without Redis, Kafka, AWS

| Capability | Default without the optional module | Optional module |
|---|---|---|
| Rate limit (`skeleton.web.rate-limit`) | `InMemoryFixedWindowRateLimitStore` (per instance) | `redis-rate-limit` |
| Idempotency | `InMemoryIdempotencyStore` (per instance) | (Redis store: bring your own `IdempotencyStore`) |
| Scheduler lock | `NoopSkeletonScheduledLockManager` — runs locally, fine for a single instance | `redis-lock` |
| Retry queue | `job-queue-jdbc` — `FOR UPDATE SKIP LOCKED` (PostgreSQL / MySQL), safe with several instances | — |
| Notifications | in-memory broker | `notification-jdbc`, `-sse`, `-websocket`, `-slack`, `-mail` |
| Events | `event-kafka` off unless enabled | — |

## 5. Start a project: one command

```bash
scripts/new-project.sh <target-dir> <root-package> <config-prefix> <ClassPrefix> \
    [--modules a,b,c] [--db postgresql|mysql] [--with-workbench]

scripts/new-project.sh ~/work/ovation dev.sumin.ovation ovation Ovation --modules job-queue-jdbc,notification-mail,storage-s3,scheduler
scripts/new-project.sh ~/work/ovation dev.sumin.ovation ovation Ovation --db mysql --modules job-queue-jdbc
```

It copies the repo (without `build`, `.gradle`, `.kotlin`, `.git`, `.superpowers`, `.claude`, `.env`), keeps `apps/api`, and drops `apps/workbench`
unless `--with-workbench`. Then:

1. **Module set** = the starter's modules + `--modules`, closed over `project(":modules:x")` dependencies read from each module's
   `build.gradle.kts` (`api` · `implementation` · `runtimeOnly`; for example `storage-s3` pulls `storage` and `crypto`, `migration-flyway` pulls
   `migration`). A `testImplementation(project(...))` is followed too, because that module's tests would not compile without it — the script prints
   `note: tests of persistence-jooq need db-postgresql`. Every step prints why a module is in.
2. **Unselected modules are removed** everywhere they are referenced: the directory, `docs/config/modules/<m>.yml`, the `settings.gradle.kts` include,
   the root `dbTestModules` set, the `postgresTest` / `mysqlTest` suite of a dialect that is gone (and `src/<suite>/` folders), and the classes the
   starter's "optional integrations are absent" test asserts for modules you did select (`S3Client` for `storage-s3`, `jakarta.mail.Session` for
   `notification-mail`, `RedisTemplate` for `redis-*`, …). `persistence-jooq` reads sibling `job-queue-jdbc` / `notification-jdbc` migration folders by
   path; Gradle ignores a missing `from` directory, so the code generation simply has no module DDL to add when those modules were not selected.
3. **`--modules` names** get one `implementation(project(":modules:<m>"))` line in `apps/api/build.gradle.kts`, and their
   `docs/config/modules/<m>.yml` is appended, commented, to a marked section at the end of `apps/api/.../application.yml` (modules that come
   along through the closure and have a block are appended too). The blocks are appended after the rename so their root key already is your prefix.
4. **`--db mysql`**: `db-postgresql` → `db-mysql` in the module set and `apps/api`, datasource URL (`application.yml`, `.env.example`),
   the compose service (`mysql` becomes the default service, `postgres` goes), the Testcontainers configuration (`MySQLContainer`), and the starter's
   first migration (`db/migration/mysql/`, the `postgresql` folder goes). `--with-workbench` is PostgreSQL-only and refuses `--db mysql`.
5. **Rename**: `scripts/rename-skeleton.sh` runs inside the target and fails on any leftover `skeleton` trace.

Invalid input exits 2 and creates nothing: unknown module (the message lists the valid ones), target exists or lies inside the skeleton repo,
unknown option, `--db` other than `postgresql` / `mysql`, a `db-*` name in `--modules`. The script writes only inside the target and runs on
macOS bash 3.2 and GNU bash. `scripts/test-new-project.sh` tests it (`--quick` runs inside `./gradlew check`; `--full` stamps three compositions —
defaults; `--modules job-queue-jdbc,notification-mail,storage-s3,scheduler`; `--db mysql --modules job-queue-jdbc` — and runs `./gradlew build` in each,
needing Docker; CI: `.github/workflows/new-project.yml`).

### Rename only

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
