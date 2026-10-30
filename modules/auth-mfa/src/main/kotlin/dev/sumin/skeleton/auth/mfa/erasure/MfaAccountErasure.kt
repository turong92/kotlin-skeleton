package dev.sumin.skeleton.auth.mfa.erasure

import dev.sumin.skeleton.auth.mfa.MfaService
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest

/** 계정이 지워질 때 그 계정의 2단계 비밀 · 복구 코드 · 로그인 대기를 지운다 (멱등) */
class MfaAccountErasureListener(private val service: MfaService) : AccountErasureListener {
    override val name: String = "auth-mfa"

    override fun erase(request: ErasureRequest) = service.erase(request.accountId)
}
