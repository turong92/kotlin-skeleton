# Schema management — with or without Flyway

`apps/api` ships with Flyway (`spring-boot-starter-flyway`, the dialect's Flyway support comes from
`modules:db-postgresql` / `modules:db-mysql`) and `modules:migration-flyway` (on top of the tool-agnostic `modules:migration`: clean guard, opt-in local clean,
naming check). Both modes below are supported and tested.

## Mode A — Flyway (default)

### Where migrations live

```
src/main/resources/db/migration/<vendor>/V<yyyyMMddHHmmss UTC>__<snake_case>.sql      vendor = postgresql | mysql
```

`spring.flyway.locations=classpath:db/migration/{vendor}` — set by the app (`apps/api` `application.yml`); required
because modules ship one folder per database (without it Flyway scans both and fails on the other dialect's SQL). Boot fills `{vendor}` from the connected database, so the app's migrations and
every module's migrations (`job-queue-jdbc`, `notification-jdbc`, …) merge on one path and the folder for the
other database is never read. A module that supports both databases ships both folders with the same version
and name.

### Naming: UTC timestamps, generated

Versions are UTC timestamps to the second — two branches or worktrees adding a migration at the same time no
longer fight over `V25`. Do not type the timestamp by hand:

```bash
./gradlew newMigration -Pname=add_artist_country                       # apps/api, every vendor folder it has
./gradlew newMigration -Pname=add_x -Pmodule=modules/job-queue-jdbc    # both postgresql and mysql, same version
./gradlew newMigration -Pname=add_x -Pvendor=postgresql
```

`./gradlew build` fails (`modules:migration-flyway` `RepositoryMigrationsTest`, rules in `MigrationFileRules`)
when anywhere in the repository — app, modules, test resources:

- a file under `db/migration/` is not in a `<vendor>/` folder, or its name does not match
  `V<14 digits>__<snake_case>.sql`, or the 14 digits are not a real UTC date-time;
- two files in the same vendor share a version;
- a source folder with both vendor folders has a migration in one and not the other.

The walk does not enter `build`, `.gradle`, `.git`, `.kotlin`, `node_modules`, `out`, `.claude`, or any directory
below the root that has its own `.git` entry (a nested git worktree or repository — e.g. Claude Code's
`.claude/worktrees/<name>/`). Those hold copies of the same migrations and would otherwise fail the build with
"duplicate version". The Gradle test inputs in `modules/migration-flyway/build.gradle.kts` exclude the main ones
(`build`, `node_modules`, `.claude`, `.git`) so those copies do not invalidate the test either.

### outOfOrder — the app's choice (`apps/api` turns it on)

The skeleton modules do not change Flyway's defaults; usage choices like this live in the app's `application.yml`
as explicit overrides. `apps/api` sets `spring.flyway.out-of-order=true` (all environments) and
`spring.flyway.validate-migration-naming=true`, and a derived app keeps or drops them. Why `apps/api` turns it on: with timestamps, a
branch merged late legitimately brings a file whose timestamp is older than migrations already applied in
dev/stage/prod. Without outOfOrder that file either fails validation or never runs.

The price is one rule: **migrations are independent.** A migration may not rely on another branch's migration
that has not been applied yet — write it so that it works whichever of the two runs first
(`add column if not exists`, `create index if not exists`, no reliance on a column another open branch adds).

### Two branches touching the same table

Each branch writes its own independent migration. If they conflict after merging (same column, incompatible
types), do not edit either file once it has reached a shared environment — add a new migration that reconciles
them.

### When you may edit a migration — never, once it is locked

`migrations.lock` (repository root) pins the sha256 of every Flyway migration the repository ships (apps and modules,
both dialects, `src/main/resources` only). **The files in it are frozen from this commit on** (the next release tag
carries the same lock): a locked file is never edited or deleted in place — the next change is a new `V` file.
`modules:migration-flyway` `MigrationLockTest` (so `./gradlew build`, and CI) runs `perl scripts/migrations-lock.pl --check`:

| What changed | Result | Do |
|---|---|---|
| a locked file's content changed, or the file is gone | fails: "이미 배포됐을 수 있는 마이그레이션은 고치지 않는다 — 새 V 파일을 추가하라" | undo the edit and add a new file: `./gradlew newMigration -Pname=<what>` |
| a new file newer than every locked version of that dialect | fails until the lock adds it | `perl scripts/migrations-lock.pl` — it adds new files only and never touches an existing line |
| a new file *between* locked versions (out-of-order) | fails with a separate message (the skeleton's own files are stacked in order; `outOfOrder` stays the app's choice) | take a fresh version with `newMigration` |
| exception: a locked file that really never left your machine | — | `perl scripts/migrations-lock.pl --rewrite <path>` (changed hash, or the line of a deleted file), and write it in `CHANGELOG.md` |

`perl scripts/migrations-lock.pl --regenerate` rebuilds the whole lock. It is for `scripts/new-project.sh`, which locks a
freshly stamped project's own files (a derived project owns its lock from then on). Never use it to get past a failing check.

`baseline` (first line of the lock) is the schema the upgrade test starts from: `MigrationUpgradeIntegrationTest`
(`apps/sample`, PostgreSQL and MySQL, one shared container each) migrates a database to `target = baseline`, inserts
representative rows for every module, migrates to the latest and asserts the rows are alive and (PostgreSQL) the
repositories read and write them. So every new migration runs over existing data — one that adds a `NOT NULL` column
without a default fails there. The baseline does not move when files are added; move it only together with the test's rows.

#### Local databases

The `local` profile no longer wipes your database. `skeleton.migration.clean-on-validation-error` is `false` in every
app's `application-local.yml` (`apps/api`, `apps/sample`, `apps/workbench`); the module's setting still exists and still
defaults to `false`. When an applied migration changed, startup fails with a short message (`FlywayValidationFailureAnalyzer`):
undo the edit and add a new file, or — for a database you really can throw away — `docker compose down -v` and start again.
For one run with the old wipe-and-reapply behaviour set `SKELETON_MIGRATION_CLEAN_ON_VALIDATION_ERROR=true` (it is still
refused outside `skeleton.migration.clean-allowed-profiles`, default `local`). It assumes the app runs with `out-of-order=true`
(as `apps/api` does): with it off, a late-merged older file makes `migrate()` itself fail and the strategy wipes.

Guard (`modules:migration`, tool-agnostic): `skeleton.migration.clean-on-validation-error=true`, `spring.flyway.clean-disabled=false` or `spring.liquibase.drop-first=true` outside
`skeleton.migration.clean-allowed-profiles` (default `local`) fails startup before the database is touched.
No active profile counts as `default`, which is not allowed.

The guard is an `EnvironmentPostProcessor`, so it runs before any bean exists. An app that adds its own startup
guard on top (e.g. "stage/prod never allow clean, whatever the allowed profiles say") must also run before the
`Flyway` bean — `FlywayMigrationInitializer`, which needs `Flyway`, runs `migrate()` (and the local clean strategy)
when it is created, and with no ordering Spring may create both first (Ovation measured jOOQ → Flyway being created before its
`DeployGuards` bean, so the guard fired only after clean had wiped the database). Either:

- make the app guard an `EnvironmentPostProcessor` too (registered in `META-INF/spring.factories`) when it only
  reads the `Environment`; or
- keep it a bean and make every `Flyway` bean depend on it with a **static** `BeanFactoryPostProcessor`
  (what Ovation does, verified by an `ApplicationContextRunner` test that registers a Flyway configuration first and
  asserts the guard fails startup before the Flyway bean is created):

```kotlin
@Configuration(proxyBeanMethods = false)
class DeployGuardsConfiguration {
    @Bean // the bean name is the method name — FlywayAfterDeployGuards refers to it
    fun deployGuardsChecked(/* inputs */): Any { /* throw when clean is on in stage/prod */ return Any() }

    companion object {
        @JvmStatic
        @Bean
        fun flywayAfterDeployGuards(): BeanFactoryPostProcessor = FlywayAfterDeployGuards()
    }
}

// org.springframework.boot.autoconfigure.AbstractDependsOnBeanFactoryPostProcessor
private class FlywayAfterDeployGuards : AbstractDependsOnBeanFactoryPostProcessor(Flyway::class.java, "deployGuardsChecked")
```

`@DependsOn` on the guard does not help (it orders the guard after its own dependencies, not Flyway after the
guard), and a guard inside a custom `FlywayMigrationStrategy` would replace the skeleton's local clean strategy
(`@ConditionalOnMissingBean`).

## Mode B — `schema.sql` only (no migration tool yet)

For a v0 with no external data yet. In `application.yml`:

```yaml
spring:
  flyway:
    enabled: false
  sql:
    init:
      mode: always            # runs src/main/resources/schema.sql on every start (idempotent DDL!)
      continue-on-error: false
```

Rules for `schema.sql` in this mode:

- Every statement must be idempotent: `create table if not exists …`, `create index if not exists …`.
  It runs on every boot.
- Copy the module migrations you depend on into `schema.sql` verbatim (e.g. `jobs` from
  `modules/job-queue-jdbc/src/main/resources/db/migration/postgresql/`). Module jars still contain the Flyway
  file, but nothing runs it in this mode. On MySQL (`db/migration/mysql/`) the inline `index` clauses sit
  between `/* [jooq ignore start] */ … /* [jooq ignore stop] */` markers so the same file also feeds jOOQ codegen
  (`docs/persistence-jooq.md`); MySQL 8.4 has no `create index if not exists`.
- You may remove the flyway dependencies from the app; leaving them is harmless when `spring.flyway.enabled=false`.

Proof: `apps/workbench` `SchemaSqlInitIntegrationTest` boots with these properties against Testcontainers PostgreSQL
and verifies the table exists and `flyway_schema_history` does not; a second test checks the copied
`jobs` indexes exist and re-runs the script to prove it is idempotent.

## Moving from Mode B to Flyway later

1. `./gradlew newMigration -Pname=init` and paste the current `schema.sql` into it.
2. Set `spring.flyway.enabled=true`, `spring.flyway.baseline-on-migrate=true` and
   `spring.flyway.baseline-version=<that file's 14-digit version>` (existing databases are marked as already
   at that version; empty databases run it).
3. Remove `spring.sql.init.mode` and delete `schema.sql`.
4. From now on every change is a new file from `newMigration`; never edit applied files.
