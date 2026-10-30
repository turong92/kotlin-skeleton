package dev.sumin.skeleton.review

import java.time.Instant

/**
 * 리뷰 저장 포트 - `review-jdbc` 가 구현한다(메모리 구현은 없다: 영속이 본질이다). 쓰기는 모두 [inTransaction] 안(또는 호출자 트랜잭션 안)에서 부른다.
 *
 * **잠금 순서는 하나다: 리뷰 줄 → 집계 줄.** 없는 리뷰 줄을 `lock…` 으로 잠그지 않는다(MySQL 의 틈 잠금으로 동시에 온 두 쓰기가 교착한다) -
 * 새 줄은 [insertIfAbsent] 로 먼저 넣고, 못 넣었을 때만 잠근다.
 */
interface ReviewStore {
    /** 열린 트랜잭션이 있으면 거기에 합류하고, 없으면 새로 연다 */
    fun <T> inTransaction(block: () -> T): T

    // 리뷰 줄

    fun find(id: String): Review?

    fun findByTarget(target: ReviewTarget, accountId: String): Review?

    /** 줄을 잠그고 현재 값을 읽는다(`for update`) */
    fun lock(id: String): Review?

    fun lockByTarget(target: ReviewTarget, accountId: String): Review?

    /** (대상, 계정) 줄이 없을 때만 넣는다. 넣었으면 true */
    fun insertIfAbsent(review: Review): Boolean

    /** `DELETED` 줄을 새 내용의 `PUBLISHED` 로 되살린다 - 도움돼요 · 신고 · 답글 · 사진은 비운다(옛 내용에 달린 것이다). `created_at` · `published_at` 도 [at] 으로 */
    fun revive(id: String, rating: Int, body: String, verified: Boolean, at: Instant): Boolean

    fun updateContent(id: String, rating: Int, body: String, at: Instant): Boolean

    /** `PUBLISHED`/`HIDDEN` → `DELETED`. 바뀐 줄이 있으면 true */
    fun markDeleted(id: String, at: Instant): Boolean

    /**
     * 대상의 리뷰 한 쪽. 남에게는 `PUBLISHED` 만 읽고, 보는 사람([viewer])이 있으면 **자기의** `HIDDEN` 줄도 함께 읽는다 -
     * 걸러 내기가 아니라 안 읽기다(공개 응답의 근거).
     */
    fun page(target: ReviewTarget, viewer: String?, filter: ReviewFilter, sort: ReviewSort, offset: Int, limit: Int): ReviewRows

    /** 내 리뷰(`DELETED` 제외), id 내림차순. [before] 는 직전 쪽의 마지막 id */
    fun pageMine(accountId: String, before: String?, limit: Int): List<Review>

    // 신고 · 운영 - 호출자가 리뷰 줄을 잠근 채로 부른다

    fun hasReport(reviewId: String, accountId: String): Boolean

    fun insertReport(reviewId: String, accountId: String, reason: ReportReason, detail: String?, at: Instant)

    /** `report_count + 1` · `reported_at = at` 를 하고 바뀐 개수를 돌려준다 */
    fun addReportCount(reviewId: String, at: Instant): Int

    /** `PUBLISHED` → `HIDDEN` - 숨긴 사람 · 사유를 남기고 신고를 확인한 것으로 친다. 바뀐 줄이 있으면 true */
    fun hide(id: String, by: String, reason: String, at: Instant): Boolean

    /** `HIDDEN`(또는 운영자가 숨긴 뒤 작성자가 지운 `DELETED`) → `PUBLISHED` - 숨긴 기록을 비운다. 바뀐 줄이 있으면 true */
    fun restore(id: String, at: Instant): Boolean

    /** 신고를 확인한 것으로 친다(`reports_reviewed_at`). 바뀐 줄이 있으면 true */
    fun dismissReports(id: String, at: Instant): Boolean

    fun pageAdmin(queue: ReviewQueue, targetType: String?, before: String?, limit: Int): List<Review>

    /** 리뷰별 사유 코드별 신고 수 */
    fun reportReasonCounts(reviewIds: Collection<String>): Map<String, Map<String, Int>>

    // 답글

    fun repliesOf(reviewIds: Collection<String>): Map<String, ReviewReply>

    /** 답글을 넣거나(없을 때) 고친다 */
    fun saveReply(reviewId: String, body: String, actorId: String, at: Instant)

    fun deleteReply(reviewId: String): Boolean

    // 사진

    /** 사진 저장 키를 [keys] 로 바꾼다(순서가 인덱스) - `photo_count` 도 맞춘다 */
    fun replacePhotos(reviewId: String, keys: List<String>)

    fun photoKey(reviewId: String, index: Int): String?

    /** 그 계정의 리뷰 줄 수(줄은 지워지지 않으니 늘기만 한다) */
    fun countWritten(accountId: String): Int

    // 집계

    fun stats(target: ReviewTarget): TargetStats

    fun statsOf(targets: Collection<ReviewTarget>): Map<ReviewTarget, TargetStats>

    /** 집계 줄이 없으면 0 으로 만든 뒤 개수 · 합 · 별점 칸을 원자적으로 [delta] 만큼 바꾼다 */
    fun adjustStats(target: ReviewTarget, rating: Int, delta: Int, at: Instant)

    // 도움돼요 - 호출자가 리뷰 줄을 잠근 채로 부른다

    fun hasHelpful(reviewId: String, accountId: String): Boolean

    fun helpfulOf(accountId: String, reviewIds: Collection<String>): Set<String>

    fun insertHelpful(reviewId: String, accountId: String, at: Instant)

    fun deleteHelpful(reviewId: String, accountId: String): Boolean

    /** `helpful_count` 를 [delta] 만큼 바꾸고 바뀐 값을 돌려준다 */
    fun adjustHelpful(reviewId: String, delta: Int): Int

    // 구매 신호

    /** 살아 있는(취소 안 된) 구매 줄이 있나 - 최신 값을 읽는다 */
    fun hasLivePurchase(accountId: String, target: ReviewTarget): Boolean

    /** [ref] 의 구매 줄을 **잠그고** 읽는다(`for update`) - 취소는 이 줄을 먼저 잠근 뒤 리뷰 줄을 잠근다 */
    fun lockPurchase(ref: String): ReviewPurchase?

    /** 같은 `ref` 가 없을 때만 넣는다. 넣었으면 true */
    fun insertPurchaseIfAbsent(purchase: ReviewPurchase): Boolean

    /** 살아 있는 구매를 취소한다. 바뀐 줄이 있으면 true */
    fun revokePurchase(ref: String, at: Instant): Boolean

    /** (계정, 대상)의 리뷰 줄 `verified` 를 맞춘다 */
    fun setVerified(accountId: String, target: ReviewTarget, verified: Boolean): Int

    // 탈퇴

    /** 그 계정의 리뷰 · 도움돼요 · 신고 줄의 계정을 [tombstone] 으로 바꾸고 구매 줄은 지운다. 두 번 불러도 같다. 바꾼 리뷰 줄 수를 돌려준다 */
    fun eraseAccount(accountId: String, tombstone: String): Int
}
