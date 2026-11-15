package dev.sumin.skeleton.common.account

/**
 * 운영자가 「시험 계정」으로 표시한 계정을 집계에서 빼는 자리(`RoleHolders` · [AccountFacts] 와 같은 선례) — 통계 · 대시보드 · 매출 · 결제 집계 · 공지 대상 `all` 이 같은 조건을 쓴다.
 * `account-jdbc` 가 표시 표를 읽는 구현을 내고, 계정 표를 모르는 모듈(결제 · 쿠폰 · 활동 …)은 이 조건 조각만 질의에 붙인다. 구현이 없으면 부르는 쪽이 [NONE] 을 쓴다 — 아무도 빼지 않는다.
 *
 * 규칙: 집계(여러 계정의 합 · 수 · 순위)에만 건다. 계정 한 명을 보는 화면 · 운영자의 조회 · 그 계정 자신의 요청에는 걸지 않는다. 시험 계정의 주문 · 쿠폰은 장부에 그대로 남는다(지우지 않는다).
 */
fun interface TestAccountFilter {
    /**
     * 질의의 `where` 에 `and` 로 붙이는 SQL 조건 — [accountColumn] 의 계정이 시험 계정이 **아니면** 참이다. 열 이름은 코드가 정한 상수만 넘긴다(`o.account_id` 처럼 별칭 포함).
     * 조건 안의 별칭은 `tm` 이라서 질의가 같은 이름을 쓰면 겹친다.
     */
    fun notTestSql(accountColumn: String): String

    companion object {
        /** 아무도 빼지 않는다 — 시험 계정 표시를 모르는 조립의 기본 */
        val NONE: TestAccountFilter = object : TestAccountFilter {
            override fun notTestSql(accountColumn: String) = "1 = 1"
        }
    }
}
