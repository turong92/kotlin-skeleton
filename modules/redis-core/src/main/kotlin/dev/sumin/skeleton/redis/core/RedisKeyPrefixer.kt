package dev.sumin.skeleton.redis.core

class RedisKeyPrefixer(
    private val keyPrefix: String,
) {
    fun key(vararg parts: String): String =
        normalize(listOf(keyPrefix) + parts.toList()).joinToString(":")

    private fun normalize(parts: List<String>): List<String> =
        parts.mapNotNull { part ->
            part.trim()
                .trim(':')
                .takeIf { it.isNotBlank() }
        }
}
