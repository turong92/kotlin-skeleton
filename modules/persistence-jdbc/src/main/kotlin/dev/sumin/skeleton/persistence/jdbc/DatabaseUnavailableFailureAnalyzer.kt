package dev.sumin.skeleton.persistence.jdbc

import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.boot.diagnostics.FailureAnalyzer
import org.springframework.context.EnvironmentAware
import org.springframework.core.Ordered
import org.springframework.core.env.Environment

/**
 * 시작할 때 DB 에 못 닿는 것(아직 초기화 중 · 주소 틀림 · 망)을 읽을 수 있는 말로 바꾼다. 실제 홈서버 배포에서 앱이 PostgreSQL 첫 초기화보다 먼저 떠서
 * `JdbcAggregateOperations` 빈 스택 트레이스만 남기고 죽었다(컨테이너를 두 번 다시 시작하니 떴다) — 원인은 맨 밑의 연결 실패였다.
 * JDBC URL 에서는 **host:port 만** 말한다(사용자 · 비밀번호 · 쿼리는 읽지 않는다). 방언 모듈이 없는 경우는 [SqlDialectFailureAnalyzer] 의 일이다.
 */
class DatabaseUnavailableFailureAnalyzer : FailureAnalyzer, EnvironmentAware, Ordered {
    private var environment: Environment? = null

    override fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 10   // 스프링 기본 분석기(빈 · 순환 의존 …)보다 먼저 — 맨 위 예외가 빈 생성 실패라서

    override fun analyze(failure: Throwable): FailureAnalysis? {
        val chain = generateSequence(failure) { it.cause?.takeIf { c -> c !== it } }.take(40).toList()
        val gaveUp = chain.filterIsInstance<DatabaseNotReachableException>().firstOrNull()
        val problem = gaveUp?.let { DatabaseConnectionProblem.of(it) ?: DatabaseConnectionProblem.UNREACHABLE } ?: DatabaseConnectionProblem.of(failure) ?: return null
        val target = gaveUp?.target?.takeIf { it != "the configured database" }
            ?: DatabaseTarget.fromUrl(environment?.getProperty("spring.datasource.url"))
            ?: DatabaseTarget.fromFailure(failure)
            ?: "the configured database (spring.datasource.url)"
        val description = buildString {
            append("Cannot connect to the database at $target during startup: ${problem.phrase}.")
            if (gaveUp != null) append(" The app waited ${gaveUp.waited.seconds}s (skeleton.persistence-jdbc.startup-wait) and gave up.")
        }
        val action = "Likely causes: (1) the database is still starting — a fresh PostgreSQL / MySQL container accepts connections only after its first-time initialisation finishes, " +
            "so the app was started too early; (2) the JDBC URL is wrong — check SPRING_DATASOURCE_URL (spring.datasource.url): host, port and database name; " +
            "(3) a network problem between the app and the database (not on the same docker network, DNS name not resolvable, firewall). " +
            "To let the app wait for a late database instead of failing, set skeleton.persistence-jdbc.startup-wait.enabled=true " +
            "(env SKELETON_PERSISTENCEJDBC_STARTUPWAIT_ENABLED=true; optional .timeout / .interval) or start the app after the database reports healthy."
        return FailureAnalysis(description, action, gaveUp ?: failure)
    }
}
