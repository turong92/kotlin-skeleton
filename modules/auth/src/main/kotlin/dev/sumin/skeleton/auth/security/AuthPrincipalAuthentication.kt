package dev.sumin.skeleton.auth.security

import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority

class AuthPrincipalAuthentication(
    private val currentPrincipal: CurrentPrincipal,
) : AbstractAuthenticationToken(currentPrincipal.roles.toAuthorities()) {

    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): CurrentPrincipal = currentPrincipal
}

private fun Set<String>.toAuthorities(): Collection<GrantedAuthority> =
    map { role ->
        SimpleGrantedAuthority(if (role.startsWith("ROLE_")) role else "ROLE_$role")
    }
