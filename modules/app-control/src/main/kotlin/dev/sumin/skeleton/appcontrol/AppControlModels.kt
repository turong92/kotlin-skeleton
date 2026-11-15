package dev.sumin.skeleton.appcontrol

import java.time.Instant

/** 저장소의 한 줄. [name] 은 `flag:<키>` · `maintenance` · `platform:<이름>`, [payload] 는 그 종류의 JSON */
data class AppControlEntry(
    val name: String,
    val payload: String,
    val updatedBy: String,
    val updatedAt: Instant,
)

/** 기능 스위치 하나. [clientVisible] 이 true 일 때만 클라이언트 설정에 실린다 */
data class FlagState(
    val key: String,
    val enabled: Boolean,
    val clientVisible: Boolean,
    val description: String?,
    val updatedBy: String,
    val updatedAt: Instant,
)

/** 점검 상태. 한 번도 켠 적 없으면 [OFF] */
data class MaintenanceState(
    val enabled: Boolean,
    val message: String?,
    /** 점검 끝 예정(알면). `Retry-After` 와 점검 화면이 쓴다 */
    val endsAt: Instant?,
    val updatedBy: String?,
    val updatedAt: Instant?,
) {
    companion object {
        val OFF = MaintenanceState(false, null, null, null, null)
    }
}

/** 한 플랫폼(`android` · `ios` …)의 버전 정책. 칸이 null 이면 그 규칙이 없다 */
data class PlatformVersions(
    val platform: String,
    val minVersion: String?,
    val recommendedVersion: String?,
    val storeUrl: String?,
    val updatedBy: String,
    val updatedAt: Instant,
)

/** 한 번 읽은 전체 상태 - 캐시가 이 값을 통째로 바꿔 끼운다 */
data class AppControlSnapshot(
    val flags: Map<String, FlagState>,
    val maintenance: MaintenanceState,
    val platforms: Map<String, PlatformVersions>,
) {
    companion object {
        val EMPTY = AppControlSnapshot(emptyMap(), MaintenanceState.OFF, emptyMap())
    }
}
