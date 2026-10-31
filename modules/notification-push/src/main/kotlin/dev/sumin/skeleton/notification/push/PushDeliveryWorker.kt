package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.common.time.TimeProvider
import org.slf4j.LoggerFactory

/**
 * 작업 `notification.push` 하나(= 기기 하나에 푸시 하나)를 처리한다. 큐 어댑터(`notification-push-jdbc`)가 부른다.
 * 보내기 직전에 다시 확인한다: 기기 줄이 없다 · **주인이 바뀌었다**(`account_id ≠ recipientId`) · 만료됐다 · **그 종류를 푸시만 끈 사람이 됐다**(큐에서 기다리는 동안) → 끝.
 * **광고 작업**은 여기서 동의를 다시 판정한다([AdvertisingGate]): 철회 · 만료 · 조회 실패면 끝(대기 중 철회가 광고를 내보내면 사고다), 아직 야간이고 야간 동의가 없으면 같은 규칙으로 새 `runAt` 작업을 넣고 끝(앞당겨 집힌 경우의 안전망).
 * 광고 종류의 작업은 `advertising` 표시가 없어도 광고로 본다. 광고 설정이 없는 앱이 광고 작업을 집으면 보내지 않고 끝낸다.
 * 판정: Delivered → `last_success_at` · Gone → 그 줄만 지운다 · Retry → [PushRetryException] · Failed → [PushPermanentFailure](줄은 남긴다).
 */
class PushDeliveryWorker(
    private val devices: PushDeviceStore,
    private val mutes: PushTypeMuteStore,
    private val senders: () -> List<PushSender>,
    private val time: TimeProvider,
    private val gate: AdvertisingGate = AdvertisingGate(PushProperties(), null, null, time),
    private val tasks: PushTasks? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(job: PushJob) {
        val device = devices.find(job.deviceId) ?: return
        if (device.accountId != job.recipientId) return
        val now = time.now()
        val expiresAt = job.message.expiresAt
        if (expiresAt != null && !now.isBefore(expiresAt)) return
        if (mutes.mutedAmong(job.message.type, setOf(job.recipientId)).isNotEmpty()) return
        var message = job.message
        if (job.advertising || gate.isAdvertising(job.message.type)) {
            when (val decision = gate.decide(job.recipientId)) {
                AdvertisingDecision.Skip -> return
                AdvertisingDecision.SendNow -> message = gate.brand(message)   // 대기 중 광고로 바뀐 종류도 「(광고)」 표기를 달고 나간다(이미 있으면 그대로)
                is AdvertisingDecision.Hold -> {
                    tasks?.enqueue(job.copy(advertising = true), decision.runAt)
                    return
                }
            }
        }
        val sender = senders().firstOrNull { it.kind == device.kind }
        if (sender == null) {
            log.warn("push job dropped: no sender for kind={} device={}", device.kind, device.id)
            return
        }
        when (val result = sender.send(device, message)) {
            PushResult.Delivered -> devices.markSuccess(device.id, now)
            PushResult.Gone -> devices.delete(device.id)
            is PushResult.Retry -> throw PushRetryException(result.after)
            is PushResult.Failed -> throw PushPermanentFailure("push to ${device.kind} device ${device.id} failed: ${result.reason}")
        }
    }
}
