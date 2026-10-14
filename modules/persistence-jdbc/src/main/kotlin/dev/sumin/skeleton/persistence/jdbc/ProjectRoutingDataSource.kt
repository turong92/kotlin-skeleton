package dev.sumin.skeleton.persistence.jdbc

import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectContext
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource

/**
 * 지금 프로젝트의 연결 풀로 가는 DataSource. **프로젝트 문맥 없이 연결을 꺼내면 예외**다 — 조용히 기본 풀로 가지 않는다.
 * 트랜잭션이 열린 뒤 프로젝트가 바뀌면 열린 연결은 첫 프로젝트의 것이므로, 그 연결로 문장을 만들 때 예외를 낸다.
 * 풀은 [poolFor] 가 프로젝트마다 하나 만든다 (연결은 처음 쓸 때 연다 — 안 쓰는 프로젝트는 연결 0개).
 * Boot 의 `/health` 는 라우팅 DataSource 의 대상 풀을 하나씩 본다.
 */
class ProjectRoutingDataSource(projects: List<Project>, poolFor: (Project) -> DataSource) : AbstractRoutingDataSource() {
    init {
        setTargetDataSources(projects.associate { it.id to poolFor(it) })
        setLenientFallback(false)
    }

    override fun determineCurrentLookupKey(): Any = ProjectContext.current().id

    override fun getConnection(): Connection = bound(super.getConnection())

    override fun getConnection(username: String, password: String): Connection = bound(super.getConnection(username, password))

    private fun bound(connection: Connection): Connection {
        val owner = ProjectContext.current().id
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name in STATEMENT_FACTORIES) {
                val now = ProjectContext.currentOrNull()?.id
                check(now == owner) { "This connection belongs to project '$owner', but the code is running in project '${now ?: "(none)"}' — a project cannot change inside a transaction." }
            }
            try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Connection
    }

    private companion object {
        val STATEMENT_FACTORIES = setOf("createStatement", "prepareStatement", "prepareCall", "nativeSQL")
    }
}
