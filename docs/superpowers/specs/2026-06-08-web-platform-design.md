# Web Platform Capability Design

## Goal

Add a composable web platform capability for inbound HTTP policy and outbound HTTP clients.

The skeleton keeps Spring MVC as the server stack while providing ready-to-use scaffolding for public endpoint policy, proxy/forwarded headers, security headers, CORS, rate limiting, and external HTTP calls. Each default must be replaceable by application beans or narrow configuration.

## Decisions

- Keep the server on Spring MVC. Do not convert the application to a full Spring WebFlux server.
- Use Spring `WebClient` for outbound HTTP because it supports asynchronous non-blocking calls, streaming, filters, and per-request composition.
- Add `spring-boot-starter-webflux` only for the outbound client runtime. Spring Boot keeps MVC auto-configuration when MVC and WebFlux starters are both present.
- Keep YAML thin. Use defaults that work locally, with focused properties for things that truly vary by environment.
- Put generic inbound and outbound web types under `modules/platform`.
- Let `modules/auth` consume platform public endpoint policy when constructing its default `SecurityFilterChain`.

## Scope

This design covers two related but separate slices.

### Inbound Web Policy

Inbound policy handles requests entering this backend.

Responsibilities:

- Public endpoint registration for `permitAll`.
- Forwarded/proxy header support.
- Standard security response headers.
- CORS scaffold.
- Rate limit scaffold.

Package:

```text
modules/platform/src/main/kotlin/dev/sumin/skeleton/common/web/
```

### Outbound Web Client

Outbound clients handle calls from this backend to external HTTP services such as OAuth providers, payment vendors, internal APIs, and AI/agent services.

Responsibilities:

- Standard async HTTP client based on `WebClient`.
- Standard methods for `GET`, `POST`, `PUT`, `PATCH`, and `DELETE`.
- Per-call request manipulation.
- Timeout policy.
- Error mapping.
- Trace header propagation.
- Safe request/response logging.
- Override hooks for vendor modules and real applications.

Package:

```text
modules/platform/src/main/kotlin/dev/sumin/skeleton/common/http/
```

## Inbound Web Policy Design

### Properties

Create `WebProperties` under `skeleton.web`.

```yaml
skeleton:
  web:
    forwarded-headers:
      enabled: true
    security-headers:
      enabled: true
      content-security-policy: ""
      hsts:
        enabled: true
        max-age: 31536000
        include-sub-domains: true
    cors:
      enabled: false
      path-pattern: "/api/**"
      allowed-origin-patterns:
        - "http://localhost:[*]"
        - "http://127.0.0.1:[*]"
      allowed-methods:
        - GET
        - POST
        - PUT
        - PATCH
        - DELETE
        - OPTIONS
      allowed-headers:
        - Authorization
        - Content-Type
        - traceparent
        - X-Request-Id
        - X-Trace-Id
      exposed-headers:
        - Location
        - traceparent
        - X-Trace-Id
        - X-Span-Id
      allow-credentials: false
      max-age: 3600
    rate-limit:
      enabled: false
      path-pattern: "/api/**"
      capacity: 60
      window: 1m
```

Defaults:

- Forwarded headers are enabled because this skeleton expects Caddy or another reverse proxy in production.
- Security headers are enabled because they are low-risk baseline hygiene.
- CORS is disabled because same-origin deployment through Caddy is the preferred production shape.
- Rate limit is disabled because real policies vary by endpoint, account, and deployment topology.

### Public Endpoint Policy

Create a platform registry that can be consumed by auth.

Types:

- `PublicEndpoint`: method-aware public endpoint rule. `method = null` means any method.
- `PublicEndpointRegistry`: immutable list of public endpoint rules.
- `PublicEndpointContributor`: extension point for modules and apps.

Default platform public endpoints:

- `/health`
- `/info`
- `/api/v1/docs`
- `/api/v1/docs/**`
- `/api/v1/docs/ui`
- `/api/v1/docs/ui/**`
- `/swagger-ui/**`
- `/v3/api-docs/**`

