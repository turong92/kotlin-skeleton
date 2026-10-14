package dev.sumin.skeleton.persistence.jdbc

import dev.sumin.skeleton.common.project.Project
import java.sql.Connection
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails

/**
 * 프로젝트 모드의 DB 계정 규칙 — 모든 빈(마이그레이션 포함)이 만들어진 뒤 활성 프로젝트마다 확인하고, 어기면 기동이 실패한다 (메시지에 프로젝트 id).
 * 이유: PgBouncer 트랜잭션 모드는 트랜잭션마다 실제 연결을 바꿔 끼워 연결 단위 `SET search_path` 가 따라가지 않고, 계정을 같이 쓰면 연결이 프로젝트끼리 돌려 쓰여
 * 한 프로젝트의 요청이 다른 프로젝트의 스키마에 붙을 수 있다. 계정을 나누고 계정에 기본 스키마를 박으면(`ALTER ROLE … SET search_path`) 풀도 계정별로 나뉘고 붙을 때마다 그 스키마가 걸린다.
 *
 * 1. 두 프로젝트가 같은 DB 계정이면 실패 (프로젝트 `datasource.username` 이 없어 공통 계정으로 떨어지는 것도 같은 계정이다)
 * 2. (PostgreSQL) 프로젝트의 실제 풀 연결의 `current_schema()` 가 자기 스키마가 아니면 실패 — 계정 기본 search_path 누락
 * 3. 그 계정이 다른 활성 프로젝트 스키마에 접근할 수 있으면 실패 (PostgreSQL `has_schema_privilege USAGE`, MySQL 은 그 데이터베이스의 표가 보이는지)
 * MySQL 은 스키마 = 데이터베이스라 2 에 해당하는 것이 없다(풀이 catalog 를 고른다) — 한계로 문서에 있다.
 */
class ProjectAccountsVerifier(
    private val projects: List<Project>,
    private val details: JdbcConnectionDetails,
    private val vendor: String,
    /** 프로젝트의 **실제 풀** 에서 연결 하나 — 풀이 받는 설정(공통 hikari.* 포함)까지 그대로 본다 */
    private val connect: (Project) -> Connection,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        sameAccount()
        // 프로젝트 문맥 안에서 — 풀 연결은 그 프로젝트의 것이고, 문맥 밖에서 쓰면 라우팅 DataSource 가 막는다
        projects.forEach { p -> dev.sumin.skeleton.common.project.ProjectContext.runIn(p) { checkProject(p) } }
    }

    private fun accountOf(p: Project): String = p.datasource.username ?: details.username.orEmpty()

    private fun sameAccount() {
        projects.groupBy { accountOf(it) }.filterValues { it.size > 1 }.forEach { (_, same) ->
            val ids = same.joinToString { "'${it.id}'" }
            error("Projects $ids use the same database account — every project needs its own account (skeleton.projects.<id>.datasource.username/password; a project without one falls back to the shared spring.datasource account, which counts as the same account). See docs/multi-project.md.")
        }
    }

    private fun checkProject(p: Project) {
        val user = accountOf(p)
        val others = projects.filter { it.id != p.id }
        try {
            connect(p).use { c ->
                if (vendor == "postgresql") {
                    // 풀은 schema 를 걸지 않는다(ProjectPools) — 계정에 박힌 기본값이 보인다
                    val current = c.createStatement().use { s -> s.executeQuery("select current_schema()").use { rs -> if (rs.next()) rs.getString(1) else null } }
                    check(current == p.schema) {
                        "Project '${p.id}': the database account '$user' does not default to schema '${p.schema}' (current_schema() is ${current ?: "null"}). " +
                            "Set it on the account — ALTER ROLE \"$user\" SET search_path = \"${p.schema}\" — so PgBouncer pooling cannot land the project on another schema (scripts/project-account.sql)."
                    }
                    others.forEach { o ->
                        val usage = c.prepareStatement("select has_schema_privilege(current_user, ?, 'USAGE')").use { ps ->
                            ps.setString(1, o.schema); ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                        }
                        check(!usage) { "Project '${p.id}': the database account '$user' can use schema '${o.schema}' of project '${o.id}' — revoke it (the account of a project must reach only its own schema)." }
                    }
                } else {
                    others.forEach { o ->
                        val visible = c.prepareStatement("select count(*) from information_schema.tables where table_schema = ?").use { ps ->
                            ps.setString(1, o.schema); ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
                        }
                        check(!visible) { "Project '${p.id}': the database account '$user' can see database '${o.schema}' of project '${o.id}' — grant it only its own database." }
                    }
                }
            }
        } catch (e: java.sql.SQLException) {
            throw IllegalStateException("Project '${p.id}': cannot connect as database account '$user' to check it: ${e.message?.lineSequence()?.firstOrNull()}", e)
        }
    }
}
