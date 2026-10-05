package dev.sumin.skeleton.common.deploy

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis

/** 가드 실패를 스택 트레이스 대신 읽을 수 있는 화면으로 — 가드별로 고칠 환경변수 이름을 나열한다 */
class DeployGuardFailureAnalyzer : AbstractFailureAnalyzer<DeployGuardViolationException>() {
    override fun analyze(rootFailure: Throwable, cause: DeployGuardViolationException): FailureAnalysis {
        val description = buildString {
            append("The application refused to start: ").append(cause.problems.size).append(" deploy guard problem(s).\n")
            cause.problems.forEach { append("\n  - [").append(it.guard).append("] ").append(it.message) }
        }
        val action = "Set the variables named above, then start again. " +
            "To run without environment guards, use ${DeployContext.ENV_VAR}=local (or leave it unset). " +
            "Values are never printed here."
        return FailureAnalysis(description, action, cause)
    }
}
