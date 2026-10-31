package dev.sumin.skeleton.notification.push.legal

import dev.sumin.skeleton.legal.ConsentService
import dev.sumin.skeleton.legal.ConsentState
import dev.sumin.skeleton.legal.Subject
import dev.sumin.skeleton.notification.push.AdvertisingConsent
import dev.sumin.skeleton.notification.push.AdvertisingConsentPolicy
import dev.sumin.skeleton.notification.push.PushProperties
import java.time.Instant

/**
 * 한 동의 종류(광고 수신 · 야간 수신)의 상태. [AGREED] 동의했고 만료 전 · [EXPIRED] 동의했지만 [AdvertisingConsentItem.expiresAt] 이 지남 ·
 * [OUTDATED] 새 판이 시행됐다(다시 동의 필요) · [WITHDRAWN] 거뒀다 · [NONE] 한 적 없다(또는 문서가 없다).
 */
enum class AdvertisingConsentState { AGREED, EXPIRED, OUTDATED, WITHDRAWN, NONE }

/**
 * [effective] = 실제로 보낼 수 있다. 광고 수신은 `state == AGREED`, 야간 수신은 `state == AGREED` **이고** 광고 수신이 유효할 때만 -
 * 광고 동의를 거두면 야간 동의가 남아 있어도 같이 멈춘다(연쇄 쓰기 없이 계산으로).
 * [reconfirmDue] = `AGREED` 이고 만료까지 `reconfirm-notice-before` 이내.
 */
data class AdvertisingConsentItem(
    val type: String,
    val state: AdvertisingConsentState,
    val agreedAt: Instant?,
    val expiresAt: Instant?,
    val reconfirmDue: Boolean,
    val effective: Boolean,
)

data class AdvertisingConsentSnapshot(val consent: AdvertisingConsentItem, val night: AdvertisingConsentItem)

/** `legal` 의 동의 기록에서 광고 동의의 지금 상태를 읽는다 - 정책 구현과 `GET /advertising` 이 같은 계산을 쓴다. 동의 조회는 받는 사람 한 명당 읽기 한 번 */
class LegalAdvertisingConsents(private val service: ConsentService, private val advertising: PushProperties.Advertising) {
    fun snapshot(accountId: String, now: Instant): AdvertisingConsentSnapshot {
        val items = service.status(Subject.account(accountId)).items.associateBy { it.type }
        val consent = item(advertising.consentType, items[advertising.consentType], now)
        val night = item(advertising.nightConsentType, items[advertising.nightConsentType], now)
        return AdvertisingConsentSnapshot(consent, night.copy(effective = night.state == AdvertisingConsentState.AGREED && consent.effective))
    }

    private fun item(type: String, status: dev.sumin.skeleton.legal.TypeStatus?, now: Instant): AdvertisingConsentItem {
        val none = AdvertisingConsentItem(type, AdvertisingConsentState.NONE, null, null, reconfirmDue = false, effective = false)
        status ?: return none
        val agreedAt = status.agreed?.at
        return when (status.state) {
            ConsentState.MISSING -> none
            ConsentState.WITHDRAWN -> none.copy(state = AdvertisingConsentState.WITHDRAWN)
            ConsentState.OUTDATED -> none.copy(state = AdvertisingConsentState.OUTDATED, agreedAt = agreedAt)
            ConsentState.CURRENT, ConsentState.GRACE -> {
                val expiresAt = agreedAt!!.plus(advertising.reconfirmAfter)
                if (now.isBefore(expiresAt)) {
                    AdvertisingConsentItem(
                        type, AdvertisingConsentState.AGREED, agreedAt, expiresAt,
                        reconfirmDue = !now.isBefore(expiresAt.minus(advertising.reconfirmNoticeBefore)), effective = true,
                    )
                } else {
                    none.copy(state = AdvertisingConsentState.EXPIRED, agreedAt = agreedAt, expiresAt = expiresAt)
                }
            }
        }
    }
}

/** [AdvertisingConsentPolicy] 를 `legal` 의 동의 기록으로 구현한다 - 철회 · 미동의 · 2년 만료 · 새 판(OUTDATED)은 보내지 않는다. 조회가 실패하면 던진다(게이트가 「안 보냄」으로 닫는다) */
class LegalAdvertisingConsentPolicy(private val consents: LegalAdvertisingConsents) : AdvertisingConsentPolicy {
    override fun consentOf(accountId: String, now: Instant): AdvertisingConsent {
        val snapshot = consents.snapshot(accountId, now)
        return AdvertisingConsent(send = snapshot.consent.effective, night = snapshot.night.effective)
    }
}
