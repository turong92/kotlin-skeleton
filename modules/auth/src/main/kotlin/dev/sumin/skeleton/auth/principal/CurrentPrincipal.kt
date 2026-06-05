package dev.sumin.skeleton.auth.principal

data class CurrentPrincipal(
    val accountId: String,
    val username: String? = null,
    val email: String? = null,
    val roles: Set<String> = emptySet(),
) {
    fun hasRole(role: String): Boolean = roles.contains(role)
}
