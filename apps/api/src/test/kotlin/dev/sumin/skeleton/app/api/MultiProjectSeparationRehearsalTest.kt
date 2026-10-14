package dev.sumin.skeleton.app.api

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/** 분리 리허설 — 프로젝트 a · b 를 함께 띄워 a 의 데이터를 만든 뒤, **a 만 남긴 설정**으로 같은 DB 의 그 스키마에 붙여 혼자 띄워도 그대로 로그인된다 (코드 수정 없이) */
@SpringBootTest(properties = ["skeleton.projects.a.hosts=a.localhost", "skeleton.projects.a.schema=sep_a", "skeleton.projects.a.datasource.username=sep_a", "skeleton.projects.a.datasource.password=sep_a-pw"])
@AutoConfigureMockMvc
@Import(MultiProjectSeparationRehearsalTest.SameDatabase::class)
class MultiProjectSeparationRehearsalTest {
    @TestConfiguration(proxyBeanMethods = false)
    class SameDatabase {
        @Bean fun jdbcConnectionDetails(): JdbcConnectionDetails = db
    }

    companion object {
        val db: JdbcConnectionDetails by lazy { MultiProjectTestAccounts.newDatabase("sep_a", "sep_b") }

        @JvmStatic @BeforeAll
        fun runTogether() {
            // 함께 띄운 서버: a · b 둘 다, a 에 시드 계정
            SpringApplicationBuilder(ApiApplication::class.java, SameDatabase::class.java).web(WebApplicationType.SERVLET)
                .properties(
                    "skeleton.projects.a.hosts=a.localhost", "skeleton.projects.a.schema=sep_a", "skeleton.projects.a.datasource.username=sep_a", "skeleton.projects.a.datasource.password=sep_a-pw",
                    "skeleton.projects.b.hosts=b.localhost", "skeleton.projects.b.schema=sep_b", "skeleton.projects.b.datasource.username=sep_b", "skeleton.projects.b.datasource.password=sep_b-pw",
                    "skeleton.projects.a.config.account.seed.accounts[0].email=alone@example.com",
                    "skeleton.projects.a.config.account.seed.accounts[0].password=password",
                    "skeleton.persistence-jdbc.startup-wait.enabled=false", "skeleton.account.password.bcrypt-strength=4",
                    "spring.main.banner-mode=off", "server.port=0", "management.endpoints.web.exposure.include=health",
                ).run().close()
        }
    }

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `alone with only project a, the same schema still holds the account made while running together`() {
        mvc.post("/api/v1/auth/login") {
            with { it.serverName = "a.localhost"; it }
            contentType = MediaType.APPLICATION_JSON; content = """{"email":"alone@example.com","password":"password"}"""
        }.andExpect { status { isOk() } }
        mvc.post("/api/v1/auth/login") {
            with { it.serverName = "b.localhost"; it }
            contentType = MediaType.APPLICATION_JSON; content = """{"email":"alone@example.com","password":"password"}"""
        }.andExpect { status { isMisdirectedRequest() } }   // b 는 이 서버의 프로젝트가 아니다
    }
}
