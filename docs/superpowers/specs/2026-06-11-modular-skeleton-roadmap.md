# Modular Skeleton Roadmap

> Purpose: keep a durable checklist for turning proven code from
> `/Users/sumin/IdeaProjects/crabs/mdc-main/be-api` into this skeleton's
> composable modules. This is not a copy list. Each item must be standardized,
> renamed, tested, and fitted into the current module boundaries.

## Guiding Rules

- Absorb patterns, not old code shape.
- Keep modules optional. A service should only carry auth, payment, Slack, S3,
  Kafka, WebSocket, or Redis pieces when it intentionally depends on them.
- Keep `modules:platform` small and foundational: trace context, response
  contract, exception contract, web policy, OpenAPI, and outbound HTTP.
- Put channel/vendor implementations in separate modules:
  `notification-slack`, `notification-sse`, `payment-toss`, `storage-s3`.
- Keep Redis capabilities grouped as `redis-*` modules:
  `redis-core`, `redis-lock`, `redis-cache`, and `redis-rate-limit`.
- No product-domain dependencies inside skeleton modules. Use contributor,
  resolver, mapper, and customizer interfaces for service-specific context.
- Prefer property-driven auto-configuration with safe defaults. Missing secrets
  must disable optional integrations instead of breaking startup unless a module
  explicitly opts into fail-fast.
- Preserve W3C trace context. New modules should read `traceId`, `spanId`, and
  `parentSpanId` from the platform MDC keys and propagate `traceparent` for
  outbound calls where applicable.
- Do not log or send secrets, tokens, authorization headers, cookies, or full
  request bodies by default. Body capture must be size-limited and redacted.
- Every module needs focused unit tests plus at least one integration-style
  proof in `apps:api` when it affects HTTP behavior.
- Update this checklist as each module moves from `Planned` to `Done`.

## Current Baseline

- [x] `modules:platform`: trace/logging, response DTOs, exception response,
  web policy, CORS/rate-limit basics, OpenAPI, outbound HTTP.
- [x] `modules:auth`: JWT, principal, dev login, break-glass login.
- [x] `modules:auth-social`: provider-neutral OAuth login.
- [x] `modules:auth-social-google`: Google provider.
- [x] `modules:auth-social-kakao`: Kakao provider.
- [x] `modules:auth-social-naver`: Naver provider.
- [x] `modules:config-aws-ssm`: AWS SSM Parameter Store loading.
- [x] `modules:crypto`: AES-GCM text encryption and opt-in persistence
  converters.
- [x] `modules:json`: raw JSON, versioned JSON envelopes, migrations,
  DB converters, REST/OpenAPI schemas, external HTTP helpers, and event
  payload serialization.
- [x] `modules:notification`: notification contracts and in-memory broker.
- [x] `modules:notification-sse`: optional SSE channel.
- [x] `modules:notification-slack`: Slack webhook alerts and exception notices.
- [x] `modules:notification-websocket`: optional STOMP/WebSocket channel.
- [x] `modules:idempotency`: idempotent operation support.
- [x] `modules:persistence-jpa`: JPA audit timestamp support.
- [x] `modules:persistence-jdbc`: JDBC audit timestamp support.
- [x] `modules:redis-core`: Redis connection/templates/key prefixing.
- [x] `modules:redis-lock`: Redis distributed lock support.
- [x] `modules:redis-cache`: Redis cache defaults.
- [x] `modules:redis-rate-limit`: Redis-backed rate-limit store.
- [x] `modules:scheduler`: annotation-driven scheduler.
- [x] `modules:storage`: storage contracts and validation.
- [x] `modules:storage-s3`: S3 object storage and presigned storage adapter.
- [x] `modules:event-kafka`: Kafka event publishing adapter.
- [x] `modules:payment`: provider-neutral payment contracts.
- [x] `modules:payment-toss`: Toss payment provider.
- [x] `modules:payment-stripe`: Stripe payment provider.
- [x] `apps:api`: runnable composition workbench for module smoke checks.

The module base layer is now present. Open checklist items below are follow-up
hardening, extra provider behavior, distributed guarantees, and operational
polish rather than proof that the module directory is missing.

## Capability Matrix

Use this table as the quick orientation point before assigning large batches of
work.

