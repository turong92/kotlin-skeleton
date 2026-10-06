package dev.sumin.skeleton.persistence.jdbc

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException

/**
 * "시작할 때 DB 에 못 닿는다" 의 종류 — 기다려 볼 만한 것(DB 가 아직 뜨는 중 · 주소 · 망)만 고른다. 드라이버가 달라도(PostgreSQL · MySQL) 예외 사슬 맨 밑의 java.net 예외와
 * SQLState(08 = 연결 · 57P03 = 서버가 시작하는 중)로 읽는다. 잘못된 비밀번호(28…) · 없는 데이터베이스(3D000) 같은 **기다려도 소용없는** 실패는 고르지 않는다.
 */
enum class DatabaseConnectionProblem(val phrase: String) {
    UNKNOWN_HOST("unknown host (the name does not resolve)"),
    REFUSED("connection refused (nothing is accepting connections there)"),
    STARTING_UP("the database server is starting up and not accepting connections yet"),
    TIMED_OUT("connection timed out (no answer)"),
    UNREACHABLE("the connection could not be established"),
    ;

    companion object {
        fun of(failure: Throwable): DatabaseConnectionProblem? {
            val chain = generateSequence(failure) { it.cause?.takeIf { c -> c !== it } }.take(40).toList()
            if (chain.any { it is SQLException && it.sqlState?.startsWith("28") == true }) return null   // 인증 실패는 기다려도 소용없다
            val found = linkedSetOf<DatabaseConnectionProblem>()
            for (t in chain) {
                when {
                    t is UnknownHostException -> found += UNKNOWN_HOST
                    t is ConnectException -> found += REFUSED
                    t is SocketTimeoutException || t is NoRouteToHostException -> found += TIMED_OUT
                    t is SQLException && (t.sqlState == "57P03" || t.message.orEmpty().contains("starting up", ignoreCase = true)) -> found += STARTING_UP
                    t is SQLException && t.sqlState?.startsWith("08") == true -> found += UNREACHABLE
                    t.javaClass.simpleName == "SQLTransientConnectionException" && t.message.orEmpty().contains("request timed out") -> found += TIMED_OUT
                }
            }
            return listOf(UNKNOWN_HOST, REFUSED, STARTING_UP, TIMED_OUT, UNREACHABLE).firstOrNull { it in found }
        }
    }
}

/** JDBC URL 에서 `host:port` 만 — 사용자 · 비밀번호 · 쿼리(`?user=…&password=…`)는 절대 읽지 않는다 */
object DatabaseTarget {
    private val URL = Regex("""^jdbc:([a-z0-9]+)(?::[a-z0-9]+)?://(?:[^@/?]*@)?([^/:?,&;]+)(?::(\d+))?""", RegexOption.IGNORE_CASE)
    private val DEFAULT_PORT = mapOf("postgresql" to 5432, "mysql" to 3306, "mariadb" to 3306, "sqlserver" to 1433)
    private val DRIVER_MESSAGE = Regex("""Connection to ([^\s:]+):(\d+) refused""")

    fun fromUrl(url: String?): String? {
        val m = URL.find(url?.trim().orEmpty()) ?: return null
        val port = m.groupValues[3].ifEmpty { DEFAULT_PORT[m.groupValues[1].lowercase()]?.toString().orEmpty() }
        return m.groupValues[2] + if (port.isNotEmpty()) ":$port" else ""
    }

    /** URL 을 모를 때(환경에 없음) 드라이버 메시지에서 */
    fun fromFailure(failure: Throwable): String? {
        for (t in generateSequence(failure) { it.cause?.takeIf { c -> c !== it } }.take(40)) {
            DRIVER_MESSAGE.find(t.message.orEmpty())?.let { return "${it.groupValues[1]}:${it.groupValues[2]}" }
        }
        return null
    }
}
