# persistence-jooq — jOOQ with code generation from Flyway migrations (no database at build time)

Optional module, same rank as `persistence-jpa` / `persistence-jdbc`.

What it gives you:

- `spring-boot-starter-jooq` wiring (`DSLContext`); the app assembles a dialect module (`modules:db-postgresql`
  by default, or `modules:db-mysql`)
- generated `*_at` columns are `java.time.Instant`: PostgreSQL `timestamptz` → jOOQ `INSTANT`; MySQL
  `datetime(6)` → `UtcInstantConverter` (UTC literal). Independent of the JVM default zone (tested with `Asia/Seoul`)
- `JooqAuditRecordListener` — fills `created_at` / `updated_at` on insert/update when the record has them
- a working Gradle recipe that generates code **from the Flyway migration folder** with `DDLDatabase`, so the build needs no DB

## App recipe

```kotlin
// apps/<app>/build.gradle.kts
plugins { id("org.jooq.jooq-codegen-gradle") version "3.21.7" }   // same as the Boot-managed jOOQ version

dependencies {
    implementation(project(":modules:persistence-jooq"))
    jooqCodegen("org.jooq:jooq-meta-extensions")
}

jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    // the app's own migration folder, read in Flyway order — codegen and runtime schema never drift
                    property { key = "scripts"; value = "src/main/resources/db/migration/postgresql" }
                    property { key = "sort"; value = "flyway" }
                    property { key = "unqualifiedSchema"; value = "none" }
                    property { key = "defaultNameCase"; value = "lower" }
                }
                forcedTypes {
                    // *_at timestamptz = a moment → Instant (jOOQ binds it as OffsetDateTime at runtime)
                    forcedType {
                        name = "INSTANT"
                        includeExpression = "(?i:.*_at)"
                        includeTypes = "(?i:timestamp.*with.*time.*zone)"
                    }
                }
            }
            target { packageName = "<your.package>.jooq"; directory = "build/generated-src/jooq/main" }
        }
    }
}
sourceSets.main { java.srcDir("build/generated-src/jooq/main") }
tasks.named("compileKotlin") { dependsOn("jooqCodegen") }
tasks.named("compileJava") { dependsOn("jooqCodegen") }
```

Module tables you query with jOOQ (`skeleton_jobs`, `skeleton_notification_inbox`) live in the module jars, not in
your folder: add `src/main/resources/db/migration/postgresql` of those modules to `scripts` (comma-separated
paths are not supported — collect them into one build directory with a `Sync` task first, as
`modules/persistence-jooq/build.gradle.kts` `collectModuleDdl` does), or skip them if you only use the module API.

Measured with jOOQ 3.21.7 (2026-10-01): identity columns, `timestamptz`, `jsonb`, partial indexes (`where`),
`comment on`, `alter table … add column if not exists` and Flyway ordering across files all parse. `jsonb`
columns are generated as `JSON`.

### MySQL (`modules:db-mysql`)

`scripts` → `src/main/resources/db/migration/mysql`, add `property { key = "parseIgnoreComments"; value = "true" }`,
and replace the forced type with:

```kotlin
forcedType {
    userType = "java.time.Instant"
    converter = "dev.sumin.skeleton.persistence.jooq.UtcInstantConverter"
    includeExpression = "(?i:.*_at)"
    includeTypes = "(?i:datetime.*|timestamp.*)"
}
```

This module switches between the two with `-Pskeleton.jooq.dialect=mysql` (default `postgresql`).
MySQL parser rules: standard DDL only — no `engine=`, `charset=`, `collate` clauses
(keep those in a separate MySQL-only file if you need them, or apply them by `alter table` later).

**Inline indexes.** MySQL's `create table (…, index idx_x (a, b))` is not standard DDL and the parser rejects
the whole file ("Your SQL string could not be parsed"). Moving the index to a separate `create index` is not
an option either: MySQL 8.4 has no `create index if not exists`, so a `schema.sql` that runs on every boot
would fail the second time. Keep the inline index and hide it from the parser with jOOQ's ignore markers,
putting the preceding comma inside the ignored span so the parser never sees `not null,)`:

```sql
create table if not exists skeleton_jobs (
    …,
    updated_at datetime(6) not null
    /* [jooq ignore start] */,
    index idx_skeleton_jobs_claim (status, next_run_at),
    index idx_skeleton_jobs_running (status, locked_at)
    /* [jooq ignore stop] */
);
```

MySQL and Spring's `ScriptUtils` treat the markers as plain block comments and run the index clauses;
`DDLDatabase` skips them only when `parseIgnoreComments=true` is set (above). The module migrations
(`job-queue-jdbc`, `notification-jdbc`) already carry these markers, so they can be copied into `schema.sql`
verbatim — `modules/persistence-jooq -Pskeleton.jooq.dialect=mysql` generates code from them.
The same file is what `spring.sql.init.mode=always` runs at boot in the no-Flyway mode
(`docs/schema-management.md`), so codegen and runtime schema never drift.

## Time column convention

| column name | generated type | meaning |
|---|---|---|
| `*_at` (`created_at`, `paid_at`, `deadline_at`) | `Instant` | a moment (UTC) |
| `*_local` | `LocalDateTime` | wall-clock of a `ZonedMoment` (pair with `*_zone`) |
| `DATE` | `LocalDate` | calendar date, never converted |

## Testing pattern

`modules/persistence-jooq` `JooqIntegrationTest`: Testcontainers PostgreSQL 18, schema applied with
`spring.sql.init` from the same DDL used for codegen, JVM default zone forced to Seoul, asserts raw
column literals and round-tripped values.
