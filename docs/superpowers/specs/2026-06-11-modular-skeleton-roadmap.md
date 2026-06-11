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
- [x] `modules:notification`: notification contracts and in-memory broker.
- [x] `modules:notification-sse`: optional SSE channel.
- [x] `modules:idempotency`: idempotent operation support.
- [x] `modules:persistence-jpa`: JPA audit timestamp support.
- [x] `modules:persistence-jdbc`: JDBC audit timestamp support.

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

### 2. `modules:lock-redisson` - Planned

Old references:

- `infra/lock/.../DistributedLock.kt`
- `infra/lock/.../DistributedLockAspect.kt`
- `infra/lock/.../LockExecutor.kt`
- `infra/lock/.../LockImportance.kt`
- `infra/lock/src/test/.../DistributedLockSpELTest.kt`
- `infra/lock/src/test/.../LockExecutorConcurrencyTest.kt`

Absorb:

- [ ] `@DistributedLock` with `keyPrefix`, SpEL `key`, wait time, lease time.
- [ ] Critical vs non-critical lock failure policy.
- [ ] Redisson-backed executor.
- [ ] Watchdog mode for negative lease time if retained.
- [ ] SpEL expression caching and parameter-name support.

Standardize:

- [ ] Rename policy names if needed: `CRITICAL`, `SKIP_ON_FAILURE`, or
  `PROCEED_ON_BACKEND_FAILURE`.
- [ ] Avoid returning nullable from critical flows unless explicitly configured.
- [ ] Add property namespace `skeleton.lock.redisson`.

Acceptance:

- [ ] Same key runs only once under concurrency.
- [ ] Different keys run concurrently.
- [ ] Critical acquisition failure throws.
- [ ] Non-critical acquisition failure follows configured policy.
- [ ] Redis backend failure behavior is tested.

### 3. `modules:scheduler` - Planned

Old references:

- `batch/.../CustomScheduled.kt`
- `batch/.../CustomSchedulerProcessor.kt`
- `batch/.../ScheduleType.kt`

Absorb:

- [ ] `@SkeletonScheduled` or `@CustomScheduled` equivalent.
- [ ] Schedule types: every seconds, minutes, hours, fixed time, custom cron.
- [ ] Time zone selection per task.
- [ ] Local execution guard.
- [ ] Optional distributed-lock integration.

Standardize:

- [ ] Keep lock integration optional so `scheduler` can run without Redis.
- [ ] Use `Clock` or `TimeProvider` where tests need deterministic time.
- [ ] Keep failed task logging and notification hooks pluggable.

Acceptance:

- [ ] Cron resolution is unit tested.
- [ ] Local profile skip behavior is tested.
- [ ] Task registration waits until application ready.
- [ ] Lock integration can be enabled without changing task code.

### 4. `modules:cache-redis` - Planned

Old references:

- `domain/.../CacheType.java`
- `domain/.../AppKeyGenerator.java`
- `domain/.../RedisConfig.java`
- `domain/.../RedisCacheErrorHandler.java`
- `domain/.../RedisValueObjectMapper.java`

Absorb:

- [ ] Named cache registry with TTL per cache.
- [ ] Stable key generator.
- [ ] Redis cache manager auto-configuration.
- [ ] Cache error handler with fail-open behavior.
- [ ] No-op cache fallback when Redis is unavailable if configured.

Standardize:

- [ ] Do not expose old app-specific cache names.
- [ ] Prefer property-defined cache specs plus optional typed constants.
- [ ] Review serializer choice. Avoid unsafe broad default typing unless there
  is a clear, tested reason.

Acceptance:

- [ ] Cache TTL is applied per named cache.
- [ ] Key generator output is stable.
- [ ] Redis get/put/evict failures do not break API when fail-open is enabled.
- [ ] No-op fallback is covered.

### 5. `modules:web-rate-limit-redis` - Planned

Old references:

- `user-api/.../InsightRateLimit.kt`
- `user-api/.../InsightRateLimitInterceptor.kt`
- `user-api/.../UserTTSService.kt` preview rate-limit Lua pattern.

Absorb:

- [ ] Annotation-driven per-route rate limit.
- [ ] Redis Lua `INCR` plus `EXPIRE` atomic fixed-window counter.
- [ ] Auth principal key fallback to IP key.
- [ ] Fail-open/fail-closed switch.

Standardize:

- [ ] Keep basic in-memory rate limiting in `platform`.
- [ ] Put Redis-backed implementation in this optional module.
- [ ] Allow route key strategy override.

Acceptance:

- [ ] Limit allows requests inside window.
- [ ] Limit rejects requests above threshold.
- [ ] Authenticated and anonymous keys are distinct.
- [ ] Redis failure respects fail-open/fail-closed.

### 6. `modules:storage-s3` - Planned

Old references:

- `infra/aws/.../AbstractAwsS3Service.kt`
- `user-api/.../SupportAttachmentService.kt`

Absorb:

- [ ] S3 client and presigner auto-configuration.
- [ ] Put/get presigned URL creation.
- [ ] Multipart upload start, part presign, complete, abort.
- [ ] Upload bytes, copy, move, delete, list by prefix.
- [ ] File upload validation helper pattern.

Standardize:

