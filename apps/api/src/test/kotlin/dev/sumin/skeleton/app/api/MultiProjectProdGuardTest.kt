package dev.sumin.skeleton.app.api

import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

/** 운영 환경(`skeleton.env=prod`)에서 프로젝트 config 의 시드 계정 · 로그 링크 · 빠진 링크 주소는 기동을 막는다 — 가드가 공통 값만 보던 구멍 */
class MultiProjectProdGuardTest {
    @Test
    fun `prod refuses seeds, log-links and a missing link base url that live in a project config`() {
        val failure = runCatching {
            val db = MultiProjectTestAccounts.newDatabase("guard_a", "guard_b")
            SpringApplicationBuilder(ApiApplication::class.java).initializers(org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> { (it as org.springframework.context.support.GenericApplicationContext).registerBean(org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails::class.java, java.util.function.Supplier { db }) }).web(WebApplicationType.SERVLET).run(*listOf(
                "skeleton.env=prod", "server.port=0", "skeleton.auth.jwt.secret=prod-like-secret-prod-like-secret-32-bytes", "spring.main.banner-mode=off",
                "skeleton.persistence-jdbc.startup-wait.enabled=false", "skeleton.account.password.bcrypt-strength=4",
                "skeleton.projects.a.hosts=a.example.com", "skeleton.projects.a.schema=guard_a", "skeleton.projects.a.datasource.username=guard_a", "skeleton.projects.a.datasource.password=guard_a-pw",
                "skeleton.projects.a.config.account.mail.link-base-url=https://a.example.com",
                "skeleton.projects.a.config.account.seed.accounts[0].email=known@example.com",
                "skeleton.projects.a.config.account.seed.accounts[0].password=password",
                "skeleton.projects.a.config.account.mail.log-links=ON",
                "skeleton.projects.b.hosts=b.example.com", "skeleton.projects.b.schema=guard_b", "skeleton.projects.b.datasource.username=guard_b", "skeleton.projects.b.datasource.password=guard_b-pw",
                "skeleton.account.mail.link-base-url=https://common-is-not-enough.example.com",
              ).map { "--$it" }.toTypedArray()).close()
        }.exceptionOrNull()
        val text = generateSequence(failure) { it.cause }.joinToString("\n") { it.message.orEmpty() }
        assertTrue("project 'a': account.seed.accounts" in text, text)
        assertTrue("project 'a': account.mail.log-links=ON" in text, text)
        assertTrue("project 'b': skeleton.projects.b.config.account.mail.link-base-url is empty" in text, text)
    }
}
