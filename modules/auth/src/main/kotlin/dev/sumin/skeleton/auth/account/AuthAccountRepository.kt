package dev.sumin.skeleton.auth.account

interface AuthAccountRepository {
    fun findBy(identifier: AccountIdentifier): AuthAccount?

    /** 로그인 중 인코더가 더 새 해시 방식을 원할 때 저장소에 새 해시를 돌려준다. 시드 저장소는 아무것도 하지 않는다 */
    fun upgradePasswordHash(accountId: String, newHash: String) {}
}
