package dev.sumin.skeleton.search

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 검색 에러 코드 - `SEARCH.` 로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class SearchErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    QUERY_TOO_SHORT("SEARCH.QUERY_TOO_SHORT", HttpStatus.BAD_REQUEST, "Search query needs at least one word of two or more characters"),
    PAGE_TOO_DEEP("SEARCH.PAGE_TOO_DEEP", HttpStatus.BAD_REQUEST, "Search results are available only up to the 1,000th result"),
    SOURCE_NOT_FOUND("SEARCH.SOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, "No search source for that type"),
    REBUILD_RUNNING("SEARCH.REBUILD_RUNNING", HttpStatus.CONFLICT, "A rebuild of that type is already running"),
}

class SearchException(val searchError: SearchErrorCode, message: String) : ApplicationException(message, searchError)
