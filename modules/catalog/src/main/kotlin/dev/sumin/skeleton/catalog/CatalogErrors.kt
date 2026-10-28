package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 카탈로그 에러 코드 - `CATALOG.` 으로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class CatalogErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    CODE_TAKEN("CATALOG.CODE_TAKEN", HttpStatus.CONFLICT, "Catalog code is already taken"),
    LEVEL_TAKEN("CATALOG.LEVEL_TAKEN", HttpStatus.CONFLICT, "Subscription level is already used by another product"),
    PRICE_OVERLAP("CATALOG.PRICE_OVERLAP", HttpStatus.CONFLICT, "Another price of this product already covers that period"),
    PRICE_INVALID("CATALOG.PRICE_INVALID", HttpStatus.BAD_REQUEST, "Price is invalid"),
    NOT_FOUND("CATALOG.NOT_FOUND", HttpStatus.NOT_FOUND, "Catalog item not found"),
}

class CatalogException(val catalogError: CatalogErrorCode, message: String) : ApplicationException(message, catalogError)
