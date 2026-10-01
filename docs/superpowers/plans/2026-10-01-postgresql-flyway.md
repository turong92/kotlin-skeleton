# PostgreSQL 기본 + 방언 조립식 + Flyway 충돌 방지 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 스켈레톤의 DB 계층을 "전략 인터페이스(`SqlDialect`) + 방언 모듈(`db-postgresql` / `db-mysql`) 조립식"으로 바꾸고 PostgreSQL 을 기본으로 하며, Flyway 마이그레이션을 UTC 타임스탬프 버전 · outOfOrder · 로컬 전용 clean · 빌드 검사로 충돌 없게 만든다.

**Architecture:** `persistence-jdbc` 가 `SqlDialect` 인터페이스와 "방언 모듈 정확히 하나 + 연결된 DB 와 일치" 검증기를 가진다. 기능 모듈(`job-queue-jdbc`, `notification-jdbc`)은 인터페이스만 쓰고, 테이블 DDL 을 `db/migration/<vendor>/` 두 벌로 들고 다닌다. 앱은 방언 모듈 하나를 끼우고 Flyway 는 `classpath:db/migration/{vendor}` 로 맞는 폴더를 고른다. 새 모듈 `migration-flyway` 가 Flyway 기본값 · 가드 · 로컬 clean · 이름 규칙 검사를 담당한다.

**Tech Stack:** Spring Boot 4.1.1, Kotlin 2.3.21, Gradle 9.4.1 (JVM Test Suite), Flyway 12.4.0, pgjdbc 42.7.x, Connector/J 9.7.0, jOOQ 3.21.7 (`DDLDatabase`), Testcontainers 2.x (`postgres:18`, `mysql:8.4`).

**Spec:** `docs/superpowers/specs/2026-10-01-postgresql-flyway-design.md`

## Global Constraints

- 작업 위치: `~/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton` (`main`). **커밋은 주인이 말할 때만, push 안 함.** 각 작업 끝의 "체크포인트" 는 테스트 확인까지만.
- 같은 디렉토리에서 Gradle 빌드를 동시에 두 개 돌리지 않는다 (생성 소스가 서로 지워짐).
- PostgreSQL 이미지 `postgres:18`, MySQL 이미지 `mysql:8.4` — Testcontainers · docker-compose 동일.
- 마이그레이션 이름: `^V\d{14}__[a-z0-9]+(_[a-z0-9]+)*\.sql$`, 14자리 = UTC `yyyyMMddHHmmss`. 위치: `src/<sourceSet>/resources/db/migration/<vendor>/`, vendor ∈ {`postgresql`, `mysql`}.
- Flyway 위치: `spring.flyway.locations=classpath:db/migration/{vendor}`.
- `spring.flyway.out-of-order=true` 모든 환경.
- 새 설정 접두사: `skeleton.migration` (`clean-on-validation-error` 기본 `false`, `clean-allowed-profiles` 기본 `[local]`). Gradle 속성 `skeleton.jooq.dialect` 기본 `postgresql`.
- 시간 바인딩: PG = UTC `OffsetDateTime`, MySQL = UTC `LocalDateTime`. 앱 코드는 `SqlDialect.instantParam / readInstant` 를 거친다.
- 테이블 이름 `skeleton_jobs`, `skeleton_notification_inbox` 와 잡 핸들러 멱등 의미 유지.
- 모듈의 모든 빈은 AutoConfiguration 으로 등록 (앱 컴포넌트 스캔에 기대지 않음), `.imports` 파일 등록.
- KDoc 안에 `/**` 를 쓰지 않는다 (예: 경로 `db/migration/**` → "`db/migration/` 하위"로 풀어 씀) — 중첩 주석으로 컴파일 실패.

## Review Focus

- PG 에서 같은 알림을 같은 트랜잭션 안에서 두 번 저장해도 트랜잭션이 깨지지 않아야 한다 (예외 삼키기 → PG abort). → Task 5 테스트.
- 로컬 clean 전략이 "새 마이그레이션이 미적용(pending)" 인 정상 상황에서 DB 를 밀면 안 된다 — 체크섬 불일치 같은 진짜 검증 실패에서만. → Task 6 테스트.
- 활성 프로필이 없는(default) 상태에서 `clean-on-validation-error=true` 면 가드가 막아야 한다 (default 는 `local` 이 아님). → Task 6 테스트.
- `db-mysql` 을 끼웠는데 PG 에 연결(또는 반대)하면 첫 쿼리가 아니라 기동 시점에 원인이 적힌 메시지로 실패해야 한다. → Task 1 테스트.
- 서울 JVM 에서 UTC 자정 근처 시각을 저장해도 `LocalDate` 는 하루 밀리지 않고, `Instant` 원문은 UTC 여야 한다. → Task 2 · 3 테스트 (`2026-03-01T00:30:00Z` = 서울 09:30 사용).

---

### Task 1: `SqlDialect` 인터페이스 + 검증기 (persistence-jdbc)

**Files:**
- Create: `modules/persistence-jdbc/src/main/kotlin/dev/sumin/skeleton/persistence/jdbc/SqlDialect.kt`
- Create: `modules/persistence-jdbc/src/main/kotlin/dev/sumin/skeleton/persistence/jdbc/SqlDialectVerifier.kt`
- Create: `modules/persistence-jdbc/src/main/kotlin/dev/sumin/skeleton/persistence/jdbc/SqlDialectAutoConfiguration.kt`
- Modify: `modules/persistence-jdbc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (줄 추가)
- Modify: `modules/persistence-jdbc/build.gradle.kts` (테스트 의존성)
- Test: `modules/persistence-jdbc/src/test/kotlin/dev/sumin/skeleton/persistence/jdbc/SqlDialectVerifierTest.kt`

**Interfaces:**
- Produces: `interface SqlDialect { val vendor: String; fun instantParam(value: Instant?): Any?; fun readInstant(rs: ResultSet, column: String): Instant?; fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>): String }` — `insertIgnore` 는 이름 있는 파라미터 `:<column>` 을 쓰는 SQL 을 돌려준다.
- Produces: `class SqlDialectVerifier(dataSource: DataSource, dialects: List<SqlDialect>) : InitializingBean`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package dev.sumin.skeleton.persistence.jdbc

import java.sql.ResultSet
import java.time.Instant
import java.util.function.Supplier
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType

class SqlDialectVerifierTest {
    class FakeDialect(override val vendor: String) : SqlDialect {
        override fun instantParam(value: Instant?): Any? = value
        override fun readInstant(rs: ResultSet, column: String): Instant? = null
        override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>) = ""
    }

    private val h2: Supplier<DataSource> = Supplier {
        EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build()
    }
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SqlDialectAutoConfiguration::class.java))

    @Test
    fun `no dialect module fails with the modules to add`() {
        runner.withBean(DataSource::class.java, h2).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause()
                .hasMessageContaining("modules:db-postgresql").hasMessageContaining("modules:db-mysql")
        }
    }

    @Test
    fun `two dialect modules fail`() {
        runner.withBean(DataSource::class.java, h2)
            .withBean("a", SqlDialect::class.java, Supplier<SqlDialect> { FakeDialect("h2") })
            .withBean("b", SqlDialect::class.java, Supplier<SqlDialect> { FakeDialect("h2") })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("exactly one")
            }
    }

    @Test
    fun `dialect that does not match the connected database fails at startup`() {
        runner.withBean(DataSource::class.java, h2)
            .withBean(SqlDialect::class.java, Supplier<SqlDialect> { FakeDialect("postgresql") })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause()
                    .hasMessageContaining("postgresql").hasMessageContaining("h2")
            }
    }

    @Test
    fun `matching dialect starts`() {
        runner.withBean(DataSource::class.java, h2)
            .withBean(SqlDialect::class.java, Supplier<SqlDialect> { FakeDialect("h2") })
            .run { context -> assertThat(context).hasNotFailed().hasSingleBean(SqlDialectVerifier::class.java) }
    }

    @Test
    fun `app without a DataSource is not checked`() {
        runner.run { context -> assertThat(context).hasNotFailed().doesNotHaveBean(SqlDialectVerifier::class.java) }
    }
}
```

`modules/persistence-jdbc/build.gradle.kts` 의 `dependencies` 에 추가:

```kotlin
    implementation("org.springframework.boot:spring-boot-jdbc")
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testRuntimeOnly("com.h2database:h2")
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :modules:persistence-jdbc:test --tests '*SqlDialectVerifierTest*'`
Expected: 컴파일 실패 "Unresolved reference 'SqlDialect'".

- [ ] **Step 3: 구현**

`SqlDialect.kt`:

```kotlin
package dev.sumin.skeleton.persistence.jdbc

import java.sql.ResultSet
import java.time.Instant

/**
 * DB 방언마다 달라지는 것만 담는 전략. 구현은 방언 모듈(`modules:db-postgresql`, `modules:db-mysql`)이 하나씩 제공하고,
 * 앱은 그중 하나만 끼운다. 기능 모듈은 이 인터페이스만 안다.
 *
 * 시각 규칙 (실측 2026-10-01, JVM Asia/Seoul): 두 DB 에 공통으로 맞는 바인딩 타입이 없다.
 * PG `timestamptz` 는 UTC `OffsetDateTime` 만 정확하고(UTC `LocalDateTime` 은 −9h, `Instant` 는 드라이버 거부),
 * MySQL `datetime(6)` 은 UTC `LocalDateTime` 만 원문이 UTC 로 남는다. 그래서 JdbcClient / NamedParameterJdbcTemplate 에
 * 시각을 넣을 때는 항상 [instantParam] 을, 꺼낼 때는 [readInstant] 를 쓴다.
 */
interface SqlDialect {
    /** Spring Boot `DatabaseDriver.id` 와 같은 값 — Flyway `{vendor}` 폴더 이름이기도 하다 (`postgresql`, `mysql`). */
    val vendor: String

    fun instantParam(value: Instant?): Any?

    fun readInstant(rs: ResultSet, column: String): Instant?

    /**
     * 충돌(기본 키 · 유니크)하면 아무것도 하지 않는 insert. 파라미터 이름은 칼럼 이름과 같다 (`:recipient_id`).
     * 예외를 잡아 삼키는 방식은 PG 에서 트랜잭션 전체를 abort 시키므로 쓰지 않는다.
     */
    fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>): String
}
```

`SqlDialectVerifier.kt`:

```kotlin
package dev.sumin.skeleton.persistence.jdbc

import javax.sql.DataSource
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.jdbc.DatabaseDriver

/** 기동 때 "방언 모듈 정확히 하나" 와 "연결된 DB 와 같은 방언" 을 확인한다. 틀리면 첫 쿼리가 아니라 여기서 실패한다. */
class SqlDialectVerifier(
    private val dataSource: DataSource,
    private val dialects: List<SqlDialect>,
) : InitializingBean {
    override fun afterPropertiesSet() {
        val dialect = when (dialects.size) {
            0 -> throw IllegalStateException(
                "No SqlDialect. Add exactly one of implementation(project(\":modules:db-postgresql\")) " +
                    "or implementation(project(\":modules:db-mysql\")) to the app.",
            )
            1 -> dialects.single()
            else -> throw IllegalStateException(
                "Found ${dialects.size} SqlDialect beans (${dialects.joinToString { it.vendor }}). " +
                    "An app assembles exactly one of modules:db-postgresql / modules:db-mysql.",
            )
        }
        val connected = dataSource.connection.use { DatabaseDriver.fromJdbcUrl(it.metaData.url).id }
        check(connected == dialect.vendor) {
            "SqlDialect '${dialect.vendor}' does not match the connected database '$connected'. " +
                "Swap the db-* module or fix spring.datasource.url."
        }
    }
}
```

`SqlDialectAutoConfiguration.kt`:

```kotlin
package dev.sumin.skeleton.persistence.jdbc

import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean

@AutoConfiguration(afterName = ["org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"])
@ConditionalOnBean(DataSource::class)
class SqlDialectAutoConfiguration {
    @Bean
    fun sqlDialectVerifier(dataSource: DataSource, dialects: ObjectProvider<SqlDialect>) =
        SqlDialectVerifier(dataSource, dialects.orderedStream().toList())
}
```

