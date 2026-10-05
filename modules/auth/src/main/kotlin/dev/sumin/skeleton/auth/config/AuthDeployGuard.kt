package dev.sumin.skeleton.auth.config

import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard

/** 인증 모듈의 배포 가드 — 규칙은 [AuthStartupValidator.problems] 에 있다 (JWT 비밀 · 시드 계정 저장소 · dev-login · break-glass) */
class AuthDeployGuard(
    private val properties: AuthProperties,
    /** 계정 저장소는 가드가 돌 때 읽는다 — 빈 생성 순서에 기대지 않는다 */
    private val accountRepository: () -> AuthAccountRepository?,
) : DeployGuard {
    constructor(properties: AuthProperties, accountRepository: AuthAccountRepository?) : this(properties, { accountRepository })

    override val name: String = "auth"

    override fun problems(context: DeployContext): List<String> =
        AuthStartupValidator.problems(properties, context, accountRepository())
}
