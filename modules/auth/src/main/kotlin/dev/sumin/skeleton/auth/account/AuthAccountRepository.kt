package dev.sumin.skeleton.auth.account

interface AuthAccountRepository {
    fun findBy(identifier: AccountIdentifier): AuthAccount?

    /** 새 해시를 받아 저장할 수 있나 — 아니면(기본) 로그인이 해시를 새로 계산하지 않는다 (계산해도 버려질 bcrypt 를 로그인마다 돌리지 않게) */
    val storesUpgradedPasswordHash: Boolean get() = false

    /** 로그인 중 인코더가 더 새 해시 방식을 원할 때 저장소에 새 해시를 돌려준다([storesUpgradedPasswordHash] 가 참일 때만 불린다). 시드 저장소는 아무것도 하지 않는다 */
    fun upgradePasswordHash(accountId: String, newHash: String) {}
}
