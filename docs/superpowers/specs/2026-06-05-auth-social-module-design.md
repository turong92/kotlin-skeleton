# Auth Social Module Design

## Goal

Add social login as a composable skeleton capability without making the auth core or executable app depend on every provider detail.

The user should decide at a coarse capability level:

- Include `modules/auth` for stateless authentication.
- Include `modules/auth-social` when social login is needed.
- Enable or wire specific providers inside `auth-social` as needed.

Google, Kakao, and Naver start as provider packages inside `auth-social`, not separate Gradle modules. They can be promoted later only if provider SDKs, credentials, tests, or runtime behavior become large enough to justify new module boundaries.

## Current Baseline

`modules/auth` already owns:

- `CurrentPrincipal`
- account lookup abstraction
- password login
- JWT issuing and authentication
- stateless Spring Security integration
- local/dev header login
- production break-glass access
- overridable Spring Boot auto-configuration defaults

Social login must reuse these contracts rather than create a second auth model.

## Target Module Layout

```text
modules/
  auth/
    security/
    principal/
    jwt/
    password/
    sessionless/

  auth-social/
    oauth/
    providers/
      google/
      kakao/
      naver/
```

The package names can follow the existing Kotlin package root:

```text
modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/
  oauth/
  providers/google/
  providers/kakao/
  providers/naver/
  config/
  api/
```

## Responsibilities

### modules/auth

Auth core remains provider-neutral.

Responsibilities:

- Current principal contract.
- JWT token service.
- Password login.
- Security filters and shared auth error behavior.
- Account lookup and default seed account repository.
- Dev-login and break-glass access.
- Auto-configuration defaults that can be replaced by app beans.

`auth` must not know about Google, Kakao, Naver, OAuth authorization-code exchange, or social account-link persistence.

### modules/auth-social

Social login capability.

Responsibilities:

- Provider-neutral OAuth/social login contracts.
- Provider registry.
- Social login endpoint.
- Mapping provider identity to internal account identity.
- Default in-memory account-link implementation for skeleton development.
- Provider packages for Google, Kakao, and Naver.
- Auto-configuration that activates only when `auth-social` is on the classpath and provider settings are enabled.

`auth-social` depends on `auth` and `platform`.

It must not define a separate principal, token format, or security chain. Successful social login issues the same JWT response shape as password login.

### apps/api

The app composes modules.

Responsibilities:

- Depend on `modules/auth-social` when social login is wanted.
- Provide real `OAuthAccountLinkRepository` or account provisioning policy when moving beyond skeleton defaults.
- Provide provider credentials through external configuration.

The app should not call provider HTTP clients directly.

## Provider Boundary

Provider code starts inside `auth-social/providers`.

```text
providers/google/
  GoogleOAuthClient
  GoogleOAuthProperties
  GoogleOAuthProvider

providers/kakao/
  KakaoOAuthClient
  KakaoOAuthProperties
  KakaoOAuthProvider

providers/naver/
  NaverOAuthClient
  NaverOAuthProperties
  NaverOAuthProvider
```

Provider packages implement a shared contract from `oauth/`. They do not redefine account linking, JWT issuing, or controller response types.

Provider packages can become separate Gradle modules later if one of these becomes true:

- They require a heavy provider SDK.
- They need provider-specific test fixtures or mocks that are not useful to other providers.
- They introduce provider-specific webhook or callback behavior.
- They carry credentials or properties large enough to make dependency selection valuable.
- Users commonly want one provider without shipping classes for the others.

Until then, keeping them in one `auth-social` module is easier to use and matches the coarse-grained skeleton style.

## Core Contracts

`oauth/` should define provider-neutral contracts.

```kotlin
data class OAuthLoginRequest(
    val provider: String,
    val authorizationCode: String,
    val redirectUri: String? = null,
)

data class OAuthUserProfile(
    val provider: String,
    val providerUserId: String,
    val email: String?,
    val username: String?,
    val displayName: String?,
)

interface OAuthProvider {
    val providerId: String
    fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile
}

interface OAuthAccountLinkRepository {
    fun findAccountId(provider: String, providerUserId: String): String?
}

interface OAuthAccountProvisioningPolicy {
    fun resolveOrCreateAccount(profile: OAuthUserProfile): String?
}
```

Implementation may add small helper DTOs, but the public boundaries stay the same:

- Provider modules turn provider credentials/code into an `OAuthUserProfile`.
- Account-linking maps `provider + providerUserId` to internal `accountId`.
- Auth core loads the internal account and issues the JWT.

## HTTP API

Start with one backend endpoint:

