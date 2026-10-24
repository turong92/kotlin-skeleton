package dev.sumin.skeleton.common.authz

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.core.env.Environment

class LegacyRoleKeyException(val problems: List<String>) : IllegalStateException(
    "Removed role keys are set — move them to skeleton.authz:\n  " + problems.joinToString("\n  "),
)

/** 지워진 옛 역할 키 네 개에 값이 있으면 기동을 막고 새 자리를 알려 준다 */
class LegacyRoleKeysGuard(environment: Environment) {
    init {
        val problems = MOVED.mapNotNull { (oldKey, newPlace) ->
            if (environment.getProperty(oldKey).isNullOrBlank()) null else "$oldKey -> $newPlace"
        }
        if (problems.isNotEmpty()) throw LegacyRoleKeyException(problems)
    }

    companion object {
        val MOVED = linkedMapOf(
            "skeleton.account.admin.role" to "skeleton.authz.super-role",
            "skeleton.payment.admin.role" to "skeleton.authz.roles.<역할>: [payment.read, payment.refund, payment.operate]",
            "skeleton.legal.admin-role" to "skeleton.authz.roles.<역할>: [legal.read]",
            "skeleton.board.moderator-role" to "skeleton.authz.roles.<역할>: [board.moderate, board.manage]",
        )
    }
}

class LegacyRoleKeysFailureAnalyzer : AbstractFailureAnalyzer<LegacyRoleKeyException>() {
    override fun analyze(rootFailure: Throwable, cause: LegacyRoleKeyException) = FailureAnalysis(
        "The application refused to start: removed role settings are still set.\n\n  " + cause.problems.joinToString("\n  "),
        "Delete the old keys and set the new place named above (docs/modules/platform.md, authz).",
        cause,
    )
}