imports 파일에 줄 추가: `dev.sumin.skeleton.persistence.jdbc.SqlDialectAutoConfiguration`

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :modules:persistence-jdbc:test`
Expected: PASS (기존 2개 + 새 5개).

- [ ] **Step 5: 체크포인트** — 커밋하지 않는다. `git status` 로 바뀐 파일만 확인.

---

### Task 2: `db-postgresql` 방언 모듈

**Files:**
- Modify: `settings.gradle.kts` (`include(":modules:db-postgresql")`)
- Create: `modules/db-postgresql/build.gradle.kts`
- Create: `modules/db-postgresql/src/main/kotlin/dev/sumin/skeleton/persistence/postgresql/PostgresSqlDialect.kt`
- Create: `modules/db-postgresql/src/main/kotlin/dev/sumin/skeleton/persistence/postgresql/PostgresTimeConversions.kt`
- Create: `modules/db-postgresql/src/main/kotlin/dev/sumin/skeleton/persistence/postgresql/PostgresDialectAutoConfiguration.kt`
- Create: `modules/db-postgresql/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `modules/db-postgresql/src/test/kotlin/dev/sumin/skeleton/persistence/postgresql/PostgresTimeRoundTripTest.kt`

**Interfaces:**
- Consumes: `SqlDialect` (Task 1).
- Produces: `class PostgresSqlDialect : SqlDialect` (`vendor = "postgresql"`), `object PostgresTimeConversions { val all: List<Converter<*, *>> }`.

- [ ] **Step 1: 빌드 파일과 실패하는 테스트**

`modules/db-postgresql/build.gradle.kts`:

```kotlin
// PostgreSQL 방언 — 앱은 db-postgresql / db-mysql 중 하나만 끼운다
dependencies {
    api(project(":modules:persistence-jdbc"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    api("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

`PostgresTimeRoundTripTest.kt` — 서울 JVM, JdbcClient 왕복 · 원문 UTC · `LocalDate` · `insertIgnore`:

```kotlin
package dev.sumin.skeleton.persistence.postgresql

import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class PostgresTimeRoundTripTest {
    private val dialect = PostgresSqlDialect()
    private val jdbc = JdbcClient.create(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))

    @Test
    fun `instant written through the dialect stays UTC and LocalDate does not shift in a Seoul JVM`() {
        jdbc.sql("create table if not exists t (id int primary key, at timestamptz, d date)").update()
        val at = Instant.parse("2026-03-01T00:30:00Z") // 서울 09:30 — 날짜 경계 근처
        jdbc.sql("insert into t (id, at, d) values (1, :at, :d)")
            .param("at", dialect.instantParam(at)).param("d", LocalDate.of(2026, 3, 1)).update()

        val read = jdbc.sql("select at from t where id = 1").query { rs, _ -> dialect.readInstant(rs, "at") }.single()
        val raw = jdbc.sql("select (at at time zone 'UTC')::text || '|' || d::text from t where id = 1").query(String::class.java).single()
        assertEquals(at, read)
        assertEquals("2026-03-01 00:30:00|2026-03-01", raw)
    }

    @Test
    fun `insertIgnore does nothing on conflict and keeps the transaction usable`() {
        jdbc.sql("create table if not exists u (k varchar(10) primary key, v int)").update()
        val sql = dialect.insertIgnore("u", listOf("k", "v"), listOf("k"))
        assertEquals("insert into u (k, v) values (:k, :v) on conflict (k) do nothing", sql)
        jdbc.sql(sql).param("k", "a").param("v", 1).update()
        assertEquals(0, jdbc.sql(sql).param("k", "a").param("v", 2).update())
        assertEquals(1, jdbc.sql("select v from u where k = 'a'").query(Int::class.java).single())
    }

    companion object {
        val db = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
        private lateinit var original: TimeZone

        @JvmStatic @BeforeAll
        fun start() {
            original = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            db.start()
        }

        @JvmStatic @AfterAll
        fun stop() {
            db.stop()
            TimeZone.setDefault(original)
        }
    }
}
```

`settings.gradle.kts` 에 `include(":modules:db-postgresql")` 추가.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :modules:db-postgresql:test`
Expected: 컴파일 실패 "Unresolved reference 'PostgresSqlDialect'". (`PostgreSQLContainer` 패키지가 다르면 여기서 드러난다 — Testcontainers 2.x 는 `org.testcontainers.postgresql.PostgreSQLContainer`. 다르면 `~/.gradle/caches` 의 testcontainers-postgresql jar 를 `unzip -l` 로 확인해 맞춘다.)

- [ ] **Step 3: 구현**

`PostgresSqlDialect.kt`:

```kotlin
package dev.sumin.skeleton.persistence.postgresql

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** `timestamptz` ↔ UTC `OffsetDateTime`. pgjdbc 는 `Instant` 를 바인딩하지 못하고 UTC `LocalDateTime` 은 세션 시간대로 해석한다. */
class PostgresSqlDialect : SqlDialect {
    override val vendor: String = "postgresql"

    override fun instantParam(value: Instant?): Any? = value?.atOffset(ZoneOffset.UTC)

    override fun readInstant(rs: ResultSet, column: String): Instant? =
        rs.getObject(column, OffsetDateTime::class.java)?.toInstant()

    override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>): String =
        "insert into $table (${columns.joinToString()}) values (${columns.joinToString { ":$it" }}) " +
            "on conflict (${conflictColumns.joinToString()}) do nothing"
}
```

`PostgresTimeConversions.kt` (Data JDBC):

```kotlin
package dev.sumin.skeleton.persistence.postgresql

import java.sql.JDBCType
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.core.mapping.JdbcValue

/** Data JDBC 의 시각 3종을 PG 에서 JVM 시간대와 무관하게 고정. `Instant`=`timestamptz`, `LocalDateTime`=`timestamp`, `LocalDate`=`date`. */
object PostgresTimeConversions {
    @WritingConverter
    object InstantToJdbcValue : Converter<Instant, JdbcValue> {
        override fun convert(source: Instant): JdbcValue =
            JdbcValue.of(source.atOffset(ZoneOffset.UTC), JDBCType.TIMESTAMP_WITH_TIMEZONE)
    }

    @WritingConverter
    object LocalDateTimeToJdbcValue : Converter<LocalDateTime, JdbcValue> {
        override fun convert(source: LocalDateTime): JdbcValue = JdbcValue.of(source, JDBCType.TIMESTAMP)
    }

    @WritingConverter
    object LocalDateToJdbcValue : Converter<LocalDate, JdbcValue> {
        override fun convert(source: LocalDate): JdbcValue = JdbcValue.of(source, JDBCType.DATE)
    }

    @ReadingConverter
    object OffsetDateTimeToInstant : Converter<OffsetDateTime, Instant> {
        override fun convert(source: OffsetDateTime): Instant = source.toInstant()
    }

    @ReadingConverter
    object TimestampToInstant : Converter<Timestamp, Instant> {
        override fun convert(source: Timestamp): Instant = source.toInstant()
    }

    val all: List<Converter<*, *>> = listOf(
        InstantToJdbcValue, LocalDateTimeToJdbcValue, LocalDateToJdbcValue, OffsetDateTimeToInstant, TimestampToInstant,
    )
}
```

`PostgresDialectAutoConfiguration.kt`:

```kotlin
package dev.sumin.skeleton.persistence.postgresql

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions

/** `modules:db-postgresql` 을 끼우면 SqlDialect = PostgreSQL. 다른 db-* 모듈과 같이 끼우면 SqlDialectVerifier 가 기동을 막는다. */
@AutoConfiguration
class PostgresDialectAutoConfiguration {
    @Bean
    fun postgresSqlDialect(): SqlDialect = PostgresSqlDialect()

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcCustomConversions::class)
    class DataJdbcConversions {
        /** 앱이 자기 JdbcCustomConversions 를 만들면 [PostgresTimeConversions.all] 을 포함시켜야 한다 */
        @Bean
        @ConditionalOnMissingBean
        fun jdbcCustomConversions(): JdbcCustomConversions = JdbcCustomConversions(PostgresTimeConversions.all)
    }
}
```

imports 파일: `dev.sumin.skeleton.persistence.postgresql.PostgresDialectAutoConfiguration`

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :modules:db-postgresql:test`
Expected: PASS 2개.

- [ ] **Step 5: 체크포인트** — 커밋하지 않는다.

---

### Task 3: `db-mysql` 방언 모듈 (MySQL 전용 코드 이동)

**Files:**
- Modify: `settings.gradle.kts` (`include(":modules:db-mysql")`)
- Create: `modules/db-mysql/build.gradle.kts`
- Create: `modules/db-mysql/src/main/kotlin/dev/sumin/skeleton/persistence/mysql/MySqlSqlDialect.kt`
- Move: `modules/persistence-jdbc/.../UtcInstantConversions.kt` → `modules/db-mysql/src/main/kotlin/dev/sumin/skeleton/persistence/mysql/MySqlTimeConversions.kt` (object 이름 `MySqlTimeConversions`, 내용 · KDoc 유지)
- Move: `modules/persistence-jdbc/.../JdbcTimeZoneEnvironmentPostProcessor.kt` → `modules/db-mysql/src/main/kotlin/dev/sumin/skeleton/persistence/mysql/MySqlTimeZoneEnvironmentPostProcessor.kt` (property source 이름 `skeleton-db-mysql-defaults`)
- Create: `modules/db-mysql/src/main/kotlin/dev/sumin/skeleton/persistence/mysql/MySqlDialectAutoConfiguration.kt`
- Create: `modules/db-mysql/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `modules/db-mysql/src/main/resources/META-INF/spring.factories`
- Delete: `modules/persistence-jdbc/.../JdbcUtcAutoConfiguration.kt`, `modules/persistence-jdbc/src/main/resources/META-INF/spring.factories`, imports 파일의 `JdbcUtcAutoConfiguration` 줄
- Delete: `modules/persistence-jooq/src/main/kotlin/dev/sumin/skeleton/persistence/jooq/JooqTimeZoneEnvironmentPostProcessor.kt`, `modules/persistence-jooq/src/main/resources/META-INF/spring.factories` (같은 MySQL 세션 강제 — 이제 db-mysql 이 한다)
- Test: `modules/db-mysql/src/test/kotlin/dev/sumin/skeleton/persistence/mysql/MySqlTimeRoundTripTest.kt`

**Interfaces:**
- Consumes: `SqlDialect` (Task 1).
- Produces: `class MySqlSqlDialect : SqlDialect` (`vendor = "mysql"`), `object MySqlTimeConversions { val all }`, `class MySqlTimeZoneEnvironmentPostProcessor`.

- [ ] **Step 1: 빌드 파일과 실패하는 테스트**

`modules/db-mysql/build.gradle.kts`:

```kotlin
// MySQL 방언 — 앱은 db-postgresql / db-mysql 중 하나만 끼운다
dependencies {
    api(project(":modules:persistence-jdbc"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    runtimeOnly("com.mysql:mysql-connector-j")
    api("org.flywaydb:flyway-mysql")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

`MySqlTimeRoundTripTest.kt`:

```kotlin
package dev.sumin.skeleton.persistence.mysql

import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

class MySqlTimeRoundTripTest {
    private val dialect = MySqlSqlDialect()
    // 드라이버 속성은 MySqlTimeZoneEnvironmentPostProcessor 가 Hikari 에 넣는 것과 같게
    private val jdbc = JdbcClient.create(
        DriverManagerDataSource(
            db.jdbcUrl + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=false",
            db.username,
            db.password,
        ),
    )

    @Test
    fun `instant written through the dialect is stored as a UTC literal in a Seoul JVM`() {
        jdbc.sql("create table if not exists t (id int primary key, at datetime(6), d date)").update()
        val at = Instant.parse("2026-03-01T00:30:00Z")
        jdbc.sql("insert into t (id, at, d) values (1, :at, :d)")
            .param("at", dialect.instantParam(at)).param("d", LocalDate.of(2026, 3, 1)).update()

        val read = jdbc.sql("select at from t where id = 1").query { rs, _ -> dialect.readInstant(rs, "at") }.single()
        val raw = jdbc.sql("select concat(at, '|', d) from t where id = 1").query(String::class.java).single()
        assertEquals(at, read)
        assertEquals("2026-03-01 00:30:00.000000|2026-03-01", raw)
    }

