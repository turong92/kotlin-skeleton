package dev.sumin.skeleton.scheduler

import java.time.Duration
import java.time.ZoneId
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.scheduler")
data class SkeletonSchedulerProperties(
    val enabled: Boolean = true,
    val zone: String = "UTC",
    val timeZone: String = "",
    val poolSize: Int = 2,
    val shutdownAwaitTermination: Duration = Duration.ofSeconds(10),
    val localExecution: LocalExecution = LocalExecution(),
    val locking: Locking = Locking(),
    val mode: Mode = Mode.LOCAL,
    val external: External = External(),
) {
    /** LOCAL = 앱 안 타이머(기본). EXTERNAL = 타이머를 걸지 않고 밖의 호출(Cloud Scheduler 등)이 작업마다 주소를 부를 때 돈다 */
    enum class Mode { LOCAL, EXTERNAL }

    data class External(
        val path: String = "/internal/scheduler/run",   // 작업 주소는 POST {path}/{taskId}
        val secret: String = "",                        // 헤더 X-Scheduler-Secret 과 맞춘다. 비면 주소는 닫혀 있다(404)
    )

    data class LocalExecution(
        val enabled: Boolean = true,
        val allowedProfiles: Set<String> = emptySet(),
        val blockedProfiles: Set<String> = emptySet(),
    )

    data class Locking(
        val enabled: Boolean = true,
    )

    fun defaultZoneId(): ZoneId =
        zoneId(zone = zone, timeZone = timeZone)

    companion object {
        fun zoneId(
            zone: String,
            timeZone: String,
        ): ZoneId {
            val normalizedZone = zone.trim()
            val normalizedTimeZone = timeZone.trim()
            require(
                normalizedZone.isBlank() ||
                    normalizedTimeZone.isBlank() ||
                    normalizedZone == normalizedTimeZone,
            ) {
                "Use either zone or timeZone, or set both to the same value"
            }

            return ZoneId.of(
                normalizedZone
                    .ifBlank { normalizedTimeZone }
                    .ifBlank { "UTC" },
            )
        }
    }
}
