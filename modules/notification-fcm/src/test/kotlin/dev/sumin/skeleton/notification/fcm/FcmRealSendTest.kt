package dev.sumin.skeleton.notification.fcm

import dev.sumin.skeleton.notification.push.PushDevice
import dev.sumin.skeleton.notification.push.PushMessage
import dev.sumin.skeleton.notification.push.PushResult
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 진짜 FCM 으로 한 번 보낸다 — `FCM_TEST_CREDENTIALS`(서비스 계정 JSON 의 base64)와 `FCM_TEST_TOKEN`(기기 토큰) 이 둘 다 있을 때만 돈다. 평소에는 건너뛴다.
 * 키 만드는 법: docs/real-provider-setup.md 의 「푸시 알림 키」.
 */
@EnabledIfEnvironmentVariable(named = "FCM_TEST_CREDENTIALS", matches = ".+")
@EnabledIfEnvironmentVariable(named = "FCM_TEST_TOKEN", matches = ".+")
class FcmRealSendTest {
    @Test
    fun `a real push to a real device token is delivered`() {
        val properties = FcmProperties(enabled = true, credentials = System.getenv("FCM_TEST_CREDENTIALS"))
        val sender = FcmSender(properties, requireNotNull(FcmConfigCheck.credentialsOf(properties)) { FcmConfigCheck.problems(properties).joinToString() })
        val now = Instant.now()
        val device = PushDevice(
            id = "dev", accountId = "acc", installationId = "inst-0123456789abcdef", sessionId = null, kind = "FCM", platform = "android",
            address = System.getenv("FCM_TEST_TOKEN"), p256dh = null, authSecret = null, keyId = null, label = null,
            createdAt = now, lastSeenAt = now, lastSuccessAt = null,
        )
        val message = PushMessage("evt-real", "test", "Skeleton FCM test", "sent by FcmRealSendTest", "/", "fcm-real-test", createdAt = now, expiresAt = now.plus(Duration.ofMinutes(10)))

        assertThat(sender.send(device, message)).isEqualTo(PushResult.Delivered)
    }
}
