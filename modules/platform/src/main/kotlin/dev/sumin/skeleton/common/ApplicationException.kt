package dev.sumin.skeleton.common

import org.springframework.http.HttpStatus

/**
 * 도메인/애플리케이션 레벨 예외의 베이스.
 *
 * 각 앱에서 의미 있는 예외는 이 클래스 상속으로 선언.
 * [GlobalExceptionHandler]가 [status]/[title]을 그대로 [ApiError]에 실어 반환.
 *
 * 예:
 * ```
 * class MemoNotFoundException(id: Long) : ApplicationException(
 *     message = "Memo not found: $id",
 *     status = HttpStatus.NOT_FOUND,
 *     title = "Memo not found",
 * )
 * ```
 */
open class ApplicationException(
    message: String,
    val status: HttpStatus,
    val title: String = status.reasonPhrase,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
