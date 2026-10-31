package dev.sumin.skeleton.notification.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class PushPropertiesTest {
    @Test
    fun `stale-after counts days when written without a unit and anything under a day is refused`() {
        fun bind(value: String) = Binder(MapConfigurationPropertySource(mapOf("skeleton.notification.push.stale-cleanup.stale-after" to value, "skeleton.notification.push.stale-cleanup.enabled" to "true")))
            .bind("skeleton.notification.push", PushProperties::class.java)

        assertEquals(java.time.Duration.ofDays(60), bind("60").get().staleCleanup.staleAfter)   // 60 은 60일이다(60밀리초가 아니다)
        assertFailsWith<Exception> { bind("60s") }
    }
}