    @Test
    fun `insertIgnore does nothing on duplicate key`() {
        jdbc.sql("create table if not exists u (k varchar(10) primary key, v int)").update()
        val sql = dialect.insertIgnore("u", listOf("k", "v"), listOf("k"))
        assertEquals("insert into u (k, v) values (:k, :v) on duplicate key update k = k", sql)
        jdbc.sql(sql).param("k", "a").param("v", 1).update()
        jdbc.sql(sql).param("k", "a").param("v", 2).update()
        assertEquals(1, jdbc.sql("select v from u where k = 'a'").query(Int::class.java).single())
    }

    companion object {
        val db = MySQLContainer(DockerImageName.parse("mysql:8.4"))
        private lateinit var original: TimeZone

        @JvmStatic @BeforeAll
        fun start() {
            original = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            db.start()
        }

        @JvmStatic @AfterAll
        fun stop() {
            db.stop()
            TimeZone.setDefault(original)
        }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :modules:db-mysql:test`
Expected: 컴파일 실패 "Unresolved reference 'MySqlSqlDialect'".

- [ ] **Step 3: 구현**

`MySqlSqlDialect.kt`:

```kotlin
package dev.sumin.skeleton.persistence.mysql

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * `datetime(6)` ↔ UTC 벽시계 `LocalDateTime`. Connector/J 는 `OffsetDateTime`/`Timestamp` 를 JVM 시간대 벽시계로 저장하므로
 * (실측 2026-10-01) 원문이 UTC 로 남는 건 UTC `LocalDateTime` 뿐이다.
 * insertIgnore 는 `insert ignore` 대신 `on duplicate key update` — `ignore` 는 잘림 같은 다른 오류까지 경고로 삼킨다.
 */
class MySqlSqlDialect : SqlDialect {
    override val vendor: String = "mysql"

    override fun instantParam(value: Instant?): Any? = value?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }

    override fun readInstant(rs: ResultSet, column: String): Instant? =
        rs.getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

    override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>): String {
        val noop = conflictColumns.first()
        return "insert into $table (${columns.joinToString()}) values (${columns.joinToString { ":$it" }}) " +
            "on duplicate key update $noop = $noop"
    }
}
```

`MySqlTimeConversions.kt`: 기존 `UtcInstantConversions.kt` 내용을 패키지 `dev.sumin.skeleton.persistence.mysql`, object 이름 `MySqlTimeConversions` 로 옮긴다 (나머지 코드 · KDoc 그대로).

`MySqlTimeZoneEnvironmentPostProcessor.kt`: 기존 `JdbcTimeZoneEnvironmentPostProcessor.kt` 내용을 패키지 `dev.sumin.skeleton.persistence.mysql`, 클래스 이름 `MySqlTimeZoneEnvironmentPostProcessor`, property source 이름 `"skeleton-db-mysql-defaults"` 로 옮긴다. KDoc 의 "규칙: SQL 파라미터는 … UTC LocalDateTime" 줄을 "규칙: 시각 파라미터는 `SqlDialect.instantParam` 으로 넘긴다" 로 바꾼다.

`MySqlDialectAutoConfiguration.kt`:

```kotlin
package dev.sumin.skeleton.persistence.mysql

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions

/** `modules:db-mysql` 을 끼우면 SqlDialect = MySQL, 세션 UTC 강제는 [MySqlTimeZoneEnvironmentPostProcessor]. */
@AutoConfiguration
class MySqlDialectAutoConfiguration {
    @Bean
    fun mySqlSqlDialect(): SqlDialect = MySqlSqlDialect()

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcCustomConversions::class)
    class DataJdbcConversions {
        @Bean
        @ConditionalOnMissingBean
        fun jdbcCustomConversions(): JdbcCustomConversions = JdbcCustomConversions(MySqlTimeConversions.all)
    }
}
```

imports: `dev.sumin.skeleton.persistence.mysql.MySqlDialectAutoConfiguration`
spring.factories:

```
org.springframework.boot.env.EnvironmentPostProcessor=\
dev.sumin.skeleton.persistence.mysql.MySqlTimeZoneEnvironmentPostProcessor
```

persistence-jdbc 에서 `UtcInstantConversions.kt`, `JdbcTimeZoneEnvironmentPostProcessor.kt`, `JdbcUtcAutoConfiguration.kt`, `META-INF/spring.factories` 삭제, imports 에서 `JdbcUtcAutoConfiguration` 줄 삭제. persistence-jooq 의 `JooqTimeZoneEnvironmentPostProcessor.kt` 와 `META-INF/spring.factories` 삭제, `JooqAutoConfiguration` KDoc 의 "UTC 세션은 [JooqTimeZoneEnvironmentPostProcessor]" 를 "시각 바인딩은 방언 모듈(db-postgresql / db-mysql)" 로 고친다.

`settings.gradle.kts` 에 `include(":modules:db-mysql")` 추가.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :modules:db-mysql:test :modules:persistence-jdbc:test`
Expected: PASS. 이어서 `grep -rn "UtcInstantConversions\|JdbcTimeZoneEnvironmentPostProcessor\|JooqTimeZoneEnvironmentPostProcessor" modules apps --include='*.kt' --include='*.imports' --include='*.factories'` → 남은 참조가 `apps/api` 테스트 KDoc 뿐인지 확인 (Task 8 에서 정리).

- [ ] **Step 5: 체크포인트** — 커밋하지 않는다.

---

### Task 4: 두 DB 테스트 묶음 + `job-queue-jdbc` 이식

**Files:**
- Modify: `build.gradle.kts` (루트: 두 DB 테스트 묶음 등록)
- Move: `modules/job-queue-jdbc/src/main/resources/db/migration/V2026091001__skeleton_jobs.sql` → `…/db/migration/mysql/V20260910010000__skeleton_jobs.sql` (내용 그대로)
- Create: `modules/job-queue-jdbc/src/main/resources/db/migration/postgresql/V20260910010000__skeleton_jobs.sql`
- Modify: `modules/job-queue-jdbc/build.gradle.kts`
- Modify: `modules/job-queue-jdbc/src/main/kotlin/dev/sumin/skeleton/jobqueue/jdbc/JdbcJobRepository.kt`
- Modify: `modules/job-queue-jdbc/src/main/kotlin/dev/sumin/skeleton/jobqueue/jdbc/JobQueueJdbcAutoConfiguration.kt`
- Move: `src/test/kotlin/…/JdbcJobQueueIntegrationTest.kt`, `JobQueueTestApplication.kt` → `src/dbTest/kotlin/dev/sumin/skeleton/jobqueue/jdbc/`
- Move: `src/test/resources/application.yml` → `src/dbTest/resources/application.yml`
- Create: `src/postgresTest/kotlin/dev/sumin/skeleton/jobqueue/jdbc/DbTestcontainers.kt`, `src/mysqlTest/kotlin/dev/sumin/skeleton/jobqueue/jdbc/DbTestcontainers.kt`

**Interfaces:**
- Consumes: `SqlDialect.instantParam / readInstant` (Task 1), `PostgresSqlDialect`, `MySqlSqlDialect` (Task 2·3).
- Produces: 루트 `build.gradle.kts` 의 `dbTestModules` 집합 — 여기 든 모듈은 `postgresTest`, `mysqlTest` 테스트 묶음을 갖고, 공통 소스는 `src/dbTest/{kotlin,resources}`, 묶음별 소스는 `src/<suite>/kotlin`. 두 묶음 모두 `check` 에 걸린다. 묶음의 `implementation` 은 모듈의 `implementation` + `testImplementation` 을 물려받는다.
- Produces: `JdbcJobRepository(jdbc: JdbcClient, transactions: TransactionTemplate, dialect: SqlDialect)`.

- [ ] **Step 1: 루트 빌드에 두 DB 테스트 묶음 등록**

루트 `build.gradle.kts` 끝에 추가:

```kotlin
// DB 통합 테스트를 PostgreSQL · MySQL 두 벌로 돈다. 같은 테스트 소스(src/dbTest), 묶음마다 방언 모듈 하나만 끼운다
// (실제 앱처럼 db-* 모듈은 정확히 하나여야 하므로 한 클래스패스에 둘을 넣지 않는다)
val dbTestModules = setOf(":modules:job-queue-jdbc", ":modules:notification-jdbc")
val dbSuites = mapOf(
    "postgresTest" to (":modules:db-postgresql" to "org.testcontainers:testcontainers-postgresql"),
    "mysqlTest" to (":modules:db-mysql" to "org.testcontainers:testcontainers-mysql"),
)
configure(subprojects.filter { it.path in dbTestModules }) {
    val testing = extensions.getByType<org.gradle.testing.base.TestingExtension>()
    dbSuites.forEach { (suiteName, deps) ->
        val (dialectModule, containerArtifact) = deps
        testing.suites.register(suiteName, org.gradle.api.plugins.jvm.JvmTestSuite::class.java) {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(project(dialectModule))
                implementation(containerArtifact)
            }
        }
        configurations.named("${suiteName}Implementation") {
            extendsFrom(configurations.getByName("implementation"), configurations.getByName("testImplementation"))
        }
        configurations.named("${suiteName}RuntimeOnly") {
            extendsFrom(configurations.getByName("testRuntimeOnly"))
        }
        extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
            .sourceSets.named(suiteName) { kotlin.srcDir("src/dbTest/kotlin") }
        extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()
            .named(suiteName) { resources.srcDir("src/dbTest/resources") }
        tasks.named("check") { dependsOn(suiteName) }
    }
}
```

- [ ] **Step 2: 마이그레이션 두 벌**

`git mv modules/job-queue-jdbc/src/main/resources/db/migration/V2026091001__skeleton_jobs.sql modules/job-queue-jdbc/src/main/resources/db/migration/mysql/V20260910010000__skeleton_jobs.sql` (디렉토리 먼저 `mkdir -p`).

`…/db/migration/postgresql/V20260910010000__skeleton_jobs.sql`:

```sql
-- modules:job-queue-jdbc — PostgreSQL 테이블 기반 재시도 큐 (Redis 없음, FOR UPDATE SKIP LOCKED)
create table if not exists skeleton_jobs (
    id            bigint generated by default as identity primary key,
    job_type      varchar(128) not null,
    payload_json  text         not null,
    status        varchar(16)  not null,            -- PENDING | RUNNING | DONE | DEAD
    attempts      int          not null default 0,
    max_attempts  int          not null,
    next_run_at   timestamptz  not null,
    locked_by     varchar(128) null,
    locked_at     timestamptz  null,
    last_error    text         null,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null
);
create index if not exists idx_skeleton_jobs_claim on skeleton_jobs (status, next_run_at);
create index if not exists idx_skeleton_jobs_running on skeleton_jobs (status, locked_at);
```

- [ ] **Step 3: 테스트 소스 재배치**

```bash
cd modules/job-queue-jdbc
mkdir -p src/dbTest/kotlin/dev/sumin/skeleton/jobqueue/jdbc src/dbTest/resources \
         src/postgresTest/kotlin/dev/sumin/skeleton/jobqueue/jdbc src/mysqlTest/kotlin/dev/sumin/skeleton/jobqueue/jdbc
git mv src/test/kotlin/dev/sumin/skeleton/jobqueue/jdbc/JdbcJobQueueIntegrationTest.kt src/dbTest/kotlin/dev/sumin/skeleton/jobqueue/jdbc/
git mv src/test/kotlin/dev/sumin/skeleton/jobqueue/jdbc/JobQueueTestApplication.kt src/dbTest/kotlin/dev/sumin/skeleton/jobqueue/jdbc/
git mv src/test/resources/application.yml src/dbTest/resources/application.yml
```

`JobQueueTestApplication.kt` 에서 `JobQueueTestcontainers` 클래스와 testcontainers import 를 지우고 `@SpringBootApplication class JobQueueTestApplication` 만 남긴다. `JdbcJobQueueIntegrationTest` 의 `@Import(JobQueueTestcontainers::class, …)` → `@Import(DbTestcontainers::class, …)`, KDoc "실제 MySQL(Testcontainers)" → "실제 DB(Testcontainers — postgresTest · mysqlTest 두 벌)".

`src/dbTest/resources/application.yml` 끝에 추가 (테스트 앱에는 migration-flyway 가 없으므로 직접):

```yaml
spring:
  flyway:
    locations: classpath:db/migration/{vendor}
```

(기존 파일에 `spring:` 이 이미 있으면 그 아래 `flyway.locations` 만 합친다.)

`src/postgresTest/kotlin/dev/sumin/skeleton/jobqueue/jdbc/DbTestcontainers.kt`:

