package dev.sumin.skeleton.notification.push

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse

class PushSecretsTest {
    @Test
    fun `toString of a device and a registration leaks neither the endpoint nor the subscription keys`() {
        val device = PushDevice(
            "pd_1", "acc_a", "inst_0123456789abcdef", null, PushKinds.WEBPUSH, PushPlatforms.WEB,
            "https://push.example.com/send/SECRET-ENDPOINT", "SECRET-P256DH", "SECRET-AUTH", null, null, Instant.EPOCH, Instant.EPOCH, null,
        )
        val registration = PushRegistration(PushKinds.WEBPUSH, PushPlatforms.WEB, "https://push.example.com/send/SECRET-ENDPOINT", "SECRET-P256DH", "SECRET-AUTH")

        for (text in listOf(device.toString(), registration.toString(), PushDeviceRegistration("acc_a", "inst", null, registration, null, Instant.EPOCH, 20).toString())) {
            assertFalse("SECRET" in text, text)
        }
        val request = dev.sumin.skeleton.notification.push.web.PushDeviceRequest(
            PushKinds.WEBPUSH, "https://push.example.com/send/SECRET-ENDPOINT",
            dev.sumin.skeleton.notification.push.web.PushDeviceRequest.Keys("SECRET-P256DH", "SECRET-AUTH"), "SECRET-TOKEN", PushPlatforms.WEB, "label",
        )
        assertFalse("SECRET" in request.toString(), request.toString())
    }
}
