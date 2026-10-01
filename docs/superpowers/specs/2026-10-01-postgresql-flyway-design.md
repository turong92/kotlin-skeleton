# PostgreSQL 기본 + 방언 조립식 + Flyway 충돌 방지 — 설계

- 날짜: 2026-10-01
- 요청: ovation `.superpowers/sdd/2026-10-01-skeleton-pg-request.md` (주인 sumin)
- 기준 커밋: `5cf377b`
- 결과물의 핵심: 하위 앱(ovation)이 손으로 옮길 수 있는 **CHANGELOG 이식 안내**

## 1. 목표와 결정

| 항목 | 결정 | 근거 |
|---|---|---|
| 기본 DB | PostgreSQL, 이미지 `postgres:18` (Testcontainers · compose 동일) | 주인 결정. 2026-10-01 기준 최신 주 판 (19 이미지 없음 — 실측) |
| MySQL | **유지**. 단 조립식으로 — 앱이 방언 모듈 하나를 골라 끼운다 | 주인 결정 "둘 다 유지" + "전략 패턴 / 조립식" |
| jOOQ 코드 생성 | `DDLDatabase` 유지 (빌드에 DB 없음), Flyway 마이그레이션 폴더를 `sort=flyway` 로 읽음 | 주인 "flyway sql 읽어서 db 안 띄워도". PG DDL 파싱 실측 통과 (§4) |
| Flyway 버전 | `V<UTC yyyyMMddHHmmss>__<snake_case>.sql` | 브랜치 · 워크트리 동시 작업 시 순번 충돌 제거 |
| outOfOrder | 앱의 선택 — `apps/api` 는 **모든 환경 `true`** (모듈 기본값 아님, 주인 정정) | 주인 "모르겠다" → 권장안. §5.2 |
| 로컬 clean | 허용 프로필(기본 `local`)에서만, 체크섬 검증 실패 시 clean 후 재적용. 허용 밖에서 켜지면 기동 실패 | 주인 "로컬 flyway clean 활성화" |

## 2. 실측 (설계 근거)

JVM `Asia/Seoul`, 값 `2026-03-01T00:30:00Z`, 2026-10-01 측정.

pgjdbc 42.7.8 + postgres:18, 칼럼 `timestamptz`:

| 바인딩 | 저장 결과 | 읽기 |
|---|---|---|
| `OffsetDateTime` (UTC) | 정확 | `getObject(OffsetDateTime)` 정확 |
| `Timestamp.from(instant)` | 정확 | 정확 |
| `Instant` | 드라이버 거부 ("Can't infer the SQL type") | `getObject(Instant)` 도 거부 |
| UTC `LocalDateTime` (현재 하위 앱 규칙) | **−9시간** | 틀림 |

`date` ↔ `LocalDate` 는 그대로 왕복. pgjdbc 는 세션 `TimeZone` 을 JVM 시간대로 잡는다 (실측 `Asia/Seoul`).

Connector/J 9.7.0 + mysql:8.4, 칼럼 `datetime(6)` (UTC 세션 설정 유무 모두 동일):

| 바인딩 | 저장 원문 | 읽기 |
|---|---|---|
| `OffsetDateTime` (UTC) | `09:30` (JVM 벽시계) | 같은 JVM 에서만 맞음 |
| UTC `LocalDateTime` | `00:30` (UTC) | 정확 |

→ **두 DB 에 공통으로 맞는 바인딩 타입은 없다.** 방언별 전략이 필요하다.

Flyway: Boot 4.1.1 이 관리하는 판은 12.4.0. `cleanOnValidationError` 없음. 있는 것: `cleanDisabled`, `outOfOrder`, `validateMigrationNaming`, `validateOnMigrate`, `ignoreMigrationPatterns` (Boot 속성 `spring.flyway.*` 로 모두 노출).

## 3. 구조 — 전략 인터페이스 + 방언 모듈 조립

```
persistence-jdbc   ── SqlDialect (인터페이스), 방언 없음 감지 가드
   ▲          ▲
db-postgresql  db-mysql      ← 앱이 하나만 끼운다
   ▲
job-queue-jdbc, notification-jdbc, persistence-jooq  ← SqlDialect 만 안다
```

### 3.1 `SqlDialect` (persistence-jdbc)

방언마다 다른 것만 담는다.

