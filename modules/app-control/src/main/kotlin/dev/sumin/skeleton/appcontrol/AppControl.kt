package dev.sumin.skeleton.appcontrol

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.time.TimeProvider
import java.net.URI
import dev.sumin.skeleton.common.project.ProjectContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * 읽기(짧은 캐시)와 운영자 쓰기. 읽기는 [snapshot] 한 곳 - 값은 [AppControlProperties.cacheTtl] 동안 메모리에 있고, 만료 뒤 첫 읽기가 표 전체를 한 번 읽어 통째로 바꿔 끼운다.
 * 읽지 못하면(DB 장애) 직전 값을 그대로 쓰고 다음 시도는 ttl 뒤 - 값을 한 번도 읽지 못했으면 중립(스위치 없음 · 점검 아님 · 버전 규칙 없음).
 * 쓰기는 값 검증 → 저장 + 운영 기록(같은 트랜잭션) → 이 인스턴스의 캐시를 만료. 다른 인스턴스는 ttl 안에 따라온다.
 */
class AppControl(
    private val store: AppControlStore,
    private val audit: OperatorAuditLog,
    private val time: TimeProvider,
    private val properties: AppControlProperties,
    private val mapper: ObjectMapper,
) {
    private class Cached(val snapshot: AppControlSnapshot, val expiresAt: Instant)

    /** 프로젝트마다 DB 스키마가 따로라 캐시 · 잠금 · 세대를 프로젝트마다 둔다(프로젝트 밖이면 하나) */
    private class Slot {
        @Volatile var cached: Cached? = null
        val lock = ReentrantLock()
        val generation = AtomicLong()
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    private fun slot() = slots.computeIfAbsent(ProjectContext.scopedKey("app-control")) { Slot() }

    fun snapshot(): AppControlSnapshot {
        val slot = slot()
        val current = slot.cached
        if (current != null && time.now() < current.expiresAt) return current.snapshot
        return reload(slot)
    }

    /**
     * 읽는 사람은 프로젝트마다 한 명이다 - 읽는 동안 온 다른 요청은 줄 서지 않고 직전 값(없으면 중립)을 바로 받는다.
     * 만료는 **읽기가 끝난 시각**부터 ttl 이라 느리게 실패한 읽기를 곧바로 다시 하지 않는다. 읽는 도중 이 인스턴스가 값을 바꿨으면(세대가 달라짐) 그 읽기의 결과는 바로 만료로 둔다.
     */
    private fun reload(slot: Slot): AppControlSnapshot {
        val before = slot.cached
        if (!slot.lock.tryLock()) return before?.snapshot ?: AppControlSnapshot.EMPTY
        try {
            val current = slot.cached
            if (current != null && time.now() < current.expiresAt) return current.snapshot   // 방금 다른 스레드가 읽었다
            val generation = slot.generation.get()
            val snapshot = try {
                load()
            } catch (e: Exception) {
                log.warn("app-control: reading the store failed, keeping the last known values: {}", e.toString())
                current?.snapshot ?: AppControlSnapshot.EMPTY
            }
            slot.cached = Cached(snapshot, if (slot.generation.get() != generation) Instant.MIN else time.now().plus(properties.cacheTtl))
            return snapshot
        } finally {
            slot.lock.unlock()
        }
    }

    private fun expire() {
        val slot = slot()
        slot.generation.incrementAndGet()
        slot.cached?.let { slot.cached = Cached(it.snapshot, Instant.MIN) }
    }

    fun setFlag(actor: String, key: String, enabled: Boolean, clientVisible: Boolean = false, description: String? = null): FlagState {
        requireField("key", FLAG_KEY.matches(key), "Pattern", "Flag keys are lowercase letters, digits, '.', '_' and '-', starting with a letter, up to 64 characters")
        requireField("description", (description?.length ?: 0) <= MAX_DESCRIPTION, "Size", "At most $MAX_DESCRIPTION characters")
        val now = time.now()
        val payload = FlagPayload(enabled, clientVisible, description?.trim()?.ifEmpty { null })
        store.transaction {
            store.put(AppControlEntry(FLAG_PREFIX + key, mapper.writeValueAsString(payload), actor, now))
            audit.record(OperatorAction(actor, "app-control.flag.updated", "app-control", key, mapOf("enabled" to enabled.toString(), "clientVisible" to clientVisible.toString())))
        }
        expire()
        return FlagState(key, payload.enabled, payload.clientVisible, payload.description, actor, now)
    }

    fun deleteFlag(actor: String, key: String) {
        val removed = store.transaction {
            store.delete(FLAG_PREFIX + key).also { if (it) audit.record(OperatorAction(actor, "app-control.flag.deleted", "app-control", key)) }
        }
        if (!removed) throw ApplicationException("No flag '$key'", PlatformErrorCode.NOT_FOUND)
        expire()
    }

    fun setMaintenance(actor: String, enabled: Boolean, message: String?, endsAt: Instant?): MaintenanceState {
        val now = time.now()
        requireField("message", (message?.length ?: 0) <= MAX_MESSAGE, "Size", "At most $MAX_MESSAGE characters")
        requireField("endsAt", !enabled || endsAt == null || endsAt.isAfter(now), "Future", "The expected end must be in the future")
        val payload = MaintenancePayload(enabled, message?.trim()?.ifEmpty { null }, endsAt?.toString())
        store.transaction {
            store.put(AppControlEntry(MAINTENANCE, mapper.writeValueAsString(payload), actor, now))
            audit.record(OperatorAction(actor, "app-control.maintenance.updated", "app-control", MAINTENANCE, mapOf("enabled" to enabled.toString())))
        }
        expire()
        return MaintenanceState(enabled, payload.message, endsAt, actor, now)
    }

    /** 통째로 바꾼다(PUT) — 주지 않은 칸은 지워진다. 한 칸은 있어야 한다 */
    fun setPlatform(actor: String, platform: String, minVersion: String?, recommendedVersion: String?, storeUrl: String?): PlatformVersions =
        savePlatform(actor, platform, minVersion, recommendedVersion, storeUrl, merge = false)

    /** 준 칸만 바꾸고 나머지는 저장된 값을 유지한다(PATCH). 합친 결과를 검증한다 */
    fun patchPlatform(actor: String, platform: String, minVersion: String?, recommendedVersion: String?, storeUrl: String?): PlatformVersions =
        savePlatform(actor, platform, minVersion, recommendedVersion, storeUrl, merge = true)

    private fun savePlatform(actor: String, platform: String, minVersion: String?, recommendedVersion: String?, storeUrl: String?, merge: Boolean): PlatformVersions {
        requireField("platform", PLATFORM.matches(platform), "Pattern", "Platform names are lowercase letters, digits and '-' (e.g. android, ios)")
        val now = time.now()
        val saved = store.transaction {
            val kept = if (merge) store.find(PLATFORM_PREFIX + platform)?.let { runCatching { mapper.readValue<PlatformPayload>(it.payload) }.getOrNull() } else null
            val min = (minVersion ?: kept?.minVersion)?.let { parseVersion("minVersion", it) }
            val recommended = (recommendedVersion ?: kept?.recommendedVersion)?.let { parseVersion("recommendedVersion", it) }
            val url = storeUrl ?: kept?.storeUrl
            requireField("minVersion", min != null || recommended != null || url != null, "Required", "Give at least one of minVersion, recommendedVersion, storeUrl")
            requireField("recommendedVersion", min == null || recommended == null || recommended >= min, "Range", "The recommended version cannot be below the minimum")
            requireField("storeUrl", url == null || isHttpsUrl(url), "Pattern", "An https:// store link")
            requireField("storeUrl", min == null || url != null || platform !in MOBILE, "Required", "A mobile platform with a minimum version needs its store link")
            val payload = PlatformPayload(min?.text, recommended?.text, url)
            store.put(AppControlEntry(PLATFORM_PREFIX + platform, mapper.writeValueAsString(payload), actor, now))
            audit.record(
                OperatorAction(
                    actor, "app-control.platform.updated", "app-control", platform,
                    listOfNotNull(min?.let { "minVersion" to it.text }, recommended?.let { "recommendedVersion" to it.text }, "storeUrlSet" to (url != null).toString()).toMap(),
                ),
            )
            PlatformVersions(platform, payload.minVersion, payload.recommendedVersion, payload.storeUrl, actor, now)
        }
        expire()
        return saved
    }

    fun deletePlatform(actor: String, platform: String) {
        val removed = store.transaction {
            store.delete(PLATFORM_PREFIX + platform).also { if (it) audit.record(OperatorAction(actor, "app-control.platform.deleted", "app-control", platform)) }
        }
        if (!removed) throw ApplicationException("No platform '$platform'", PlatformErrorCode.NOT_FOUND)
        expire()
    }

    private fun load(): AppControlSnapshot {
        val flags = LinkedHashMap<String, FlagState>()
        var maintenance = MaintenanceState.OFF
        val platforms = LinkedHashMap<String, PlatformVersions>()
        for (entry in store.all()) {
            try {
                when {
                    entry.name.startsWith(FLAG_PREFIX) -> {
                        val key = entry.name.removePrefix(FLAG_PREFIX)
                        val p = mapper.readValue<FlagPayload>(entry.payload)
                        flags[key] = FlagState(key, p.enabled, p.clientVisible, p.description, entry.updatedBy, entry.updatedAt)
                    }
                    entry.name == MAINTENANCE -> {
                        val p = mapper.readValue<MaintenancePayload>(entry.payload)
                        maintenance = MaintenanceState(p.enabled, p.message, p.endsAt?.let(Instant::parse), entry.updatedBy, entry.updatedAt)
                    }
                    entry.name.startsWith(PLATFORM_PREFIX) -> {
                        val name = entry.name.removePrefix(PLATFORM_PREFIX)
                        val p = mapper.readValue<PlatformPayload>(entry.payload)
                        platforms[name] = PlatformVersions(name, p.minVersion, p.recommendedVersion, p.storeUrl, entry.updatedBy, entry.updatedAt)
                    }
                }
            } catch (e: Exception) {
                log.warn("app-control: skipping the unreadable entry '{}': {}", entry.name, e.toString())
            }
        }
        return AppControlSnapshot(flags, maintenance, platforms)
    }

    private fun parseVersion(field: String, raw: String): AppVersion =
        AppVersion.parse(raw) ?: throw FieldValidationException(field, "Pattern", "Use dotted numbers such as 1.10 or 2.3.1 (up to four parts)")

    private fun isHttpsUrl(raw: String): Boolean =
        raw.length <= MAX_URL && runCatching { URI(raw) }.getOrNull()?.let { it.scheme == "https" && !it.host.isNullOrEmpty() } == true

    private fun requireField(field: String, ok: Boolean, code: String, message: String) {
        if (!ok) throw FieldValidationException(field, code, message)
    }

    private data class FlagPayload(val enabled: Boolean, val clientVisible: Boolean = false, val description: String? = null)

    private data class MaintenancePayload(val enabled: Boolean, val message: String? = null, val endsAt: String? = null)

    private data class PlatformPayload(val minVersion: String? = null, val recommendedVersion: String? = null, val storeUrl: String? = null)

    private companion object {
        val log = LoggerFactory.getLogger(AppControl::class.java)
        val FLAG_KEY = Regex("^[a-z][a-z0-9._-]{0,63}$")
        val PLATFORM = Regex("^[a-z][a-z0-9-]{0,19}$")
        val MOBILE = setOf("android", "ios")
        const val FLAG_PREFIX = "flag:"
        const val PLATFORM_PREFIX = "platform:"
        const val MAINTENANCE = "maintenance"
        const val MAX_DESCRIPTION = 200
        const val MAX_MESSAGE = 500
        const val MAX_URL = 300
    }
}