| Area | Modules | Base status | Default posture | Next hardening focus |
| --- | --- | --- | --- | --- |
| Platform | `platform` | Done | Always included | Response/OpenAPI polish and shared redaction |
| Auth | `auth` | Done | Optional by dependency | More sample principals and app-specific account adapters |
| Social login | `auth-social`, provider modules | Done | Provider modules opt in | Provider profile mapping samples and callback smoke flows |
| Config/secrets | `config-aws-ssm` | Done | Auto from profile/runtime context | Real AWS profile/role smoke run when SSM is available |
| Crypto | `crypto` | Done | Optional by dependency; passive without keys and fail-fast for invalid configured keys | Public asset URL codec integration only if storage modules need it |
| JSON | `json` | Done | Optional by dependency; apps can expose `JsonDocument` directly | Add more concrete versioned DTO examples as provider modules need them |
| Async | `async` | Done | Optional by dependency; enabled by default when included | Optional annotation-based async exception bridge to notification modules |
| Notification | `notification`, `notification-sse`, `notification-slack`, `notification-websocket` | Done | Channels opt in | Async exception hook, WebSocket auth docs, operational alert links |
| Idempotency | `idempotency` | Done | Optional by dependency | More sample endpoint patterns |
| Persistence audit and JPA operations | `persistence-jpa`, `persistence-jdbc` | Done | Optional by dependency | Add Querydsl module later only if needed |
| Redis | `redis-core`, `redis-lock`, `redis-cache`, `redis-rate-limit` | Done | Core is passive; feature modules own fail policy | Testcontainers concurrency/infra proofs and per-route rate-limit override |
| Scheduler | `scheduler` | Done | Disabled unless configured by app/profile | Redis lock bridge sample and notification failure bridge |
| Storage | `storage`, `storage-s3` | Done | S3 disabled until configured; public URLs can be raw or opaque | LocalStack or real AWS smoke run when credentials exist |
| Events | `event-kafka` | Done | Producer-only; disabled sender can log/no-op | Add `event-kafka-consumer` only when consumer conventions are needed |
| Payment | `payment`, `payment-toss`, `payment-stripe` | Done | Providers disabled until secrets/config exist | Provider routing samples, alerts, response headers/vendor trace hooks |
| Composition app | `apps:api` | Done | Runnable workbench | Add FE/workbench wiring and more smoke endpoints as modules mature |

## Priority Checklist

### 1. `modules:notification-slack` - Done (base webhook + sync exception alerts)

Old references:

- `core-service/.../SlackExceptionNotify.kt`
- `core-service/.../SlackExceptionAspect.kt`
- `core-service/.../SlackAsyncExceptionHandler.kt`
- `infra/web/.../SlackExceptionService.java`
- `infra/web/.../SlackMessageBuilder.java`
- `infra/web/.../SlackPushClient.java`
- `infra/web/.../SlackMessageFactory.java`

Absorb:

- [x] Slack webhook sender with retry and no-op disabled mode.
- [x] Slack block message builder for operational alerts.
- [x] `@SlackExceptionNotify` concept for sync exception notification.
- [ ] Optional async exception hook if it can be integrated cleanly.
- [x] Trace fields: `traceId`, `spanId`, `parentSpanId` when MDC has them.
- [ ] Optional Grafana/Loki URL.
- [x] Context contributor API for account, user, tenant, plan, route, or
  domain-specific fields.
- [x] Topic/severity based routing instead of old hard-coded flags like
  `isPayment` and `isSign`.

Do not absorb directly:

- [x] Domain service lookups from the aspect.
- [x] Static profile state.
- [x] Unbounded request body forwarding.
- [x] Slack failures that break the original request.

Acceptance:

- [x] Disabled or missing webhook does not fail startup by default.
- [x] Annotation sends one alert for a matching exception.
- [x] `exclude` prevents alert delivery.
- [x] Alert includes trace fields when MDC has them.
- [x] Sensitive headers/body fields are redacted or absent by default.

### 2. `modules:redis-core` - Done (base)

Old references:

- `domain/.../RedisConfig.java`
- `domain/.../RedisValueObjectMapper.java`
- `infra/lock/.../RedissonConfig.kt`

Absorb:

- [x] `skeleton.redis.*` connection properties.
- [x] Standalone Lettuce connection factory.
- [x] `StringRedisTemplate` and JSON Redis template defaults.
- [x] Shared key prefix handling.
- [x] Serializer override points through named serializer beans.
- [x] Connect and command timeout properties.

Standardize:

- [x] Do not eagerly ping Redis in the core module.
- [x] Keep `spring.data.redis.*` as internal implementation detail, not the
  primary skeleton contract.
- [x] Keep Redis Cluster support out of the first pass.

Acceptance:

