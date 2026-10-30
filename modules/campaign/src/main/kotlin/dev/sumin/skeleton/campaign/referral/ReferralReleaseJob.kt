package dev.sumin.skeleton.campaign.referral

import dev.sumin.skeleton.campaign.Referrals
import dev.sumin.skeleton.scheduler.SkeletonScheduled
import java.util.concurrent.TimeUnit

/**
 * 10분마다 보류 기한이 지난 초대에 초대자 보상을 주고 30일 지난 네트워크 값을 지운다. `scheduler` 모듈이 있을 때 돈다(없으면 표시가 무시된다 - 앱이 [Referrals.releaseDue] 를 부른다).
 * [Referrals.releaseDue] 가 조건부 갱신이라 여러 인스턴스가 겹쳐 돌려도 지급 건은 한 벌이다.
 */
class ReferralReleaseJob(private val referrals: Referrals) {
    @SkeletonScheduled(id = "campaign.referral.release", fixedDelay = 10, timeUnit = TimeUnit.MINUTES, lockKey = "campaign.referral.release")
    fun run() {
        referrals.releaseDue()
    }
}
