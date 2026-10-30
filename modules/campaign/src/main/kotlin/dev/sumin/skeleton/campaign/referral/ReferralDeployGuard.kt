package dev.sumin.skeleton.campaign.referral

import dev.sumin.skeleton.campaign.Campaign
import dev.sumin.skeleton.campaign.ReferralConfig
import dev.sumin.skeleton.campaign.ReferralKind
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard

/**
 * 같은 네트워크 확인([ReferralConfig.reviewSameNetwork])을 켠 초대 이벤트가 진행 중인데 `skeleton.web.client-ip.mode` 가 비어 있으면 경고한다 -
 * 모드가 없으면 호출자가 `X-Forwarded-For` 로 자기 IP 를 고르므로 네트워크 표시를 속이거나 피할 수 있다.
 * 또 초대 이벤트가 진행 중인데 `scheduler` 가 없으면 네트워크 값(IP)을 30일 뒤 지우는 `releaseDue()` 를 도는 쪽이 없으니 앱이 불러야 한다고 경고한다. **경고만, 막지 않는다**(저장소를 못 읽어도 기동을 막지 않는다).
 */
class ReferralDeployGuard(
    private val activeCampaigns: () -> List<Campaign>,
    private val clientIpModeConfigured: () -> Boolean,
    private val releaseScheduled: () -> Boolean = { true },
) : DeployGuard {
    override val name: String = "campaign"

    override fun warnings(context: DeployContext): List<String> {
        val referrals = runCatching { activeCampaigns().filter { it.kind == ReferralKind.KIND } }.getOrDefault(emptyList())
        val out = mutableListOf<String>()
        if (!clientIpModeConfigured() && referrals.any { runCatching { ReferralConfig.parse(it.config).reviewSameNetwork }.getOrDefault(false) }) {
            out += "an active referral campaign sets reviewSameNetwork, but skeleton.web.client-ip.mode is not set, so any caller can pick its own network with X-Forwarded-For and dodge or fake the review: set it to direct, proxy or cloudflare (docs/client-ip.md)"
        }
        if (referrals.isNotEmpty() && !releaseScheduled()) {
            out += "a referral campaign is active but the scheduler module is not on the classpath, so nothing clears the stored caller networks after 30 days: call Referrals.releaseDue() periodically from the app (docs/modules/campaign.md)"
        }
        return out
    }
}
