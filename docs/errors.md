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

- `COMMON.*`: platform and generic web errors. `COMMON.UNKNOWN_HOST` (421) — only when `skeleton.projects` is set and the request's Host belongs to no project (`/health` and `/info` are exempt); see [multi-project.md](multi-project.md).
- `AUTH.*`: password/JWT/authentication errors — `INVALID_CREDENTIALS` (401), `EMAIL_NOT_VERIFIED` · `ACCOUNT_SUSPENDED` (403), `TOO_MANY_ATTEMPTS` (429, `Retry-After`), and from `auth-session` `REFRESH_INVALID` · `REFRESH_REUSED` (401), `SESSION_NOT_FOUND` (404), `CSRF_HEADER_REQUIRED` (403).
- `ACCOUNT.*`: the `account` module — `TOKEN_INVALID` (410), `PASSWORD_POLICY` (400, `data.violations`), `CURRENT_PASSWORD_INVALID` · `REAUTH_FAILED` · `CAPTCHA_FAILED` · `METHOD_UNKNOWN` · `PASSWORD_REQUIRED` (400), `SIGN_UP_CLOSED` (403), `LOGIN_ID_REQUIRED` (403 — the account has not chosen a login ID; login IDs on only), `LOGIN_ID_TAKEN` · `LOGIN_ID_ALREADY_SET` (409 — login IDs on only; `TAKEN` covers reserved, live and retired IDs alike), `EMAIL_TAKEN` · `SOCIAL_EMAIL_CONFLICT` · `IDENTITY_TAKEN` · `IDENTITY_EXISTS` · `LAST_SIGN_IN_METHOD` · `LAST_ADMIN` · `SELF_ACTION_FORBIDDEN` (409), `NOT_FOUND` · `IDENTITY_NOT_FOUND` (404), `RATE_LIMITED` (429, `Retry-After`). Full table and meanings: [accounts.md](accounts.md).
- `AUTH_SOCIAL.*`: OAuth/social login errors.
- `BOARD.*`: the `board` module — `NOT_FOUND`, `POST_NOT_FOUND`, `COMMENT_NOT_FOUND` (404), `FORBIDDEN` (403),
  `REACTION_TYPE_INVALID`, `CONTENT_INVALID`, `AUTHOR_REQUIRED` (400), `AUTHOR_NOT_ALLOWED` (403 — the same words for another account's, an unknown and a deleted author id), `COMMENT_TOO_DEEP` (422), `POST_NOT_COMMENTABLE`, `CODE_TAKEN` (409), `RATE_LIMITED` (429), `REVISION_CHANGED` (409 — the edit you approved or rejected is not the version you reviewed; send `pendingRevision.submittedAt` of the version on screen, and review the new one), `CAPTCHA_FAILED` (400 — guest create with `guest.captcha=REQUIRED`; a missing token reads the same). Guest writes: a wrong secret, another guest's row, a member's row and a missing `X-Board-Guest-Secret` all answer the same `FORBIDDEN`; rows that are not public yet answer 404.
- `AUTHOR_PROFILE.*`: the `author-profile` module — `NAME_TAKEN` · `LIMIT_REACHED` (409), `RATE_LIMITED` (429, `Retry-After` and `data.retryAfterSeconds`, same shape as `ACCOUNT.RATE_LIMITED`), `NOT_FOUND` (404 — the same words for another account's, a missing and a deleted profile id; the administrator link lookup uses it too for an unknown profile or account id). A bad name is `COMMON.VALIDATION_FAILED` with field `name` and the nickname codes (`Required` · `Size` · `Pattern` · `Reserved`). See `docs/modules/author-profile.md`.
- `PAYMENT.*`: provider-neutral payment errors. Vendor codes are nested under
  `data.providerCode`, not promoted to the top-level `code`. Contract with the screens: the payment HTTP contract document, which ships only in a project that chose the `payment` module.
  - Provider side: `PROVIDER_NOT_FOUND` · `ROUTING_FAILED` · `CAPABILITY_UNSUPPORTED` (400 — on the user's confirm path an order whose provider bean is gone is `400 PROVIDER_NOT_FOUND`; only on the operator refund/approve paths (`PROVIDER_NOT_FOUND` · `ROUTING_FAILED`; a provider without refund ability is `409 REFUND_UNSUPPORTED` there) and on `sync` and a same-key order resend (`PROVIDER_NOT_FOUND` · `ROUTING_FAILED`; `CAPABILITY_UNSUPPORTED` stays 400 on `sync`) is it `503 UNAVAILABLE` instead, see below), `PROVIDER_ERROR` (502, operator `sync` only: `data.provider` · `providerCode` · `providerMessage` · `providerTrace` — the provider's own words are in `data` only; `detail` is a fixed sentence), `PROVIDER_PAYMENT_NOT_FOUND` (404, operator `sync` only: the provider does not know the payment; the ledger row is left as it was — an earlier mode-mismatch mark is not released), `UNAVAILABLE` (503 — payments cannot be taken: no catalog, no provider that opens a checkout, or one that cannot hand out its checkout values — `GET /config` says `available: false` for the same reasons, and creating an order then writes nothing; or **nothing was sent**: the refund/approve check before the call could not reach the provider; never after a refund line was claimed).
  - Orders: `ORDER_NOT_FOUND` (404 — the same words for another account's and a missing order), `ITEM_NOT_PURCHASABLE` (422), `IDEMPOTENCY_CONFLICT` (409 — one `Idempotency-Key`, two different requests), `AMOUNT_MISMATCH` (400 — before the provider is called), `ORDER_NOT_PAYABLE` · `MODE_MISMATCH` (409 — test/live key of the order differs from the key in use), `DECLINED` (402, `data.providerCode` · `data.category` = `LIMIT_OR_BALANCE` | `CARD_DECLINED` | `TEMPORARY` | `OTHER`).
  - Refunds: `REFUND_NOT_FOUND` (404), `REFUND_NOT_ALLOWED` (409 — also "match with the provider first" and "already decided"), `REFUND_IN_PROGRESS` (409 — also a refund the provider holds for review), `REFUND_UNSUPPORTED` (409 — the provider cannot refund from the server; nothing was sent), `REFUND_AMOUNT_EXCEEDED` (400), `REFUND_DECLINED` (402, `data.category` = `ALREADY_REFUNDED` | `AMOUNT_EXCEEDED` | `PARTIAL_NOT_ALLOWED` | `PERIOD_EXPIRED` | `TEMPORARY` | `OTHER`, `data.providerCode`).
  - Webhooks (provider-facing, not the app's clients): `WEBHOOK_REJECTED` (401 — the signature or body is not genuine), `WEBHOOK_NOT_FOUND` (404 — unknown provider, wrong or missing address token: the same words), `WEBHOOK_TOO_LARGE` (413), `WEBHOOK_EVENT_NOT_FOUND` (404 — operator retry of an unknown inbox row).
  - A malformed provider payment key or order number is `COMMON.VALIDATION_FAILED` (400), not a payment code. Request-rate limits answer `COMMON.TOO_MANY_REQUESTS` (429, `Retry-After`).

Wrong calls from clients are answered by the handler, never as 500: `405 COMMON.METHOD_NOT_ALLOWED` (with an `Allow` header),
`415 COMMON.UNSUPPORTED_MEDIA_TYPE`, `406` (empty body — the client cannot read JSON either) and `400 COMMON.PARAMETER_VALIDATION_FAILED`
with `errors[{field, code: "Missing"}]` for a missing required query parameter.

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

A 403 (except a break-glass refusal, which has its own WARN line) is logged as one WARN line starting `Forbidden:` (method, path without the query string, caller `Authentication.name` — the account id with the default auth, so an app that swaps the security chain should keep it that way — or `anonymous`, error code, message); the trace id comes from the log context. It is an application log line, not an audit record.
