# Error Responses

The platform exposes one standard error shape for controllers, filters, and
interceptors.

```json
{
  "code": "AUTH.INVALID_CREDENTIALS",
  "title": "Invalid credentials",
  "status": 401,
  "detail": "Invalid credentials",
  "traceId": "0123456789abcdef0123456789abcdef",
  "spanId": "abcdef0123456789",
  "timestamp": "2026-06-16T00:00:00Z",
  "errors": [],
  "data": {}
}
```

## Code Rules

- `code` is the stable application contract. Clients, agents, tests, logs, and
  stored failure rows should key off this value.
- `status` remains the HTTP/protocol signal and can be shared by many codes.
- `title` is safe display text for the category.
- `detail` is optional safe detail. Unknown server errors use a generic detail
  and never echo raw exception messages.
- `data` is optional machine-readable context. Only include fields that are safe
  to expose outside the process. Provider raw bodies and secrets should stay in
  logs or secured storage, not API responses.

## Namespaces

- `COMMON.*`: platform and generic web errors.
- `AUTH.*`: password/JWT/authentication errors.
- `AUTH_SOCIAL.*`: OAuth/social login errors.
- `PAYMENT.*`: provider-neutral payment errors. Vendor codes are nested under
  `data.providerCode`, not promoted to the top-level `code`.

`COMMON.DATA_INTEGRITY_VIOLATION` is reserved for sanitized persistence
constraint conflicts. Keep raw database/vendor messages in logs, traces, or
secure operational notifications instead of returning them to clients.

Each capability module owns its own enum implementing
`dev.sumin.skeleton.common.ErrorCode`.

## Persistence

For normal services, store the public string code directly:

```sql
error_code varchar(80) not null
```

Add an index when the table is queried by code:

```sql
create index ix_payment_failures_error_code_created_at
  on payment_failures (error_code, created_at);
```

This keeps operations readable: `PAYMENT.PROVIDER_ERROR` is useful in SQL,
logs, Slack alerts, and dashboards. If a service later needs very high-volume
analytics or compact foreign keys, add an internal registry/surrogate id beside
the public code:

```text
error_code_id bigint not null
error_code varchar(80) not null
```

Do not expose numeric ids as the API contract.

## Module Example

```kotlin
enum class OrderErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    NOT_FOUND(
        code = "ORDER.NOT_FOUND",
        status = HttpStatus.NOT_FOUND,
        title = "Order not found",
    ),
}

class OrderNotFoundException(orderId: String) : ApplicationException(
    message = "Order not found",
    errorCode = OrderErrorCode.NOT_FOUND,
    data = mapOf("orderId" to orderId),
)
```
