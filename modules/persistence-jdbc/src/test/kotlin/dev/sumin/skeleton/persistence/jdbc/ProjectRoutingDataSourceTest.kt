package dev.sumin.skeleton.persistence.jdbc

import dev.sumin.skeleton.common.project.NoProjectContextException
import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectDatasource
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.support.TransactionTemplate

class ProjectRoutingDataSourceTest {
    private fun project(id: String) = Project(id, listOf("$id.test"), id, ProjectDatasource(), emptyMap())
    private val a = project("a")
    private val b = project("b")

    private fun h2(): DataSource = EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build()

    private val routing = ProjectRoutingDataSource(listOf(a, b)) { h2() }.also { it.afterPropertiesSet() }
    private val jdbc = JdbcTemplate(routing)

    @Test
    fun `using the database without a project fails instead of falling to some default`() {
        assertThatThrownBy { jdbc.queryForObject("select 1", Int::class.java) }.hasRootCauseInstanceOf(NoProjectContextException::class.java)
    }

    @Test
    fun `each project reads and writes only its own database`() {
        ProjectContext.runIn(a) { jdbc.execute("create table t (v varchar(10))"); jdbc.update("insert into t values ('of-a')") }
        ProjectContext.runIn(b) { jdbc.execute("create table t (v varchar(10))") }
        assertThat(ProjectContext.runIn(a) { jdbc.queryForList("select v from t", String::class.java) }).containsExactly("of-a")
        assertThat(ProjectContext.runIn(b) { jdbc.queryForList("select v from t", String::class.java) }).isEmpty()
    }

    @Test
    fun `switching project inside a transaction fails - the open transaction belongs to the first project`() {
        val tx = TransactionTemplate(DataSourceTransactionManager(routing))
        ProjectContext.runIn(a) { jdbc.execute("create table u (v int)") }
        ProjectContext.runIn(b) { jdbc.execute("create table u (v int)") }
        assertThatThrownBy {
            ProjectContext.runIn(a) {
                tx.executeWithoutResult {
                    jdbc.update("insert into u values (1)")
                    ProjectContext.runIn(b) { jdbc.update("insert into u values (2)") }
                }
            }
        }.hasMessageContaining("project")
        assertThat(ProjectContext.runIn(a) { jdbc.queryForObject("select count(*) from u", Int::class.java) }).isZero()
        // 같은 프로젝트 안의 트랜잭션은 그대로 된다
        ProjectContext.runIn(a) { tx.executeWithoutResult { jdbc.update("insert into u values (3)") } }
        assertThat(ProjectContext.runIn(a) { jdbc.queryForObject("select count(*) from u", Int::class.java) }).isEqualTo(1)
    }

    @Test
    fun `a project the routing does not know fails`() {
        assertThatThrownBy { ProjectContext.runIn(project("zzz")) { jdbc.queryForObject("select 1", Int::class.java) } }.isInstanceOf(Exception::class.java)
    }
}
