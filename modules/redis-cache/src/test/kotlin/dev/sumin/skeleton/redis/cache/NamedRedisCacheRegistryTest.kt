package dev.sumin.skeleton.redis.cache

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NamedRedisCacheRegistryTest {
    @Test
    fun `uses default ttl for named cache without explicit ttl`() {
        val registry = NamedRedisCacheRegistry.from(
            RedisCacheProperties(
                defaultTtl = Duration.ofSeconds(15),
                caches = mapOf("users" to RedisCacheProperties.CacheSpec()),
            ),
        )

        assertThat(registry.cacheTtl("users")).isEqualTo(Duration.ofSeconds(15))
    }
}
