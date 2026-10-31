package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.common.time.TimeProvider
import org.slf4j.LoggerFactory

/**
 * 작업 `notification.push` 하나(= 기기 하나에 푸시 하나)를 처리한다. 큐 어댑터(`notification-push-jdbc`)가 부른다.
 * 보내기 직전에 다시 확인한다: 기기 줄이 없다 · **주인이 바뀌었다**(`account_id ≠ recipientId`) · 만료됐다 · **그 종류를 푸시만 끈 사람이 됐다**(큐에서 기다리는 동안) → 끝.
 * 판정: Delivered → `last_success_at` · Gone → 그 줄만 지운다 · Retry → [PushRetryException] · Failed → [PushPermanentFailure](줄은 남긴다).
 */
class PushDeliveryWorker(
    private val devices: PushDeviceStore,
    private val mutes: PushTypeMuteStore,
    private val senders: () -> List<PushSender>,
    private val time: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(job: PushJob) {
        val device = devices.find(job.deviceId) ?: return
        if (device.accountId != job.recipientId) return
        val now = time.now()
        val expiresAt = job.message.expiresAt
        if (expiresAt != null && !now.isBefore(expiresAt)) return
        if (mutes.mutedAmong(job.message.type, setOf(job.recipientId)).isNotEmpty()) return
        val sender = senders().firstOrNull { it.kind == device.kind }
        if (sender == null) {
            log.warn("push job dropped: no sender for kind={} device={}", device.kind, device.id)
            return
        }
        when (val result = sender.send(device, job.message)) {
            PushResult.Delivered -> devices.markSuccess(device.id, now)
            PushResult.Gone -> devices.delete(device.id)
            is PushResult.Retry -> throw PushRetryException(result.after)
            is PushResult.Failed -> throw PushPermanentFailure("push to ${device.kind} device ${device.id} failed: ${result.reason}")
        }
    }
}
