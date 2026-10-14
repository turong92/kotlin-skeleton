package dev.sumin.skeleton.app.api

import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext

/** 프로젝트 모드의 DB 계정 규칙 — 같은 계정 · 계정에 기본 스키마가 없음 · 다른 프로젝트 스키마에 권한이 있음, 각각 기동이 실패하고 프로젝트 id 를 말한다 */
class MultiProjectAccountsTest {
    private fun start(db: JdbcConnectionDetails, vararg properties: String): Throwable? = runCatching {
        SpringApplicationBuilder(ApiApplication::class.java).web(WebApplicationType.SERVLET)
            .initializers(ApplicationContextInitializer<ConfigurableApplicationContext> { (it as GenericApplicationContext).registerBean(JdbcConnectionDetails::class.java, Supplier { db }) })
            .run(*(listOf("server.port=0", "spring.main.banner-mode=off", "skeleton.persistence-jdbc.startup-wait.enabled=false", "skeleton.account.password.bcrypt-strength=4") + properties).map { "--$it" }.toTypedArray()).close()
    }.exceptionOrNull()

    private fun text(t: Throwable?) = generateSequence(t) { it.cause }.joinToString("\n") { it.message.orEmpty() }

    @Test
    fun `two projects on one database account fail the start - a project without its own account counts as the shared one`() {
        val db = MultiProjectTestAccounts.newDatabase("acc1_a", "acc1_b")
        val failure = text(start(db, "skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=acc1_a", "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=acc1_b"))
        assertTrue("same database account" in failure && "'a'" in failure && "'b'" in failure, failure)
    }

    @Test
    fun `an account whose default schema is not the project's fails the start - the pool must not be the only thing that picks the schema`() {
        val db = MultiProjectTestAccounts.newDatabase("acc2_a")
        // b 의 계정은 만들었지만 기본 search_path 를 박지 않았다
        // (계정 이름이 스키마 이름과 같으면 PostgreSQL 의 기본 search_path "$user" 가 우연히 맞는다 — 이름을 다르게 한다)
        MultiProjectTestAccounts.execute(db, "create role \"acc2_b_user\" login password 'acc2_b-pw'; create schema \"acc2_b\" authorization \"acc2_b_user\";")
        val failure = text(start(db, *(MultiProjectTestAccounts.project("a", "a.test", "acc2_a") + listOf(
            "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=acc2_b", "skeleton.projects.b.datasource.username=acc2_b_user", "skeleton.projects.b.datasource.password=acc2_b-pw",
        )).toTypedArray()))
        assertTrue("Project 'b'" in failure && "does not default to schema 'acc2_b'" in failure, failure)
    }

    @Test
    fun `an account that can use another project's schema fails the start`() {
        val db = MultiProjectTestAccounts.newDatabase("acc3_a", "acc3_b")
        MultiProjectTestAccounts.execute(db, "grant usage on schema \"acc3_b\" to \"acc3_a\";")
        val failure = text(start(db, *(MultiProjectTestAccounts.project("a", "a.test", "acc3_a") + MultiProjectTestAccounts.project("b", "b.test", "acc3_b")).toTypedArray()))
        assertTrue("Project 'a'" in failure && "can use schema 'acc3_b'" in failure && "'b'" in failure, failure)
    }

    @Test
    fun `separate accounts with their own default schema start`() {
        val db = MultiProjectTestAccounts.newDatabase("acc4_a", "acc4_b")
        val failure = start(db, *(MultiProjectTestAccounts.project("a", "a.test", "acc4_a") + MultiProjectTestAccounts.project("b", "b.test", "acc4_b")).toTypedArray())
        assertTrue(failure == null, text(failure))
    }
}
