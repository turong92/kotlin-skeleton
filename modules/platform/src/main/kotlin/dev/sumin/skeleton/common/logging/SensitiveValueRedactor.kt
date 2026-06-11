package dev.sumin.skeleton.common.logging

class SensitiveValueRedactor(
    private val replacement: String = "[REDACTED]",
    sensitiveNames: Set<String> = DEFAULT_SENSITIVE_NAMES,
) {
    private val exactNames = sensitiveNames.map { normalize(it) }.toSet()

    fun redact(name: String, value: String?): String? {
        if (value == null) return null
        return if (isSensitive(name)) replacement else value
    }

    fun redact(values: Map<String, String?>): Map<String, String?> =
        values.mapValues { (name, value) -> redact(name, value) }

    fun isSensitive(name: String): Boolean {
        val normalized = normalize(name)
        return normalized in exactNames ||
            normalized.endsWith("token") ||
            normalized.endsWith("secret") ||
            normalized.endsWith("password")
    }

    private fun normalize(name: String): String =
        name
            .trim()
            .replace("-", "")
            .replace("_", "")
            .lowercase()

    companion object {
        private val DEFAULT_SENSITIVE_NAMES = setOf(
            "authorization",
            "cookie",
            "set-cookie",
            "password",
            "passwd",
            "token",
            "access-token",
            "access_token",
            "accessToken",
            "refresh-token",
            "refresh_token",
            "refreshToken",
            "secret",
            "client-secret",
            "client_secret",
            "clientSecret",
            "api-key",
            "api_key",
            "apiKey",
        )
    }
}
