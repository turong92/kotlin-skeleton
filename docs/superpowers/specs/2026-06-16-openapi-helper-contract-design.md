# OpenAPI Helper Contract Design

## Goal

Make Swagger/OpenAPI output follow the skeleton API contract with minimal
controller ceremony. Services should be able to return `Response.ok(...)`,
`DataResponse<T>`, `ListResponse<T>`, `PageResponse<T>`, or
`CursorResponse<T>` and get predictable response-envelope schemas. When generic
return type inference is ambiguous, developers should use one annotation,
`@ApiResponseEnvelope`, instead of choosing from many fine-grained annotations.

This work also hardens `CodeEnum` documentation so generated OpenAPI describes
the actual wire code values, not only Kotlin enum names.

## Current Context

The project already has these pieces:

- `modules:platform` owns `PlatformOpenApiAutoConfiguration`.
- `PlatformOpenApiAutoConfiguration` registers common schemas such as
  `ApiError`, `ResponseMeta`, and `PaginationMeta`.
- `OperationResponses.kt` already provides status helpers:
  `@CreatedOperation`, `@AcceptedOperation`, and `@NoContentOperation`.
- `CodeEnumOpenApiCustomizer` already patches some DTO and parameter schemas.
- `apps:api` owns the Springdoc UI dependency and sample controllers.

The gap is that response wrappers are not yet reliable enough as reusable
contract documentation. Developers still have to reason about when Springdoc
can infer nested generics and when it cannot.

## Chosen Approach

Use a hybrid approach:

1. Infer response envelopes from controller return types whenever possible.
2. Provide one override annotation, `@ApiResponseEnvelope`, for ambiguous or
   custom method signatures.
3. Improve `CodeEnum` OpenAPI customization across request DTOs, response DTOs,
   and operation parameters.

This keeps common controllers clean while giving developers one clear escape
hatch. A single annotation is easier for skeleton users than separate
`@ApiValueResponse`, `@ApiListResponse`, `@ApiPageResponse`, and
`@ApiCursorResponse` annotations.

## API Shape

Add one public annotation in `modules:platform`:

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ApiResponseEnvelope(
    val type: ApiEnvelopeType,
    val value: KClass<*> = Unit::class,
)