- [x] App can include `redis-core` without Redis running.
- [x] Templates use the configured key/value serializers.
- [x] Key prefix helper creates stable namespaced keys.
- [x] User-supplied connection factory or templates override defaults.

### 3. `modules:redis-lock` - Done (base)

Old references:

- `infra/lock/.../DistributedLock.kt`
- `infra/lock/.../DistributedLockAspect.kt`
- `infra/lock/.../LockExecutor.kt`
- `infra/lock/.../LockImportance.kt`
- `infra/lock/src/test/.../DistributedLockSpELTest.kt`
- `infra/lock/src/test/.../LockExecutorConcurrencyTest.kt`

Absorb:

- [x] `@DistributedLock` with `keyPrefix`, SpEL `key`, wait time, lease time,
  retry attempts, retry backoff, and failure policy.
- [x] Critical vs non-critical lock failure policy.
- [x] Redisson-backed executor.
- [x] Watchdog mode for negative lease time.
- [x] SpEL expression caching and parameter-name support.

Standardize:

- [x] Use explicit policy names: `THROW`, `SKIP`, and
  `PROCEED_ON_BACKEND_FAILURE`.
- [x] Avoid returning nullable from critical flows unless explicitly configured.
- [x] Add property namespace `skeleton.redis-lock`.
- [x] Fail startup when Redis is unavailable when `redis-lock` is enabled,
  including local.

Acceptance:

- [x] Same key runs only once under concurrency.
- [x] Different keys run concurrently.
- [x] Critical acquisition failure throws.
- [x] Non-critical acquisition failure follows configured policy.
- [x] Redis backend failure behavior is tested.

### 4. `modules:scheduler` - Done (base)

Old references:

- `batch/.../CustomScheduled.kt`
- `batch/.../CustomSchedulerProcessor.kt`
- `batch/.../ScheduleType.kt`

Absorb:

- [x] `@SkeletonScheduled` or `@CustomScheduled` equivalent.
- [x] Schedule types: fixed delay, fixed rate, local time, custom cron.
- [x] Time zone selection per task.
- [x] Local/profile/property execution guard.
- [x] Optional distributed-lock integration through a lock manager.

Standardize:

- [x] Keep lock integration optional so `scheduler` can run without Redis.
- [x] Use scheduler `Clock` where tests need deterministic time.
- [x] Keep failed task logging and notification hooks pluggable.

Acceptance:

- [x] Cron resolution is unit tested.
- [x] Local profile skip behavior is tested.
- [x] Task registration waits until application ready.
- [x] Lock integration can be enabled without changing task code.

### 5. `modules:redis-cache` - Done (base)

Old references:

- `domain/.../CacheType.java`
- `domain/.../AppKeyGenerator.java`
- `domain/.../RedisConfig.java`
- `domain/.../RedisCacheErrorHandler.java`
- `domain/.../RedisValueObjectMapper.java`

Absorb:

- [x] Named cache registry with TTL per cache.
- [x] Stable key generator.
- [x] Redis cache manager auto-configuration.
- [x] Cache error handler with fail-open behavior.
- [x] No-op cache fallback when Redis is unavailable if configured.

Standardize:

- [x] Do not expose old app-specific cache names.
- [x] Prefer property-defined cache specs plus optional typed constants.
- [x] Add property namespace `skeleton.redis-cache`.
- [x] Review serializer choice. Avoid unsafe broad default typing unless there
  is a clear, tested reason.

Acceptance:

- [x] Cache TTL is applied per named cache.
- [x] Key generator output is stable.
- [x] Redis get/put/evict failures do not break API when fail-open is enabled.
- [x] No-op fallback is covered.

### 6. `modules:redis-rate-limit` - Done (base)

Old references:

- `user-api/.../InsightRateLimit.kt`
- `user-api/.../InsightRateLimitInterceptor.kt`
- `user-api/.../UserTTSService.kt` preview rate-limit Lua pattern.

Absorb:

- [ ] Annotation-driven or route-specific rate-limit override on top of the
  current web filter.
- [x] Redis Lua `INCR` plus `EXPIRE` atomic fixed-window counter.
- [x] Auth principal key fallback to IP key.
- [x] Fail-open/fail-closed switch.

Standardize:

- [x] Keep basic in-memory rate limiting in `platform`.
- [x] Put Redis-backed implementation in this optional module.
- [x] Add property namespace `skeleton.redis-rate-limit`.
- [x] Allow route key strategy override through `RateLimitKeyResolver`.

Acceptance:

- [x] Limit allows requests inside window.
- [x] Limit rejects requests above threshold.
- [x] Authenticated and anonymous keys are distinct.
- [x] Redis failure respects fail-open/fail-closed.

