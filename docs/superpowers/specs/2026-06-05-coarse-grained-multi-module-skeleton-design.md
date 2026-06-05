# Coarse-Grained Multi-Module Skeleton Design

## Goal

Build the skeleton as a coarse-grained, composable platform rather than a full-option application template.

The user should choose capabilities, not tiny implementation fragments. Internal code can be split into focused packages, but Gradle modules should represent clear product-level choices such as `platform`, `auth`, and `payment`.

## Design Principles

- The default skeleton remains a runnable minimal API server.
- Gradle modules are capability and dependency selection boundaries.
- Packages inside a module are implementation boundaries.
- Features that are usually used together stay in one module.
- Vendor/provider integrations become separate modules when they bring distinct SDKs, credentials, webhooks, or environment settings.
- Provider modules implement contracts from their parent capability module; they do not redefine the domain.
- If a module is not included, it must not affect startup, configuration, or security behavior.

## Target Layout

```text
kotlin-skeleton/
  apps/
    api/

  modules/
    platform/
    auth/
    payment/
    payment-toss/
    payment-stripe/
```

For the first implementation slice, only `apps/api`, `modules/platform`, and `modules/auth` are required. Payment modules are part of the design target and should be introduced later with the same rules.

## Module Responsibilities

### apps/api

The only executable Spring Boot application.

Responsibilities:

- Compose selected modules.
- Own application entrypoint and final runtime configuration.
- Expose the selected HTTP API surface.
- Keep domain/provider implementation out of the app module where possible.

### modules/platform

Shared foundation used by most applications.

Responsibilities:

- Web configuration.
- Global error handling and `ApiError`.
- Observability: trace context, request logging, MDC log context.
- Common Jackson and validation behavior.
- Common actuator and health configuration when added.
- Test fixtures only if they are broadly useful.

`platform` must not contain login, payment, business domain, or provider-specific logic.

### modules/auth

Authentication and stateless security capability.

Responsibilities:

- Stateless Spring Security setup.
- Current principal model.
- Public endpoint registration.
- Login request/response contracts.
- JWT issuing and verification.
- Password login option.
- Refresh token policy when implemented.
- Auth-specific errors and tests.
- OAuth abstractions if needed later.

`auth` should not be split into `auth-jwt`, `auth-password`, or `auth-core` Gradle modules at the start. Those are internal packages because users should not need to decide that finely.

Suggested package layout:

```text
modules/auth/src/main/kotlin/.../auth/
  config/
  principal/
  security/
  jwt/
  password/
  oauth/
```

Provider-specific social login can start inside `auth/oauth/providers` if it is lightweight. If Google/Kakao/Naver integrations grow distinct SDK/config surfaces, they can be promoted to separate provider modules such as `auth-social-google`.

### modules/payment

Provider-neutral payment capability.

Responsibilities:

- Payment intent and common payment state.
- Idempotency contracts.
- Provider-neutral webhook/event model.
- `PaymentProviderPort`.
- `PaymentProviderRegistry`.
- `PaymentRoutingPolicy`.
- Common payment endpoints and services.

`payment` describes how this skeleton thinks about payments. It must not know Toss or Stripe API details.

### modules/payment-toss and modules/payment-stripe

Provider adapters for payment.

Responsibilities:

- Implement `PaymentProviderPort`.
- Own provider API clients.
- Own provider credentials/properties.
- Verify provider webhooks.
- Map provider-specific statuses into common `PaymentStatus`.
- Register themselves through auto-configuration when the module is included.

Example routing:

```text
KRW or domestic payment -> toss
USD/EUR or overseas payment -> stripe
subscription payment -> stripe
```

The app should call `PaymentService`, not Toss or Stripe clients directly.

## Auth-First Sequence

Login is the right first capability after multi-module foundation, but it should not be implemented before the module structure exists.

Recommended sequence:

