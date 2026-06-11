package dev.sumin.skeleton.common

import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiResponseTest {
    @Test
    fun `ok without value returns basic response metadata`() {
        val response = Response.ok(
            meta = ResponseMeta(
                traceId = "trace",
                spanId = "span",
                timestamp = "2026-06-06T00:00:00Z",
            ),
        )

        assertEquals("trace", response.meta.traceId)
        assertEquals("span", response.meta.spanId)
        assertEquals("2026-06-06T00:00:00Z", response.meta.timestamp)
    }

    @Test
    fun `ok wraps a custom DTO with metadata`() {
        val response: DataResponse<SampleDto> = Response.ok(
            value = SampleDto(id = "sample-1"),
            meta = ResponseMeta(
                traceId = "trace",
                spanId = "span",
                timestamp = "2026-06-06T00:00:00Z",
            ),
        )

        assertEquals(SampleDto(id = "sample-1"), response.value)
        assertEquals("trace", response.meta.traceId)
        assertEquals("span", response.meta.spanId)
        assertEquals("2026-06-06T00:00:00Z", response.meta.timestamp)
    }

    @Test
    fun `ok wraps list values with metadata`() {
        val response: ListResponse<SampleDto> = Response.ok(
            values = listOf(SampleDto(id = "sample-1"), SampleDto(id = "sample-2")),
            meta = ResponseMeta(traceId = "trace"),
        )

        assertEquals(listOf(SampleDto(id = "sample-1"), SampleDto(id = "sample-2")), response.values)
        assertEquals("trace", response.meta.traceId)
    }

    @Test
    fun `ok wraps page values with pagination and metadata`() {
        val response: PageResponse<SampleDto> = Response.ok(
            values = listOf(SampleDto(id = "sample-1")),
            pagination = PaginationMeta(
                page = 1,
                size = 10,
                totalElements = 21,
                totalPages = 3,
                hasNext = true,
                hasPrevious = true,
            ),
            meta = ResponseMeta(traceId = "trace"),
        )

        assertEquals(listOf(SampleDto(id = "sample-1")), response.values)
        assertEquals(1, response.pagination.page)
        assertEquals(10, response.pagination.size)
        assertEquals(21, response.pagination.totalElements)
        assertEquals(3, response.pagination.totalPages)
        assertTrue(response.pagination.hasNext)
        assertTrue(response.pagination.hasPrevious)
    }

    @Test
    fun `ok wraps cursor values with cursor metadata`() {
        val response: CursorResponse<SampleDto> = Response.ok(
            values = listOf(SampleDto(id = "sample-1"), SampleDto(id = "sample-2")),
            hasNext = true,
        ) { it.id }

        assertEquals("sample-2", response.cursor.nextCursor)
        assertTrue(response.cursor.hasNext)
        assertEquals(2, response.values.size)
    }

    @Test
    fun `pagination can be created from page request and total count`() {
        val first = PaginationMeta.of(page = 0, size = 10, totalElements = 21)
        val last = PaginationMeta.of(page = 2, size = 10, totalElements = 21)

        assertEquals(3, first.totalPages)
        assertTrue(first.hasNext)
        assertFalse(first.hasPrevious)
        assertFalse(last.hasNext)
        assertTrue(last.hasPrevious)
    }

    @Test
    fun `response metadata omits null trace fields when serialized`() {
        val json = ObjectMapper().writeValueAsString(
            Response.ok(
                value = SampleDto(id = "sample-1"),
                meta = ResponseMeta(traceId = "trace", spanId = null, timestamp = "2026-06-06T00:00:00Z"),
            ),
        )

        assertTrue(json.contains("traceId"))
        assertFalse(json.contains("spanId"))
    }

    private data class SampleDto(
        val id: String,
    )
}
