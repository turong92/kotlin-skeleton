# Schema management — with or without Flyway

`apps/api` ships with Flyway (`spring-boot-starter-flyway` + `flyway-mysql`) and `db/migration/V1__init.sql`.
Both modes are supported and tested.

## Mode A — Flyway (default)

Versioned SQL under `src/main/resources/db/migration/V{n}__{desc}.sql`. Module-owned tables ship their own
migrations inside the module jar (`notification-jdbc`, `job-queue-jdbc`, …) and are picked up because
Flyway scans `classpath:db/migration` recursively across jars. Modules use date-based versions
(`V2026091001__…`) so they never collide with app versions `V1…V999`.

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

- Every statement must be idempotent: `create table if not exists …`, `create index` guarded, etc.
  It runs on every boot.
- Copy the module migrations you depend on into `schema.sql` (e.g. `skeleton_jobs` from
  `modules/job-queue-jdbc/src/main/resources/db/migration/`). Module jars still contain the Flyway
  file, but nothing runs it in this mode.
- You may remove the two flyway dependencies from `apps/api/build.gradle.kts`; leaving them is harmless
  when `spring.flyway.enabled=false`.

Proof: `apps/api` `SchemaSqlInitIntegrationTest` boots with these properties against Testcontainers MySQL
and verifies the table exists and `flyway_schema_history` does not.

## Moving from Mode B to Flyway later

1. Take the current `schema.sql` verbatim as `db/migration/V1__init.sql`.
2. Set `spring.flyway.enabled=true`, `spring.flyway.baseline-on-migrate=true`, `baseline-version=1`
   (existing databases are marked as already at V1; empty databases run V1).
3. Remove `spring.sql.init.mode` and delete `schema.sql`.
4. From now on every change is a new `V{n}__…sql`; never edit applied files.