```kotlin
package dev.sumin.skeleton.jobqueue.jdbc

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class DbTestcontainers {
    @Bean
    @ServiceConnection
    fun db(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
}
```

`src/mysqlTest/kotlin/dev/sumin/skeleton/jobqueue/jdbc/DbTestcontainers.kt`:

```kotlin
package dev.sumin.skeleton.jobqueue.jdbc

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class DbTestcontainers {
    @Bean
    @ServiceConnection
    fun db(): MySQLContainer = MySQLContainer(DockerImageName.parse("mysql:8.4"))
}
```

`modules/job-queue-jdbc/build.gradle.kts` 를 아래로 바꾼다 (testcontainers 종류 · 드라이버 · flyway 방언은 묶음/방언 모듈이 가져온다):

```kotlin
dependencies {
    implementation(project(":modules:platform"))
    implementation(project(":modules:persistence-jdbc"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // src/dbTest — postgresTest · mysqlTest 묶음이 물려받는다 (루트 build.gradle.kts dbTestModules)
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- [ ] **Step 4: 실패 확인**

Run: `./gradlew :modules:job-queue-jdbc:postgresTest`
Expected: 실패 — 기동 시 `SqlDialectVerifier` 는 통과하지만 `insert` 가 UTC `LocalDateTime` 을 `timestamptz` 에 넣어 시각이 −9h 로 저장되고(JVM 이 서울이면) 생성 키 조회가 "multiple keys" 로 깨진다. 실패 메시지를 기록해 둔다.

- [ ] **Step 5: 저장소 이식**

`JdbcJobRepository.kt` 수정:
- 생성자에 `private val dialect: SqlDialect` 추가 (`import dev.sumin.skeleton.persistence.jdbc.SqlDialect`).
- `utc(instant)` 호출을 모두 `dialect.instantParam(instant)` 로, `instant(rs, column)` 을 `dialect.readInstant(rs, column)` 로 바꾼다. private `utc`, `instant`, `timestamp` 함수와 `LocalDateTime` · `ZoneOffset` · `Timestamp` import 삭제.
- `insert` 의 `.update(keys)` → `.update(keys, "id")`, 반환 `keys.key!!.toLong()` 그대로 (키 칼럼 지정으로 PG 의 "모든 칼럼 반환" 을 막는다).
- 클래스 KDoc 첫 줄을 "시각은 SqlDialect 로 바인딩한다 (방언 모듈이 결정, JVM 시간대 무관)." 로.

`JobQueueJdbcAutoConfiguration.kt`:

```kotlin
    @Bean
    @ConditionalOnMissingBean
    fun jdbcJobRepository(jdbc: JdbcClient, transactionManager: PlatformTransactionManager, dialect: SqlDialect) =
        JdbcJobRepository(jdbc, TransactionTemplate(transactionManager), dialect)
```

클래스 KDoc 의 마이그레이션 경로를 "`db/migration/<vendor>/V20260910010000__skeleton_jobs.sql` — 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다" 로 고친다.

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :modules:job-queue-jdbc:postgresTest :modules:job-queue-jdbc:mysqlTest`
Expected: 두 묶음 모두 6개 PASS (claim, 백오프, DEAD, 핸들러 없음, SKIP LOCKED, stale 복구).

- [ ] **Step 7: 체크포인트** — 커밋하지 않는다.

---

### Task 5: `notification-jdbc` 이식 (insertIgnore, 두 DB)

**Files:**
- Move: `…/db/migration/V2026061701__notification_inbox.sql` → `…/db/migration/mysql/V20260617010000__skeleton_notification_inbox.sql` (내용 그대로)
- Create: `modules/notification-jdbc/src/main/resources/db/migration/postgresql/V20260617010000__skeleton_notification_inbox.sql`
- Modify: `modules/notification-jdbc/build.gradle.kts`, `JdbcNotificationInboxRepository.kt`, `NotificationJdbcAutoConfiguration.kt`
- Delete: `modules/notification-jdbc/src/test/kotlin/dev/sumin/skeleton/notification/jdbc/JdbcNotificationInboxRepositoryTest.kt` (H2 — `on conflict` 를 모름. 아래 dbTest 로 대체)
- Modify: `modules/notification-jdbc/src/test/kotlin/dev/sumin/skeleton/notification/jdbc/NotificationJdbcAutoConfigurationTest.kt` (SqlDialect 빈 제공)
- Create: `src/dbTest/kotlin/dev/sumin/skeleton/notification/jdbc/JdbcNotificationInboxRepositoryDbTest.kt`
- Create: `src/postgresTest/kotlin/dev/sumin/skeleton/notification/jdbc/DbTestDatabase.kt`, `src/mysqlTest/kotlin/dev/sumin/skeleton/notification/jdbc/DbTestDatabase.kt`

**Interfaces:**
- Consumes: `SqlDialect.insertIgnore / instantParam / readInstant`; 루트 `dbTestModules` 묶음 (Task 4).
- Produces: `JdbcNotificationInboxRepository(jdbc: NamedParameterJdbcTemplate, jsonCodec: JsonCodec, dialect: SqlDialect)`; 묶음별 `object DbTestDatabase { val vendor: String; val dialect: SqlDialect; fun dataSource(): DataSource }`.

- [ ] **Step 1: PG 마이그레이션 + MySQL 이동**

`git mv` 로 MySQL 파일을 `db/migration/mysql/V20260617010000__skeleton_notification_inbox.sql` 로. PG 파일:

```sql
create table if not exists skeleton_notification_inbox (
    recipient_id       varchar(128)  not null,
    event_id           varchar(128)  not null,
    topic              varchar(128)  not null,
    type               varchar(128)  not null,
    severity           varchar(32)   not null,
    title              varchar(255),
    message            varchar(2000),
    payload_json       text          not null,
    recipient_ids_json text          not null,
    event_created_at   timestamptz   not null,
    read_at            timestamptz,
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null,
    primary key (recipient_id, event_id)
);
create index if not exists idx_skeleton_notification_inbox_recipient_read_created
    on skeleton_notification_inbox (recipient_id, read_at, event_created_at);
create index if not exists idx_skeleton_notification_inbox_recipient_topic_created
    on skeleton_notification_inbox (recipient_id, topic, event_created_at);
```

- [ ] **Step 2: 실패하는 DB 테스트**

`build.gradle.kts` 를 아래로 (`dbTestModules` 에는 Task 4 에서 이미 들어 있다):

```kotlin
dependencies {
    implementation(project(":modules:notification"))
    implementation(project(":modules:json"))
    implementation(project(":modules:persistence-jdbc"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.flywaydb:flyway-core")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

`src/postgresTest/kotlin/dev/sumin/skeleton/notification/jdbc/DbTestDatabase.kt`:

```kotlin
package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.persistence.postgresql.PostgresSqlDialect
import javax.sql.DataSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object DbTestDatabase {
    const val vendor = "postgresql"
    val dialect: SqlDialect = PostgresSqlDialect()
    private val container = PostgreSQLContainer(DockerImageName.parse("postgres:18")).also { it.start() }
    fun dataSource(): DataSource = DriverManagerDataSource(container.jdbcUrl, container.username, container.password)
}
```

`src/mysqlTest/kotlin/dev/sumin/skeleton/notification/jdbc/DbTestDatabase.kt`:

```kotlin
package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.persistence.mysql.MySqlSqlDialect
import javax.sql.DataSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

object DbTestDatabase {
    const val vendor = "mysql"
    val dialect: SqlDialect = MySqlSqlDialect()
    private val container = MySQLContainer(DockerImageName.parse("mysql:8.4")).also { it.start() }
    fun dataSource(): DataSource = DriverManagerDataSource(
        container.jdbcUrl + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=false",
        container.username,
        container.password,
    )
}
```

`src/dbTest/kotlin/dev/sumin/skeleton/notification/jdbc/JdbcNotificationInboxRepositoryDbTest.kt`:

```kotlin
package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.json.JacksonJsonCodec
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationInboxQuery
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** 모듈 마이그레이션(db/migration/<vendor>) 을 Flyway 로 깔고 실제 DB 에서 저장 · 조회 · 읽음 · 중복 저장을 검증. */
class JdbcNotificationInboxRepositoryDbTest {
    private val dataSource = DbTestDatabase.dataSource().also {
        Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate()
    }
    private val repository = JdbcNotificationInboxRepository(NamedParameterJdbcTemplate(dataSource), JacksonJsonCodec(), DbTestDatabase.dialect)

    private fun event(id: String = UUID.randomUUID().toString()) = NotificationEvent(
        id = id,
        topic = "demo",
        type = "created",
        recipientIds = setOf("acc_user"),
        title = "Hello",
        message = "Stored notification",
        payload = mapOf("source" to "test", "count" to 1),
        createdAt = Instant.parse("2026-03-01T00:30:00.123456Z"),
    )

    @Test
    fun `persists notifications and read state`() {
        val e = event()
        val recipient = "acc_${UUID.randomUUID()}"
        repository.save(e, setOf(recipient, "acc_other"))
        val readAt = Instant.parse("2026-03-01T00:31:00Z")
        val read = repository.markRead(recipient, e.id, readAt)
        val unread = repository.findByRecipient(recipient, NotificationInboxQuery(page = 0, size = 10, unreadOnly = true))
        val all = repository.findByRecipient(recipient, NotificationInboxQuery(page = 0, size = 10))

        assertEquals(1, all.totalElements)
        assertEquals(e.createdAt, all.values.single().event.createdAt)
        assertEquals("test", all.values.single().event.payload["source"])
        assertNotNull(read)
        assertEquals(readAt, read.readAt)
        assertEquals(0, unread.totalElements)
    }

    @Test
    fun `saving the same event twice inside one transaction keeps the transaction usable`() {
        val e = event()
        val recipient = "acc_${UUID.randomUUID()}"
        val tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
        val count = tx.execute {
            repository.save(e, setOf(recipient))
            repository.save(e, setOf(recipient)) // PG: 예외 삼키기였다면 여기서 트랜잭션 abort → 다음 쿼리 실패
            repository.findByRecipient(recipient, NotificationInboxQuery(page = 0, size = 10)).totalElements
        }
        assertEquals(1, count)
    }
}
```

Run: `./gradlew :modules:notification-jdbc:postgresTest`
Expected: 컴파일 실패 — `JdbcNotificationInboxRepository` 생성자에 dialect 인자 없음.

- [ ] **Step 3: 저장소 이식**

`JdbcNotificationInboxRepository.kt`:
- 생성자 세 번째 인자 `private val dialect: SqlDialect`.
- `insertIfAbsent` 를 아래로 바꾸고 `runCatching` · `DuplicateKeyException` import 삭제:

```kotlin
    private fun insertIfAbsent(event: NotificationEvent, recipientId: String) {
        val now = Instant.now()
        jdbc.update(
            dialect.insertIgnore(TABLE, INSERT_COLUMNS, listOf("recipient_id", "event_id")),
            MapSqlParameterSource()
                .addValue("recipient_id", recipientId)
                .addValue("event_id", event.id)
                .addValue("topic", event.topic)
                .addValue("type", event.type)
                .addValue("severity", event.severity.name)
                .addValue("title", event.title)
                .addValue("message", event.message)
                .addValue("payload_json", jsonCodec.canonicalString(jsonCodec.toDocument(event.payload)))
                .addValue("recipient_ids_json", jsonCodec.canonicalString(jsonCodec.toDocument(event.recipientIds)))
                .addValue("event_created_at", dialect.instantParam(event.createdAt))
                .addValue("read_at", null)
                .addValue("created_at", dialect.instantParam(now))
                .addValue("updated_at", dialect.instantParam(now)),
        )
    }

    private companion object {
        const val TABLE = "skeleton_notification_inbox"
        val INSERT_COLUMNS = listOf(
            "recipient_id", "event_id", "topic", "type", "severity", "title", "message",
            "payload_json", "recipient_ids_json", "event_created_at", "read_at", "created_at", "updated_at",
        )
    }
