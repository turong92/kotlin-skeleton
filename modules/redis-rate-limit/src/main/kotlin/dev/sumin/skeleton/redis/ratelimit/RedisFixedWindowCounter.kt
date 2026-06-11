package dev.sumin.skeleton.redis.ratelimit

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

fun interface RedisFixedWindowCounter {
    fun increment(
        key: String,
        windowMillis: Long,
    ): Long
}

class StringRedisFixedWindowCounter(
    private val stringRedisTemplate: StringRedisTemplate,
) : RedisFixedWindowCounter {
    override fun increment(
        key: String,
        windowMillis: Long,
    ): Long =
        stringRedisTemplate.execute(SCRIPT, listOf(key), windowMillis.coerceAtLeast(1).toString())
            ?: throw IllegalStateException("Redis rate limit script returned no count")

    companion object {
        private val SCRIPT: RedisScript<Long> = RedisScript.of(
            """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """.trimIndent(),
            Long::class.java,
        )
    }
}
