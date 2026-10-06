package dev.sumin.skeleton.auth.account

interface AuthAccountRepository {
    fun findBy(identifier: AccountIdentifier): AuthAccount?

    /** 새 해시를 받아 저장할 수 있나 — 아니면(기본) 로그인이 해시를 새로 계산하지 않는다 (계산해도 버려질 bcrypt 를 로그인마다 돌리지 않게) */
    val storesUpgradedPasswordHash: Boolean get() = false

    /** 로그인 중 인코더가 더 새 해시 방식을 원할 때 저장소에 새 해시를 돌려준다([storesUpgradedPasswordHash] 가 참일 때만 불린다). 시드 저장소는 아무것도 하지 않는다 */
    fun upgradePasswordHash(accountId: String, newHash: String) {}

    /**
     * 같은 일인데 **저장돼 있는 해시가 아직 [oldHash]**(로그인이 검증한 그 해시)일 때만 — 로그인 도중 비밀번호가 재설정 · 증명으로 바뀌었다면 옛 비밀번호의 새 해시가 그것을 덮지 못하게.
     * 기본은 조건 없는 [upgradePasswordHash] — 조건부 쓰기를 할 수 있는 저장소가 이 쪽을 재정의한다
     */
    fun upgradePasswordHash(accountId: String, newHash: String, oldHash: String) = upgradePasswordHash(accountId, newHash)
}
