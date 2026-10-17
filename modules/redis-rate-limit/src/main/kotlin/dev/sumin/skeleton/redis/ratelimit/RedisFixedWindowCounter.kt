package dev.sumin.skeleton.redis.ratelimit

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

fun interface RedisFixedWindowCounter {
    fun increment(
        key: String,
        windowMillis: Long,
    ): Long

    /** 센 한 번을 되돌린다(0 아래로는 내려가지 않는다). 지원하지 않으면 아무것도 하지 않는다 (기본) */
    fun decrement(key: String) {}
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

    override fun decrement(key: String) {
        stringRedisTemplate.execute(DECREMENT, listOf(key))
    }

    companion object {
        private val DECREMENT: RedisScript<Long> = RedisScript.of(
            """
            local current = tonumber(redis.call('GET', KEYS[1]))
            if current and current > 0 then
              return redis.call('DECR', KEYS[1])
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )

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
