package dev.sumin.skeleton.campaign

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「오늘」을 가르는 서비스 시간대 - 보는 사람의 시간대가 아니다(시간대를 옮겨 두 번 찍기를 막는다). 사다리: `skeleton.campaign.zone` → `skeleton.time.default-zone` → `Asia/Seoul`.
 */
class CampaignZone(val zone: ZoneId) {
    fun today(now: Instant): LocalDate = LocalDate.ofInstant(now, zone)

    companion object {
        const val FALLBACK = "Asia/Seoul"

        fun resolve(configured: String?, appDefault: String?): CampaignZone {
            val name = configured?.takeIf { it.isNotBlank() } ?: appDefault?.takeIf { it.isNotBlank() } ?: FALLBACK
            return try {
                CampaignZone(ZoneId.of(name))
            } catch (e: DateTimeException) {
                throw IllegalStateException("Invalid time zone '$name' for skeleton.campaign.zone / skeleton.time.default-zone", e)
            }
        }
    }
}
