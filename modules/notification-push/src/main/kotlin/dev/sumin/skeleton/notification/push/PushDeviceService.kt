package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 기기 등록(=갱신) · 시험 발송. HTTP 와 앱 코드가 같은 길을 쓴다 */
class PushDeviceService(
    private val devices: PushDeviceStore,
    private val tasks: PushTasks,
    private val senders: () -> List<PushSender>,
    private val properties: PushProperties,
    private val time: TimeProvider,
) {
    private val testSentAt = ConcurrentHashMap<String, Instant>()

    fun register(accountId: String, installationId: String, sessionId: String?, registration: PushRegistration): PushDevice {
        if (!INSTALLATION_ID.matches(installationId)) throw invalid("installation_id")
        val sender = senders().firstOrNull { it.kind == registration.kind }
            ?: throw PushException(PushErrorCode.KIND_DISABLED, "Push kind is not enabled: ${registration.kind}")
        if (registration.platform !in PushPlatforms.ALL) throw invalid("platform")
        if (registration.address.isBlank() || registration.address.length > MAX_ADDRESS_LENGTH) throw invalid("address")
        val normalized = registration.copy(label = registration.label?.trim()?.take(MAX_LABEL_LENGTH)?.takeIf { it.isNotEmpty() })
        sender.validate(normalized)?.let { throw invalid(it) }
        return devices.register(
            PushDeviceRegistration(
                accountId = accountId, installationId = installationId, sessionId = sessionId, registration = normalized,
                keyId = sender.publicKey?.let(PushHashes::keyId), now = time.now(), maxDevicesPerAccount = properties.maxDevicesPerAccount,
            ),
        )
    }

    /** 내 기기에 시험 알림 하나(종류 목록 · 끄기와 무관). 계정당 60초에 한 번 */
    fun sendTest(accountId: String, installationId: String) {
        val device = devices.findByInstallation(accountId, installationId)
            ?: throw PushException(PushErrorCode.DEVICE_NOT_FOUND, "Push device not found")
        val now = time.now()
        if (!tryAcquireTest(accountId, now)) throw PushException(PushErrorCode.TEST_TOO_SOON, "A test push was sent less than a minute ago")
        val message = PushMessage(
            eventId = "test_${now.toEpochMilli()}", type = TEST_TYPE, title = "시험 알림", body = "이 기기로 푸시가 잘 도착했어요.",
            createdAt = now, expiresAt = now.plus(properties.ttl),
        )
        tasks.enqueue(PushJob(device.id, accountId, message))
    }

    /** 프로젝트 모드에서 계정 id 가 겹쳐도 섞이지 않게 프로젝트까지 열쇠로 쓴다. 인스턴스마다 따로 센다(여러 대면 최대 대수만큼) */
    private fun tryAcquireTest(accountId: String, now: Instant): Boolean {
        if (testSentAt.size >= 1024) testSentAt.entries.removeIf { Duration.between(it.value, now) >= TEST_INTERVAL }
        var acquired = false
        testSentAt.compute("${ProjectContext.currentOrNull()?.id}/$accountId") { _, last ->
            if (last == null || Duration.between(last, now) >= TEST_INTERVAL) {
                acquired = true
                now
            } else {
                last
            }
        }
        return acquired
    }

    private fun invalid(reason: String) = PushException(PushErrorCode.INVALID_DEVICE, "Invalid push device: $reason", reason)

    companion object {
        const val TEST_TYPE = "push.test"
        private val TEST_INTERVAL: Duration = Duration.ofSeconds(60)
        private val INSTALLATION_ID = Regex("[A-Za-z0-9_-]{16,64}")
        private const val MAX_ADDRESS_LENGTH = 2048
        private const val MAX_LABEL_LENGTH = 100
    }
}
