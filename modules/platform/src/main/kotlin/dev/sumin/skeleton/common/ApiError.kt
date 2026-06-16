package dev.sumin.skeleton.common

import java.time.Instant

/**
 * 모든 에러 응답의 공통 포맷.
 *
 * code/status/detail + traceId/spanId/timestamp 기반의 API 에러 계약.
 *
 * 예:
 * ```json
 * {
 *   "code": "COMMON.VALIDATION_FAILED",
 *   "title": "Validation failed",
 *   "status": 400,
 *   "detail": "email format invalid",
 *   "traceId": "7a8b9c0d1e2f...",
 *   "spanId": "0f1e2d3c4b5a6978",
 *   "timestamp": "2026-04-20T10:00:00Z",
 *   "errors": [{"field": "email", "code": "INVALID_FORMAT"}]
 * }
 * ```
 */
data class ApiError(
    val code: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val traceId: String? = null,
    val spanId: String? = null,
    val timestamp: String = Instant.now().toString(),
    val errors: List<FieldError>? = null,
    val data: Any? = null,
) {
    data class FieldError(
        val field: String,
        val code: String,
        val message: String? = null,
    )
}
