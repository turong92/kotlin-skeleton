package dev.sumin.skeleton.common.logging

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.redaction")
data class RedactionProperties(
    val replacement: String = "[REDACTED]",
    val additionalSensitiveNames: Set<String> = emptySet(),
    /** 출력 끝 로그 마스킹([LogMasker]) — 앱이 logback 조각을 include 했을 때만 적용된다 (docs/logging.md) */
    val output: Output = Output(),
) {
    data class Output(
        /** 기본 규칙(Authorization · Bearer · JWT · token/secret/password 쌍)에 더하는 정규식. 이름 있는 그룹 `value` 가 있으면 그 부분만 가린다 */
        val patterns: List<String> = emptyList(),
        /** 이메일을 `a***@d***.com` 모양으로 가린다 — 기본 꺼짐: 디버깅에 이메일이 필요한 앱이 많다 */
        val maskEmails: Boolean = false,
    )
}