Auth contributes:

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/social/*/login`

The sample app will contribute:

- `GET /api/v1/hello`

Applications can add public rules through a bean:

```kotlin
@Bean
fun appPublicEndpoints(): PublicEndpointContributor =
    PublicEndpointContributor { registry ->
        registry.add("GET", "/api/v1/public/catalog/**")
        registry.add("/api/v1/public/**")
    }
```

`AuthAutoConfiguration.securityFilterChain` will read `PublicEndpointRegistry` and apply method-specific and path-wide `permitAll` matchers before `anyRequest().authenticated()`.

### Forwarded Headers

Register `ForwardedHeaderFilter` by default.

This makes generated absolute URLs, including `Location` headers from `ApiResponseEntity.created(...)`, respect `X-Forwarded-Proto`, `X-Forwarded-Host`, and related proxy headers.

Applications can replace or disable this if their proxy or container already handles forwarded headers.

### Security Headers

Register a small `OncePerRequestFilter` that adds headers to every response:

- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: no-referrer`
- `Permissions-Policy` with conservative defaults
- `Strict-Transport-Security` only when `request.isSecure`
- `Content-Security-Policy` only when configured

CSP is not enabled by default because Swagger UI and application frontends often need project-specific policies.

### CORS

Register a `CorsFilter` only when `skeleton.web.cors.enabled=true`.

The CORS filter will run after `TraceIdFilter`, inside the request logging boundary, and before Spring Security so preflight requests are handled consistently.

Defaults are useful for local frontend development but safe for production because CORS is off unless explicitly enabled.

Applications can override CORS by defining their own `CorsConfigurationSource` or `CorsFilter`.

### Rate Limit

Register a rate limit filter only when `skeleton.web.rate-limit.enabled=true`.

The rate limit filter will run after `TraceIdFilter`, inside the request logging boundary, and before Spring Security. Rejected requests are logged by `RequestLoggingFilter`.

Default algorithm:

- Fixed window.
- In-memory store.
- Keyed by client IP.
- Applies to `skeleton.web.rate-limit.path-pattern`.

Types:

- `RateLimitKeyResolver`: default extracts client IP, respecting forwarded headers.
- `RateLimitStore`: default in-memory fixed-window implementation.
- `RateLimitDecision`: allowed/rejected result with limit, remaining, and reset time.
- `RateLimitFilter`: emits headers and rejects over-limit requests.

Response headers:

- `X-RateLimit-Limit`
- `X-RateLimit-Remaining`
- `X-RateLimit-Reset`
- `Retry-After` on rejected requests

Over-limit response:

- Status: `429 Too Many Requests`
- Body: standard `ApiError`
- Includes current `traceId` and `spanId`

Applications can replace `RateLimitStore` with Redis or replace `RateLimitKeyResolver` with account/API-key based logic.

## Outbound Web Client Design

### Dependencies

Add WebClient support to `modules/platform`.

Preferred dependency:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-webflux")
```

This adds `WebClient` and Reactor Netty client support. It does not switch the app server to WebFlux when Spring MVC is also present.

### Properties

Create `OutboundHttpProperties` under `skeleton.http`.

```yaml
skeleton:
  http:
    default-connect-timeout: 2s
    default-response-timeout: 5s
    max-in-memory-size: 2MB
    logging:
      enabled: true
      include-query: false
      include-headers: false
      include-body: false
    clients:
      toss:
        base-url: "https://api.tosspayments.com"
        connect-timeout: 2s
        response-timeout: 5s
      stripe:
        base-url: "https://api.stripe.com"
        connect-timeout: 2s
        response-timeout: 5s
```

Client-specific values override defaults.

### Client API

Create an `ExternalHttpClient` interface.

It will expose method-specific helpers:

- `get`
- `post`
- `put`
- `patch`
- `delete`

Each helper returns `Mono<T>` for async composition.

Each helper accepts a per-call customizer:

```kotlin
externalHttpClient.get(
    clientName = "payment",
    path = "/v1/payments/{paymentKey}",
    responseType = PaymentResponse::class.java,
) {
    uriVariable("paymentKey", paymentKey)
    queryParam("expand", "customer")
    header("Idempotency-Key", idempotencyKey)
    timeout(Duration.ofSeconds(3))
    errorMapper(paymentErrorMapper)
}
```

For imperative service code, provide blocking convenience helpers that apply the same timeout and error mapping:

```kotlin
val payment = externalHttpClient.getBlocking(
    clientName = "payment",
    path = "/v1/payments/{paymentKey}",
    responseType = PaymentResponse::class.java,
) {
    uriVariable("paymentKey", paymentKey)
}
```

Blocking helpers are convenience APIs for Spring MVC services. They must not be used inside Reactor event-loop callbacks.

### Per-Call Manipulation

Create `ExternalHttpRequestSpec`.

Supported manipulation:

- Path URI variables.
- Query parameters.
- Headers.
- Cookies.
- Request body for `POST`, `PUT`, and `PATCH`.
- Per-request timeout override.
- Per-request error mapper override.
- Per-request logging tag.
- Per-request attribute map for custom filters.

This makes vendor integrations flexible without forcing each module to build raw `WebClient` logic.

### Error Handling

Create default exceptions:

- `ExternalHttpException`: base exception with `clientName`, `method`, `uri`, `upstreamStatus`, and `retryable`.
- `ExternalHttpStatusException`: upstream returned an error status.
- `ExternalHttpTimeoutException`: connect, read, or response timeout.
- `ExternalHttpNetworkException`: connection reset, DNS, TLS, or transport failure.

Default HTTP mapping when the exception reaches an API controller:

- Upstream timeout → `504 Gateway Timeout`
- Network failure → `502 Bad Gateway`
- Upstream 4xx/5xx → `502 Bad Gateway` by default

Applications can replace this through `ExternalHttpErrorMapper`.

Do not include raw upstream response bodies in public error messages. Store a truncated internal detail for logs only.

### Trace Propagation

Add a `WebClient` filter that propagates W3C trace context.

When MDC contains the current inbound trace:

- Use the current `traceId`.
- Use the current server `spanId` as the outgoing parent span.
- Send `traceparent: 00-{traceId}-{spanId}-01`.
- Send `X-Trace-Id` for compatibility.

When no inbound trace exists:

- Generate a new trace context.

### Outbound Logging

Default logging is safe:

- Log method, client name, host/path, response status, and duration.
- Do not log query strings by default.
- Do not log request or response bodies by default.
- Mask sensitive headers such as `Authorization`, `Cookie`, `Set-Cookie`, `X-Break-Glass-Secret`, API keys, and secrets.

The log lines will include inbound MDC trace fields automatically.

### Override Hooks

Provide the following extension points:

- `ExternalHttpClientCustomizer`: customize the `WebClient.Builder` per named client.
- `ExternalHttpErrorMapper`: map upstream status/body/exception to domain-specific exceptions.
- `ExternalHttpClientFactory`: replace the default factory entirely.
- `WebClient.Builder` remains injectable for low-level escape hatches.

Vendor modules such as `payment-toss`, `payment-stripe`, or real OAuth providers will depend on these abstractions rather than constructing raw clients from scratch.

## Testing Strategy

### Inbound Tests

Add integration tests for:

- Default public endpoints remain public.
- App-contributed public endpoint is `permitAll`.
- Non-public endpoint still requires auth.
- Forwarded headers affect `Location`.
- Security headers are present.
- CORS preflight works only when CORS is enabled.
- Rate limit returns `429 ApiError` with rate limit headers when enabled.

### Outbound Tests

Add tests for:

- `GET`, `POST`, `PUT`, `PATCH`, and `DELETE` build the expected outbound request.
- Per-call headers, query params, URI variables, body, and timeout customizations apply.
- Trace headers propagate.
- Timeout maps to `ExternalHttpTimeoutException`.
- Upstream error maps through the default mapper.
- A custom mapper can override default mapping.
- A customizer can modify a named client.

Use a local mock HTTP server or Spring test server; do not call external networks.

## Non-Goals

- Do not convert inbound controllers to WebFlux.
- Do not require reactive repositories.
- Do not add Redis rate limiting in the first implementation.
- Do not implement retries or circuit breakers in the first implementation. Leave hooks for future resilience work.
- Do not build payment or OAuth vendor clients in this slice.

## Implementation Order

1. Inbound web policy.
2. Auth permitAll integration.
3. Outbound WebClient foundation.
4. Documentation and examples.

Each implementation slice will use TDD and be committed separately when possible.
