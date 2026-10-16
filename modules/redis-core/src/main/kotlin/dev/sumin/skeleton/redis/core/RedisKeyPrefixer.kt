package dev.sumin.skeleton.redis.core

import dev.sumin.skeleton.common.project.ProjectContext

/**
 * 레디스 키 = `<key-prefix>:<조각…>`. 프로젝트 안(요청 · `ProjectContext.runIn`)에서는 `<key-prefix>:project:<프로젝트 id>:<조각…>` — 같은 IP · 계정 id · 락 이름이
 * 프로젝트 사이에서 한도 · 캐시 · 락을 나눠 쓰지 않게. 프로젝트 밖(한 서비스, 서버 전체 일)은 지금까지와 같은 키다. 프로젝트 id 에는 `:` 가 들지 못해 모호하지 않다.
 */
class RedisKeyPrefixer(
    private val keyPrefix: String,
) {
    fun key(vararg parts: String): String {
        val project = ProjectContext.currentOrNull()?.let { listOf("project", it.id) }.orEmpty()
        return normalize(listOf(keyPrefix) + project + parts.toList()).joinToString(":")
    }

    private fun normalize(parts: List<String>): List<String> =
        parts.mapNotNull { part ->
            part.trim()
                .trim(':')
                .takeIf { it.isNotBlank() }
        }
}
