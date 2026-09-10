# Time — three temporal kinds

`modules/time` + `modules/persistence-jdbc`. The rule that prevents most global-service time bugs is to
classify every value into one of three kinds and never mix them.

| Kind | Type | DB column | Examples | Convert to viewer zone? |
|---|---|---|---|---|
| Something that happened | `Instant` | `DATETIME(6)` (UTC) | `createdAt`, paid at, sent at | yes |
| Calendar date | `LocalDate` | `DATE` | birthday, anniversary | **never** (March 5 stays March 5 in Brazil) |
| Scheduled local time | `ZonedMoment(local, zone, at)` | `xxx_local DATETIME(6)`, `xxx_zone VARCHAR(50)`, `xxx_at DATETIME(6)` | deadline, event start | show both event zone and viewer zone |

Why `ZonedMoment` instead of an `Instant` for future times: if the region changes its DST/offset rules
(Mexico 2022, Egypt 2023, Kazakhstan 2024) an `Instant` no longer means "21:00 local". `local + zone` is the
source of truth; `at` is derived for sorting/scheduling and can be recomputed (`recompute()`).

Forbidden: `TIMESTAMP` columns (2038, session conversion), bare `LocalDateTime` for instants,
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

## DB session UTC (persistence-jdbc)

`JdbcTimeZoneEnvironmentPostProcessor` sets Hikari `data-source-properties` `connectionTimeZone=UTC` and
`forceConnectionTimeZoneToSession=true`, so `NOW()` and stored literals are UTC regardless of JVM or DB
defaults. `UtcInstantConversions` passes `Instant` (as UTC `LocalDateTime`), `LocalDate`, and
`LocalDateTime` to JDBC as `JdbcValue` literals and interprets `DATETIME` reads as UTC.
`apps/api` `UtcRoundTripIntegrationTest` runs with the JVM default zone forced to `Asia/Seoul`.

Frontend counterpart: react-skeleton `src/lib/time` (`formatInstant`, `formatDate`, `formatDual`,
`toZonedMoment`, `createServerClock`, `defaultZoneOf`).
