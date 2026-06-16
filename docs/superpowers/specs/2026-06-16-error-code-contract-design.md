# Error Code Contract Design

## Goal

Standardize API errors around a stable, namespaced string `code` while keeping
HTTP status, trace context, validation details, and provider-specific data
predictable for frontend clients, agents, logs, and future persistence.

## Decisions

- Use string codes as the public contract: `AUTH.INVALID_CREDENTIALS`,
  `PAYMENT.PROVIDER_ERROR`, `COMMON.VALIDATION_FAILED`.
- Keep HTTP status separate from the code. `status` remains the protocol-level
  signal; `code` is the application-level machine key.
- Use compact fields in `ApiError`: `code`, `title`, `status`, `detail`, plus
  skeleton-specific `traceId`, `spanId`, `timestamp`.
- Do not expose RFC problem `type` by default. Public consumers use `code`;
  richer diagnostic links belong in logs, Slack alerts, and dashboards.
- Add `data` for safe domain/provider context. Do not leak raw exception
  messages from unknown/internal errors.
- Put common errors in `platform`; provider or capability-specific codes live in
  their module and implement the shared contract.
- Do not use enum ordinal or numeric code as the public API. If high-volume DB
  analytics later needs compact indexing, add a registry table or internal
  surrogate id without changing public `code`.

## Shape

```json
{
  "code": "AUTH.INVALID_CREDENTIALS",
  "status": 401,
  "title": "Invalid credentials",
  "detail": "Email or password is invalid.",
  "traceId": "0123456789abcdef0123456789abcdef",
  "spanId": "abcdef0123456789",
  "timestamp": "2026-06-16T00:00:00Z",
  "errors": [],
  "data": {}
}
```

## Module Boundaries

- `modules:platform` owns `ErrorCode`, common platform codes,
  `ApplicationException`, `ApiError`, and `GlobalExceptionHandler`.
- `modules:auth` owns auth-specific codes.
- `modules:auth-social` owns social login-specific codes.
- `modules:payment` owns payment-specific codes and maps provider codes into
  safe `data`.
- Filters that write errors directly, such as auth and rate-limit filters, must
  use the same `ApiError` shape.

## Error Handling Rules

- Validation errors use `COMMON.VALIDATION_FAILED` or
  `COMMON.PARAMETER_VALIDATION_FAILED`.
- Malformed JSON uses `COMMON.MALFORMED_REQUEST`.
- Missing routes use `COMMON.NOT_FOUND`.
- Unknown exceptions use `COMMON.INTERNAL_SERVER_ERROR` with a generic detail.
- Application exceptions use their own `ErrorCode`, optional safe `detail`, and
  optional safe `data`.
- External provider failures preserve provider-specific codes under `data`,
  while the top-level `code` remains a skeleton-owned namespaced code.
