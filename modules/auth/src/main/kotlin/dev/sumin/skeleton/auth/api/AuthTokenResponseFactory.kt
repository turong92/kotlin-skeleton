package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.principal.CurrentPrincipal

class AuthTokenResponseFactory(
    private val jwtTokenService: JwtTokenService,
) {
    fun issue(account: AuthAccount): AuthTokenResponse {
        val principal = account.toCurrentPrincipal()
        val token = jwtTokenService.issue(principal)
        return AuthTokenResponse(
            accessToken = token.accessToken,
            expiresAt = token.expiresAt,
            principal = principal,
        )
    }

    private fun AuthAccount.toCurrentPrincipal(): CurrentPrincipal =
        CurrentPrincipal(
            accountId = accountId,
            username = username,
            email = email,
            roles = roles,
        )
}