1. Convert the current single app into `apps/api`.
2. Move existing web/error/observability code into `modules/platform`.
3. Add `modules/auth` as a coarse capability module.
4. Wire `apps/api` to depend on `platform` and `auth`.
5. Implement stateless login in `auth`.
6. Add integration tests at the `apps/api` boundary.

The first auth implementation should prioritize stateless JWT login and current-user resolution. OAuth providers should wait until the auth contracts are stable.

## Dependency Direction

```text
apps/api -> modules/platform
apps/api -> modules/auth
apps/api -> modules/payment
apps/api -> modules/payment-toss

modules/auth -> modules/platform
modules/payment -> modules/platform
modules/payment-toss -> modules/payment
modules/payment-stripe -> modules/payment
```

Provider modules depend on the parent capability module. Parent capability modules do not depend on provider modules.

## Configuration Style

Use Spring Boot's standard model: code owns defaults and structure, while external configuration supplies environment-specific values.

Capability modules should expose typed properties under `skeleton.*` through `@ConfigurationProperties`. YAML should stay thin and should not become a large domain-specific language. Module behavior should be enabled by dependency composition and auto-configuration first, then refined by properties when runtime environments differ.

Use external configuration for:

- Secrets, credentials, and URLs.
- Token TTLs, issuers, and public origins.
- Feature enablement when a dependency is present but should be disabled in a profile.
- Provider routing rules that vary by deployment.
- Operational thresholds such as slow request duration.

Keep in code:

- Default beans and default policies.
- Security filter chain structure.
- Provider contracts and status mapping.
- Standard endpoint paths.
- Common domestic/overseas payment routing presets.
- Validation and error mapping rules.

This keeps local skeleton startup predictable while still allowing production overrides through YAML, properties, environment variables, or command-line arguments.

Example auth properties:

```yaml
skeleton:
  auth:
    enabled: true
    jwt:
      issuer: kotlin-skeleton
      access-token-ttl: 15m
      refresh-token-ttl: 14d
```

Payment routing should start with code presets, not mandatory YAML. For example, the payment module can provide a default routing policy where KRW routes to Toss and USD/EUR routes to Stripe when both provider modules are present.

Advanced payment routing can be exposed later as external configuration:

```yaml
skeleton:
  payment:
    default-provider: toss
    routes:
      - when:
          currency: KRW
        provider: toss
      - when:
          currency: USD,EUR
        provider: stripe
```

Provider-specific configuration remains under provider-specific namespaces.

```yaml
skeleton:
  payment-toss:
    secret-key: ${TOSS_SECRET_KEY:}
  payment-stripe:
    secret-key: ${STRIPE_SECRET_KEY:}
```

## Error Handling

All modules should use the platform error model.

- Public HTTP errors return `ApiError`.
- Auth errors should map cleanly to `401` and `403`.
- Payment provider errors should map to common payment exceptions before becoming HTTP errors.
- Trace and span identifiers remain available in error responses and logs.

## Testing Strategy

- `modules/platform`: unit tests for trace/log/error helpers and focused MVC tests for platform behavior.
- `modules/auth`: unit tests for token and principal behavior, plus MVC/security tests.
- `apps/api`: integration tests that verify module composition and real HTTP behavior.
- Provider modules: contract tests against the provider port plus mocked external clients.

The app boundary tests are the source of truth for whether the skeleton is usable after modules are composed.

## Initial Implementation Scope

Version `v1.2.0` should implement the multi-module foundation only:

- Create `apps/api`.
- Create `modules/platform`.
- Move current trace/log/error/web code into `platform`.
- Keep current `/api/v1/hello` behavior working through `apps/api`.
- Add `modules/auth` as a module with structure and minimal contracts, but do not complete login yet unless the foundation is stable.
- Update README/CHANGELOG with module selection rules.

Version `v1.3.0` should implement stateless login:

- Add Spring Security stateless configuration.
- Add JWT issue/verify behavior.
- Add password login path.
- Add current user endpoint.
- Add FE protected-route/auth-client follow-up in the React skeleton.

Payment modules should start after auth proves the module pattern.
