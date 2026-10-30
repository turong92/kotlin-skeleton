package dev.sumin.skeleton.apikey.erasure

import dev.sumin.skeleton.apikey.ApiKeyOwnerTypes
import dev.sumin.skeleton.apikey.ApiKeyStore
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest

/** 계정이 지워질 때 그 계정이 소유한 키를 모두 지운다 (멱등) */
class ApiKeyAccountErasureListener(private val store: ApiKeyStore) : AccountErasureListener {
    override val name: String = "api-key"

    override fun erase(request: ErasureRequest) {
        store.deleteByOwner(ApiKeyOwnerTypes.ACCOUNT, request.accountId)
    }
}
