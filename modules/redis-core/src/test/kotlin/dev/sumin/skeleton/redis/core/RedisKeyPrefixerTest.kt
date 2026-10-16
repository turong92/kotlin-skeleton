package dev.sumin.skeleton.redis.core

import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectDatasource
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

    private fun project(id: String) = Project(id, listOf("$id.test"), "p$id", ProjectDatasource(), emptyMap())

    @Test
    fun `inside a project every key carries the project, so ids and addresses of two projects never meet`() {
        val prefixer = RedisKeyPrefixer("app")

        val a = ProjectContext.runIn(project("a")) { prefixer.key("rate-limit", "ip", "1.2.3.4") }
        val b = ProjectContext.runIn(project("b")) { prefixer.key("rate-limit", "ip", "1.2.3.4") }

        assertThat(a).isEqualTo("app:project:a:rate-limit:ip:1.2.3.4")
        assertThat(b).isEqualTo("app:project:b:rate-limit:ip:1.2.3.4")
    }

    @Test
    fun `outside a project the key is the one it always was - server-wide`() {
        assertThat(RedisKeyPrefixer("app").key("lock", "leader")).isEqualTo("app:lock:leader")
    }
}