### 7. `modules:storage-s3` - Done (base)

Old references:

- `infra/aws/.../AbstractAwsS3Service.kt`
- `user-api/.../SupportAttachmentService.kt`

Absorb:

- [x] S3 client and presigner auto-configuration.
- [x] Put/get presigned URL creation.
- [x] Multipart upload start, part presign, complete, abort.
- [x] Upload bytes, copy, move, delete, list by prefix.
- [x] File upload validation helper pattern.

Standardize:

- [x] Prefer composition over abstract base class.
- [x] Use properties for region, bucket aliases, presign durations.
- [x] Keep CloudFront/public URL generation pluggable.

Acceptance:

- [x] Presigned PUT/GET generation can be tested without contacting AWS.
- [x] File validation rejects unsupported extension and size.
- [x] Multipart flow exposes stable DTOs.

### 8. `modules:config-aws-ssm` - Done

Old references:

- `infra/system-core/.../AwsParameterStoreInitializer.java`

Absorb:

- [x] Load Parameter Store paths into Spring property sources.
- [x] Support profile-based path mapping.
- [x] Local override over shared dev values if configured.

Standardize:

- [x] Do not hard-code old `/wkwk/...` paths.
- [x] Use `skeleton.config.aws.ssm.paths`.
- [x] Auto-load from runtime context, with explicit disabled override.
- [x] Decide fail-fast vs warn-only by property.

Acceptance:

- [x] Disabled mode does nothing.
- [x] Multiple paths merge in order.
- [x] Later paths override earlier paths.
- [x] AWS failure behavior follows configured policy.

### 9. `modules:event-kafka` - Done (base)

Old references:

- `infra/event/.../KafkaEventPublisher.kt`
- `infra/event/.../KafkaEventDefaultPublisher.kt`
- `infra/event/.../KafkaDirectPublishListener.kt`
- `infra/event/.../KafkaProducerConfig.kt`
- `infra/event/.../KafkaConsumerConfig.kt`

Absorb:

- [x] Application-event based publish API.
- [x] Transaction `AFTER_COMMIT` publish listener.
- [x] Environment topic prefix.
- [x] Partition key strategy.
- [x] Headers for event type, environment, event id, trace id.

Standardize:

- [x] Fix old package naming issues by designing fresh APIs.
- [x] Keep `event-kafka` producer-only; add consumer support later as `event-kafka-consumer`.
- [x] Provide disabled mode that logs intended events.

Acceptance:

- [x] Event published only after commit.
- [x] Rollback does not publish.
- [x] Disabled mode does not call Kafka.
- [x] Trace context appears in event headers.

### 10. `modules:notification-websocket` - Done (base)

Old references:

- `infra/web-socket/.../WebSocketConfig.kt`
- `infra/web-socket/.../CustomChannelInterceptor.kt`
- `infra/web-socket/.../SocketTokenAuthProvider.kt`
- `user-api/.../SupportStompController.kt`

Absorb:

- [x] STOMP endpoint auto-configuration.
- [x] JWT/token authentication hook on `CONNECT`.
- [x] User destination prefix support.
- [x] Heartbeat and bounded channel executors.
- [x] Message size, send buffer, and send timeout properties.

Standardize:

- [x] Keep domain chat/support controllers out of module.
- [x] Integrate with `notification` contracts where possible.
- [x] Keep SSE and WebSocket as separate optional channels.

Acceptance:

- [x] CONNECT rejects missing or invalid token when auth is enabled.
- [x] Valid token becomes Principal.
- [x] User-targeted message delivery path is documented.

### 11. `modules:payment`, `modules:payment-toss`, and `modules:payment-stripe` - Done (base)

Old references:

- `infra/payments/contract/...`
- `infra/payments/providers/.../TossPaymentApiWebClientService.kt`
- `infra/payments/providers/.../TossPaymentWidgetWebClientService.kt`
- `core-service/.../PaymentRefundExceptionHandler.kt`

Absorb:

- [x] Provider-neutral payment contracts in `payment`.
- [x] Toss implementation in `payment-toss`.
- [x] Stripe implementation in `payment-stripe`.
- [x] Provider trace extraction from stable response/error bodies and headers.
- [x] Idempotency key header support.
- [x] Provider error body mapping.
- [x] Payment/refund failure alert hooks through `notification`.

Standardize:

- [x] Keep domestic/international provider routing in payment core.
- [x] Let Toss and Stripe modules implement provider interfaces.
- [x] Do not bake product/subscription domain into payment core.

