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
) {
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
