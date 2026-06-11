package dev.sumin.skeleton.redis.cache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StableRedisCacheKeyGeneratorTest {
    private val generator = StableRedisCacheKeyGenerator()
    private val method = SampleService::class.java.getDeclaredMethod("find", String::class.java, Map::class.java, IntArray::class.java)

    @Test
    fun `generates deterministic keys with double colon delimiters`() {
        val first = generator.generate(SampleService(), method, "user", mapOf("b" to 2, "a" to 1), intArrayOf(1, 2))
        val second = generator.generate(SampleService(), method, "user", mapOf("a" to 1, "b" to 2), intArrayOf(1, 2))

        assertThat(first).isEqualTo(second)
        assertThat(first.toString()).isEqualTo("dev.sumin.skeleton.redis.cache.SampleService::find::user::{a=1,b=2}::[1,2]")
    }

    @Test
    fun `distinguishes null and empty parameters`() {
        val nullKey = generator.generate(SampleService(), method, null, emptyMap<String, Int>(), intArrayOf())
        val emptyKey = generator.generate(SampleService(), method, "", emptyMap<String, Int>(), intArrayOf())

        assertThat(nullKey).isNotEqualTo(emptyKey)
    }
}

private class SampleService {
    @Suppress("UNUSED_PARAMETER")
    fun find(
        name: String,
        attributes: Map<String, Int>,
        ids: IntArray,
    ): String = name
}
