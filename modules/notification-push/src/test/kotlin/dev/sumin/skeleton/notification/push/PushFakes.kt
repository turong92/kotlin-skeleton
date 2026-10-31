package dev.sumin.skeleton.notification.push

import java.time.Duration
import java.time.Instant
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
