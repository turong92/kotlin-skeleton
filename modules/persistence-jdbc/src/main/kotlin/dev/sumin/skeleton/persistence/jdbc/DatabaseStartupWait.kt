package dev.sumin.skeleton.persistence.jdbc

import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment

/**
 * 첫 연결을 정해진 시간 동안 다시 시도하는 **선택** 기능 — 끈 채가 모듈 기본값이다(모듈은 사용법을 고르지 않는다). 켜는 것은 앱의 선택: 앱 `application.yml` 에
 * `skeleton.persistence-jdbc.startup-wait.enabled: true`. 컨테이너 배포에서 앱이 DB(막 만든 PostgreSQL 은 첫 초기화 동안 TCP 를 열지 않는다)보다 먼저 뜨는 경우를 위한 것이다.
 */
@ConfigurationProperties("skeleton.persistence-jdbc.startup-wait")
data class JdbcStartupWaitProperties(
    /** 켜면 시작할 때 `spring.datasource.url` 로 연결이 될 때까지 다시 시도한다 (기본 꺼짐 — 실패는 곧바로 읽을 수 있는 메시지로 나온다) */
    val enabled: Boolean = false,
    /** 이 시간 안에 안 되면 [DatabaseNotReachableException] 으로 기동 실패 */
    val timeout: Duration = Duration.ofSeconds(60),
    /** 시도 사이 간격 */
    val interval: Duration = Duration.ofSeconds(1),
)

/** 기다렸는데도 DB 에 닿지 못했다 — [DatabaseUnavailableFailureAnalyzer] 가 읽을 수 있는 말로 바꾼다. 비밀번호는 담지 않는다 (target 은 host:port) */
class DatabaseNotReachableException(val target: String, val waited: Duration, cause: Throwable) :
    RuntimeException("Gave up after ${waited.seconds}s waiting for the database at $target: ${cause.message?.lineSequence()?.firstOrNull().orEmpty()}", cause)

object DatabaseStartupWait {
    /** [attempt] 를 첫 시도 즉시, 이어서 [interval] 간격으로 반복한다. 기다려 볼 만한 실패([DatabaseConnectionProblem])만 다시 시도하고 나머지(잘못된 비밀번호)는 그 자리에서 던진다. */
    fun await(
        target: String,
        timeout: Duration,
        interval: Duration,
        sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
        nanoTime: () -> Long = System::nanoTime,
        report: (String) -> Unit = {},
        attempt: () -> Unit,
    ) {
        val start = nanoTime()
        var tries = 0
        while (true) {
            tries++
            try {
                attempt()
                if (tries > 1) report("[startup-wait] database $target is reachable (attempt $tries)")
                return
            } catch (e: SQLException) {
                val problem = DatabaseConnectionProblem.of(e) ?: throw e
                val elapsed = Duration.ofNanos(nanoTime() - start)
                if (elapsed + interval > timeout) throw DatabaseNotReachableException(target, timeout, e)
                report("[startup-wait] waiting for database $target — ${problem.phrase} (attempt $tries, ${elapsed.seconds}s of ${timeout.seconds}s)")
                sleep(interval)
            }
        }
    }
}

/**
 * `skeleton.persistence-jdbc.startup-wait.enabled=true` 이고 `spring.datasource.url` 이 있으면, 컨텍스트가 시작하기 **전에** 원시 JDBC 로 첫 연결이 될 때까지 기다린다 —
 * Flyway · 풀 · 저장소 빈의 생성 순서와 상관없고, 실패해도 어중간한 빈 스택 트레이스가 아니라 [DatabaseNotReachableException] 하나다.
 * (`JdbcConnectionDetails` 빈으로 연결을 정하는 앱 · 시험은 URL 이 환경에 없으므로 건너뛴다.) 진행 상황은 로깅 시스템이 뜨기 전이라 표준 출력으로 바로 쓴다.
 */
class DatabaseStartupWaitPostProcessor(
    private val report: (String) -> Unit = { println(it) },
    private val connector: (url: String, username: String?, password: String?) -> Unit = ::connectOnce,
) : EnvironmentPostProcessor, Ordered {
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE - 10   // 설정 파일(config data)을 다 읽은 뒤

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val props = Binder.get(environment).bind("skeleton.persistence-jdbc.startup-wait", JdbcStartupWaitProperties::class.java).orElseGet { JdbcStartupWaitProperties() }
        if (!props.enabled) return
        val url = environment.getProperty("spring.datasource.url")?.takeIf { it.isNotBlank() } ?: return
        val target = DatabaseTarget.fromUrl(url) ?: "the configured database"
        DatabaseStartupWait.await(target, props.timeout, props.interval, report = report) {
            connector(url, environment.getProperty("spring.datasource.username"), environment.getProperty("spring.datasource.password"))
        }
    }

    companion object {
        /** 연결 하나를 열고 바로 닫는다. 시도 하나가 오래 매달리지 않게 로그인 제한(5초)을 건다 */
        fun connectOnce(url: String, username: String?, password: String?) {
            val previous = DriverManager.getLoginTimeout()
            if (previous == 0) DriverManager.setLoginTimeout(5)
            try {
                val connection = if (username == null) DriverManager.getConnection(url) else DriverManager.getConnection(url, username, password.orEmpty())
                connection.close()
            } finally {
                DriverManager.setLoginTimeout(previous)
            }
        }
    }
}
