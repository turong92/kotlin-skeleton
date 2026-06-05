package dev.sumin.skeleton.auth.config

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.account.InMemoryAuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.security.AuthErrorWriter
import dev.sumin.skeleton.auth.security.BreakGlassAuthenticationFilter
import dev.sumin.skeleton.auth.security.DevLoginAuthenticationFilter
import dev.sumin.skeleton.auth.security.JwtAuthenticationFilter
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import tools.jackson.databind.ObjectMapper

@AutoConfiguration
@EnableConfigurationProperties(AuthProperties::class)
class AuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    @ConditionalOnMissingBean(AuthAccountRepository::class)
    fun authAccountRepository(passwordEncoder: PasswordEncoder): AuthAccountRepository =
        InMemoryAuthAccountRepository(
            listOf(
                AuthAccount(
                    accountId = "acc_user",
                    username = "user",
                    email = "user@example.com",
                    passwordHash = requireNotNull(passwordEncoder.encode("password")),
                    roles = setOf("USER"),
                ),
                AuthAccount(
                    accountId = "acc_admin",
                    username = "admin",
                    email = "admin@example.com",
                    passwordHash = requireNotNull(passwordEncoder.encode("password")),
                    roles = setOf("USER", "ADMIN"),
                ),
            ),
        )

    @Bean
    @ConditionalOnMissingBean
    fun jwtTokenService(properties: AuthProperties): JwtTokenService =
        JwtTokenService(properties.jwt)

    @Bean
    @ConditionalOnMissingBean
    fun authTokenResponseFactory(jwtTokenService: JwtTokenService): AuthTokenResponseFactory =
        AuthTokenResponseFactory(jwtTokenService)

    @Bean
    fun authStartupValidation(properties: AuthProperties, environment: Environment): ApplicationRunner =
        ApplicationRunner {
            AuthStartupValidator.validate(properties, environment.activeProfiles.toSet())
        }

    @Bean
    @ConditionalOnMissingBean
    fun authErrorWriter(objectMapper: ObjectMapper): AuthErrorWriter =
        AuthErrorWriter(objectMapper)

    @Bean
    @ConditionalOnMissingBean
    fun jwtAuthenticationFilter(
        jwtTokenService: JwtTokenService,
        authErrorWriter: AuthErrorWriter,
    ): JwtAuthenticationFilter =
        JwtAuthenticationFilter(jwtTokenService, authErrorWriter)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.auth.dev-login", name = ["enabled"], havingValue = "true")
    fun devLoginAuthenticationFilter(
        properties: AuthProperties,
        environment: Environment,
        accountRepository: AuthAccountRepository,
        authErrorWriter: AuthErrorWriter,
    ): DevLoginAuthenticationFilter =
        DevLoginAuthenticationFilter(properties, environment, accountRepository, authErrorWriter)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.auth.break-glass", name = ["enabled"], havingValue = "true")
    fun breakGlassAuthenticationFilter(
        properties: AuthProperties,
        accountRepository: AuthAccountRepository,
        authErrorWriter: AuthErrorWriter,
    ): BreakGlassAuthenticationFilter =
        BreakGlassAuthenticationFilter(properties, accountRepository, authErrorWriter)

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain::class)
    fun securityFilterChain(
        http: HttpSecurity,
        devLoginAuthenticationFilter: ObjectProvider<DevLoginAuthenticationFilter>,
        breakGlassAuthenticationFilter: ObjectProvider<BreakGlassAuthenticationFilter>,
        jwtAuthenticationFilter: JwtAuthenticationFilter,
        authErrorWriter: AuthErrorWriter,
    ): SecurityFilterChain {
        http
            .csrf { csrf -> csrf.disable() }
            .sessionManagement { session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .httpBasic { basic -> basic.disable() }
            .formLogin { form -> form.disable() }
            .logout { logout -> logout.disable() }
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { _, response, _ ->
                    authErrorWriter.writeUnauthorized(response)
                }
                exceptions.accessDeniedHandler { _, response, _ ->
                    authErrorWriter.writeForbidden(response)
                }
            }
            .authorizeHttpRequests { authorize ->
                authorize
                    .requestMatchers(
                        "/health",
                        "/info",
                        "/api/v1/hello",
                        "/api/v1/auth/login",
                        "/api/v1/docs",
                        "/api/v1/docs/**",
                        "/api/v1/docs/ui",
                        "/api/v1/docs/ui/**",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                    ).permitAll()
                    .anyRequest().authenticated()
            }

        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

        breakGlassAuthenticationFilter.ifAvailable { filter ->
            http.addFilterBefore(filter, JwtAuthenticationFilter::class.java)
        }

        devLoginAuthenticationFilter.ifAvailable { filter ->
            http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter::class.java)
        }

        return http.build()
    }
}
