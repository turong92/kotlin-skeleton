package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 이벤트 에러 코드 — `CAMPAIGN.` 으로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class CampaignErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    NOT_FOUND("CAMPAIGN.NOT_FOUND", HttpStatus.NOT_FOUND, "Campaign not found"),
    NOT_ACTIVE("CAMPAIGN.NOT_ACTIVE", HttpStatus.CONFLICT, "Campaign is not active"),
    REFERRAL_INVALID("CAMPAIGN.REFERRAL_INVALID", HttpStatus.BAD_REQUEST, "Referral code cannot be used"),
    CONFIG_INVALID("CAMPAIGN.CONFIG_INVALID", HttpStatus.BAD_REQUEST, "Campaign settings are invalid"),
}

class CampaignException(errorCode: CampaignErrorCode, message: String, data: Any? = null) :
    ApplicationException(message, errorCode, message, data)

/** 초대 코드를 쓸 수 없는 이유 — `CAMPAIGN.REFERRAL_INVALID` 의 `detail` 과 `data.reason` 에 실린다 */
enum class ReferralRejection { UNKNOWN_CODE, OWN_CODE, ALREADY_INVITED, MUTUAL, NOT_NEW_ACCOUNT }