```text
POST /api/v1/auth/social/{provider}/login
```

Request:

```json
{
  "authorizationCode": "...",
  "redirectUri": "http://localhost:3000/auth/callback/google"
}
```

Response should reuse the password-login response shape:

```json
{
  "accessToken": "...",
  "tokenType": "Bearer",
  "expiresAt": "...",
  "principal": {
    "accountId": "acc_user",
    "username": "user",
    "email": "user@example.com",
    "roles": ["USER"]
  }
}
```

This endpoint assumes the frontend obtains the authorization code. The backend exchanges the code and verifies the profile through the provider package.

## Login Flow

```text
Frontend
  -> provider login page
  -> receives authorization code
  -> POST /api/v1/auth/social/{provider}/login

auth-social
  -> select OAuthProvider by provider path
  -> exchange code and fetch profile
  -> find linked internal account by provider + providerUserId
  -> if not linked, apply provisioning policy
  -> load AuthAccount through AuthAccountRepository
  -> issue JWT through JwtTokenService
  -> return AuthTokenResponse
```

The default skeleton policy can be conservative:

- If a link exists, login succeeds.
- If no link exists, return a clear `409 ApiError`.
- Auto-provisioning can be added as an explicit policy later, not as an implicit default.

This avoids quietly creating production accounts before account lifecycle rules are designed.

## Configuration

Use Spring Boot typed properties. YAML remains thin and environment-specific.

Example:

```yaml
skeleton:
  auth-social:
    providers:
      google:
        enabled: true
        client-id: ${GOOGLE_OAUTH_CLIENT_ID}
        client-secret: ${GOOGLE_OAUTH_CLIENT_SECRET}
      kakao:
        enabled: false
        client-id: ${KAKAO_OAUTH_CLIENT_ID:}
        client-secret: ${KAKAO_OAUTH_CLIENT_SECRET:}
      naver:
        enabled: false
        client-id: ${NAVER_OAUTH_CLIENT_ID:}
        client-secret: ${NAVER_OAUTH_CLIENT_SECRET:}
```

Keep in code:

- Provider IDs: `google`, `kakao`, `naver`.
- Standard endpoint paths.
- Provider contract interfaces.
- Common error mapping.

External configuration supplies:

- Client IDs.
- Client secrets.
- Redirect URIs if deployment-specific.
- Provider enablement.
- Optional provider API base URLs for tests or local mocks.

## Error Handling

All failures return the existing `ApiError` shape with trace/span fields.

Statuses:

- Unknown provider: `404`.
- Disabled provider: `404`.
- Invalid authorization code: `401`.
- Provider API failure: `502`.
- No linked account and no provisioning policy: `409`.
- Linked internal account not found: `409`, treated as a stale account link.

Do not expose provider access tokens, refresh tokens, or raw provider error payloads in responses.

## Testing

First implementation should avoid live provider calls.

Required tests:

- `auth-social` unit tests for provider registry selection.
- `auth-social` service tests for linked account success.
- `auth-social` service tests for no linked account.
- `apps/api` integration test for `POST /api/v1/auth/social/{provider}/login` with a fake provider bean.
- Tests proving roles come from `AuthAccountRepository`, not from provider profile.
- Tests proving disabled providers cannot log in.

Provider-specific HTTP client tests can use fake servers or mocked provider clients. Live Google/Kakao/Naver credentials are not required for skeleton CI.

## Non-Goals

Do not implement these in the first slice:

- Full OAuth provider production clients for Google, Kakao, and Naver all at once.
- Refresh token rotation.
- Provider unlink.
- Account merge UI.
- Signup onboarding UI.
- Payment integration.
- Frontend protected route work.
- Separate Gradle modules for each provider.

## First Implementation Slice

Recommended first slice:

1. Add `modules/auth-social`.
2. Add provider-neutral contracts under `oauth/`.
3. Add fake/test provider support.
4. Add social login service and endpoint.
5. Add default in-memory account-link repository for tests and local skeleton use.
6. Add app integration tests proving successful login returns the same JWT response as password login.

After that, add one real provider package. Google is the cleanest first real provider because it is the most standard OAuth/OIDC reference. Kakao can follow once the shared flow proves stable.

## Provider Implementation Order

The first implementation slice uses a fake/test provider only. This keeps the module boundary, endpoint, account-linking, and JWT reuse testable without live provider credentials.

The first real provider after that is Google by default because it is the cleanest OAuth/OIDC reference provider. Kakao follows as the first domestic provider. Naver follows once the common provider contract has survived two real implementations.

Changing the first real provider to Kakao does not change the module structure or first fake-provider implementation slice.
