# 테스트 — DB 컨테이너 · 시간 · 토큰

## DB 컨테이너: JVM 당 하나, 컨텍스트마다 새 데이터베이스

**하지 않는 것**: 테스트 설정 클래스에 `@Bean @ServiceConnection fun db(): PostgreSQLContainer = …` 를 두는 것.
스프링 컨텍스트가 서로 다르면(프로퍼티 · `@Import` 가 다르면) 컨텍스트마다 컨테이너가 하나씩 뜨고, 컨텍스트 캐시(기본 32 개)가 JVM 이 끝날 때까지 컨텍스트를 붙들기 때문에
컨테이너는 하나도 내려가지 않는다. 찍은 프로젝트에서 `postgres:18` 이 32 개 동시에 떠 스왑이 95% 에 이른 원인이 이것이다
(스켈레톤 `apps/workbench` 는 컨텍스트 27 개 — 측정값은 CHANGELOG). 실행이 중간에 죽으면 Ryuk 이 회수하기 전까지 남기도 한다.

**하는 것** (정본: `apps/api/src/test/…/TestcontainersConfiguration.kt`):

- `object SharedPostgres` (MySQL 이면 `SharedMySql`) — `by lazy` 로 컨테이너를 **JVM 에서 한 번만** 띄운다. `max_connections=500`: 살아 있는 컨텍스트마다 풀이 연결을 잡으므로 기본값(100 · 151)으로는 모자란다. PostgreSQL 은 `fsync=off`(시험 DB 에 내구성은 필요 없고 부하에서 빠르다). 앱의 `src/test/resources/config/application.yml` 이 Hikari `minimum-idle: 1` 로 놀고 있는 연결을 줄인다(`config/` 라서 main 의 application.yml 을 가리지 않고 합쳐진다 — `src/test/resources/application.yml` 로 두면 가려진다).
- 컨텍스트마다 `create database "ctx_<n>_<uuid>"` 로 **새 데이터베이스**를 만들고 `JdbcConnectionDetails` 빈으로 내보낸다 — 앱 코드는 그대로이고, 컨텍스트마다 Flyway 가 빈 데이터베이스에 처음부터 마이그레이션한다(격리는 컨테이너가 아니라 데이터베이스가 한다).
- 그래서 컨테이너 수는 컨텍스트 수와 상관없이 **Gradle 테스트 포크(JVM) 당 DB 종류별 1 개**다. 이 레포는 포크 하나(`maxParallelForks` 기본 1)라 PostgreSQL 1 + MySQL 1(`mysqlTest` 는 별도 태스크). 포크를 늘리면 포크 수만큼.
- `postgresTest` · `mysqlTest`(`src/dbTest` 를 공유하는 두 묶음)의 `DbTestcontainers`(`job-queue-jdbc` · `alert-jdbc` · `legal-jdbc`)와 `persistence-jooq` 도 같은 모양이다. `DbTestDatabase` 를 쓰는 모듈(account-jdbc · auth-session-jdbc · board-jdbc · notification-jdbc)은 처음부터 static 컨테이너 하나 + 데이터베이스 하나다.
- IDE 에서 `TestApiApplication.main` 으로 컨테이너 DB 와 함께 앱을 띄우는 경로도 같은 설정(`.with(TestcontainersConfiguration::class)`)을 쓴다.

**가드**: `TestContainerRulesTest`(`modules/platform`)가 모든 테스트 소스 세트에서 `@ServiceConnection` 과 컨테이너를 돌려주는 `@Bean` 을 찾아 실패시킨다. 이 레포를 본뜬 프로젝트가 같은 가드를 갖는다(`modules/platform` 은 늘 따라온다).

### 컨텍스트 수를 줄이는 법

컨테이너와 달리 컨텍스트는 여전히 비싸다(Flyway + 빈 + 풀). 시험이 다른 시험과 **무관한 프로퍼티** 하나 때문에 컨텍스트를 따로 만들지 않게 한다 —
`@SpringBootTest(properties = …)`, `@Import`, `@MockitoBean`, `@AutoConfigureMockMvc` 의 유무가 모두 캐시 키다. 같은 설정이면 같은 컨텍스트를 재사용한다.
`spring.test.context.cache.maxSize` 로 캐시 상한을 줄이면 컨텍스트(힙)는 줄지만 밀려난 컨텍스트를 다시 만드는 비용이 늘고, DB 컨테이너 수에는 영향이 없다 — 힙이 모자랄 때만 쓴다.

## 시간에 기대지 않는 시험

- 시간 초과를 보는 시험은 서버가 응답을 **잡아 둔다**(`CountDownLatch`) — 고정 `sleep` 은 부하에서 클라이언트 시간 초과와 경주가 된다. 예외 *종류*를 단언하고 벽시계 시간은 단언하지 않는다.
- "시간 안에 끝난다"는 상한은 넉넉하게(수십 초). 호스트 load 60~90 에서 `localhost` 연결 · 첫 클래스 로딩만으로 2 초가 지난다. 참고: `ExternalHttpClientTest`.
- 시각은 `TimeProvider` 를 주입해 고정한다.

## 변조 시험은 실제 바이트를 뒤집는다

base64url 의 **마지막 글자만** 바꾸면 버려지는 하위 비트만 건드려 디코드된 바이트가 그대로일 수 있다(길이에 따라 5~25%) — 변조 시험이 가끔 "통과"한다.
서명 영역 · 페이로드 영역의 바이트를 디코드해 뒤집고 다시 인코드한다(`OpaqueUrlTokenCodecTest`). `OpaqueUrlTokenCodec` · `AesGcmTextEncryptor` 는 정규형(패딩 없음, 버려지는 비트 0)이 아닌 base64url 을 거부한다 — 한 값에 문자열이 하나여야 한다.
