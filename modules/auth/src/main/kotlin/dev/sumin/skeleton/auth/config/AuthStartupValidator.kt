package dev.sumin.skeleton.auth.config

object AuthStartupValidator {
    private val protectedProfiles = setOf("prod", "staging")

    fun validate(properties: AuthProperties, activeProfiles: Set<String>) {
        val protectedProfileActive = activeProfiles.any { it in protectedProfiles }

        if (properties.devLogin.enabled && protectedProfileActive) {
            throw IllegalStateException("Dev login cannot be enabled in prod or staging")
        }

        if (!properties.breakGlass.enabled) {
            return
        }

        if (properties.breakGlass.secret.isBlank()) {
            throw IllegalStateException("Break-glass secret is required when break-glass login is enabled")
        }

        if (protectedProfileActive && properties.breakGlass.allowedAccountIds.none { it.isNotBlank() }) {
            throw IllegalStateException("Break-glass allowed account ids are required in prod or staging")
        }
    }
}
