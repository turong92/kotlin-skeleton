package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.notification.advertising.AdvertisingConsent
import dev.sumin.skeleton.notification.advertising.AdvertisingConsentPolicy
import dev.sumin.skeleton.notification.advertising.AdvertisingRecipientZones
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.DeliveryOutcome
import dev.sumin.skeleton.notification.NotificationDeliveryHook
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/** 시험용 인메모리 저장소 — 계약만 흉내 낸다(같은 주소 정리 · 상한은 실제 저장소의 dbTest 가 증명한다) */
class FakePushDeviceStore : PushDeviceStore {
    val devices = CopyOnWriteArrayList<PushDevice>()
    val successes = CopyOnWriteArrayList<String>()

    fun add(
        accountId: String,
        installationId: String = "inst_${devices.size}_0123456789",
        kind: String = PushKinds.WEBPUSH,
        sessionId: String? = null,
        keyId: String? = null,
    ): PushDevice = PushDevice(
        id = "pd_${devices.size}", accountId = accountId, installationId = installationId, sessionId = sessionId, kind = kind,
        platform = PushPlatforms.WEB, address = "https://push.example.com/$installationId", p256dh = "p", authSecret = "a", keyId = keyId,
        label = null, createdAt = Instant.EPOCH, lastSeenAt = Instant.EPOCH, lastSuccessAt = null,
    ).also { devices += it }

    override fun register(request: PushDeviceRegistration): PushDevice {
        devices.removeIf { it.installationId == request.installationId }
        val r = request.registration
        return PushDevice(
            id = "pd_${devices.size}_${request.installationId.take(4)}", accountId = request.accountId, installationId = request.installationId,
            sessionId = request.sessionId, kind = r.kind, platform = r.platform, address = r.address, p256dh = r.p256dh, authSecret = r.authSecret,
            keyId = request.keyId, label = r.label, createdAt = request.now, lastSeenAt = request.now, lastSuccessAt = null,
        ).also { devices += it }
    }

    override fun find(id: String) = devices.firstOrNull { it.id == id }
    override fun listByAccount(accountId: String) = devices.filter { it.accountId == accountId }
    override fun listByAccounts(accountIds: Set<String>) = devices.filter { it.accountId in accountIds }
    override fun findByInstallation(accountId: String, installationId: String) =
        devices.firstOrNull { it.accountId == accountId && it.installationId == installationId }

    override fun deleteByInstallation(accountId: String, installationId: String) =
        devices.removeIf { it.accountId == accountId && it.installationId == installationId }

    override fun delete(id: String) = devices.removeIf { it.id == id }
    override fun deleteBySession(accountId: String, sessionId: String) = removeCount { it.accountId == accountId && it.sessionId == sessionId }
    override fun deleteByAccount(accountId: String) = removeCount { it.accountId == accountId }
    override fun deleteStale(before: Instant) = removeCount { it.lastSeenAt.isBefore(before) }
    override fun markSuccess(id: String, at: Instant) { successes += id }

    private fun removeCount(match: (PushDevice) -> Boolean): Int {
        val doomed = devices.filter(match)
        devices.removeAll(doomed.toSet())
        return doomed.size
    }
}

class FakePushTypeMuteStore : PushTypeMuteStore {
    private val muted = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    override fun mutedTypes(accountId: String) = muted.filter { it.first == accountId }.mapTo(linkedSetOf()) { it.second }
    override fun mutedAmong(type: String, accountIds: Set<String>) = accountIds.filterTo(linkedSetOf()) { (it to type) in muted }
    override fun setMuted(accountId: String, type: String, muted: Boolean) {
        if (muted) this.muted += accountId to type else this.muted -= accountId to type
    }
}

class RecordingPushTasks : PushTasks {
    val jobs = CopyOnWriteArrayList<Pair<PushJob, Instant?>>()
    @Volatile var failure: RuntimeException? = null

    override fun enqueue(job: PushJob, runAt: Instant?) {
        failure?.let { throw it }
        jobs += job to runAt
    }
}

class FakePushSender(
    override val kind: String = PushKinds.WEBPUSH,
    override val publicKey: String? = null,
    private val invalidReason: String? = null,
) : PushSender {
    val sent = CopyOnWriteArrayList<Pair<PushDevice, PushMessage>>()
    @Volatile var result: PushResult = PushResult.Delivered

    override fun validate(registration: PushRegistration) = invalidReason
    override fun send(device: PushDevice, message: PushMessage): PushResult {
        sent += device to message
        return result
    }
}

val DEFAULT_TTL: Duration = Duration.ofHours(24)

class MutableTime(@Volatile var now: Instant) : TimeProvider {
    override fun now(): Instant = now
}

/** 시험용 광고 동의 정책 - 계정마다 답을 정해 두고, [failure] 가 있으면 던진다. [calls] 는 조회한 계정들 */
class FakeAdvertisingPolicy(private val answers: Map<String, AdvertisingConsent> = emptyMap()) : AdvertisingConsentPolicy {
    val calls = CopyOnWriteArrayList<String>()
    @Volatile var failure: RuntimeException? = null
    val overrides = ConcurrentHashMap<String, AdvertisingConsent>()

    override fun consentOf(accountId: String, now: Instant): AdvertisingConsent {
        calls += accountId
        failure?.let { throw it }
        return overrides[accountId] ?: answers[accountId] ?: AdvertisingConsent(send = false, night = false)
    }
}

class FakeRecipientZones(private val zones: Map<String, ZoneId?> = emptyMap(), private val failure: RuntimeException? = null) : AdvertisingRecipientZones {
    override fun zoneOf(accountId: String): ZoneId? {
        failure?.let { throw it }
        return zones[accountId]
    }
}

/** 시험용 첫 클릭 기록 - (이벤트, 받는 사람) 쌍이 처음일 때만 true */
class FakePushOpenStore : PushOpenStore {
    val rows = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    override fun recordFirst(eventId: String, recipientId: String, at: Instant): Boolean = rows.add(eventId to recipientId)
}

/** 시험용 전달 고리 - 불린 것을 모두 적고, [haltedIds] 에 든 이벤트는 멈춘 것으로 답한다 */
class RecordingDeliveryHook : NotificationDeliveryHook {
    val haltedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val queued = CopyOnWriteArrayList<Triple<String, String, Int>>()
    val outcomes = CopyOnWriteArrayList<Triple<String, String, DeliveryOutcome>>()
    val opened = CopyOnWriteArrayList<Pair<String, String>>()

    override fun halted(eventId: String) = eventId in haltedIds
    override fun onQueued(eventId: String, channel: String, count: Int) { queued += Triple(eventId, channel, count) }
    override fun onOutcome(eventId: String, channel: String, outcome: DeliveryOutcome) { outcomes += Triple(eventId, channel, outcome) }
    override fun onOpened(eventId: String, channel: String) { opened += eventId to channel }
}
