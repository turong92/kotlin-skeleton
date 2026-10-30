package dev.sumin.skeleton.review.erasure

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.review.ReviewStore

/**
 * 계정이 지워질 때 그 계정의 리뷰 · 도움돼요 · 신고의 계정을 계정마다 하나인 톰스톤으로 바꾼다(`board` 와 같다). 줄 · 집계는 남고 응답에 `authorDeleted: true` 가 나간다.
 * 구매 신호 줄은 지운다. 멱등이다.
 */
class ReviewAccountErasureListener(private val store: ReviewStore) : AccountErasureListener {
    override val name: String = "review"

    override fun erase(request: ErasureRequest) {
        store.eraseAccount(request.accountId, request.tombstone)
    }
}
