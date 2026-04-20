package dev.sumin.skeleton.common

import java.time.Instant

/**
 * 모든 에러 응답의 공통 포맷.
 *
 * RFC 7807 (Problem Details for HTTP APIs) 변형 + traceId/timestamp 추가.
 * 프론트엔드의 `ApiError` 타입과 1:1 매칭.
 *
 * 예:
 * ```json
 * {
 *   "type": "about:blank",
 *   "title": "Validation failed",
 *   "status": 400,
 *   "detail": "email format invalid",
 *   "traceId": "7a8b9c0d1e2f...",
 *   "timestamp": "2026-04-20T10:00:00Z",
 *   "errors": [{"field": "email", "code": "INVALID_FORMAT"}]
 * }
 * ```
 */
data class ApiError(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    val traceId: String? = null,
    val timestamp: String = Instant.now().toString(),
    val errors: List<FieldError>? = null,
) {
    data class FieldError(
        val field: String,
        val code: String,
        val message: String? = null,
    )
}
