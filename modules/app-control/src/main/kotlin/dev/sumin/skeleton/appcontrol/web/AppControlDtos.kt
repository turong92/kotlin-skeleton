package dev.sumin.skeleton.appcontrol.web

import dev.sumin.skeleton.appcontrol.AppControlSnapshot
import java.time.Instant

// ---- 운영자 경로 ----

data class FlagBody(val enabled: Boolean? = null, val clientVisible: Boolean? = null, val description: String? = null)

data class MaintenanceBody(val enabled: Boolean? = null, val message: String? = null, val endsAt: Instant? = null)

data class PlatformBody(val minVersion: String? = null, val recommendedVersion: String? = null, val storeUrl: String? = null)

data class FlagResponse(val key: String, val enabled: Boolean, val clientVisible: Boolean, val description: String?, val updatedBy: String, val updatedAt: Instant)

data class MaintenanceResponse(val enabled: Boolean, val message: String?, val endsAt: Instant?, val updatedBy: String?, val updatedAt: Instant?)

data class PlatformResponse(val platform: String, val minVersion: String?, val recommendedVersion: String?, val storeUrl: String?, val updatedBy: String, val updatedAt: Instant)

/** 운영자 화면이 한 번에 읽는 전체 상태 — 클라이언트에 보이지 않는 스위치도 들어 있다 */
data class AppControlStateResponse(val flags: List<FlagResponse>, val maintenance: MaintenanceResponse, val platforms: List<PlatformResponse>)

internal fun AppControlSnapshot.toStateResponse() = AppControlStateResponse(
    flags = flags.values.sortedBy { it.key }.map { FlagResponse(it.key, it.enabled, it.clientVisible, it.description, it.updatedBy, it.updatedAt) },
    maintenance = maintenance.let { MaintenanceResponse(it.enabled, it.message, it.endsAt, it.updatedBy, it.updatedAt) },
    platforms = platforms.values.sortedBy { it.platform }.map { PlatformResponse(it.platform, it.minVersion, it.recommendedVersion, it.storeUrl, it.updatedBy, it.updatedAt) },
)

// ---- 클라이언트가 읽는 설정 ----

data class AppConfigMaintenance(val enabled: Boolean, val message: String?, val endsAt: Instant?)

data class AppConfigPlatform(val minVersion: String?, val recommendedVersion: String?, val storeUrl: String?)

/**
 * `GET /api/v1/app/config` — 익명. 점검 · 클라이언트에 보이기로 한 스위치(키 → 켜짐) · 플랫폼별 버전 정책(`android` · `ios` …).
 * [serverTime] 은 점검 끝 예정 시각을 기기 시계와 맞춰 볼 때 쓴다.
 */
data class AppConfigResponse(
    val serverTime: Instant,
    val maintenance: AppConfigMaintenance,
    val flags: Map<String, Boolean>,
    val platforms: Map<String, AppConfigPlatform>,
)

internal fun AppControlSnapshot.toConfigResponse(now: Instant) = AppConfigResponse(
    serverTime = now,
    maintenance = AppConfigMaintenance(maintenance.enabled, maintenance.message.takeIf { maintenance.enabled }, maintenance.endsAt?.takeIf { maintenance.enabled && it.isAfter(now) }),
    flags = flags.values.filter { it.clientVisible }.sortedBy { it.key }.associate { it.key to it.enabled },
    platforms = platforms.values.sortedBy { it.platform }.associate { it.platform to AppConfigPlatform(it.minVersion, it.recommendedVersion, it.storeUrl) },
)