```

- `markRead`, `markAllRead` 의 `readAt.toTimestamp()` → `dialect.instantParam(readAt)`.
- `toRecord()` 의 `getUtcInstant(...)` → `dialect.readInstant(this, ...)`.
- private `toTimestamp`, `getUtcInstant` 와 `Timestamp`, `LocalDateTime`, `ZoneOffset` import 삭제.

PG 에서 `read_at` 의 `null` 파라미터 타입 추론이 실패하면("could not determine data type of parameter") `INSERT_COLUMNS` 에서 `read_at` 을 빼고 `addValue("read_at", null)` 도 삭제한다 (칼럼 기본값 null).

`NotificationJdbcAutoConfiguration.kt` 의 빈:

```kotlin
    @Bean
    @ConditionalOnBean(DataSource::class, SqlDialect::class)
    @ConditionalOnMissingBean(NotificationInboxRepository::class)
    fun jdbcNotificationInboxRepository(dataSource: DataSource, jsonCodec: JsonCodec, dialect: SqlDialect): NotificationInboxRepository =
        JdbcNotificationInboxRepository(jdbc = NamedParameterJdbcTemplate(dataSource), jsonCodec = jsonCodec, dialect = dialect)
```

`NotificationJdbcAutoConfigurationTest` 의 컨텍스트 러너에 `.withBean(SqlDialect::class.java, Supplier<SqlDialect> { FakeDialect("h2") })` 를 추가한다 — `FakeDialect` 는 이 테스트 파일 안에 Task 1 의 것과 같은 모양으로 둔다:

```kotlin
private class FakeDialect(override val vendor: String) : dev.sumin.skeleton.persistence.jdbc.SqlDialect {
    override fun instantParam(value: java.time.Instant?): Any? = value
    override fun readInstant(rs: java.sql.ResultSet, column: String): java.time.Instant? = null
    override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>) = ""
}
```

(이 테스트가 `SqlDialectAutoConfiguration` 을 안 올리면 검증기는 돌지 않는다 — 올리는 경우 H2 DataSource 와 vendor `h2` 로 일치한다.)

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :modules:notification-jdbc:test :modules:notification-jdbc:postgresTest :modules:notification-jdbc:mysqlTest`
Expected: PASS (autoconfig 1 + PG 2 + MySQL 2).

- [ ] **Step 5: 체크포인트** — 커밋하지 않는다.

---

### Task 6: `migration-flyway` 모듈 + `newMigration` 작업

**Files:**
- Modify: `settings.gradle.kts` (`include(":modules:migration-flyway")`), 루트 `build.gradle.kts` (`newMigration` 작업)
- Create: `modules/migration-flyway/build.gradle.kts`
- Create (패키지 `dev.sumin.skeleton.migration`): `MigrationProperties.kt`, `MigrationFlywayEnvironmentPostProcessor.kt`, `CleanOnValidationErrorMigrationStrategy.kt`, `MigrationFlywayAutoConfiguration.kt`, `MigrationFileRules.kt`
- Create: `src/main/resources/META-INF/spring.factories`, `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `MigrationFlywayEnvironmentPostProcessorTest.kt`, `CleanOnValidationErrorMigrationStrategyTest.kt`, `MigrationFileRulesTest.kt`, `RepositoryMigrationsTest.kt`

**Interfaces:**
- Produces: `data class MigrationProperties(cleanOnValidationError: Boolean = false, cleanAllowedProfiles: List<String> = listOf("local"))` (`@ConfigurationProperties("skeleton.migration")`).
- Produces: `object MigrationFileRules { val NAME: Regex; fun violations(root: Path): List<String> }`.
- Produces: `class CleanOnValidationErrorMigrationStrategy : FlywayMigrationStrategy`.

- [ ] **Step 1: 빌드 파일**

```kotlin
// Flyway 운영 규칙: 기본값(outOfOrder · 이름 검증 · {vendor} 위치), 로컬 전용 clean, 이름 규칙 검사
dependencies {
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-flyway")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    // RepositoryMigrationsTest 가 레포 전체의 db/migration 을 훑는다
    systemProperty("skeleton.repoRoot", rootDir.absolutePath)
    inputs.files(fileTree(rootDir) { include("**/src/*/resources/db/migration/**"); exclude("**/build/**") })
}
```

- [ ] **Step 2: 실패하는 테스트 — 기본값과 가드**

`MigrationFlywayEnvironmentPostProcessorTest.kt`:

```kotlin
package dev.sumin.skeleton.migration

import kotlin.test.assertEquals
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

class MigrationFlywayEnvironmentPostProcessorTest {
    private val processor = MigrationFlywayEnvironmentPostProcessor()
    private fun run(env: MockEnvironment) = processor.postProcessEnvironment(env, SpringApplication())

    @Test
    fun `adds flyway defaults the app can override`() {
        val env = MockEnvironment().withProperty("spring.flyway.out-of-order", "false")
        run(env)
        assertEquals("false", env.getProperty("spring.flyway.out-of-order"))
        assertEquals("true", env.getProperty("spring.flyway.validate-migration-naming"))
        assertEquals("classpath:db/migration/{vendor}", env.getProperty("spring.flyway.locations"))
    }

    @Test
    fun `clean on validation error is allowed in the local profile`() {
        val env = MockEnvironment().withProperty("skeleton.migration.clean-on-validation-error", "true")
        env.setActiveProfiles("local")
        run(env)
    }

    @Test
    fun `clean on validation error without any active profile fails`() {
        val env = MockEnvironment().withProperty("skeleton.migration.clean-on-validation-error", "true")
        assertThatThrownBy { run(env) }.hasMessageContaining("skeleton.migration.clean-on-validation-error").hasMessageContaining("local")
    }

    @Test
    fun `flyway clean enabled in prod fails`() {
        val env = MockEnvironment().withProperty("spring.flyway.clean-disabled", "false")
        env.setActiveProfiles("prod")
        assertThatThrownBy { run(env) }.hasMessageContaining("spring.flyway.clean-disabled").hasMessageContaining("prod")
    }

    @Test
    fun `allowed profiles are configurable`() {
        val env = MockEnvironment()
            .withProperty("skeleton.migration.clean-on-validation-error", "true")
            .withProperty("skeleton.migration.clean-allowed-profiles", "local,dev")
        env.setActiveProfiles("dev")
        run(env)
    }
}
```

Run: `./gradlew :modules:migration-flyway:test` → Expected: 컴파일 실패 "Unresolved reference 'MigrationFlywayEnvironmentPostProcessor'".

- [ ] **Step 3: 구현 — 속성, 기본값 + 가드**

`MigrationProperties.kt`:

```kotlin
package dev.sumin.skeleton.migration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.migration")
data class MigrationProperties(
    /** true 면 체크섬 불일치 같은 검증 실패 때 DB 를 clean 하고 다시 migrate 한다. [cleanAllowedProfiles] 밖에서는 기동 실패. */
    val cleanOnValidationError: Boolean = false,
    val cleanAllowedProfiles: List<String> = listOf("local"),
)
```

`MigrationFlywayEnvironmentPostProcessor.kt`:

```kotlin
package dev.sumin.skeleton.migration

import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * 1) Flyway 기본값 (최하위 우선순위 — 앱 yml 이 이긴다):
 *    out-of-order=true (타임스탬프 버전에선 늦게 합쳐진 이른 시각 파일이 정상), validate-migration-naming=true,
 *    locations=classpath:db/migration/{vendor} (앱 · 모듈 마이그레이션이 같은 경로에서 합쳐진다)
 * 2) 가드: clean 이 가능한 설정(skeleton.migration.clean-on-validation-error=true 또는 spring.flyway.clean-disabled=false)이
 *    허용 프로필 밖에서 켜져 있으면 DB 에 닿기 전에 기동을 실패시킨다.
 * application.yml 과 프로필이 다 읽힌 뒤에 돌도록 가장 늦게 실행된다.
 */
class MigrationFlywayEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        environment.propertySources.addLast(
            MapPropertySource(
                "skeleton-migration-flyway-defaults",
                mapOf(
                    "spring.flyway.out-of-order" to "true",
                    "spring.flyway.validate-migration-naming" to "true",
                    "spring.flyway.locations" to "classpath:db/migration/{vendor}",
                ),
            ),
        )
        guard(environment)
    }

    private fun guard(environment: ConfigurableEnvironment) {
        val binder = Binder.get(environment)
        val properties = binder.bind("skeleton.migration", MigrationProperties::class.java).orElse(MigrationProperties())
        val flywayCleanEnabled = !binder.bind("spring.flyway.clean-disabled", Boolean::class.javaObjectType).orElse(true)
        val active = environment.activeProfiles.toSet()
        if (active.any { it in properties.cleanAllowedProfiles }) return
        val offending = buildList {
            if (properties.cleanOnValidationError) add("skeleton.migration.clean-on-validation-error=true")
            if (flywayCleanEnabled) add("spring.flyway.clean-disabled=false")
        }
        check(offending.isEmpty()) {
            "${offending.joinToString()} can wipe the database and is only allowed in profiles " +
                "${properties.cleanAllowedProfiles} (skeleton.migration.clean-allowed-profiles); active profiles: " +
                (active.ifEmpty { setOf("default") })
        }
    }
}
```

`META-INF/spring.factories`:

```
org.springframework.boot.env.EnvironmentPostProcessor=\
dev.sumin.skeleton.migration.MigrationFlywayEnvironmentPostProcessor
```

Run: `./gradlew :modules:migration-flyway:test --tests '*EnvironmentPostProcessorTest*'` → Expected: PASS 5.

- [ ] **Step 4: 실패하는 테스트 — 로컬 clean 전략**

`CleanOnValidationErrorMigrationStrategyTest.kt`:

```kotlin
package dev.sumin.skeleton.migration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class CleanOnValidationErrorMigrationStrategyTest {
    private fun flyway(dir: Path, schema: String) = Flyway.configure()
        .dataSource(db.jdbcUrl, db.username, db.password)
        .schemas(schema).locations("filesystem:$dir").load()

    private fun jdbc() = JdbcClient.create(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))

    @Test
    fun `checksum mismatch cleans and re-applies`() {
        val dir = Files.createTempDirectory("mig")
        dir.resolve("V20261001000000__t.sql").writeText("create table a.t (id int); insert into a.t values (1);")
        flyway(dir, "a").migrate()
        jdbc().sql("insert into a.t values (2)").update()

        dir.resolve("V20261001000000__t.sql").writeText("create table a.t (id int, v int); insert into a.t values (1, 1);")
        CleanOnValidationErrorMigrationStrategy().migrate(flyway(dir, "a"))

        assertEquals(1, jdbc().sql("select count(*) from a.t").query(Int::class.java).single())
        assertEquals(1, jdbc().sql("select count(*) from information_schema.columns where table_schema = 'a' and table_name = 't' and column_name = 'v'").query(Int::class.java).single())
    }

    @Test
    fun `a new pending migration does not clean`() {
        val dir = Files.createTempDirectory("mig")
        dir.resolve("V20261001000000__t.sql").writeText("create table b.t (id int);")
        flyway(dir, "b").migrate()
        jdbc().sql("insert into b.t values (42)").update()

        dir.resolve("V20261001000100__u.sql").writeText("create table b.u (id int);")
        CleanOnValidationErrorMigrationStrategy().migrate(flyway(dir, "b"))

        assertEquals(42, jdbc().sql("select id from b.t").query(Int::class.java).single())
        assertEquals(1, jdbc().sql("select count(*) from information_schema.tables where table_schema = 'b' and table_name = 'u'").query(Int::class.java).single())
    }

    companion object {
        val db = PostgreSQLContainer(DockerImageName.parse("postgres:18"))

        @JvmStatic @BeforeAll
        fun start() = db.start()

        @JvmStatic @AfterAll
        fun stop() = db.stop()
    }
}
```

Run: `./gradlew :modules:migration-flyway:test --tests '*CleanOnValidationError*'` → Expected: 컴파일 실패.

- [ ] **Step 5: 구현 — 전략 + 자동설정**

`CleanOnValidationErrorMigrationStrategy.kt`:

```kotlin
package dev.sumin.skeleton.migration

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy

/**
 * 로컬 전용. 검증이 실패하면(이미 적용된 파일을 고쳐 체크섬이 다르거나, 적용된 파일이 사라짐) clean 후 다시 migrate.
 * 미적용(pending) 마이그레이션은 정상 상황이므로 검증 대상에서 빼고 clean 하지 않는다.
 * Flyway 12 에는 cleanOnValidationError 가 없어서 전략으로 구현한다. Boot 의 clean-disabled 기본값(true)을 건드리지 않고
 * clean 할 때만 같은 설정에 cleanDisabled(false) 를 얹은 Flyway 를 따로 만든다.
 */
