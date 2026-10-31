package dev.sumin.skeleton.notification.push.nooptional

import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.push.PushAutoConfiguration
import dev.sumin.skeleton.notification.push.PushDeviceStore
import dev.sumin.skeleton.notification.push.PushNotificationChannel
import dev.sumin.skeleton.notification.push.PushSessionAutoConfiguration
import dev.sumin.skeleton.notification.push.PushSessionIdResolver
import dev.sumin.skeleton.notification.push.PushTasks
import dev.sumin.skeleton.notification.push.PushTypeMuteStore
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** `auth` 가 클래스패스에 없어도 모듈이 뜨고, 세션 연동만 빠진다(세션 id 는 늘 null) */
class PushWithoutAuthTest {
    private inline fun <reified T : Any> stub(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> null } as T

    @Test
    fun `boots without auth, no session listener, session id is null`() {
        assertFalse(runCatching { Class.forName("dev.sumin.skeleton.auth.session.SessionEventListener") }.isSuccess, "auth must be off the classpath")
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java, PushSessionAutoConfiguration::class.java, PushAutoConfiguration::class.java))
            .withPropertyValues("skeleton.notification.push.enabled=true")
            .withBean(PushDeviceStore::class.java, { stub<PushDeviceStore>() })
            .withBean(PushTypeMuteStore::class.java, { stub<PushTypeMuteStore>() })
            .withBean(PushTasks::class.java, { stub<PushTasks>() })
            .run { context ->
                assertNull(context.startupFailure)
                assertEquals(1, context.getBeansOfType(PushNotificationChannel::class.java).size)
                assertNull(context.getBean(PushSessionIdResolver::class.java).sessionIdOf(Any()))
            }
    }
}
