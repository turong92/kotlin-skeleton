package dev.sumin.skeleton.common.account

/**
 * 운영 화면이 계정 id 옆에 보여 줄 사람이 알아볼 이름(이메일, 없으면 이메일 대신 쓰는 아이디)을 모듈이 `account` 를 모르고 묻는 자리([AccountFacts] 와 같은 선례).
 * `account` 가 구현한다. 구현이 없으면 부르는 쪽은 이름 없이 id 만 낸다(모듈 문서에 적는다).
 */
fun interface AccountLabels {
    /** 계정 id → 표시 이름. 없는 계정 · 지워진(`ERASED`) 계정 · 보여 줄 식별자가 없는 계정은 결과에 없다 */
    fun of(ids: Collection<String>): Map<String, String>
}
