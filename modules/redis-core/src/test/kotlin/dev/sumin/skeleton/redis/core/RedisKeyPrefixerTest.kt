package dev.sumin.skeleton.redis.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisKeyPrefixerTest {
    @Test
    fun `builds namespaced redis key`() {
        val prefixer = RedisKeyPrefixer("kotlin-skeleton:local")

        assertThat(prefixer.key("lock", "order", "123")).isEqualTo("kotlin-skeleton:local:lock:order:123")
    }

    @Test
    fun `trims duplicate separators and blank parts`() {
        val prefixer = RedisKeyPrefixer("kotlin-skeleton:local:")

        assertThat(prefixer.key(":rate-limit:", "", " ip ", "127.0.0.1 "))
            .isEqualTo("kotlin-skeleton:local:rate-limit:ip:127.0.0.1")
    }

    @Test
    fun `uses raw key without prefix when prefix is blank`() {
        val prefixer = RedisKeyPrefixer("")

        assertThat(prefixer.key("cache", "sample")).isEqualTo("cache:sample")
    }
}
