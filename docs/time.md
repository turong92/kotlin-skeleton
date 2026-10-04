# Time — three temporal kinds

`modules/time` + a dialect module (`modules/db-postgresql` or `modules/db-mysql`). The rule that prevents most global-service time bugs is to
classify every value into one of three kinds and never mix them.

| Kind | Type | PostgreSQL column | MySQL column | Examples | Convert to viewer zone? |
|---|---|---|---|---|---|
| Something that happened | `Instant` | `timestamptz` | `datetime(6)` (UTC literal) | `createdAt`, paid at, sent at | yes |
| Calendar date | `LocalDate` | `date` | `date` | birthday, anniversary | **never** (March 5 stays March 5 in Brazil) |
| Scheduled local time | `ZonedMoment(local, zone, at)` | `xxx_local timestamp`, `xxx_zone varchar(50)`, `xxx_at timestamptz` | `xxx_local datetime(6)`, `xxx_zone varchar(50)`, `xxx_at datetime(6)` | deadline, event start | show both event zone and viewer zone |

Why `ZonedMoment` instead of an `Instant` for future times: if the region changes its DST/offset rules
(Mexico 2022, Egypt 2023, Kazakhstan 2024) an `Instant` no longer means "21:00 local". `local + zone` is the
source of truth; `at` is derived for sorting/scheduling and can be recomputed (`recompute()`).

Forbidden: MySQL `TIMESTAMP` columns (2038, session conversion), PG `timestamp` (without time zone) for instants, bare `LocalDateTime` for instants,
`ZoneId.systemDefault()`, `new Date('YYYY-MM-DD')` on the frontend.

## Viewer zone and locale

```kotlin
class NoteController(private val ctx: TimeContext, private val fmt: TimeFormatter) {
    fun show(note: Note) = fmt.dual(note.deadline)   // event = "… 9:00 PM GMT+9", viewer = "… 9:00 AM GMT-3"
}
```

Resolution order for both zone and locale: account preference (`UserTimePreferences` bean, e.g. from the
auth principal) → request headers (`X-Time-Zone`, `Accept-Language`) → `skeleton.time.default-zone` /
`default-locale`. The event zone is data (`ZonedMoment.zone`), not request context.

```yaml
skeleton:
  time:
    default-zone: Asia/Seoul
    default-locale: ko-KR
```

## Storing `ZonedMoment` with Spring Data JDBC

```kotlin
@Table("notes")
data class Note(
    @Id val id: Long? = null,
    @Embedded.Nullable(prefix = "deadline_") val deadline: ZonedMoment? = null,
)
```

Never trust a client-supplied `at`; accept `local + zone` and rebuild with `ZonedMoment.of(local, zone)`.

## Country → zone

`CountryTimeZones.defaultZoneOf("KR")` → `Asia/Seoul`. Multi-zone countries (US, BR, AU, …) return a
representative default first and the full list via `zonesOf`. Store the resolved zone on the entity, not
just the country, so later table changes do not move existing data. Regenerate the table from tzdata:

```bash
python3 modules/time/scripts/gen-country-zones.py /usr/share/zoneinfo/zone.tab \
  modules/time/src/main/resources/dev/sumin/skeleton/time/country-zones.tsv \
  [../react-skeleton/src/lib/time/country-zones.ts]
```

The JDK's own tzdata decides zone rules; zones unknown to the running JDK are skipped with a warning.
Updating rules means updating the JDK image.

## Binding instants in SQL (SqlDialect)

There is no JDBC type that round-trips an `Instant` correctly on both databases (measured 2026-10-01, JVM
`Asia/Seoul`, value `2026-03-01T00:30:00Z`):

| Bound as | PostgreSQL `timestamptz` (pgjdbc 42.7) | MySQL `datetime(6)` (Connector/J 9.7) |
|---|---|---|
| UTC `OffsetDateTime` | correct | stored as JVM wall clock `09:30` |
| `Timestamp.from(instant)` | correct | stored as JVM wall clock `09:30` |
| `Instant` | rejected by the driver | — |
| UTC `LocalDateTime` | **−9 h** (read in the session zone) | correct (`00:30`) |

So the binding lives in the dialect module the app assembles (`SqlDialect`, `modules/persistence-jdbc`):

- **JdbcClient / NamedParameterJdbcTemplate**: bind `dialect.instantParam(instant)`, read
  `dialect.readInstant(rs, "column")`. Never bind `Instant`, `Timestamp` or `LocalDateTime` for an instant yourself.
- **Spring Data JDBC**: nothing to do — `db-postgresql` registers `PostgresTimeConversions`, `db-mysql`
  registers `MySqlTimeConversions` (if you declare your own `JdbcCustomConversions`, include them).
- **jOOQ**: generated `*_at` fields are `Instant` (`docs/persistence-jooq.md`).
- `db-mysql` additionally forces the MySQL session to UTC (`MySqlTimeZoneEnvironmentPostProcessor`:
  `connectionTimeZone=UTC`, `forceConnectionTimeZoneToSession=true`, `preserveInstants=false`).

`apps/workbench` `UtcRoundTripIntegrationTest` (PostgreSQL) and the `db-postgresql` / `db-mysql` round-trip tests run
with the JVM default zone forced to `Asia/Seoul` and assert the UTC value stored in the database.

Frontend counterpart: react-skeleton `src/lib/time` (`formatInstant`, `formatDate`, `formatDual`,
`toZonedMoment`, `createServerClock`, `defaultZoneOf`).
