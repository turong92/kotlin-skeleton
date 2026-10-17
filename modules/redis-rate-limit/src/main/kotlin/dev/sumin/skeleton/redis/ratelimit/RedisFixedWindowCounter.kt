package dev.sumin.skeleton.redis.ratelimit

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

fun interface RedisFixedWindowCounter {
    fun increment(
        key: String,
        windowMillis: Long,
    ): Long

    /** 세지 않고 지금 값을 읽는다. 지원하지 않으면 null (기본) */
    fun current(key: String): Long? = null
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

    override fun current(key: String): Long? = stringRedisTemplate.opsForValue().get(key)?.toLongOrNull() ?: 0L

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