```kotlin
interface SqlDialect {
    val vendor: String                                   // "postgresql" | "mysql" — Flyway {vendor} 와 같은 값
    fun instantParam(value: Instant?): Any?              // JdbcClient .param(...) 에 넣을 값
    fun readInstant(rs: ResultSet, column: String): Instant?
    fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>): String
}
```

- 생성 키: `GeneratedKeyHolder` + 키 칼럼 이름 지정(`keyColumnNames = ["id"]`)으로 두 DB 공통 처리 — 전략에 넣지 않는다.
- `persistence-jdbc` 의 가드: 기동 시 `SqlDialect` 빈이 0개면 "`modules:db-postgresql` 또는 `modules:db-mysql` 을 끼워라", 2개 이상이면 "하나만" 으로 실패. 단, DataSource 가 없는 앱(JDBC 미사용)은 검사하지 않는다.
- 기존 `JdbcTimeZoneEnvironmentPostProcessor` · `UtcInstantConversions`(MySQL 전용 내용)는 `db-mysql` 로 옮긴다.

### 3.2 `db-postgresql`

- 의존: `org.postgresql:postgresql`(runtime), `org.flywaydb:flyway-database-postgresql`.
- `PostgresSqlDialect`: `instantParam` = UTC `OffsetDateTime`, `readInstant` = `getObject(OffsetDateTime)` → `Instant`, `insertIgnore` = `insert … on conflict (<conflictColumns>) do nothing`.
- Data JDBC 변환: `Instant` → `OffsetDateTime`(UTC) 쓰기, `OffsetDateTime`/`Timestamp` → `Instant` 읽기, `LocalDate` 그대로.
- 세션 설정 우회 없음.

### 3.3 `db-mysql`

- 의존: `com.mysql:mysql-connector-j`(runtime), `org.flywaydb:flyway-mysql`.
- `MySqlSqlDialect`: 지금 규칙 그대로 — UTC `LocalDateTime` 바인딩, `LocalDateTime` → UTC `Instant` 읽기, `insert ignore into …`.
- 지금의 Hikari 세션 UTC 강제(`connectionTimeZone`, `forceConnectionTimeZoneToSession`, `preserveInstants=false`)와 `UtcInstantConversions` 가 여기로 온다.

### 3.4 기능 모듈

- `job-queue-jdbc`: SQL 은 공통(`for update skip locked` 는 PG · MySQL 8 모두 지원). 시각 바인딩만 `SqlDialect`. 생성 키는 키 칼럼 지정. 테이블 `skeleton_jobs`, 핸들러 멱등 의미 그대로.
- `notification-jdbc`: 중복 삽입을 `runCatching` 으로 삼키던 것 → `SqlDialect.insertIgnore`. (PG 는 실패한 문장이 트랜잭션 전체를 abort 시키므로 삼키기 방식이 PG 에서 깨진다.)
- 마이그레이션: 각 모듈이 `db/migration/postgresql/`, `db/migration/mysql/` 두 폴더를 가진다. 같은 버전 · 같은 이름 짝.
- 방언 모듈에 의존하지 않는다 (`compileOnly` 도 없음). 테스트에서만 두 방언 모듈을 `testImplementation` 으로 끼워 두 DB 를 모두 돈다.

### 3.5 앱 조립

```kotlin
// apps/<app>/build.gradle.kts
implementation(project(":modules:db-postgresql"))   // 또는 :modules:db-mysql — 하나만
```

```yaml
spring:
  flyway:
    locations: classpath:db/migration/{vendor}      # 앱 · 모듈 마이그레이션이 같은 경로에서 합쳐진다
```

`{vendor}` 는 Boot 가 연결된 DB 로 채운다(`postgresql` / `mysql`). 위치를 앱 폴더로 제한하던 꼼수는 타임스탬프 버전으로 필요 없어진다.

## 4. jOOQ (persistence-jooq)

