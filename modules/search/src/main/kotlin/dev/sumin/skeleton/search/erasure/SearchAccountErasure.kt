package dev.sumin.skeleton.search.erasure

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.search.SearchEngine

/** 계정이 지워질 때 그 계정이 주인인 색인 문서를 지운다 (멱등). 주인 없는 문서는 계정과 무관해 그대로다 */
class SearchAccountErasureListener(private val engine: SearchEngine) : AccountErasureListener {
    override val name: String = "search"

    override fun erase(request: ErasureRequest) {
        engine.eraseOwner(request.accountId)
    }
}
