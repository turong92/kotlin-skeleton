# JSON Standard

`modules:json` provides the skeleton contract for raw JSON, versioned JSON, and
JSON serialization that must stay stable across REST, database columns, external
HTTP clients, and asynchronous events.

## Choosing The Shape

Use a DTO when the payload is a stable first-class contract:

```kotlin
data class PaymentProviderResultV1(
    val provider: String,
    val providerTraceId: String?,
)
```

Use `JsonDocument` when the payload is dynamic or should pass through without a
DTO for every possible field:

```kotlin
@PostMapping("/callback")
fun callback(@RequestBody body: JsonDocument) =
    Response.ok(body)
```

Use `VersionedJsonDocument` when a JSON payload is persisted, sent through
events, used for idempotency-like storage, or otherwise expected to outlive the
current code version:

```json
{
  "type": "payment.provider-result",
  "version": 2,
  "payload": {
    "provider": "toss",
    "providerTraceId": "trace-1"
  },
  "metadata": {
    "source": "payment"
  }
}
```

The `type` is a logical payload name, not a JVM class name. Keep it stable even
when package names or implementation classes move.

## Codec

Inject `JsonCodec` for internal conversion:

```kotlin
class PaymentPayloadService(
    private val jsonCodec: JsonCodec,
) {
    fun toDocument(result: PaymentProviderResultV1): JsonDocument =
        jsonCodec.toDocument(result)

    fun toCanonicalJson(document: JsonDocument): String =
        jsonCodec.canonicalString(document)
}
```

`canonicalString` sorts object keys recursively and keeps array order. Use it
for database writes, event payload bytes, fingerprints, and repeatable tests.

## Versioned DTOs

Register a `JsonPayloadDefinition` for the current DTO version:

```kotlin
@Bean
fun paymentProviderResultDefinition() =
    object : JsonPayloadDefinition<PaymentProviderResultV2> {
        override val type = "payment.provider-result"
        override val currentVersion = 2
        override val payloadClass = PaymentProviderResultV2::class
    }
```

Register one-step migrators when old payloads must still be readable:

```kotlin
@Bean
fun paymentProviderResultV1ToV2(jsonCodec: JsonCodec) =
    object : JsonPayloadMigrator {
        override val type = "payment.provider-result"
        override val fromVersion = 1
        override val toVersion = 2

        override fun migrate(payload: JsonDocument): JsonDocument =
            jsonCodec.toDocument(
                PaymentProviderResultV2(
                    provider = payload.textAt("/provider") ?: "unknown",
                    providerTraceId = payload.textAt("/traceId"),
                    raw = payload,
                ),
            )
    }
```

Then read/write through the registry:

```kotlin
val saved = registry.writeLatest("payment.provider-result", dto)
val latest = registry.readLatest(saved, PaymentProviderResultV2::class)
```

## REST And Swagger

Controllers can return the normal response helpers:

```kotlin
fun echo(@RequestBody body: JsonDocument) = Response.ok(body)

fun versioned() = Response.ok(
    VersionedJsonDocument(
        type = "skeleton.sample-json",
        version = 1,
        payload = jsonCodec.parse("""{"name":"sample"}"""),
    ),
)
```

OpenAPI receives shared schemas for:

- `JsonDocument`
- `VersionedJsonDocument`

The workbench endpoints are available at:

- `POST /api/v1/skeleton/json/echo`
- `GET /api/v1/skeleton/json/versioned`

## Database

JPA:

```kotlin
@Convert(converter = JsonDocumentJpaAttributeConverter::class)
@Column(name = "payload", columnDefinition = "JSON")
var payload: JsonDocument? = null

@Convert(converter = VersionedJsonDocumentJpaAttributeConverter::class)
@Column(name = "versioned_payload", columnDefinition = "JSON")
var versionedPayload: VersionedJsonDocument? = null
```

Spring Data JDBC converters are auto-configured for:

- `JsonDocument <-> String`
- `VersionedJsonDocument <-> String`

For MySQL, prefer a `JSON` column when the database needs JSON validation or
path queries. Use `TEXT` only when the database is just durable blob storage.

## External HTTP

Use the JSON extensions when a vendor request or response is dynamic:

```kotlin
val response = httpClient.postJsonResponse(
    endpoint = ExternalHttpEndpoint("vendor", "/callbacks/test"),
    body = jsonCodec.parse("""{"orderId":"order-1"}"""),
) {
    timeout(Duration.ofSeconds(2))
    vendorTraceHeaders("X-Request-Id")
}.block()

val requestId = response?.trace?.traceId
val status = response?.body?.textAt("/status")
```

DTO request/response methods from `ExternalHttpClient` are still preferred for
stable vendor contracts.

## Events

`event-kafka` serializes event payloads through `KafkaEventPayloadSerializer`,
which uses `JsonCodec.canonicalString`. This keeps Kafka payload bytes aligned
with the same JSON rules used by REST and database converters.

## Defaults

- Null fields are controlled by the application Jackson `ObjectMapper`.
- `JsonDocument` keeps arbitrary JSON as a Jackson `JsonNode` under the hood.
- `VersionedJsonDocument.metadata` is string-to-string only. Put actual data in
  `payload`; keep metadata for routing, source, or operational hints.
- Do not store class names in persisted JSON envelopes.