class CleanOnValidationErrorMigrationStrategy : FlywayMigrationStrategy {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun migrate(flyway: Flyway) {
        val validation = Flyway.configure(flyway.configuration.classLoader)
            .configuration(flyway.configuration)
            .ignoreMigrationPatterns("*:pending")
            .load()
            .validateWithResult()
        if (!validation.validationSuccessful) {
            log.warn(
                "Flyway validation failed ({}); cleaning the local database and re-applying migrations",
                validation.invalidMigrations.joinToString { "${it.version}: ${it.errorDetails?.errorMessage}" },
            )
            Flyway.configure(flyway.configuration.classLoader)
                .configuration(flyway.configuration)
                .cleanDisabled(false)
                .load()
                .clean()
        }
        flyway.migrate()
    }
}
```

`MigrationFlywayAutoConfiguration.kt`:

```kotlin
package dev.sumin.skeleton.migration

import org.flywaydb.core.Flyway
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean

/** 가드와 기본값은 [MigrationFlywayEnvironmentPostProcessor] 가 (빈보다 먼저) 처리한다. 여기는 로컬 clean 전략만. */
@AutoConfiguration(before = [FlywayAutoConfiguration::class])
@ConditionalOnClass(Flyway::class)
@EnableConfigurationProperties(MigrationProperties::class)
class MigrationFlywayAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(FlywayMigrationStrategy::class)
    @ConditionalOnProperty(prefix = "skeleton.migration", name = ["clean-on-validation-error"], havingValue = "true")
    fun cleanOnValidationErrorMigrationStrategy(): FlywayMigrationStrategy = CleanOnValidationErrorMigrationStrategy()
}
```

imports 파일: `dev.sumin.skeleton.migration.MigrationFlywayAutoConfiguration`

(`FlywayAutoConfiguration` / `FlywayMigrationStrategy` 패키지가 다르면 컴파일 오류로 드러난다 — `unzip -l` 로 `spring-boot-flyway-4.1.1.jar` 의 실제 패키지를 확인해 맞춘다.)

Run: `./gradlew :modules:migration-flyway:test --tests '*CleanOnValidationError*'` → Expected: PASS 2.

- [ ] **Step 6: 실패하는 테스트 — 이름 규칙 검사**

`MigrationFileRulesTest.kt`:

```kotlin
package dev.sumin.skeleton.migration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MigrationFileRulesTest {
    private fun repo(vararg files: String): Path {
        val root = Files.createTempDirectory("repo")
        files.forEach { root.resolve(it).also { f -> f.parent.createDirectories(); f.writeText("select 1;") } }
        return root
    }

    @Test
    fun `clean repository has no violations`() {
        val root = repo(
            "modules/a/src/main/resources/db/migration/postgresql/V20261001000000__a.sql",
            "modules/a/src/main/resources/db/migration/mysql/V20261001000000__a.sql",
            "apps/api/src/main/resources/db/migration/postgresql/V20261001000100__b.sql",
        )
        assertEquals(emptyList(), MigrationFileRules.violations(root))
    }

    @Test
    fun `bad names, missing vendor folder, impossible timestamp`() {
        val root = repo(
            "apps/api/src/main/resources/db/migration/postgresql/V25__x.sql",
            "apps/api/src/main/resources/db/migration/V20261001000000__no_vendor.sql",
            "apps/api/src/main/resources/db/migration/postgresql/V20261399000000__bad_month.sql",
            "apps/api/src/main/resources/db/migration/postgresql/V20261001000000__Camel.sql",
        )
        val v = MigrationFileRules.violations(root).joinToString("\n")
        assertTrue("V25__x.sql" in v, v)
        assertTrue("V20261001000000__no_vendor.sql" in v && "<vendor>" in v, v)
        assertTrue("V20261399000000__bad_month.sql" in v, v)
        assertTrue("V20261001000000__Camel.sql" in v, v)
    }

    @Test
    fun `same version twice in one vendor across modules fails`() {
        val root = repo(
            "modules/a/src/main/resources/db/migration/postgresql/V20261001000000__a.sql",
            "apps/api/src/main/resources/db/migration/postgresql/V20261001000000__b.sql",
        )
        assertTrue(MigrationFileRules.violations(root).single().contains("duplicate version 20261001000000"))
    }

    @Test
    fun `module with both vendors must pair every migration`() {
        val root = repo(
            "modules/a/src/main/resources/db/migration/postgresql/V20261001000000__a.sql",
            "modules/a/src/main/resources/db/migration/mysql/V20261001000000__a.sql",
            "modules/a/src/main/resources/db/migration/postgresql/V20261001000100__only_pg.sql",
        )
        assertTrue(MigrationFileRules.violations(root).single().contains("only_pg"))
    }

    @Test
    fun `build output and non-sql files are ignored`() {
        val root = repo(
            "modules/a/build/resources/main/db/migration/postgresql/V1__copied.sql",
            "modules/a/src/main/resources/db/migration/postgresql/README.md",
        )
        assertEquals(emptyList(), MigrationFileRules.violations(root))
    }
}
```

`RepositoryMigrationsTest.kt`:

```kotlin
package dev.sumin.skeleton.migration

import java.nio.file.Path
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** 이 레포 전체(앱 · 모든 모듈)의 마이그레이션이 이름 규칙 · 중복 · 짝 규칙을 지키는지. ./gradlew build 에서 돈다. */
class RepositoryMigrationsTest {
    @Test
    fun `repository migrations follow the rules`() {
        val root = Path.of(requireNotNull(System.getProperty("skeleton.repoRoot")) { "skeleton.repoRoot system property" })
        assertEquals(emptyList(), MigrationFileRules.violations(root))
    }
}
```

Run: `./gradlew :modules:migration-flyway:test --tests '*MigrationFileRules*'` → Expected: 컴파일 실패.

- [ ] **Step 7: 구현 — `MigrationFileRules`**

```kotlin
package dev.sumin.skeleton.migration

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * 마이그레이션 파일 규칙 (docs/schema-management.md):
 * - 위치: src/<sourceSet>/resources/db/migration/<vendor>/   (vendor = postgresql | mysql)
 * - 이름: V<UTC yyyyMMddHHmmss>__<snake_case>.sql, 시각은 실제로 존재하는 값
 * - 같은 vendor 안에서 버전은 레포 전체(앱 · 모듈 · 테스트 리소스)에서 하나
 * - 두 vendor 폴더를 가진 소스 디렉토리는 버전 · 이름이 짝을 이룬다
 */
object MigrationFileRules {
    val NAME = Regex("""^V(\d{14})__[a-z0-9]+(_[a-z0-9]+)*\.sql$""")
    val VENDORS = setOf("postgresql", "mysql")
    private val LOCATION = Regex("""^(.*/src/[^/]+/resources/db/migration)/(.+)$""")
    private val TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val SKIP_DIRS = setOf("build", ".gradle", ".git", ".kotlin", "node_modules", "out")

    private data class Migration(val base: String, val vendor: String, val version: String, val name: String, val display: String)

    fun violations(root: Path): List<String> {
        val violations = mutableListOf<String>()
        val migrations = mutableListOf<Migration>()
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "sql" }
                .filter { path -> root.relativize(path).none { it.name in SKIP_DIRS } }
                .forEach { path ->
                    val rel = root.relativize(path).invariantSeparatorsPathString
                    val match = LOCATION.matchEntire(rel) ?: return@forEach
                    val (base, rest) = match.destructured
                    val parts = rest.split('/')
                    if (parts.size != 2 || parts[0] !in VENDORS) {
                        violations += "$rel: must be under db/migration/<vendor>/ with vendor in $VENDORS"
                        return@forEach
                    }
                    val name = NAME.matchEntire(parts[1])
                    if (name == null) {
                        violations += "$rel: name must match V<yyyyMMddHHmmss UTC>__<snake_case>.sql"
                        return@forEach
                    }
                    val version = name.groupValues[1]
                    if (runCatching { LocalDateTime.parse(version, TIMESTAMP) }.isFailure) {
                        violations += "$rel: $version is not a valid UTC timestamp"
                        return@forEach
                    }
                    migrations += Migration(base, parts[0], version, parts[1], rel)
                }
        }
        migrations.groupBy { it.vendor to it.version }.values.filter { it.size > 1 }.forEach { same ->
            violations += "duplicate version ${same.first().version} in ${same.first().vendor}: ${same.joinToString { it.display }}"
        }
        migrations.groupBy { it.base }.forEach { (base, inBase) ->
            val byVendor = inBase.groupBy({ it.vendor }, { it.name })
            if (byVendor.size > 1) {
                val all = byVendor.values.flatten().toSet()
                byVendor.forEach { (vendor, names) ->
                    (all - names.toSet()).forEach { missing -> violations += "$base/$vendor: missing $missing (every vendor folder in one module pairs up)" }
                }
            }
        }
        return violations.sorted()
    }
}
```

Run: `./gradlew :modules:migration-flyway:test --tests '*MigrationFileRules*'` → Expected: PASS 5. `RepositoryMigrationsTest` 는 Task 8 에서 앱 마이그레이션을 옮기기 전까지 `V1__init.sql`, `V9000__utc_probe.sql` 로 실패한다 — 그게 맞다 (위반 메시지에 두 파일이 나오는지 확인).

- [ ] **Step 8: `newMigration` 작업**

루트 `build.gradle.kts` 끝에 추가:

```kotlin
// ./gradlew newMigration -Pname=add_x [-Pmodule=apps/api] [-Pvendor=postgresql]
// 지금 UTC 시각으로 V<yyyyMMddHHmmss>__<name>.sql 을 만든다. -Pvendor 를 빼면 그 모듈에 있는 vendor 폴더 전부에 같은 버전으로.
tasks.register("newMigration") {
    group = "skeleton"
    description = "Creates a timestamped Flyway migration (UTC yyyyMMddHHmmss)."
    doLast {
        val name = (project.findProperty("name") as String?) ?: error("-Pname=<snake_case> is required")
        require(Regex("^[a-z0-9]+(_[a-z0-9]+)*$").matches(name)) { "-Pname must be snake_case: $name" }
        val module = (project.findProperty("module") as String?) ?: "apps/api"
        val base = rootDir.resolve("$module/src/main/resources/db/migration")
        val vendors = (project.findProperty("vendor") as String?)?.let { listOf(it) }
            ?: base.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted()?.ifEmpty { null }
            ?: listOf("postgresql")
        val version = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
        vendors.forEach { vendor ->
            val file = base.resolve("$vendor/V${version}__$name.sql")
            file.parentFile.mkdirs()
            check(!file.exists()) { "$file exists" }
            file.writeText("-- $name ($vendor). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)\n")
            println("created ${file.relativeTo(rootDir)}")
        }
    }
}
```

`settings.gradle.kts` 에 `include(":modules:migration-flyway")`.

Run: `./gradlew newMigration -Pname=probe_task -Pmodule=modules/job-queue-jdbc` → Expected: `postgresql/`, `mysql/` 두 폴더에 같은 버전 파일 생성 로그. 생성된 두 파일을 지운다 (`git status` 로 확인).

- [ ] **Step 9: 체크포인트** — 커밋하지 않는다.

---

### Task 7: `persistence-jooq` — PG 코드 생성 + PG 테스트

**Files:**
- Modify: `modules/persistence-jooq/build.gradle.kts`
- Move: `modules/persistence-jooq/src/main/resources/db/skeleton-jooq-schema.sql` → `…/db/jooq-probe-mysql.sql` (내용 그대로)
- Create: `modules/persistence-jooq/src/main/resources/db/jooq-probe-postgresql.sql`
- Modify: `modules/persistence-jooq/src/test/resources/application.yml`, `JooqTestApplication.kt`, `JooqIntegrationTest.kt`
- Modify: `modules/persistence-jooq/src/main/kotlin/dev/sumin/skeleton/persistence/jooq/UtcInstantConverter.kt` (KDoc: "MySQL 생성 경로 전용")

**Interfaces:**
- Consumes: `db-postgresql` (테스트), job-queue · notification 의 `db/migration/<vendor>/` (Task 4·5).
- Produces: Gradle 속성 `skeleton.jooq.dialect` (`postgresql` | `mysql`, 기본 `postgresql`); 생성 클래스 `JooqProbe`, `SkeletonJobs`, `SkeletonNotificationInbox` (패키지 `dev.sumin.skeleton.persistence.jooq.generated`), PG 생성물의 `*_at` 타입은 `Instant`.

- [ ] **Step 1: 예시 DDL**

`git mv src/main/resources/db/skeleton-jooq-schema.sql src/main/resources/db/jooq-probe-mysql.sql`. 새 `jooq-probe-postgresql.sql`:

```sql
-- 이 모듈의 코드 생성 · 테스트용 예시 스키마 (PostgreSQL). 앱은 자기 db/migration/postgresql 폴더를 가리킨다.
create table if not exists jooq_probe (
    id          bigint generated by default as identity primary key,
    name        varchar(100) not null,
    happened_at timestamptz  not null,
    local_wall  timestamp,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null
);
```

- [ ] **Step 2: 빌드 파일**

`dependencies` 의 테스트 부분을 PG 로:

```kotlin
    testImplementation(project(":modules:db-postgresql"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
```

(`testcontainers-mysql`, `testRuntimeOnly("com.mysql:mysql-connector-j")` 삭제.)

`jooq { … }` 블록과 `collectModuleDdl` 을 아래로 교체:

```kotlin
// 생성 기준 방언: -Pskeleton.jooq.dialect=mysql 로 바꾼다 (기본 postgresql). 생성물은 하나 — 앱이 끼운 db-* 모듈과 같게 고른다
val jooqDialect = providers.gradleProperty("skeleton.jooq.dialect").getOrElse("postgresql")
require(jooqDialect in setOf("postgresql", "mysql")) { "skeleton.jooq.dialect must be postgresql or mysql: $jooqDialect" }

// 예시 DDL + 다른 모듈의 Flyway 마이그레이션(같은 방언). 모듈 마이그레이션이 DDLDatabase 로 파싱되는지를 빌드마다 증명한다
val collectModuleDdl by tasks.registering(Copy::class) {
    from("src/main/resources/db") { include("jooq-probe-$jooqDialect.sql") }
    from("../job-queue-jdbc/src/main/resources/db/migration/$jooqDialect")
    from("../notification-jdbc/src/main/resources/db/migration/$jooqDialect")
    into(layout.buildDirectory.dir("module-ddl"))
}

jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property { key = "scripts"; value = "build/module-ddl" }
                    // 앱 레시피는 자기 마이그레이션 폴더 하나라 sort=flyway. 여기는 예시 DDL 이 섞여 semantic
                    property { key = "sort"; value = "semantic" }
                    property { key = "unqualifiedSchema"; value = "none" }
                    property { key = "defaultNameCase"; value = "lower" }
                    property { key = "parseIgnoreComments"; value = "true" }   // MySQL 마이그레이션의 [jooq ignore] 마커
                }
                forcedTypes {
                    if (jooqDialect == "postgresql") {
                        // *_at timestamptz = 일어난 시점 → jOOQ INSTANT (런타임 바인딩은 jOOQ 가 OffsetDateTime 으로)
                        forcedType {
                            name = "INSTANT"
                            includeExpression = "(?i:.*_at)"
                            includeTypes = "(?i:timestamp.*with.*time.*zone)"
                        }
                    } else {
                        forcedType {
                            userType = "java.time.Instant"
                            converter = "dev.sumin.skeleton.persistence.jooq.UtcInstantConverter"
                            includeExpression = "(?i:.*_at)"
                            includeTypes = "(?i:datetime.*|timestamp.*)"
                        }
                    }
                }
            }
            target {
                packageName = "dev.sumin.skeleton.persistence.jooq.generated"
                directory = "build/generated-src/jooq/main"
            }
        }
    }
}
tasks.named("jooqCodegen") {
    dependsOn(collectModuleDdl)
    inputs.dir(layout.buildDirectory.dir("module-ddl"))
    inputs.property("skeleton.jooq.dialect", jooqDialect)
}
```

(기존 `tasks.named("jooqCodegen") { … }` 블록은 위 것으로 대체, `sourceSets.main`, `compileKotlin/compileJava dependsOn` 은 유지.)

- [ ] **Step 3: 생성물 확인**

Run: `rm -rf modules/persistence-jooq/build/generated-src modules/persistence-jooq/build/module-ddl && ./gradlew :modules:persistence-jooq:jooqCodegen && grep -n "HAPPENED_AT\|LOCAL_WALL\|NEXT_RUN_AT\|EVENT_CREATED_AT" -r modules/persistence-jooq/build/generated-src | cut -c1-200`
Expected: `HAPPENED_AT`, `NEXT_RUN_AT`, `EVENT_CREATED_AT` 가 `TableField<…, Instant>`, `LOCAL_WALL` 은 `LocalDateTime`. `OffsetDateTime` 이면 `includeTypes` 가 안 걸린 것 — 생성 파일의 `SQLDataType.…` 이름을 보고 정규식을 맞춘다.

- [ ] **Step 4: 테스트를 PG 로**

`src/test/resources/application.yml`:

```yaml
spring:
  sql:
    init:
      mode: always
      schema-locations: classpath:db/jooq-probe-postgresql.sql   # 코드 생성에 쓴 같은 DDL 로 테스트 DB 를 만든다 (Flyway 없이)