- [ ] Prefer composition over abstract base class.
- [ ] Use properties for region, bucket aliases, presign durations.
- [ ] Keep CloudFront/public URL generation pluggable.

Acceptance:

- [ ] Presigned PUT/GET generation can be tested with mocked AWS client.
- [ ] File validation rejects unsupported extension and size.
- [ ] Multipart flow exposes stable DTOs.

### 7. `modules:config-aws-ssm` - Planned

Old references:

- `infra/system-core/.../AwsParameterStoreInitializer.java`

Absorb:

- [ ] Load Parameter Store paths into Spring property sources.
- [ ] Support profile-based path mapping.
- [ ] Local override over shared dev values if configured.

Standardize:

- [ ] Do not hard-code old `/wkwk/...` paths.
- [ ] Use `skeleton.aws.ssm.paths`.
- [ ] Default to disabled.
- [ ] Decide fail-fast vs warn-only by property.

Acceptance:

- [ ] Disabled mode does nothing.
- [ ] Multiple paths merge in order.
- [ ] Later paths override earlier paths.
- [ ] AWS failure behavior follows configured policy.

### 8. `modules:event-kafka` - Planned

Old references:

- `infra/event/.../KafkaEventPublisher.kt`
- `infra/event/.../KafkaEventDefaultPublisher.kt`
- `infra/event/.../KafkaDirectPublishListener.kt`
- `infra/event/.../KafkaProducerConfig.kt`
- `infra/event/.../KafkaConsumerConfig.kt`

Absorb:

- [ ] Application-event based publish API.
- [ ] Transaction `AFTER_COMMIT` publish listener.
- [ ] Environment topic prefix.
- [ ] Partition key strategy.
- [ ] Headers for event type, environment, event id, trace id.

Standardize:

- [ ] Fix old package naming issues by designing fresh APIs.
- [ ] Keep producer-only and consumer support separable if possible.
- [ ] Provide disabled mode that logs intended events.

Acceptance:

- [ ] Event published only after commit.
- [ ] Rollback does not publish.
- [ ] Disabled mode does not call Kafka.
- [ ] Trace context appears in event headers.

### 9. `modules:notification-websocket` - Planned

Old references:

- `infra/web-socket/.../WebSocketConfig.kt`
- `infra/web-socket/.../CustomChannelInterceptor.kt`
- `infra/web-socket/.../SocketTokenAuthProvider.kt`
- `user-api/.../SupportStompController.kt`

Absorb:

- [ ] STOMP endpoint auto-configuration.
- [ ] JWT authentication on `CONNECT`.
- [ ] User destination prefix support.
- [ ] Heartbeat and bounded channel executors.
- [ ] Message size, send buffer, and send timeout properties.

Standardize:

- [ ] Keep domain chat/support controllers out of module.
- [ ] Integrate with `notification` contracts where possible.
- [ ] Keep SSE and WebSocket as separate optional channels.

Acceptance:

- [ ] CONNECT rejects missing or invalid token when auth is enabled.
- [ ] Valid token becomes Principal.
- [ ] User-targeted message delivery path is documented.

### 10. `modules:payment` and `modules:payment-toss` - Planned

Old references:

- `infra/payments/contract/...`
- `infra/payments/providers/.../TossPaymentApiWebClientService.kt`
- `infra/payments/providers/.../TossPaymentWidgetWebClientService.kt`
- `core-service/.../PaymentRefundExceptionHandler.kt`

Absorb:

- [ ] Provider-neutral payment contracts in `payment`.
- [ ] Toss implementation in `payment-toss`.
- [ ] Toss trace header extraction.
- [ ] Idempotency key header support.
- [ ] Provider error body mapping.
- [ ] Payment/refund failure alert hooks through `notification`.

Standardize:

- [ ] Keep domestic/international provider routing in payment core.
- [ ] Let Toss and Stripe modules implement provider interfaces.
- [ ] Do not bake product/subscription domain into payment core.

Acceptance:

- [ ] Provider-neutral service can route to Toss by configuration.
- [ ] Toss error response maps to stable skeleton exception.
- [ ] Provider trace id is available for logs and alerts.
- [ ] Idempotency key is propagated.

## Platform Enhancements To Consider While Implementing Modules

- [ ] Add outbound HTTP response-entity support when provider headers matter.
- [ ] Add request-specific connect/read/write timeout support if current
  response timeout is not enough.
- [ ] Add a standard vendor trace-id extractor hook for outbound HTTP clients.
- [ ] Add redaction utilities shared by logging and Slack alert modules.
- [ ] Add OpenAPI helpers for enum descriptions and response wrapper schemas.

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
2. `lock-redisson`
3. `scheduler`
4. `cache-redis`
5. `web-rate-limit-redis`
6. `storage-s3`
7. `config-aws-ssm`
8. `event-kafka`
9. `notification-websocket`
10. `payment` / `payment-toss`

Each module should be implemented with a focused design/spec or implementation
plan before code changes. When a module is completed, mark its checklist items
above and add the commit hash or PR reference here.

## Completion Log

- 2026-06-11: Roadmap created from the old `be-api` review.
- 2026-06-11: `notification-slack` base module completed. Implementation
  commit: `3f5bfe9`.
