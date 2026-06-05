package dev.sumin.skeleton.auth.account

data class AccountIdentifier(
    val accountId: String? = null,
    val username: String? = null,
    val email: String? = null,
) {
    companion object {
        fun from(accountId: String?, username: String?, email: String?): AccountIdentifier {
            val normalizedAccountId = accountId?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedUsername = username?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedEmail = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

            require(normalizedAccountId != null || normalizedUsername != null || normalizedEmail != null) {
                "accountId, username, or email is required"
            }

            return AccountIdentifier(
                accountId = normalizedAccountId,
                username = normalizedUsername,
                email = normalizedEmail,
            )
        }
    }
}