```

`JooqTestApplication.kt` 의 컨테이너:

```kotlin
import org.testcontainers.postgresql.PostgreSQLContainer

@TestConfiguration(proxyBeanMethods = false)
class JooqTestcontainers {
    @Bean
    @ServiceConnection
    fun db(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
}
```

`JooqIntegrationTest` 의 원문 단정 두 줄을 PG 로:

```kotlin
        val raw = dsl.fetchValue(
            "select (happened_at at time zone 'UTC')::text || '|' || local_wall::text || '|' || (created_at at time zone 'UTC')::text from jooq_probe where id = ?",
            record.id,
        ) as String
        assertEquals("2026-03-01 12:00:00.123456|2026-10-05 21:00:00|2026-09-10 00:00:00", raw)
```

(`select @@session.time_zone` 단정 줄은 삭제.) KDoc "실제 MySQL" → "실제 PostgreSQL".

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :modules:persistence-jooq:test`
Expected: PASS. 이어서 MySQL 생성 경로가 살아 있는지: `./gradlew :modules:persistence-jooq:jooqCodegen -Pskeleton.jooq.dialect=mysql` → 성공, 확인 뒤 `./gradlew :modules:persistence-jooq:jooqCodegen` 로 PG 생성물 복원.

- [ ] **Step 6: 체크포인트** — 커밋하지 않는다.

---

### Task 8: `apps/api` 조립 · 인프라 · 앱 마이그레이션

**Files:**
- Modify: `apps/api/build.gradle.kts`
- Modify: `apps/api/src/main/resources/application.yml`, `application-local.yml`, `application-dev.yml`
- Delete: `apps/api/src/main/resources/db/migration/V1__init.sql`
- Move: `apps/api/src/test/resources/db/migration/V9000__utc_probe.sql` → `apps/api/src/test/resources/db/migration/postgresql/V20260101000000__utc_probe.sql` (PG DDL 로 다시 씀)
- Modify: `apps/api/src/test/kotlin/dev/sumin/skeleton/TestcontainersConfiguration.kt`, `…/persistence/UtcRoundTripIntegrationTest.kt`, `…/persistence/SchemaSqlInitIntegrationTest.kt`, `…/api/HelloControllerIntegrationTest.kt` (KDoc)
- Modify: `apps/api/src/test/resources/schema-sql-example/schema.sql`
- Modify: `docker-compose.yml`, `.env.example`
- Modify: `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/audit/BaseAuditTimestampsTest.kt` (테스트 이름만)

**Interfaces:**
- Consumes: `db-postgresql`, `migration-flyway` (Task 2·6), 모듈 마이그레이션 (Task 4·5).

- [ ] **Step 1: 의존성**

`apps/api/build.gradle.kts`:
- 추가: `implementation(project(":modules:db-postgresql"))`, `implementation(project(":modules:migration-flyway"))`
- 삭제: `implementation("org.flywaydb:flyway-mysql")`, `runtimeOnly("com.mysql:mysql-connector-j")`, `testImplementation("org.testcontainers:testcontainers-mysql")`
- 추가: `testImplementation("org.testcontainers:testcontainers-postgresql")`

- [ ] **Step 2: 설정**

`application.yml` 의 `spring.datasource.url` 줄:

```yaml
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/app}
```

같은 파일 `spring.flyway` 아래에 (migration-flyway 기본값과 같지만 앱에서 보이게):

```yaml
    locations: classpath:db/migration/{vendor}   # 앱 · 모듈 마이그레이션 합류. 이름 규칙은 docs/schema-management.md
```

`application-local.yml`, `application-dev.yml` 의 URL 기본값 → `jdbc:postgresql://localhost:5432/app` (쿼리 파라미터 없음). `application-local.yml` 에 추가:

```yaml
skeleton:
  migration:
    clean-on-validation-error: true   # 로컬 전용: 고친 미배포 마이그레이션은 DB 를 밀고 다시 깐다 (local 밖에서 켜면 기동 실패)
```

(이미 `skeleton:` 루트가 있으면 그 아래로 합친다.)

- [ ] **Step 3: 앱 마이그레이션 · 테스트 리소스**

`git rm apps/api/src/main/resources/db/migration/V1__init.sql`.
`git mv apps/api/src/test/resources/db/migration/V9000__utc_probe.sql apps/api/src/test/resources/db/migration/postgresql/V20260101000000__utc_probe.sql` (디렉토리 먼저) 후 내용:

```sql
-- 테스트 전용: 시각 3종 왕복(JVM 시간대 무관) 검증용 테이블
create table utc_probe (
    id            bigint generated by default as identity primary key,
    happened_at   timestamptz not null,   -- Instant (UTC)
    birthday      date        null,       -- LocalDate (변환 없음)
    local_wall    timestamp   null        -- LocalDateTime (벽시계)
);
```

`schema-sql-example/schema.sql` 의 `skeleton_jobs` 부분을 Task 4 의 PG 마이그레이션 내용으로 바꾸고, 위쪽 `schema_sql_probe` 를:

```sql
create table if not exists schema_sql_probe (
    id   bigint generated by default as identity primary key,
    name varchar(50) not null
);
```

`SchemaSqlInitIntegrationTest` 의 인덱스 단정에서 `"PRIMARY"` → `"skeleton_jobs_pkey"`, 조회 SQL 을 PG 로:

```kotlin
        val indexes = jdbc.sql("select indexname from pg_indexes where tablename = 'skeleton_jobs'")
            .query(String::class.java).list().toSet()
        assertEquals(setOf("skeleton_jobs_pkey", "idx_skeleton_jobs_claim", "idx_skeleton_jobs_running"), indexes)
```

`flyway_schema_history` 존재 확인 쿼리의 `table_schema = database()` → `table_schema = current_schema()`. KDoc 의 `[jooq ignore]` 마커 설명은 "PG 는 `create index if not exists` 로 재실행이 멱등" 으로 바꾼다.

- [ ] **Step 4: Testcontainers · 시간 왕복 테스트**

`TestcontainersConfiguration.kt`:

```kotlin
package dev.sumin.skeleton

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
}
```

`UtcRoundTripIntegrationTest` 의 원문 단정을 PG 로 (시각은 Review Focus 의 날짜 경계 값으로):

```kotlin
    @Test
    fun `Instant, LocalDate, LocalDateTime 이 서울 JVM 에서도 그대로 왕복한다`() {
        val at = Instant.parse("2026-03-01T00:30:00.123456Z") // 서울 09:30 — 날짜 경계
        val saved = probes.save(UtcProbe(happenedAt = at, birthday = LocalDate.of(1998, 3, 5), localWall = LocalDateTime.parse("2026-10-05T21:00")))
        val reloaded = probes.findById(saved.id!!).get()

        assertEquals(at, reloaded.happenedAt)
        assertEquals(LocalDate.of(1998, 3, 5), reloaded.birthday)
        assertEquals(LocalDateTime.parse("2026-10-05T21:00"), reloaded.localWall)

        val raw = jdbc.sql("select (happened_at at time zone 'UTC')::text || '|' || birthday::text || '|' || local_wall::text from utc_probe where id = :id")
            .param("id", saved.id).query(String::class.java).single()
        assertEquals("2026-03-01 00:30:00.123456|1998-03-05|2026-10-05 21:00:00", raw)
    }
```

(`@@session.time_zone` 단정 삭제, KDoc 을 "db-postgresql 의 시각 고정이 JVM 시간대와 무관한지" 로.) `HelloControllerIntegrationTest` KDoc "실제 MySQL" → "실제 PostgreSQL". `BaseAuditTimestampsTest` 의 테스트 이름 "MySQL DATETIME 마이크로초" → "DB 마이크로초(MySQL datetime(6), PG timestamptz)".

- [ ] **Step 5: compose · env**

`docker-compose.yml`:

```yaml
services:
  postgres:
    image: postgres:18
    environment:
      POSTGRES_DB: app
      POSTGRES_USER: app
      POSTGRES_PASSWORD: dev
    ports:
      - "127.0.0.1:5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql

  # db-mysql 모듈로 조립한 앱용: docker compose --profile mysql up
  mysql:
    image: mysql:8.4
    profiles: [mysql]
    environment:
      MYSQL_ROOT_PASSWORD: dev-root
      MYSQL_DATABASE: app
      MYSQL_USER: app
      MYSQL_PASSWORD: dev
    ports:
      - "127.0.0.1:3306:3306"
    volumes:
      - mysql-data:/var/lib/mysql

  app:
    build: .
    depends_on:
      - postgres
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/app
      SPRING_DATASOURCE_USERNAME: app
      SPRING_DATASOURCE_PASSWORD: dev
    ports:
      - "8080:8080"

volumes:
  postgres-data:
  mysql-data:
```

(postgres:18 이미지는 데이터 디렉토리가 `/var/lib/postgresql` 하위 판 폴더로 바뀌었다 — 마운트는 `/var/lib/postgresql`.)

`.env.example`: `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/app`.

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :apps:api:test :modules:migration-flyway:test`
Expected: PASS. `RepositoryMigrationsTest` 도 이제 PASS (앱 · 모듈 마이그레이션이 모두 규칙 안).

- [ ] **Step 7: 체크포인트** — 커밋하지 않는다.

---

### Task 9: 문서 · CHANGELOG · rename · 전체 빌드

**Files:**
- Modify: `docs/schema-management.md`, `docs/persistence-jooq.md`, `docs/time.md`, `docs/job-queue-jdbc.md`, `docs/minimal-composition.md`, `README.md`, `CLAUDE.md`, `CHANGELOG.md`

- [ ] **Step 1: `docs/schema-management.md`**

Mode A(Flyway) 절을 아래 내용으로 다시 쓴다 (Mode B 절은 PG 판으로 문구만):
- 위치 `src/main/resources/db/migration/<vendor>/`, `spring.flyway.locations=classpath:db/migration/{vendor}` — 모듈 마이그레이션이 같은 경로로 합쳐진다.
- 이름 `V<UTC yyyyMMddHHmmss>__<snake_case>.sql`. 만들기: `./gradlew newMigration -Pname=add_x [-Pmodule=apps/api] [-Pvendor=postgresql]`. 손으로 시각을 치지 않는다.
- 검사: `:modules:migration-flyway:test` 의 `RepositoryMigrationsTest` 가 `./gradlew build` 에서 이름 · vendor 폴더 · 중복 버전 · 짝 불일치를 막는다.
- outOfOrder 는 모든 환경 `true` — 이유(늦게 합쳐진 이른 시각 파일)와 대가("마이그레이션은 서로 독립: 다른 브랜치의 미적용 마이그레이션에 기대지 않는다").
- 두 브랜치가 같은 테이블을 건드릴 때: 각자 독립 마이그레이션(`add column if not exists` 등 서로를 가정하지 않는 형태), 합친 뒤 충돌하면 고치지 말고 새 마이그레이션으로 정리.
- 고쳐도 되는 때: 어느 공유 환경(dev · stage · prod)에도 적용 안 됐을 때만 — 로컬은 `skeleton.migration.clean-on-validation-error=true`(local 프로필)로 밀고 다시 깐다. 한 번이라도 공유 환경에 적용됐으면 새 파일.
- 가드: `clean-on-validation-error` 나 `spring.flyway.clean-disabled=false` 가 `skeleton.migration.clean-allowed-profiles`(기본 `local`) 밖에서 켜지면 기동 실패.

- [ ] **Step 2: 나머지 문서**

- `docs/persistence-jooq.md`: 레시피를 PG 기본으로 — `scripts` = 앱의 `src/main/resources/db/migration/postgresql`, `sort=flyway`, forcedType `name = "INSTANT"` + `includeTypes = "(?i:timestamp.*with.*time.*zone)"`, MySQL 은 `-Pskeleton.jooq.dialect=mysql` 경로와 기존 `UtcInstantConverter` + `[jooq ignore]` 마커. 주의: `jsonb` 가 `JSON` 으로 생성됨. 테스트 패턴 절을 PG 로.
- `docs/time.md`: 저장 규칙 표를 PG(`timestamptz`/`date`/`timestamp`)와 MySQL(`datetime(6)`/`date`/`datetime(6)`) 두 열로. 하위 앱 규칙 "JdbcClient 시각은 `SqlDialect.instantParam` / `readInstant`" 와 실측 표(spec §2).
- `docs/job-queue-jdbc.md`: 마이그레이션 경로 두 벌, 앱이 db-* 모듈 하나를 끼워야 함.
- `docs/minimal-composition.md`: 모듈 예시에 `db-postgresql`, `migration-flyway` 추가, 접두사 표에 `migration-flyway | skeleton.migration` 줄.
- `README.md`, `CLAUDE.md`: 모듈 목록에 `db-postgresql`, `db-mysql`, `migration-flyway`, 기본 DB 를 PostgreSQL 18 로.

- [ ] **Step 3: CHANGELOG `[Unreleased]` — 이식 안내**

`## [Unreleased]` 바로 아래에 `### 이식 안내 — PostgreSQL 기본 + 방언 조립식 + Flyway 충돌 방지` 절을 넣는다. 하위 앱이 위에서 아래로 따라 하게, 이 순서와 내용으로:

1. **기준**: 마지막 MySQL 전용 커밋 `5cf377b`. MySQL 로 남을 앱은 `db-mysql` 을 끼우면 동작이 같다.
2. **모듈 조립**: `implementation(project(":modules:db-postgresql"))` (또는 `db-mysql`) 정확히 하나 + `implementation(project(":modules:migration-flyway"))`. 앱에서 `flyway-mysql`, `mysql-connector-j` 삭제 (방언 모듈이 가져옴).
3. **새/옮긴 파일 표**: `SqlDialect`, `SqlDialectVerifier`, `SqlDialectAutoConfiguration` (persistence-jdbc), `PostgresSqlDialect`, `PostgresTimeConversions`, `PostgresDialectAutoConfiguration` (db-postgresql), `MySqlSqlDialect`, `MySqlTimeConversions`(← `UtcInstantConversions`), `MySqlTimeZoneEnvironmentPostProcessor`(← `JdbcTimeZoneEnvironmentPostProcessor`), `MySqlDialectAutoConfiguration` (db-mysql), 삭제: `JdbcUtcAutoConfiguration`, `JooqTimeZoneEnvironmentPostProcessor`, migration-flyway 의 다섯 클래스.
4. **설정 · 속성 전체**: `spring.datasource.url=jdbc:postgresql://…`, `spring.flyway.locations=classpath:db/migration/{vendor}`, `spring.flyway.out-of-order=true`, `spring.flyway.validate-migration-naming=true` (기본값으로 들어감), `skeleton.migration.clean-on-validation-error`, `skeleton.migration.clean-allowed-profiles`, Gradle `skeleton.jooq.dialect`. 삭제: MySQL Hikari 세션 속성 3개(db-mysql 이 넣음).
5. **새 시간 규칙**: "SQL 파라미터는 UTC LocalDateTime" 폐기 → "JdbcClient / NamedParameterJdbcTemplate 의 시각은 `SqlDialect.instantParam(instant)`, 읽기는 `SqlDialect.readInstant(rs, column)`". Data JDBC · jOOQ 는 방언 모듈 · 코드 생성이 처리. 실측 표 첨부.
6. **jOOQ 생성 설정 블록** (Task 7 의 `jooq {}` 를 앱 경로로 바꾼 판 — `scripts` = 앱 `db/migration/postgresql`, `sort=flyway`).
7. **Flyway**: 이름 규칙, `newMigration`, 검사 테스트, outOfOrder, 로컬 clean, 가드. 마이그레이션 재명명 표(`V2026091001__skeleton_jobs` → `postgresql|mysql/V20260910010000__skeleton_jobs`, `V2026061701__notification_inbox` → `…/V20260617010000__skeleton_notification_inbox`, 앱 `V1__init` 삭제). 이미 MySQL 에 적용된 DB 는 이력과 어긋나므로 새 DB 로 시작하거나 `flyway_schema_history` 를 손으로 맞춘다.
8. **Testcontainers**: `postgres:18`, `org.testcontainers.postgresql.PostgreSQLContainer`, `testcontainers-postgresql`. 모듈의 두 DB 테스트 묶음(`postgresTest`, `mysqlTest`, `src/dbTest`).
9. **MySQL → PG SQL 차이표**: `ON DUPLICATE KEY UPDATE` → `ON CONFLICT (…) DO UPDATE SET … = EXCLUDED.…`; 중복 무시 → `ON CONFLICT DO NOTHING` (`SqlDialect.insertIgnore`), 예외 삼키기 금지(PG 트랜잭션 abort); `LAST_INSERT_ID()` → `RETURNING id` 또는 `KeyHolder` + 키 칼럼 지정(`update(keys, "id")`); `datetime(6)` → `timestamptz`(시점) / `timestamp`(벽시계); `auto_increment` → `generated by default as identity`; `engine=`, `charset=`, `collate` 삭제; 인라인 `index` → `create index if not exists`; `bigint unsigned` → `bigint`; 식별자는 따옴표 없으면 소문자(대문자 칼럼은 `"Name"`); UPDATE/DELETE 의 `LIMIT` 없음 → `where id in (select … limit n)`; `json` → `jsonb`(jOOQ 생성 타입은 `JSON`); `tinyint(1)` → `boolean`; `concat(a, b)` 는 되지만 `||` 권장; `@@session.time_zone` → `show timezone`; `database()` → `current_schema()`; `ifnull` → `coalesce`.
10. **notification-jdbc 동작 수정**: 중복 저장이 같은 트랜잭션을 깨던 문제(PG).

- [ ] **Step 4: rename 스크립트 확인**

```bash
S=$(mktemp -d)
rsync -a --exclude build --exclude .gradle --exclude .kotlin --exclude .git ./ "$S/ren/"
(cd "$S/ren" && scripts/rename-skeleton.sh dev.sumin.ovation ovation Ovation && ./gradlew build)
```

Expected: 스크립트가 "no skeleton leftovers", 빌드 PASS. `skeleton.migration` → `ovation.migration` 으로 바뀌었는지 `grep -rn "ovation.migration" "$S/ren/modules/migration-flyway/src/main"` 로 확인. 남은 흔적 검사에 `skeleton_jobs`/`skeleton_notification_inbox` 가 걸리면 스크립트의 의도적 예외 목록에 `skeleton_notification_inbox` 를 추가한다 (`skeleton_jobs` 와 같은 이유).

- [ ] **Step 5: 전체 빌드**

Run: `./gradlew build` (단독 실행)
Expected: BUILD SUCCESSFUL. 테스트 수를 JUnit XML 로 집계해 기록 (`find . -path '*/build/test-results/*/*.xml'` → tests/failures 합).

- [ ] **Step 6: 리뷰** — `code-reviewer` 에이전트로 전체 변경분 리뷰, 지적 반영.

- [ ] **Step 7: 체크포인트** — 커밋하지 않는다. 주인에게 결과 보고 후 커밋 지시를 기다린다.

---

### Task 10: ovation 「A2-1 계획 실행」 세션에 보고

- [ ] **Step 1:** `ListAgents` 로 ovation 세션(이름에 `a2-1` 또는 제목 「A2-1 계획 실행」)을 찾는다.
- [ ] **Step 2:** `SendMessage` 로 보낸다: 정한 설계(주인 답 — MySQL 유지 · 조립식, DDLDatabase 유지, outOfOrder 전 환경, postgres:18), 커밋(주인이 커밋했으면 해시, 아니면 "미커밋, 작업 트리 상태"), 테스트 수, CHANGELOG `[Unreleased]` 이식 안내 절 경로와 본문.
