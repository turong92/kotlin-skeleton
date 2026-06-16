# OpenAPI

`modules:platform` contributes the skeleton OpenAPI contract when Springdoc is
present. Applications usually only need the Springdoc UI dependency in the app
module.

## Response Envelopes

Return the skeleton response wrappers from controllers:

```kotlin
fun getOrder(): DataResponse<OrderResponse>
fun listOrders(): ListResponse<OrderResponse>
fun pageOrders(): PageResponse<OrderResponse>
fun cursorOrders(): CursorResponse<OrderResponse>
fun updateConsent(): BasicResponse
```

OpenAPI generation documents the runtime JSON shape:

- `BasicResponse`: `meta`
- `DataResponse<T>`: `value`, `meta`
- `ListResponse<T>`: `values`, `meta`
- `PageResponse<T>`: `values`, `pagination`, `meta`
- `CursorResponse<T>`: `values`, `cursor`, `meta`

For ambiguous signatures, use the single override annotation:

```kotlin
@ApiResponseEnvelope(
    type = ApiEnvelopeType.PAGE,
    value = OrderResponse::class,
)
fun searchOrders(): ResponseEntity<PageResponse<OrderResponse>>
```

Prefer normal wrapper return types first. Use `@ApiResponseEnvelope` only when
generic inference cannot describe the response.

## CodeEnum

Use `CodeEnum` when the public API should communicate stable wire codes instead
of enum names.

```kotlin
enum class OrderStatus(
    override val code: Int,
    override val label: String,
    override val description: String? = null,
) : IntCodeEnum {
    CREATED(10, "Created"),
    PAID(20, "Paid"),
}
```

OpenAPI documents the code values, type, and descriptions for request DTOs,
response DTOs, query parameters, and path parameters.
