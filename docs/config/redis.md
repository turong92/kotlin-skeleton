# Redis Configuration

Redis is a composable skeleton capability. The `modules:redis-core` module
provides connection and template infrastructure, not an application feature
switch.

`redis-core` creates the `RedisConnectionFactory`, `StringRedisTemplate`, JSON
Redis template, key prefixer, and serializers from `skeleton.redis.*`. Including
`redis-core` alone does not make Redis required, and the module must not connect
to or ping Redis during startup. Feature modules decide whether Redis
availability is required.

## Module Policies

Redis availability is handled by each feature module:

| Module | Startup behavior | Runtime failure behavior |
|---|---|---|
| `redis-core` | Optional; creates infrastructure only. | Not applicable. |
| `redis-lock` | Fail-fast when enabled; Redis is required. | Lock failures are correctness-critical unless explicitly configured otherwise. |
| `redis-cache` | Optional by default. | Fail-open by default; cache failures behave like misses unless configured otherwise. |
| `redis-rate-limit` | Optional by default. | Configurable; fail-open by default. |

There is no global Redis enabled switch. Use feature-module properties such as
the future lock, cache, or rate-limit settings to decide whether a Redis-backed
feature is active and how it handles Redis failures.

## Local Usage

For local development, copy the example environment file and load it in your
shell before running the app:

```bash
cp .env.example .env.local
set -a
source .env.local
set +a
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:api:bootRun
```

`.env.local` is ignored by git. Spring Boot does not auto-read `.env.local` by
file name; the shell, IDE, Docker Compose, or a wrapper script must inject those
values as environment variables.

Development, staging, and production values should come from the runtime
environment or SSM. Do not commit Redis passwords, private endpoints, or
environment-specific secret files.

## Environment Variables

The public Redis connection contract uses `skeleton.redis.*`. These environment
variables are included in `.env.example`:

| Environment variable | Spring property |
|---|---|
| `SKELETON_REDIS_HOST` | `skeleton.redis.host` |
| `SKELETON_REDIS_PORT` | `skeleton.redis.port` |
| `SKELETON_REDIS_USERNAME` | `skeleton.redis.username` |
| `SKELETON_REDIS_PASSWORD` | `skeleton.redis.password` |
| `SKELETON_REDIS_DATABASE` | `skeleton.redis.database` |
| `SKELETON_REDIS_SSL_ENABLED` | `skeleton.redis.ssl.enabled` |
| `SKELETON_REDIS_SSL_DISABLE_PEER_VERIFICATION_LOCAL` | `skeleton.redis.ssl.disable-peer-verification-local` |
| `SKELETON_REDIS_CONNECT_TIMEOUT` | `skeleton.redis.timeout.connect` |
| `SKELETON_REDIS_COMMAND_TIMEOUT` | `skeleton.redis.timeout.command` |
| `SKELETON_REDIS_KEY_PREFIX` | `skeleton.redis.key-prefix` |
| `SKELETON_REDIS_JSON_TRUSTED_PACKAGES` | `skeleton.redis.json.trusted-packages` |

Defaults are profile-specific where the app profile files provide environment
fallbacks. The core property default for
`skeleton.redis.ssl.disable-peer-verification-local` is `false`.

## JSON Serialization

`redis-core` provides a named `redisJsonSerializer` bean for the JSON Redis
template. Its default trusted packages are:

```text
dev.sumin.skeleton,java.time,java.util
```

Applications that store DTOs outside `dev.sumin.skeleton` must add their DTO
package to `skeleton.redis.json.trusted-packages`, for example:

```bash
SKELETON_REDIS_JSON_TRUSTED_PACKAGES=dev.sumin.skeleton,java.time,java.util,com.example.app
```

Trusted package matching is package-boundary based. Adding `com.example.app`
allows `com.example.app` and its subpackages, but does not allow similarly named
packages such as `com.example.application`.

If an application needs a different serialization strategy, define a bean named
`redisJsonSerializer`. `redis-core` backs off when that named bean already
exists.

## TLS Peer Verification

TLS peer verification is safe by default. The core default for
`skeleton.redis.ssl.disable-peer-verification-local` is `false`, and shared
environments should keep it false.

The local profile and `.env.example` may opt into disabling peer verification for
local or self-signed Redis endpoints. Only use
`SKELETON_REDIS_SSL_DISABLE_PEER_VERIFICATION_LOCAL=true` on a developer
machine or another explicitly local test environment.
