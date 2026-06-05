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
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class BreakGlassAuthenticationFilter(
    private val properties: AuthProperties,
    private val accountRepository: AuthAccountRepository,
    private val authErrorWriter: AuthErrorWriter,
) : OncePerRequestFilter() {
    private val breakGlassLogger = LoggerFactory.getLogger(BreakGlassAuthenticationFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!request.hasBreakGlassHeader()) {
            filterChain.doFilter(request, response)
            return
        }

        val targetAccountId = request.header(HEADER_ACCOUNT_ID)
        val reason = request.header(HEADER_REASON)
        val secret = request.header(HEADER_SECRET)

        if (!properties.breakGlass.enabled || properties.breakGlass.secret.isBlank()) {
            breakGlassLogger.warn(
                "Break-glass rejected because it is not enabled targetAccountId={} reason={}",
                targetAccountId,
                reason,
            )
            authErrorWriter.writeUnauthorized(response, "Break-glass login is not enabled")
            return
        }

        if (secret == null || secret != properties.breakGlass.secret) {
            breakGlassLogger.warn(
                "Break-glass rejected because the secret was invalid targetAccountId={} reason={}",
                targetAccountId,
                reason,
            )
            authErrorWriter.writeUnauthorized(response, "Invalid break-glass credentials")
            return
        }

        if (reason == null) {
            breakGlassLogger.warn(
                "Break-glass rejected because reason was missing targetAccountId={}",
                targetAccountId,
            )
            authErrorWriter.writeUnauthorized(response, "Break-glass reason is required")
            return
        }

        if (targetAccountId == null) {
            breakGlassLogger.warn(
                "Break-glass rejected because account id was missing reason={}",
                reason,
            )
            authErrorWriter.writeUnauthorized(response, "Break-glass account id is required")
            return
        }

        val allowedAccountIds = properties.breakGlass.allowedAccountIds
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .toSet()
        if (allowedAccountIds.isNotEmpty() && targetAccountId !in allowedAccountIds) {
            breakGlassLogger.warn(
                "Break-glass rejected because account is not allowed targetAccountId={} reason={}",
                targetAccountId,
                reason,
            )
            authErrorWriter.writeForbidden(response, "Break-glass account is not allowed")
            return
        }

        val account = accountRepository.findBy(AccountIdentifier(accountId = targetAccountId))
        if (account == null) {
            breakGlassLogger.warn(
                "Break-glass rejected because account was not found targetAccountId={} reason={}",
                targetAccountId,
                reason,
            )
            authErrorWriter.writeUnauthorized(response, "Invalid break-glass account")
            return
        }

        val principal = account.toCurrentPrincipal()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = AuthPrincipalAuthentication(principal)
        SecurityContextHolder.setContext(context)

        breakGlassLogger.warn(
            "Break-glass authenticated targetAccountId={} username={} email={} reason={}",
            principal.accountId,
            principal.username,
            principal.email,
            reason,
        )

        filterChain.doFilter(request, response)
    }

    private fun HttpServletRequest.hasBreakGlassHeader(): Boolean {
        val names = headerNames ?: return false
        return names.asSequence().any { it.startsWith("X-Break-Glass-", ignoreCase = true) }
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
        const val HEADER_SECRET = "X-Break-Glass-Secret"
        const val HEADER_REASON = "X-Break-Glass-Reason"
        const val HEADER_ACCOUNT_ID = "X-Break-Glass-Account-Id"
    }
}
