package dev.sumin.skeleton.auth.security

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class DevLoginAuthenticationFilter(
    private val properties: AuthProperties,
    private val environment: Environment,
    private val accountRepository: AuthAccountRepository,
    private val authErrorWriter: AuthErrorWriter,
) : OncePerRequestFilter() {
    private val devLoginLogger = LoggerFactory.getLogger(DevLoginAuthenticationFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val accountId = request.header(HEADER_ACCOUNT_ID)
        val username = request.header(HEADER_USERNAME)
        val email = request.header(HEADER_EMAIL)

        if (accountId == null && username == null && email == null) {
            filterChain.doFilter(request, response)
            return
        }

        if (!properties.devLogin.enabled || !environment.activeProfiles.any { it == "local" || it == "dev" }) {
            authErrorWriter.writeUnauthorized(response, "Dev login is not enabled")
            return
        }

        val identifier = try {
            AccountIdentifier.from(accountId, username, email)
        } catch (_: IllegalArgumentException) {
            authErrorWriter.writeUnauthorized(response, "Invalid dev login account")
            return
        }

        val account = accountRepository.findBy(identifier)
        if (account == null) {
            authErrorWriter.writeUnauthorized(response, "Invalid dev login account")
            return
        }

        val principal = account.toCurrentPrincipal()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = AuthPrincipalAuthentication(principal)
        SecurityContextHolder.setContext(context)

        devLoginLogger.info(
            "Dev login authenticated accountId={} username={} email={}",
            principal.accountId,
            principal.username,
            principal.email,
        )

        filterChain.doFilter(request, response)
    }

    private fun HttpServletRequest.header(name: String): String? =
        getHeader(name)?.trim()?.takeIf { it.isNotEmpty() }

    private fun AuthAccount.toCurrentPrincipal(): CurrentPrincipal =
        CurrentPrincipal(
            accountId = accountId,
            username = username,
            email = email,
            roles = roles,
        )

    private companion object {
        const val HEADER_ACCOUNT_ID = "X-Dev-Account-Id"
        const val HEADER_USERNAME = "X-Dev-Username"
        const val HEADER_EMAIL = "X-Dev-Email"
    }
}
