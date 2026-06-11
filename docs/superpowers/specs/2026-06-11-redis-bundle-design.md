# Redis Bundle Design

## Goal

Add Redis as a composable skeleton capability without making every application
depend on Redis. Redis-backed features should be explicit modules, and each
module should define whether Redis failure is correctness-critical or
non-critical.

The module names should read as a Redis bundle:

```text
modules:redis-core
modules:redis-lock
modules:redis-cache
modules:redis-rate-limit
```

## Context

The reference server originally documented that Redis was not required for local
startup, but later Redis and Redisson beans became required by several services.
That made the whole application fail when Redis was unavailable, even for
features where Redis is only a performance layer.

The skeleton should avoid that coupling. A project that only needs cache should
not fail like a project that uses distributed locks. A project that includes
distributed locks should fail fast when Redis is unavailable, including local
profile, because the lock is a correctness guarantee.

## Module Boundaries

### `redis-core`

Provides shared Redis infrastructure:

- `skeleton.redis.*` configuration properties.
- Standalone Redis connection setup for Lettuce.
- `StringRedisTemplate` and JSON Redis template defaults.
- Shared key prefix handling.
- Serializer defaults.
- Connection and command timeout settings.

`redis-core` must not eagerly ping Redis during startup. It should only create
connection factories and templates. Feature modules decide whether startup must
verify Redis availability.

### `redis-lock`

Provides distributed lock support:

- Redisson client configuration.
- `@DistributedLock` annotation.
- Lock executor with SpEL key support.
- Watchdog mode when lease time is negative.
- Same-key exclusion and different-key concurrency.

Redis is required when this module is enabled. Startup fails in every profile,
including `local`, if Redis cannot be reached.

### `redis-cache`

Provides Spring Cache backed by Redis:

- Named cache registry.
- TTL per cache.
- Stable key generator using `::` as delimiter.
- Redis cache manager.
- Cache error handler.
- No-op fallback when Redis is unavailable and failure policy is fail-open.

Redis failure must not break normal API behavior by default. Cache failures are
treated as cache misses unless the application explicitly chooses fail-fast.

### `redis-rate-limit`

Provides a Redis implementation of the platform `RateLimitStore`:

- Atomic fixed-window counter using Lua `INCR` plus `EXPIRE`.
- Account or principal based key strategy when authentication is available.
- IP fallback for anonymous requests.
- Failure policy switch.

The default failure policy is fail-open, because generic rate limiting is usually
a protection layer. Applications that use rate limiting to prevent high-cost
abuse can set the policy to reject on Redis failure.

## Properties

Redis connection is configured under `skeleton.redis.*`. Feature behavior is
configured under `skeleton.redis-lock.*`, `skeleton.redis-cache.*`, and
`skeleton.redis-rate-limit.*`.

Example:

```yaml
skeleton:
  redis:
    mode: standalone
    host: localhost
    port: 6379
    username:
    password:
    database: 0
    ssl:
      enabled: false
      disable-peer-verification-local: true
    timeout:
      connect: 2s
      command: 2s
    key-prefix: kotlin-skeleton:local

  redis-lock:
    enabled: true
    fail-fast: true
    key-prefix: lock

  redis-cache:
    enabled: true
    failure-policy: fail-open
    caches:
      sample:
        ttl: 1h
      account:
        ttl: 14d

  redis-rate-limit:
    enabled: true
    failure-policy: fail-open
```

`spring.data.redis.*` should not be the primary public contract for skeleton
users. Internally the module may build Spring Redis objects from
`skeleton.redis.*`.

`skeleton.redis` is connection configuration, not a feature switch. Feature
modules such as `redis-lock`, `redis-cache`, and `redis-rate-limit` own their
own `enabled` and failure-policy properties. This avoids a second global enable
flag that users must remember to set.

## Failure Policy

Failure policy is per feature, not global.

| Module | Default | Startup Redis Check | Runtime Redis Failure |
|---|---|---:|---|
| `redis-core` | optional | no | not applicable |
| `redis-lock` | fail-fast | yes | critical locks throw; configured non-critical policy applies |
| `redis-cache` | fail-open | optional | warn and behave as cache miss |
| `redis-rate-limit` | fail-open | optional | allow or reject based on policy |

The important rule is that Redis availability is only required when the enabled
feature needs Redis for correctness.

## Lock Semantics

`redis-lock` should standardize the old lock behavior:

- `@DistributedLock(keyPrefix, key, waitTime, leaseTime, timeUnit, failurePolicy)`.
- SpEL keys support named parameters and indexed parameters such as `#p0`.
- Expression parsing is cached.
- Lock keys use the global key prefix plus the lock prefix:
  `{skeleton.redis.key-prefix}:lock:{keyPrefix}:{key}`.
- If a lock is not acquired:
  - critical policy throws.
  - skip policy returns a configured skipped result where possible.
  - proceed policy runs without lock only when explicitly configured.
- If Redis cannot be reached during startup, the application fails when
  `redis-lock.enabled=true`.

Returning nullable from lock advice should not be the default because it makes
controller and service contracts unpredictable. A skip behavior should be an
explicit policy with a clearly documented return contract.

## Cache Semantics

`redis-cache` should not hard-code product cache names. It should support:

- Property-defined cache specs.
- Optional typed constants in application code.
- Stable default key generation with `::`.
- String keys.
- JSON values using a controlled object mapper.

The serializer should avoid broad unsafe default typing unless a test proves it
is required. The first implementation can use a conservative JSON serializer and
allow applications to override the serializer bean.

## Rate Limit Semantics

`redis-rate-limit` should replace the platform in-memory store only when the
module is on the classpath and enabled.

Counter keys should include:

- global Redis key prefix
- rate-limit namespace
- route or configured policy id
- resolved principal/account id or client IP
- current fixed-window bucket

The Lua script should increment the key and set expiration only when the counter
is first created.

## Auto-Configuration Rules

- Each module provides its own auto-configuration.
- `redis-core` beans are conditional on missing beans so applications can
  override connection factory, templates, serializers, or key prefixing.
- `redis-lock` should be enabled by classpath plus property. If enabled, startup
  verifies Redis connectivity.
- `redis-cache` should create a Redis cache manager only when enabled and Redis
  is reachable. If failure policy is fail-open and Redis is unavailable, it uses
  a no-op cache manager.
- `redis-rate-limit` contributes a `RateLimitStore` only when enabled. It should
  not alter the platform rate-limit filter API.

## Testing

Unit tests:

- Property binding and default values.
- Key prefix construction.
- Stable key generator output.
- Lock SpEL resolution and expression caching.
- Cache failure handler behavior.
- Rate-limit Lua decision mapping.

Integration tests:

- `redis-lock` fails startup when Redis is missing.
- `redis-lock` runs same-key work once under concurrency.
- `redis-lock` allows different keys concurrently.
- `redis-cache` falls back to no-op when Redis is missing and fail-open.
- `redis-rate-limit` enforces limits with a Redis test container.
- `apps:api` proves modules remain optional when dependencies are absent.

## Implementation Order

1. `redis-core`
2. `redis-lock`
3. `redis-cache`
4. `redis-rate-limit`

Implementing `redis-core` first keeps the later modules small and prevents each
Redis feature from inventing its own connection, timeout, SSL, serializer, and
key-prefix rules.

## Out Of Scope

- Redis Cluster support in the first pass.
- Pub/Sub notification fanout.
- Redis-backed idempotency persistence.
- Product-specific cache names from the reference server.
- Using Redis as a replacement for database transactions.