enum class ApiEnvelopeType {
    BASIC,
    VALUE,
    LIST,
    PAGE,
    CURSOR,
}
```

Rules:

- `BASIC` means a successful response has only `meta`.
- `VALUE` means `value + meta`.
- `LIST` means `values + meta`.
- `PAGE` means `values + pagination + meta`.
- `CURSOR` means `values + cursor + meta`.
- `value` is required for `VALUE`, `LIST`, `PAGE`, and `CURSOR`.
- `value` is ignored for `BASIC`.
- The annotation overrides inferred envelope type and payload schema.

Example:

```kotlin
@ApiResponseEnvelope(
    type = ApiEnvelopeType.PAGE,
    value = OrderResponse::class,
)
fun searchOrders(): ResponseEntity<PageResponse<OrderResponse>>
```

## Response Envelope Inference

The platform OpenAPI customizer should inspect controller method return types.
It should support:

- Direct wrappers: `BasicResponse`, `DataResponse<T>`, `ListResponse<T>`,
  `PageResponse<T>`, `CursorResponse<T>`.
- Type aliases when reflection exposes the underlying wrapper type.
- `ResponseEntity<Wrapper<T>>`.

When inference succeeds, it updates the successful operation response schema.
The default success response code remains whatever Springdoc or the operation
status annotations produced:

- Normal methods usually stay `200`.
- `@CreatedOperation` moves success to `201`.
- `@AcceptedOperation` moves success to `202`.
- `@NoContentOperation` moves success to `204` and should not attach an
  envelope body.

When inference fails and no `@ApiResponseEnvelope` exists, the customizer leaves
Springdoc's generated schema unchanged.

## Schema Generation

The platform module should add stable component schemas for envelope shapes:

- `BasicResponse`
- `DataResponse`
- `ListResponse`
- `PageResponse`
- `CursorResponse`
- `CursorMeta`
- Existing `ResponseMeta`
- Existing `PaginationMeta`

Parameterized operation responses should be operation-local schemas that
compose or inline the payload type:

- `DataResponse<OrderResponse>` renders as an object with:
  - `value: OrderResponse`
  - `meta: ResponseMeta`
- `ListResponse<OrderResponse>` renders as:
  - `values: array<OrderResponse>`
  - `meta: ResponseMeta`
- `PageResponse<OrderResponse>` renders as:
  - `values: array<OrderResponse>`
  - `pagination: PaginationMeta`
  - `meta: ResponseMeta`
- `CursorResponse<OrderResponse>` renders as:
  - `values: array<OrderResponse>`
  - `cursor: CursorMeta`
  - `meta: ResponseMeta`
- `BasicResponse` renders as:
  - `meta: ResponseMeta`

The customizer should normalize wildcard media types to `application/json` as
the existing code already does.

## CodeEnum OpenAPI Contract

`CodeEnum` schemas should document and expose the actual code values used on the
wire.

Expected behavior:

- Numeric code enums render as `integer` with enum values like `[10, 20]`.
- String code enums render as `string` with enum values like `["ACTIVE"]`.
- Schema descriptions include each constant as `{code, name, label,
  description}`.
- Request DTO properties, response DTO properties, query parameters, and path
  parameters should all use this code schema when the underlying Kotlin type is
  a `CodeEnum`.
- Existing field descriptions should be preserved and the code enum description
  appended.

The customizer should avoid global classpath scanning. It should keep the
current controller-driven discovery model so only API surface types affect the
OpenAPI document.

## Error Handling And Boundaries

This feature does not change runtime API responses. It only changes generated
OpenAPI metadata.

If an annotated method omits `value` for an envelope type that needs payload
metadata, the customizer should fail fast during OpenAPI generation with a clear
message that includes the controller method and annotation type.

If type inference sees an unsupported generic shape, it should leave the schema
alone unless `@ApiResponseEnvelope` is present.

## Testing Strategy

Add tests at two levels:

1. Platform unit or context tests for schema builder behavior:
   - envelope type inference from direct wrappers
   - `ResponseEntity<Wrapper<T>>` inference
   - annotation override
   - invalid annotation configuration
   - `CodeEnum` schema mutation for numeric and string codes

2. App integration tests against generated OpenAPI JSON:
   - value response includes `value` and `meta`
   - list response includes `values` and `meta`
   - page response includes `values`, `pagination`, and `meta`
   - cursor response includes `values`, `cursor`, and `meta`
   - `@ApiResponseEnvelope` wins when return type inference is ambiguous
   - code enum query and DTO fields show code values and descriptions

Use existing sample controllers where they fit. Add a small test-only or sample
endpoint only when existing controllers do not express a required case.

## Documentation

Update the API documentation to explain:

- Returning skeleton response wrappers is the preferred path.
- `@ApiResponseEnvelope` is the one escape hatch for ambiguous signatures.
- `CodeEnum` should be used when the public API needs stable wire codes.
- Runtime response shape and OpenAPI schema should be treated as the same
  contract.

Update the modular skeleton roadmap item:

```markdown
- [x] Add OpenAPI helpers for enum descriptions and response wrapper schemas.
```

## Non-Goals

- Do not replace Springdoc.
- Do not introduce separate annotations for every envelope shape.
- Do not change runtime JSON response bodies.
- Do not expose observability links in public API error responses.
- Do not add UI customizations for Swagger UI in this pass.

## Success Criteria

- OpenAPI generation works when only `modules:platform` and Springdoc are
  present.
- Common response wrappers are documented predictably without per-endpoint
  annotations.
- `@ApiResponseEnvelope` handles ambiguous cases with one annotation.
- `CodeEnum` appears in OpenAPI using wire codes and clear descriptions.
- A focused app OpenAPI test proves the generated JSON contract.
- Existing OpenAPI status helpers continue to work.
