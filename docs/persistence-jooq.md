# persistence-jooq — jOOQ with code generation from `schema.sql` (no database at build time)

Optional module, same rank as `persistence-jpa` / `persistence-jdbc`.

What it gives you:

- `spring-boot-starter-jooq` wiring (`DSLContext`), MySQL session forced to UTC (Hikari driver properties)
- `UtcInstantConverter` — generated `*_at` DATETIME columns become `java.time.Instant`, UTC-fixed,
  independent of the JVM default zone (tested with the JVM set to `Asia/Seoul`)
- `JooqAuditRecordListener` — fills `created_at` / `updated_at` on insert/update when the record has them
- a working Gradle recipe that generates code **from a DDL file** with `DDLDatabase`, so CI needs no DB

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
                    property { key = "scripts"; value = "src/main/resources/schema.sql" }
                    property { key = "sort"; value = "semantic" }
                    property { key = "unqualifiedSchema"; value = "none" }
                    property { key = "defaultNameCase"; value = "lower" }
                }
                forcedTypes {
                    forcedType {
                        userType = "java.time.Instant"
                        converter = "dev.sumin.skeleton.persistence.jooq.UtcInstantConverter"
                        includeExpression = "(?i:.*_at)"
                        includeTypes = "(?i:datetime.*|timestamp.*)"
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

`schema.sql` rules for the parser: standard DDL only — no `engine=`, `charset=`, `collate` clauses
(keep those in a separate MySQL-only file if you need them, or apply them by `alter table` later).
The same file is what `spring.sql.init.mode=always` runs at boot in the no-Flyway mode
(`docs/schema-management.md`), so codegen and runtime schema never drift.

## Time column convention

| column name | generated type | meaning |
|---|---|---|
| `*_at` (`created_at`, `paid_at`, `deadline_at`) | `Instant` | a moment (UTC) |
| `*_local` | `LocalDateTime` | wall-clock of a `ZonedMoment` (pair with `*_zone`) |
| `DATE` | `LocalDate` | calendar date, never converted |

## Testing pattern

`modules/persistence-jooq` `JooqIntegrationTest`: Testcontainers MySQL 8.4, schema applied with
`spring.sql.init` from the same DDL used for codegen, JVM default zone forced to Seoul, asserts raw
column literals and round-tripped values.
