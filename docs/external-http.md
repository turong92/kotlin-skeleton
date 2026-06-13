# External HTTP

`modules:platform` provides `ExternalHttpClient` as the standard wrapper around
Spring WebClient for vendor integrations.

## Standard Call Shape

Use `ExternalHttpEndpoint` when a provider has stable API routes. This keeps
provider modules from repeating raw `clientName` and path strings across methods.

```kotlin
private val confirmEndpoint = ExternalHttpEndpoint(
    clientName = "toss",
    path = "/v1/payments/confirm",
)

val response = httpClient.postResponse(
    endpoint = confirmEndpoint,
    body = body,
    responseType = TossPaymentResponse::class.java,
) {
    baseUrl(properties.baseUrl)
    timeout(Duration.ofSeconds(2))
    header(HttpHeaders.AUTHORIZATION, authorization)
    vendorTraceHeaders("X-Toss-Trace-Id", "X-Request-Id")
    errorMapper(tossErrorMapper)
    loggingTag("payment.toss.confirm")
}.block()
```

Raw `clientName` and `path` overloads still exist for dynamic calls.

## Per-Call Manipulation

Each call can customize:

- URI variables and query params.
- Headers, cookies, and WebClient attributes.
- Response timeout.
- Base URL override.
- Error mapper.
- Logging tag.
- Vendor trace header candidates.

## Trace Handling

Outbound calls propagate the current platform trace headers:

- `traceparent`
- `X-Trace-Id`

Inbound provider trace IDs are extracted into:

```kotlin
response.trace.traceId
response.trace.source
```

On non-2xx responses, the extracted trace is also available to custom error
mappers through `ExternalHttpErrorContext.trace`. The default status exception
copies it to `ExternalHttpException.providerTraceId`.

Default candidate headers:

- `X-Provider-Trace-Id`
- `X-Request-Id`
- `X-Correlation-Id`
- `X-Amzn-Trace-Id`
- `Stripe-Request-Id`
- `Request-Id`

Provider modules can add more candidates per client or per request.

## Configuration

```yaml
skeleton:
  http:
    default-connect-timeout: 2s
    default-response-timeout: 5s
    vendor-trace-headers:
      - X-Provider-Trace-Id
      - X-Request-Id
    clients:
      toss:
        base-url: https://api.tosspayments.com
        response-timeout: 3s
        vendor-trace-headers:
          - X-Toss-Trace-Id
```

Keep secrets out of this config. Inject authorization headers inside provider
modules from their own secret-backed properties.