Acceptance:

- [x] Provider-neutral service can route to Toss by configuration.
- [x] Provider-neutral service can route between Toss and Stripe.
- [x] Toss error response maps to stable skeleton exception.
- [x] Provider trace id is available in payment results/exceptions.
- [x] Idempotency key is propagated.

## Platform Enhancements To Consider While Implementing Modules

- [x] Add outbound HTTP response-entity support when provider headers matter.
- [ ] Add request-specific connect/read/write timeout support if current
  response timeout is not enough.
- [x] Add a standard vendor trace-id extractor hook for outbound HTTP clients.
- [x] Add redaction utilities shared by logging and Slack alert modules.
- [ ] Add OpenAPI helpers for enum descriptions and response wrapper schemas.

## Next Batch Queue

Use this as the default order for the next unattended or subagent batch.

1. Add WebSocket user-targeted delivery documentation and browser/client smoke
   sample.
2. Promote OpenAPI helper utilities once response/enum docs need another pass.
3. Add request-specific connect/read/write timeout support if the current
   response-timeout override is not enough.

## Deferred Or Product-Specific Patterns

- [ ] Feature plan guard: keep for later payment/subscription modules. Extract
  only the SpEL policy pattern when needed.
- [ ] Support chat Slack bridge: product feature, not base skeleton. Extract
  only Slack signature verification and async 3-second ACK pattern if useful.
- [ ] Querydsl abstract service: heavy JPA-specific convenience. Consider later
  as `persistence-querydsl`.
- [ ] Old `BaseEntity`: retained only as historical reference. Current skeleton
  should continue using common audit timestamps plus JPA/JDBC adapters.

## Execution Order

1. `notification-slack`
2. `redis-core`
3. `redis-lock`
4. `scheduler`
5. `redis-cache`
6. `redis-rate-limit`
7. `storage-s3`
8. `config-aws-ssm`
9. `event-kafka`
10. `notification-websocket`
11. `payment` / `payment-toss`

Base module implementation is complete. Continue using this order for follow-up
hardening when a specific capability needs production-grade depth.

## Completion Log

- 2026-06-11: Roadmap created from the old `be-api` review.
- 2026-06-11: `notification-slack` base module completed. Implementation
  commit: `3f5bfe9`.
- 2026-06-11: `config-aws-ssm` module completed with EnvironmentPostProcessor
  based SSM property loading.
- 2026-06-11: Redis bundle design approved with `redis-*` module naming and
  per-feature failure policies.
- 2026-06-12: `apps/api` promoted from a simple executable sample to a module
  composition workbench. It now depends on the optional capability modules,
  keeps infrastructure-backed integrations disabled by default, and exposes
  `/api/v1/skeleton/modules` plus smoke endpoints for Redis key prefixing,
  storage validation, and notification publishing.
- 2026-06-13: Roadmap reconciled against the current module implementation.
  Added a capability matrix and next batch queue, marked implemented Redis,
  scheduler, storage, event, WebSocket, and payment base capabilities as done,
  and left only hardening/deferred work as open checklist items.
- 2026-06-13: Added Redis Testcontainers concurrency proof for `redis-lock`.
  Same-key attempts are mutually excluded and different keys run concurrently
  against a real Redis backend.
- 2026-06-13: Hardened payment skeleton follow-ups. Toss/Stripe now read
  provider trace IDs from response/error headers, payment failures publish a
  standard notification event hook, and `apps/api` exposes payment route
  preview endpoints that work even when real provider beans are disabled.
- 2026-06-13: Added explicit `event-kafka` rollback proof. The module remains
  producer-only; consumer conventions should land later as a separate
  `event-kafka-consumer` module instead of a dormant toggle.
- 2026-06-13: Wired `apps/api` JWT authentication into
  `notification-websocket` token verification and documented the STOMP topic
  plus user-destination smoke flow. The React workbench now has a WebSocket
  client path beside SSE.
- 2026-06-13: Hardened `persistence-jpa` beyond audit timestamps with
  opt-in optimistic locking, CriteriaUpdate-based partial updates with version
  guards, and standard fetch graph hints for N+1-sensitive read paths. The
  composition app and React fallback catalog now expose persistence modules.
- 2026-06-13: Hardened storage and external HTTP. `storage-s3` now implements
  upload bytes, copy, move, and list by prefix through vendor-neutral contracts,
  while platform outbound HTTP exposes endpoint value objects and standardized
  provider trace extraction on successful and failed responses.