- `DDLDatabase` 의 `scripts` = 마이그레이션 폴더(`…/db/migration/postgresql`), `sort=flyway`. 실측(2026-10-01, jOOQ 3.21.7): identity, `timestamptz`, `jsonb`, 부분 인덱스(`where`), `comment on`, `alter table … add column if not exists`, 두 파일의 Flyway 순서 모두 통과.
- 생성 기준 방언: Gradle 속성 `skeleton.jooq.dialect` (기본 `postgresql`). 모듈 자체 테스트는 PG 생성물 기준.
- 시간: PG `timestamptz` 를 jOOQ `INSTANT` 로 생성 (forcedType 을 PG 타입명 `TIMESTAMP WITH TIME ZONE` 에 맞춤 — 실측에서 기존 정규식은 안 걸려 `OffsetDateTime` 으로 생성됨). MySQL 생성 경로는 기존 `UtcInstantConverter` 유지.
- 주의(문서화): `jsonb` 가 jOOQ 에서 `JSON` 으로 생성된다.
- PG 는 `create index if not exists` 를 지원하므로 `[jooq ignore]` 마커는 MySQL 마이그레이션에만 남는다.
- 코드 생성 증명: persistence-jooq 가 빌드마다 job-queue · notification 의 PG 마이그레이션 + 예시 DDL 로 생성 (기존 `collectModuleDdl` 을 vendor 폴더 기준으로). 이 모듈은 예시 DDL 이 섞여 `sort=semantic`, 앱 레시피는 자기 마이그레이션 폴더 하나라 `sort=flyway`.

## 5. Flyway 충돌 방지

### 5.1 버전 이름

- 형식: `V<yyyyMMddHHmmss UTC>__<snake_case>.sql`, 정규식 `^V\d{14}__[a-z0-9]+(_[a-z0-9]+)*\.sql$`.
- 기존 파일 재명명: `notification` `V2026061701` → `V20260617010000__skeleton_notification_inbox`, `job-queue` `V2026091001` → `V20260910010000__skeleton_jobs`. 앱 `V1__init.sql`(주석뿐인 자리표시)은 삭제. 테스트 전용 `V9000__utc_probe` → 타임스탬프로.
- 재명명은 이미 적용된 DB 의 이력과 어긋난다. ovation 은 PG 로 새 DB 에서 시작하므로 영향 없음 — CHANGELOG 에 명시.

### 5.2 outOfOrder = 모든 환경

- 타임스탬프 방식에선 "늦게 합쳐진 브랜치의 이른 시각 파일" 이 정상 상황이다. outOfOrder 를 끄면 그 파일이 운영 배포에서 검증 실패하거나 영영 안 돈다.
- 위험 = 마이그레이션 간 순서 의존. 막는 법: 문서 규칙 "마이그레이션은 서로 독립 — 다른 브랜치의 미적용 마이그레이션에 기대지 않는다" + §5.4 검사.
- (2026-10-01 주인 정정) 스켈레톤 모듈은 Flyway 기본값을 바꾸지 않는다 — outOfOrder 는 사용법 중 하나라 앱의 선택. `apps/api` `application.yml` 이 `out-of-order=true`, `validate-migration-naming=true`, `locations=classpath:db/migration/{vendor}` 를 명시적 override 로 적는다.

### 5.3 로컬 전용 clean — 새 모듈 `migration`(공통, 접두사 `skeleton.migration`, 가드) + `migration-flyway`(Flyway 구현: clean 전략, 이름 검사). 주인 정정: 공통 모듈을 빼서 `storage`/`storage-s3` 처럼 — 나중에 `migration-liquibase`

| 속성 | 기본 | 의미 |
|---|---|---|
| `skeleton.migration.clean-on-validation-error` | `false` | `true` 면 `migrate` 가 검증 실패로 막힐 때 `clean` 후 다시 `migrate` |
| `skeleton.migration.clean-allowed-profiles` | `local` | 위 옵션과 `spring.flyway.clean-disabled=false` 를 허용하는 프로필 |

- 구현: `FlywayMigrationStrategy` 빈 (앱이 자기 전략 빈을 두면 물러남).
- 가드: 활성 프로필이 허용 목록과 하나도 안 겹치는데 `clean-on-validation-error=true` 이거나 `spring.flyway.clean-disabled=false` 면 **기동 실패** (메시지에 어느 속성 · 프로필인지).
- `apps/api` `application-local.yml` 에 `clean-on-validation-error: true` 예시.

### 5.4 생성 · 검사 Gradle 작업 (루트 `build.gradle.kts`)

