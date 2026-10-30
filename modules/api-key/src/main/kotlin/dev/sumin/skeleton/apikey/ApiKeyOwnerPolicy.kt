package dev.sumin.skeleton.apikey

import dev.sumin.skeleton.common.authz.OperatorRef

/**
 * 소유자 종류의 확장점 - 이 종류의 키를 [caller] 가 발급 · 회수할 수 있나. 1차 구현은 [AccountApiKeyOwnerPolicy](본인만).
 * 앱이 같은 종류의 정책을 빈으로 두면 대신한다(「유료 요금제만 발급」 같은 제한은 여기서 건다). 거절은 발급 403 · 회수 404 로 나간다.
 */
interface ApiKeyOwnerPolicy {
    /** 이 정책이 맡는 [ApiKeyOwner.type] */
    val ownerType: String

    fun canManage(caller: OperatorRef, owner: ApiKeyOwner): Boolean
}

/** 계정이 소유한 키 - 본인만 */
class AccountApiKeyOwnerPolicy : ApiKeyOwnerPolicy {
    override val ownerType: String = ApiKeyOwnerTypes.ACCOUNT

    override fun canManage(caller: OperatorRef, owner: ApiKeyOwner): Boolean = owner.type == ownerType && owner.id == caller.accountId
}
