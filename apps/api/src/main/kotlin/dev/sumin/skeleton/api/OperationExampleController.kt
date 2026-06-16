package dev.sumin.skeleton.api

import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.PageQuery
import dev.sumin.skeleton.common.PageResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.AcceptedOperation
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.openapi.CreatedOperation
import dev.sumin.skeleton.common.openapi.NoContentOperation
import dev.sumin.skeleton.idempotency.IdempotentOperation
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springdoc.core.annotations.ParameterObject
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

data class ExampleItemCreateRequest(
    @field:NotBlank
    @field:Size(max = 64)
    val name: String,
)

data class ExampleItemResponse(
    val id: String,
    val name: String,
)

data class ExampleJobResponse(
    val jobId: String,
    val status: String,
)

/**
 * REST operation contract examples. Real applications can delete or replace this controller.
 */
@Validated
@RestController
@RequestMapping("/api/v1/examples")
class OperationExampleController {
    @PostMapping("/items")
    @CreatedOperation
    @IdempotentOperation
    fun createItem(
        @Valid @RequestBody request: ExampleItemCreateRequest,
    ) = ExampleItemResponse(id = "item-1", name = request.name)
        .let { item ->
            Response.created(
                location = ServletUriComponentsBuilder.fromCurrentRequest()
                    .path("/{id}")
                    .buildAndExpand(item.id)
                    .toUri(),
                value = item,
            )
        }

    @DeleteMapping("/items/{id}")
    @NoContentOperation
    fun deleteItem(@PathVariable id: String) =
        Response.noContent()

    @PostMapping("/jobs")
    @AcceptedOperation
    fun startJob() =
        Response.accepted(
            ExampleJobResponse(
                jobId = "job-1",
                status = "QUEUED",
            ),
        )

    @GetMapping("/items")
    fun listItems(
        @Valid @ParameterObject @ModelAttribute pageQuery: PageQuery,
    ): PageResponse<ExampleItemResponse> {
        val items = exampleItems()
        val fromIndex = pageQuery.offset().coerceAtMost(items.size)
        val toIndex = (fromIndex + pageQuery.size).coerceAtMost(items.size)

        return Response.ok(
            values = items.subList(fromIndex, toIndex),
            pagination = pageQuery.toPagination(totalElements = items.size.toLong()),
        )
    }

    @GetMapping("/items/cursor")
    fun cursorItems(): CursorResponse<ExampleItemResponse> =
        Response.cursor(
            values = exampleItems().take(2),
            nextCursor = "item-3",
        )

    @GetMapping("/items/annotated")
    @ApiResponseEnvelope(
        type = ApiEnvelopeType.LIST,
        value = ExampleItemResponse::class,
    )
    fun annotatedItems(): Any =
        Response.ok(values = exampleItems().take(2))

    private fun exampleItems(): List<ExampleItemResponse> =
        (1..5).map { index ->
            ExampleItemResponse(
                id = "item-$index",
                name = "sample-$index",
            )
        }
}