- `./gradlew newMigration -Pname=add_x [-Pmodule=apps/api] [-Pvendor=postgresql]`: 지금 UTC 시각으로 파일 생성. `-Pvendor` 생략 시 그 모듈에 있는 vendor 폴더 전부에 같은 버전으로 만든다.
- 검사는 `migration-flyway` 의 `RepositoryMigrationsTest`(+ 규칙 클래스 `MigrationFileRules` 와 위반 사례 단위 테스트)로 구현 — `./gradlew build` 에서 돈다. 레포 전체 `src/<sourceSet>/resources/db/migration/` 하위를 훑어
  - 이름 형식 위반 → 실패
  - 같은 vendor 안에서 버전 중복(모듈 · 앱 · 테스트 리소스 통틀어) → 실패
  - 두 vendor 폴더를 가진 모듈에서 버전 · 이름 짝 불일치 → 실패

### 5.5 문서 `docs/schema-management.md`

이름 규칙, `newMigration` 사용법, 두 브랜치가 같은 테이블을 건드릴 때(각자 독립 마이그레이션, 합친 뒤 충돌하면 새 마이그레이션으로 정리), 고쳐도 되는 때(어느 공유 환경에도 적용 안 됐을 때 — 로컬은 clean 옵션으로 재적용) vs 새로 더해야 하는 때.

## 6. 앱 · 인프라

- `apps/api`: `db-postgresql` 조립, `migration-flyway` 조립, `application*.yml` 데이터소스 PG URL · `SPRING_DATASOURCE_*`, `flyway.locations` vendor 경로.
- `TestcontainersConfiguration`: `PostgreSQLContainer("postgres:18")`.
- `docker-compose.yml`: `postgres:18` 기본 서비스, `mysql:8.4` 는 `profiles: [mysql]`.
- `.env.example`: PG URL.
- `schema-sql-example` (Mode B): PG 판으로. `SchemaSqlInitIntegrationTest` 는 PG 에서 2회 실행 멱등 확인 유지.
- `persistence-jpa`: H2 테스트 그대로 (방언 무관).
- `platform` `BaseAuditTimestampsTest` 의 "MySQL DATETIME 마이크로초 절삭" 설명은 PG `timestamptz`(마이크로초)에도 맞으므로 이름 · 설명만 일반화.

## 7. 테스트

| 대상 | PG | MySQL |
|---|---|---|
| `db-postgresql` / `db-mysql` 시간 왕복 (JVM 서울, 원문 리터럴 단정) | ✓ | ✓ |
| 방언 가드 (0개 · 2개 → 실패) | 컨텍스트 러너 | — |
| `job-queue-jdbc` 통합 (SKIP LOCKED, 백오프, DEAD, 회복) | ✓ | ✓ |
| `notification-jdbc` 중복 삽입이 트랜잭션을 깨지 않음 | ✓ | ✓ |
| `persistence-jooq` 생성 + 왕복 | ✓ | — (생성 경로만 유지) |
| `migration` 가드 · `migration-flyway` clean 전략 | ✓ | — |
| `checkMigrations` 위반 사례 | 단위 테스트 | |
| `apps/api` 전체 | ✓ | — |
| `rename-skeleton.sh` 복사본 → `./gradlew build` | ✓ | |

## 8. CHANGELOG `[Unreleased]` — 이식 안내 형식

하위 앱이 위에서 아래로 따라 하면 되게 쓴다: 의존성 교체, 새 모듈 조립, 설정 · 속성 전체 목록, 새 시간 규칙(JdbcClient 시각은 `SqlDialect` 경유), jOOQ 생성 설정 블록, Flyway 버전 · outOfOrder · clean · 가드 설정, 마이그레이션 재명명, Testcontainers 변경, MySQL → PG SQL 차이표(`ON DUPLICATE KEY UPDATE`→`ON CONFLICT … DO UPDATE`, `LAST_INSERT_ID`→`RETURNING`/키 칼럼, `datetime(6)`→`timestamptz`, `engine=`/`charset=` 삭제, 인라인 index → `create index if not exists`, `bigint unsigned`→`bigint`, 식별자 대소문자(PG 는 따옴표 없으면 소문자), UPDATE/DELETE 의 `LIMIT` 없음 → 서브쿼리, `json`→`jsonb`, `tinyint(1)`→`boolean`, `auto_increment`→`generated by default as identity`).

## 9. 범위 밖

- 이미 MySQL 로 운영 중인 데이터의 이관 도구.
- R2DBC.
- jOOQ MySQL 생성 경로의 통합 테스트 (생성 설정만 유지).
