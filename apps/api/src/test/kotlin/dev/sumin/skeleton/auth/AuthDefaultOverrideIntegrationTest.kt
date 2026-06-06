package dev.sumin.skeleton.auth

import dev.sumin.skeleton.TestcontainersConfiguration
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.account.InMemoryAuthAccountRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(
    TestcontainersConfiguration::class,
    AuthDefaultOverrideIntegrationTest.CustomAuthRepositoryConfiguration::class,
)
class AuthDefaultOverrideIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `application account repository overrides auth module default repository`() {
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"custom@example.com","password":"custom-password"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.principal.accountId") { value("acc_custom") }
            jsonPath("$.value.principal.email") { value("custom@example.com") }
            jsonPath("$.value.principal.roles[0]") { value("USER") }
            jsonPath("$.meta.traceId") { isNotEmpty() }
            jsonPath("$.meta.spanId") { isNotEmpty() }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class CustomAuthRepositoryConfiguration {
        @Bean
        fun customAuthAccountRepository(passwordEncoder: PasswordEncoder): AuthAccountRepository =
            InMemoryAuthAccountRepository(
                listOf(
                    AuthAccount(
                        accountId = "acc_custom",
                        username = "custom",
                        email = "custom@example.com",
                        passwordHash = requireNotNull(passwordEncoder.encode("custom-password")),
                        roles = setOf("USER"),
                    ),
                ),
            )
    }
}
