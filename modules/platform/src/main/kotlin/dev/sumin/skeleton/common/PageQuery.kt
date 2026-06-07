package dev.sumin.skeleton.common

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class PageQuery(
    @field:Min(0)
    val page: Int = 0,
    @field:Min(1)
    @field:Max(100)
    val size: Int = 20,
) {
    fun offset(): Int = page * size

    fun toPagination(totalElements: Long): PaginationMeta =
        PaginationMeta.of(page = page, size = size, totalElements = totalElements)
}
