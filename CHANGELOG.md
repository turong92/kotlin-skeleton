# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Flaky tests under shared-host load — two root causes, both ours
- **`ExternalHttpClientTest` `PrematureCloseException` / stray 404**: test servers bound the wildcard address (`InetSocketAddress(0)`); on macOS that succeeds even when another process listens on the same port at 127.0.0.1, and connections to 127.0.0.1 then reach the other process. Reproduced with a loop of the same setUp / one GET / tearDown under 10 busy loops: **9 failures in 9300 iterations (wildcard; kinds: PrematureCloseException, ExternalHttpStatusException 404, UnsupportedMediaType) → 0 in 6000 (127.0.0.1)**; no port was ever reused, so pooled-connection reuse is ruled out. Fixed in the five tests that used the wildcard (platform, json, google/kakao/naver); `TestServerRulesTest` forbids the shape.
- **Testcontainers Ryuk initialisation error (`notification-jdbc`, recipe-community)**: Ryuk's start-up wait defaults to 30 s and a busy host needs longer (observed `ryuk started in PT30.35S`); the first DB test then dies with `ExceptionInInitializerError` and every later one with `NoClassDefFoundError` (34/34). Root `build.gradle.kts` sets `TESTCONTAINERS_RYUK_CONTAINER_TIMEOUT=120` for every Test task (`TestcontainersTimeoutsTest`); `DbTestDatabase` containers are `by lazy`. Not reproduced naturally on a quiet host (Ryuk starts in ~1 s); the chain was reproduced by forcing the wait to 0 (`Could not connect to Ryuk`, 4/4 failed) and is gone with the build setting (see docs/testing.md).

### DB not ready at startup (found in a real homeserver deploy)
- `persistence-jdbc` `DatabaseUnavailableFailureAnalyzer`: a connection failure while starting (refused / unknown host / server starting up / timeout) is reported as `Cannot connect to the database at <host>:<port> during startup: …` plus the likely causes (DB still starting, wrong `SPRING_DATASOURCE_URL`, network) — never credentials; the missing-dialect analyzer stays separate.
- Opt-in `skeleton.persistence-jdbc.startup-wait.{enabled,timeout,interval}` (module default off) retries the first connection before the context starts; `apps/api` and `apps/sample` enable it (60s / 2s; their test configs disable it). `scripts/test-deploy-contract.sh` A2 starts the app before the DB exists: it comes up without a restart once the DB appears, and fails with the readable message (no URL credentials) when it never does. See docs/deploy.md §3.

### BREAKING (unreleased) — module tables lose the `skeleton_` prefix (owner decision; full old → new list: `docs/table-names.md`)
- Every module table, index, constraint and migration description drops exactly the `skeleton_` token: `skeleton_accounts` → `accounts`, `skeleton_account_identities` → `account_identities`, `skeleton_auth_sessions` → `auth_sessions`, `skeleton_auth_refresh_tokens` → `auth_refresh_tokens`, `skeleton_boards` / `skeleton_board_*` → `boards` / `board_*`, `skeleton_jobs` → `jobs`, `skeleton_alerts` → `alerts`, `skeleton_notification_inbox` → `notification_inbox`, `idx_skeleton_x` → `idx_x`, `uq_skeleton_x` → `uq_x`. No name deviates from "prefix removed"; none is a reserved word in PostgreSQL or MySQL 8.4.
- Nothing was deployed, so the migration files were edited **in place on both dialects** (files renamed to the new description, versions unchanged) — no rename migrations. A local database that applied the old files: `flywayClean` / recreate it.
- **Projects stamped earlier** (they keep the old tables and cannot edit applied migrations) need rename migrations: one `alter table … rename to …` / `alter index … rename to …` / `alter table … rename constraint …` per row of `docs/table-names.md` (MySQL: `rename table`, `alter table … rename index`), then take this version of the module sources (repositories use the new names).
- `scripts/rename-skeleton.sh`: the `skeleton_jobs` exception is gone; the leftover scan now fails on any remaining `skeleton_` identifier in SQL/Kotlin; the refresh cookie default `skeleton_refresh` (not a table) becomes `<prefix>_refresh` in a stamped project. `MigrationFileRules` (`RepositoryMigrationsTest`) fails the build when a migration name or description carries `skeleton_` again. `scripts/test-new-project.sh` checks both.
- jOOQ: generated table classes are `Jobs`, `Accounts`, … (no `Skeleton` prefix).

### Global social login — LINE · X · generic OIDC, PKCE end to end (contract: `docs/account-http-contract.md` "FINAL-3 + social PKCE")
- **PKCE (S256) + nonce in `auth-social`**: providers declare `pkce` / `nonce` (`REQUIRED | SUPPORTED | UNSUPPORTED`); `codeVerifier` / `nonce` ride on `POST /auth/social/{provider}/login`, `POST /account/identities/social/{provider}` and every `socialReauth`; checked BEFORE the provider (and before a re-auth proof) is touched: `400 AUTH.SOCIAL_PKCE_FAILED` / `AUTH.SOCIAL_NONCE_FAILED`, new `401 AUTH.SOCIAL_ID_TOKEN_INVALID`. `GET /auth/methods` social entries gain `pkce`, `nonce`, `authorize{url,scopes,params}`. `OAuthProvider` gains `pkce`, `nonce`, `autoEnabled`, `publicClientId`, `publicRedirectUri`, `authorize`, `fetchProfile(OAuthCodeExchange)` (all defaulted — existing providers unchanged); `OAuthUserProfile.avatarUrl`; `OAuthTokenErrors` classifies token endpoint errors. Google: `pkce=SUPPORTED` (forwards `code_verifier`; web-client enforcement is 확인 필요).
- **`auth-social-oidc`** (new): generic OpenID Connect provider from properties — several providers per instance, discovery (cached, stale-on-error, startup validation only for enabled providers), explicit endpoints, JWKS cache with key rotation + refresh cooldown, ID token validation (RS256/ES256/HS256 allow list, `iss`, `aud`/`azp`, `exp`/`nbf` + skew, `nonce`), userinfo fallback, claim mapping, `email-trust`. **LINE preset** (`providers.line.client-id` / `client-secret` only): HS256 web-login tokens verified with the channel secret, PKCE + nonce required, email never treated as verified.
- **`auth-social-x`** (new): X OAuth 2.0 Authorization Code + PKCE (required), confidential client (Basic), `GET /2/users/me`, optional `users.email` / `confirmed_email` (never verified by default), 429 with `x-rate-limit-reset`.
- **`account`**: one-letter sign-in method codes are allowed (`x`); `SocialReauth` / `LinkSocialRequest` / `SocialReauthRequest` carry `codeVerifier` + `nonce`; address-less LINE/X accounts live the whole lifecycle (`GlobalSocialJourneyIntegrationTest`).
- **Wiring**: `apps/sample` gets google + oidc + x (provider entries exist only when a client id is configured; `SocialDefaultsIntegrationTest`), capability entries + decision row, `docs/deploy.md` §7 rows, module pages with console checklists, `.env.example`, `scripts/test-new-project.sh` section 7b.

### Mail templates + real-provider readiness (2026-10-07)
- **HTML account mails**: every account mail kind (12) now ships a table-based, inline-CSS HTML alternative next to the text part (ko/en): large monospace code block for code mails, one button + raw URL for link mails, preheader, footer ("ignore if you did not request this", service name, support address). Brand by configuration only: `skeleton.account.mail.brand.{service-name,logo-url,accent-color,support-address,footer}`, `html-enabled=false` = text only. SPI: `AccountMailLayout` (frame; receives raw values and escapes itself) and `AccountMailTemplates` (copy). Hostile values are escaped (property-style test), placeholders are expanded in one pass, subject/recipient/reply-to line breaks are rejected (`MailMessage`) or flattened (subject). Golden files (`src/test/resources/mail-golden`, `UPDATE_GOLDEN=1`), `./gradlew :modules:account:mailPreview`, a real SMTP round trip against mailpit (`SmtpMailSenderMailpitTest`, skipped when mailpit is absent).
- **Fix — documented social env var names did not bind**: `<P>_AUTH_SOCIAL_PROVIDERS_<X>_CLIENT_ID|CLIENT_SECRET|REDIRECT_URI` (docs/deploy.md, `secrets:`) never reached `AuthSocialProperties` (Map values + env underscores) so a deploy booted with `clientId must not be blank`. `AuthSocialEnvironmentAliasPostProcessor` rewrites those names to dotted properties.
- Social token-exchange rejections log `provider/status/error/error_code` (never the code or secret) — the user only sees `INVALID_AUTHORIZATION_CODE`. Startup log line `account sign-in methods: …; social: …; mail: …`.
- `apps/sample` adds `auth-social-google|kakao|naver` (off until `SKELETON_AUTH_SOCIAL_PROVIDERS_<X>_ENABLED=true`); `scripts/dev-sample.sh` reads `.env.local`; `scripts/check-real-providers.sh`; guide `docs/real-provider-setup.md`.

### Legal documents and consent records — new modules `legal` + `legal-jdbc` (HTTP contract `docs/legal-http-contract.md`, model `docs/legal.md`)
- **`legal`**: document types are string codes (`terms`, `privacy` by default; apps add `marketing`, `age`, `refund` …); the app ships `manifest.json` + `<type>/<version>.<locale>.md` (`skeleton.legal.location`, default `classpath:legal/`); versions have an effective date and a status (`DRAFT` / `REVIEWED`); `{{fact}}` placeholders are filled from `skeleton.legal.facts.*`; a safe markdown subset is validated at start (no raw HTML, images, unsafe link schemes); the hash is sha256 of the normalised source per locale. Without a manifest the module serves its own **TEMPLATE** ko/en documents (`terms`, `privacy`, `marketing`) which a `DeployGuard` refuses in stage/prod unless replaced or acknowledged (`skeleton.legal.acknowledge-template=true`); the guard also refuses a `DRAFT` with an effective date and missing facts (names only, never values).
- **Hash pinning**: `REVIEWED` versions must declare their hashes; an append-only ledger (`legal_document_versions`, trigger-protected) pins every (type, version, locale) at first start — a published text/effective date that changes, a published version that disappears, or a draft promoted in place fails the start with a readable message that names the version and says "add a new version".
- **Consent records**: append-only events keyed by **subject** (`subjectType` + `subjectId`, default `account` + account id) with optional `referenceId` (an order): type, version, content hash, locale shown, time, source (`sign-up` / `consent` / `re-consent` / `checkout` / `withdraw`), client IP and user agent (configurable; scrubbed after `record.personal-data-retention`, default 365d). `requireCurrent(subject, types)`, idempotent `record` (sequence number in the unique key — 16 racing threads leave one row, tested on PostgreSQL and MySQL), withdrawal of optional documents as its own event, previous-version grace window (`previous-version-grace`, default 0), per-subject daily cap, history / admin / export, `AccountErasureListener` (default `ANONYMIZE`: tombstone subject, no IP/UA, proof kept; `DELETE` removes the rows after anonymising).
- **HTTP** (`/api/v1/legal`, opt-in like other modules): public `GET /documents` · `GET /documents/{type}?version=&locale=` (ETag, `Cache-Control: public`), signed-in `GET /consents/me` · `POST /consents` · `POST /consents/{type}/withdraw` · `GET /consents/me/history`, admin `GET /admin/consents` · `GET /admin/documents`. Opt-in re-consent filter (`skeleton.legal.reconsent.enabled`) answers `403 LEGAL.RECONSENT_REQUIRED` with the missing list on real endpoints (legal/auth/account excluded).
- **`account` bridge without a dependency either way**: `SignUpConsentGate` (new, in `platform`) — `POST /account/sign-up` may carry `consents: [{type, version, locale?}]` (max 8); with `legal` present a missing/stale/unknown claim is `400 LEGAL.CONSENT_REQUIRED` (checked from the request body only, so the answer is the same for new / in-flight / registered addresses); the claims ride on the sign-up attempt and are recorded when the code is verified, **in the same transaction as the account** (`AccountTransaction`, new in `account`, implemented by `account-jdbc`). Social / magic-link first sign-ins have no consent at creation: the re-consent filter and `GET /consents/me` make the frontend collect it before the session is usable (documented in the contract).
- **Wiring**: `apps/api` (starter) and `apps/sample` carry `legal-jdbc` (turn-key: terms + privacy + (sample) marketing documents, sign-up consent, re-consent on); the starter's sign-up now needs `consents` for `terms` + `privacy` (`template-1`); `apps/workbench` has it on the class path with the auto-configuration off (like `account`). Local profiles switch the filter off (seed accounts never consented). Deploy: `docs/deploy.md` §10 and `scripts/test-deploy-contract.sh` D step (acknowledge the template for the trial deploy; without it the guard names `skeleton.legal.acknowledge-template`). MySQL needs `log_bin_trust_function_creators=1` (or no binlog / SUPER) for the trigger migration — set in the MySQL test containers and the `new-project.sh --db mysql` template.
- **Tables** (no `skeleton_` prefix, owner decision): `legal_consents`, `legal_document_versions`, migration `V20261006174411__legal.sql` for both dialects.
- **Tests**: `modules/legal` (unit, MockMvc, auto-configuration, `noOptionalTest` without Spring Security), `modules/legal-jdbc` `dbTest` (PostgreSQL + MySQL), `modules/account` `SignUpConsentTest` / `SignUpConsentWebTest`, `account-jdbc` `JdbcAccountTransactionDbTest`, `apps/api` `LegalStarterIntegrationTest`, `apps/sample` `LegalJourneyIntegrationTest`.

### Account — confirmation review of the verification-code design (2026-10-07; HTTP contract unchanged, see "FINAL-3 clarifications")
- **C1 email-change codes share the per-address buckets of sign-up**: open (`signup:email`, 5/h), mail (`verification:email`, 3/h) and a new guess counter (`verification.guesses-per-email`, default 40/h, `guess:email`) are per TARGET address across sign-up attempts and email-change challenges of any number of accounts -> at most 40 guesses / hour / address (0.004 % per hour, median ~2 years to a hit; was 25 per account per hour). An over-budget email change is answered and displayed identically and stores a challenge no code can win. `email/change/confirm` is limited per IP (same bucket as `verify-email`) and per address. An email change no longer triggers the first-admin bootstrap.
- **I1** a used refresh token goes to the reuse rule before the rotation limiter. **I2** resend reissues for an address that has a verified account exactly as for a free one. **I3** no code/link in any mail subject; log-leak tests cover every code/link mail kind through the real mailer including the SMTP failure path. **I4** every per-IP limiter keys on `ClientIps.limitKey` (IPv6 /64): sign-up, resend, verify, forgot, magic link, login, email-change confirm; `LoginAttempt.limitKey`, `PasswordLoginService.login(..., limitKey)`.
- **I5** `AccountRepository.changeEmail(..., expectEmailVerified)` (+ `STALE`) and `addIdentityIfEmailVerified` re-check `email_verified` under the account row lock; the email-change challenge records the state it was requested in; the `proveMailbox` transaction also deletes the account's open codes and the address's sign-up attempts.
- **Minor**: `Locale.ROOT` for codes; session codes check the session before spending an attempt (another session cannot burn the owner's 5 guesses; a new request still replaces the open one - documented); `LOGIN_SUCCESS`/`lastLoginAt` after token issuance (`RegistrationService.recordSignIn`); conditional password-hash upgrade (`AuthAccountRepository.upgradePasswordHash(id, new, old)`, `updateIdentitySecret(..., expectedSecret)`); dead code (`OneTimeTokens.fingerprint/pending`, `TokenRequest`, `clientIp`) and stale comments/yml/doc references removed; third-party sign-up-blocking trade-off documented next to the mail-budget one.
- **Tests**: `EmailChangeAddressBudgetTest`, `ResendOracleTest`, `ClientIpLimitKeyWebTest`, `MagicLinkClientIpKeyTest`, `LateLandingAfterMailboxProofTest`, `VerifiedAccountNeverOverwrittenTest`, `VerifyEmailSignInRecordingWebTest`, HMAC-key wiring, generator structure/uniformity, locale, real `socialReauth` ownership + provider-code replay; the unfailable `gap < 15_000` assertion and the 6-digit-substring log check were replaced; `TestContainerRulesTest` also rejects per-test-instance containers.

### Account — second security review (HTTP contract FINAL-3, see `docs/account-http-contract.md` top)
- **C1 pre-hijack, closed by construction (breaking, unreleased)**: sign-up creates a **sign-up attempt** (own password hash, own 6-digit code, 10 min, 5 guesses), not an account. `POST /account/sign-up` -> 202 `{status, signUpId}`; the mail carries a code (never a link); `POST /auth/verify-email {signUpId, code}` creates the account with the password of THAT attempt, discards the address's other attempts and **signs the browser in** (AuthTokenResponse). Wrong code -> `400 ACCOUNT.CODE_INVALID` (`data.attemptsLeft`), expired/used up/taken -> `410 ACCOUNT.CODE_EXPIRED`. `resend` takes `{signUpId}`. The fingerprint mechanism and the VERIFY_EMAIL link are removed; PENDING rows are no longer created (so a squat can no longer block an email change — the PENDING-TTL minor is moot; `AccountStatus.PENDING_VERIFICATION` stays for legacy rows).
- **Codes instead of links where a session exists**: email change (`POST /account/email/change/confirm {code}`; the public `POST /auth/confirm-email-change` is gone), re-authentication (`confirmationToken` -> `confirmationCode`, mailed to the account address), delete confirmation — all bound to account + session, 5 guesses. Reset and magic link stay links.
- **New tables/ports**: `skeleton_account_challenges` (migration `V20261006143346`, both dialects), `ChallengeStore` (+ `account-jdbc` adapter, in-memory default; DeployGuard problem in protected envs), `CodeHasher` (HMAC-SHA256 with a key derived from the JWT secret — 6 digits cannot be protected by a slow hash).
- **I1 verification-off mode**: `AccountRepository.proveMailbox` — one transaction removes every identity except the proving one (and replaces the password row id), sets the password, verifies; codes/links/sessions closed afterwards. `PasswordService.change` no longer re-creates a deleted password row. DeployGuard **warns** on email-verification=false together with a mailbox-proving method or social merge.
- **I2/I3**: accounts without an email (Naver, unverified-provider sign-ups) re-authenticate with `socialReauth {provider, authorizationCode, redirectUri?}` (a fresh code of a provider already linked to the account) for email change, social link, unlink and delete — so they can delete themselves.
- **Unlink needs re-authentication**: `DELETE /account/identities/{id}` + optional body. The social-link proof is checked before the provider code is exchanged and spent before linking (two concurrent links with one code could both succeed).
- **I5**: `skeleton.auth-session.rotation` (default 30 per 10 min per session, `RateLimitStore`) bounds refresh-token rows; over the limit -> `429 AUTH.TOO_MANY_REFRESHES` (session intact). Theft detection is untouched (used tokens are still remembered for the session's life).
- **I4 client IP**: the platform injects `<PREFIX>_CLIENT_IP_MODE` / `_CLIENT_IP_TRUSTED_PROXIES` (no `WEB_`, lower-case, comma list). `apps/api` and `apps/sample` read exactly those via `application.yml` aliases (the long relaxed names still work); `ClientIpPlatformEnvTest` (also reads the homeserver `app.tf` when it sits above this repo), `docs/deploy.md`, `deploy/app.yaml`, `scripts/test-deploy-contract.sh` corrected. Platform-side items (docker-subnet trust, `CF-Connecting-IP` off the tunnel) are in `docs/client-ip.md`.
- **Idempotency**: `@IdempotentOperation(cacheClientErrors = false)` — `email/change` and `delete` do not store 4xx under the key (a mistyped password no longer sticks); `IdempotencyStore.release` added.
- **Tests that could not fail now can**: `/auth/methods` Cache-Control asserted exactly; the actuator exposure is asserted by a signed-in 404 on `/env` & co and by the exposed endpoint set; `/health` 503 `DOWN` without details at test level; the rotation key derivation has a wiring test.
- **MySQL note**: the accounts/tokens MySQL migration `V20261005223426` was edited in place (`utf8mb4_bin` columns) before any release. No database outside local development has run it, so no follow-up `alter … collate` migration was added. A local DB that already applied the old version: `flyway repair` + re-create the columns, or drop the dev volume (`migration.clean-on-validation-error` does it in the `local` profile).
- **Missing tests added**: Naver never merges; the `proveMailbox` race and both dialects; refresh grace under 16-way concurrency on PostgreSQL and MySQL; log-leak test covers codes and sign-up ids (a real leak was found: `SignUpResponse.toString` at TRACE).

### Account
- **`POST /account/email/change` → `GET /account/me`**: the pending change is stored on the request thread, so `pendingEmail` is visible right after the 202 (it was `null` for ~120-200 ms while the deferred task ran). Only the mails stay deferred. A taken (or own) target address is stored and shown the same way — the state never reveals whether the address exists; no link is mailed to it. Contract §6 updated.

### Catalog / scripts
- `capabilities.json`: `account-jdbc` (`account-lifecycle`) and `auth-session-jdbc` (`session-refresh`) list their react capability and `@skeleton/auth`; `auth-session` lists `account-lifecycle`; the community / saas example commands no longer list starter modules (`auth-social`, `captcha-turnstile`); decision rows no longer carry `--packages auth` (always included). The mirror check against react-skeleton's recipe compares commands without starter modules, so an older react copy stays green.
- `scripts/sample-e2e-backend.sh`: the backend starts in its own session (process group), and `stop`/`status` read the ports and compose project from `build/sample-e2e/state.env` — no need to pass the env again.
- `scripts/new-project.sh` `in_list` (and the test scripts) piped a value into `grep -q` under `set -o pipefail`: grep exits early, the producer dies of SIGPIPE, and the pipeline reports "not found" — `unknown module: notification-jdbc` for a module that exists (seen once in `test-new-project.sh --full`). Here-strings now; `test-new-project.sh` guards against the pattern.

### Test infrastructure — containers, flaky tests (찍은 프로젝트가 가져갈 것은 아래 "복사할 것")
- **Testcontainers: JVM 당 컨테이너 하나.** 앱 · `job-queue-jdbc` · `alert-jdbc` · `persistence-jooq` 의 테스트 설정이 `@Bean @ServiceConnection` 컨테이너였다 → 서로 다른 스프링 컨텍스트마다 컨테이너가 뜨고 컨텍스트 캐시가 JVM 종료까지 붙들었다(찍은 프로젝트에서 `postgres:18` 32 개 · 스왑 95%). 이제 `object SharedPostgres`/`SharedMySql`(static, 한 번) + 컨텍스트마다 새 데이터베이스(`create database`) + `JdbcConnectionDetails` 빈. 앱 코드는 그대로, 컨텍스트마다 Flyway 는 그대로 돈다. `max_connections=500` · PostgreSQL `fsync=off` · 앱 시험 설정 Hikari `minimum-idle: 1`(Ovation 의 같은 수정과 맞춤). 실측(같은 호스트, load 40~75): `:apps:api:test` 동시 컨테이너 5 → 1(컨텍스트 5 → 4: MockMvc 유무만 달랐던 시험을 맞췄다), `:apps:workbench:test` 11 이상(중단 시점, 약 26초에 하나씩 증가) → 1(컨텍스트 21), `job-queue-jdbc` `postgresTest` 2 → 1. 포크는 태스크당 하나라 DB 종류당 컨테이너 1 개.
- **가드**: `TestContainerRulesTest`(platform)가 테스트 소스의 `@ServiceConnection` · 컨테이너 `@Bean` 을 막는다. `scripts/new-project.sh --db mysql` 의 `TestcontainersConfiguration` 도 같은 모양(`SharedMySql`).
- **`OpaqueUrlTokenCodecTest > rejects tampered token` 간헐 실패**: 마지막 base64 글자만 바꾸면 버려지는 비트만 건드려 바이트가 같을 수 있다(무작위 토큰의 약 6%). 서명 · 페이로드 바이트를 뒤집는 시험으로 바꾸고, `OpaqueUrlTokenCodec` · `AesGcmTextEncryptor` 가 **정규형이 아닌 base64url(패딩 · 0 이 아닌 버려진 비트)을 거부**하도록 했다(토큰 가변성 제거 — 한 값에 문자열 하나). JWT 에는 서명 · 페이로드 변조 시험을 더했다.
- **부하에 민감한 시험**(load 60~70 + CPU 부하 8 에서 30회 반복: `ExternalHttpClientTest` 4/30 → 0/30, `ExternalHttpJsonExtensionsTest` 20/30 → 0/30): `ExternalHttpClientTest` · `ExternalHttpJsonExtensionsTest` 의 연결 · 응답 상한을 2 초 → 30 초로, 시간 초과 시험은 `sleep(300)` 대 50ms 경주에서 "서버가 응답을 잡아 둠"으로 바꿨다(예외 종류만 단언). 같은 냄새를 쓸어 고친 것: `AccountResponseLevelSecurityTest`(응답 400ms 이내 · sleep 800/900 → 메일을 래치로 잡아 두고 "요청이 끝났다"로 증명, 메일 도착은 폴링), `AccountTaskRunnerTest`, `redis-lock` · workbench 래치 3초 → 30초. `MagicLinkWebTest` · `AccountWebTest` 는 요청 IP 를 1..250 무작위로 골라, 한도 시험이 채워 둔 고정 IP(.9 등)를 가끔 만나 메일이 없었다(깨끗한 복사본의 build 에서 1회 실패) — 돌아가며 쓰는 .100~.199 로.
- **복사할 것(Ovation 등 찍은 프로젝트)**: ① 앱의 `src/test/…/TestcontainersConfiguration.kt` 를 이 레포 것으로 교체(패키지만 바꾼다) ② `postgresTest`/`mysqlTest` 의 `DbTestcontainers.kt` 가 있으면 같은 방식으로 ③ `modules/platform` 의 `TestContainerRulesTest` ④ `modules/crypto` 의 `CanonicalBase64Url` + 두 호출부 + `OpaqueUrlTokenCodecTest` ⑤ 두 외부 HTTP 시험의 상한 · 래치 ⑥ `docs/testing.md` ⑦ 앱 `src/test/resources/config/application.yml`(Hikari `minimum-idle: 1`). 필요한 의존: 테스트 클래스패스의 `spring-boot-jdbc`(데이터 JDBC 스타터가 가져온다).

### Security review of the account lifecycle (fixes before the first release)
- **Pre-hijack closed**: a mailbox proof (magic link, verified social login) on an unverified account discards the password someone set at sign-up and closes sessions; verification links are bound to the password they were issued for.
- **Exact-match emails**: one normalisation rule (`Emails.normalize`: trim, NFC, lower case), exact comparison after lookup, mail to the stored address, `utf8mb4_bin` on MySQL (`email`, token `subject`); MySQL migrations keep jOOQ-unparsable clauses in `[jooq ignore]` markers (enforced by `MigrationFileRules`).
- **Sensitive actions re-authenticate**: email change, first password and social link need the current password or a mailed `confirmationToken` (`POST /account/reauth/confirmation`); a password change/reset kills pending sensitive links; the account is told of every link and email-change request; admin authorization re-reads the stored account.
- **Sessions**: `SessionEvent`s are wired (reuse -> account event + WARN + alert), the used-token memory lasts as long as the session, ended sessions are purged and erased with the account; refresh `reuse-grace` gives idempotent rotation (apps set 10s).
- **Deploy**: client IP mode guard (platform injects `<PREFIX>_WEB_CLIENT_IP_*`), honest `/health`, blank JWT secret is a named guard problem, local image + `secrets:`/`.local.env` documented, GHCR workflow on hold.
- New: `GET /auth/methods`, `me.pendingEmail`, last-administrator guard is atomic, restore/purge exclusive, social merge on a verified provider email (apps opt in).
- Capability catalog merged (`capabilities.json`, `llms.txt`) with entries for the account modules.


### 계정 수명주기 — account · auth-session · auth-magic-link (2026-10-06)

도메인과 메일 설정만 바꿔 켜기만 해도 가입 · 로그인 · 비밀번호 찾기 · 세션 관리 · 탈퇴가 있는 서비스 구색이 나온다. 전체: `docs/accounts.md` (흐름 · 새 로그인 수단 더하기 · 위협 모델과 각 행을 덮는 시험 · HTTP 계약).

- **새 모듈**: `account`(+`account-jdbc`) · `auth-session`(+`auth-session-jdbc`) · `auth-magic-link`. 선택 통합은 `account` 의 `compileOnly`(`notification-mail` · `captcha-turnstile` · `alert` · `idempotency` · `auth-social` · `job-queue-jdbc`)이고 `noOptionalTest` 가 없는 클래스패스를 증명한다.
- **로그인 수단 추상화**: 계정 하나에 `identities` 행(`method` 문자열 + `subject`) — 비밀번호 · 각 소셜 제공자 · 매직 링크가 같은 표의 행이다. `SignInMethod` 빈 + `AccountSignInService.signIn` 이면 새 수단이 스키마 · 계정 모듈 변경 없이 붙는다. 소셜은 제공자가 확인한 이메일만 믿고 기본은 병합하지 않는다(`409 ACCOUNT.SOCIAL_EMAIL_CONFLICT`); 마지막 수단은 못 뗀다.
- **가입 · 확인 · 재설정 · 변경**: 응답은 계정 유무와 무관(늘 202), 조회 · 토큰 · 메일은 요청 스레드 밖. 한 번 쓰는 링크는 해시만 저장 · 원자적 소비 · 용도 구분. 이메일 변경은 새 주소 확인 전엔 불변(옛 주소에 알림). 비밀번호는 델리게이팅 인코더(기본 bcrypt, 로그인 때 업그레이드, argon2 opt-in), 정책 설정 가능 + 유출 확인 선택 고리(`BreachedPasswordCheck`, 기본은 네트워크 호출 없음).
- **세션**: 리프레시 토큰 회전 + 재사용 탐지(가족 철회) · 기기 · IP · 마지막 사용 목록 · 철회 · 로그아웃. 전달 기본 body(CSRF 면 없음), cookie 는 opt-in(HttpOnly · Secure · SameSite=Strict + 헤더). 액세스 토큰은 그대로 JWT(`sid` 클레임 추가).
- **삭제**: 다시 인증 → 유예(30일) → 지우기(주기 또는 `job-queue-jdbc` 잡). 다른 모듈은 platform 의 `AccountErasureListener` 로 — **board**(작성자 → "삭제된 사용자", `authorDeleted`) · **notification-jdbc**(받은편지함). 데이터 내보내기는 `AccountDataExporter` 인터페이스만.
- **남용 대응**: 로그인 시도 제한(IP · 식별자, 없는 계정도 같게) · 캡차 고리(`captcha-turnstile`) · `AccountEvent*` 와 감사 표(선택) · `alert` 경보(제한 · 리프레시 재사용) · 운영자 도구(정지 · 역할, 선택 HTTP) · 안전한 첫 관리자(확인된 이메일 + ADMIN 부재일 때만).
- **`auth` 확장(선택 고리, 단독 사용은 그대로)**: `LoginHooks` · `LoginSessionIssuer` · `SessionRevoker` · `AuthAccount.loginBlock` · 계정이 없어도 해시 비교 한 번 · `upgradePasswordHash`. `AuthTokenResponse` 에 `refreshToken` · `refreshExpiresAt` · `sessionId`(있을 때만).
- **비밀이 로그에**: 비밀번호 · 토큰을 담은 요청/응답 DTO · `Identity` · `AuthAccount` 의 `toString` 을 가렸다 (실측: Spring MVC 가 DEBUG · TRACE 에서 요청 본문을 `toString` 으로 찍는다) — `PasswordLoginRequest` 포함.
- **배포 가드**: `account` · `auth-session` 이 `DeployGuard` 를 낸다 — 메모리 저장소 · 메일 길 없음 · 링크 주소 비어 있음 · 링크 로그 · 시드 계정 · 비보안 쿠키는 stage · prod 에서 기동 실패. `skeleton.env` 는 그대로 옵트인.
- **조립**: `apps/api`(스타터)에 `account-jdbc` · `auth-session-jdbc` — 가입 · 로그인 · 새로고침 · 삭제가 기본으로 돈다(메일은 `--modules notification-mail`). 시드 계정(user · admin · moderator)은 `skeleton.account.seed.accounts` 로 로컬 프로필 · 시험에서만. `apps/sample` 은 진짜 가입 · 로그인 · 메일(compose `mail` 프로필) · 매직 링크 · 삭제까지 + 전 구간 통합 시험. `apps/workbench` 는 시드 인증을 그대로 두고 새 모듈의 자동설정을 끈다(문서 스니펫 · 클래스패스 시험은 유지).
- **실측으로 잡은 것**: PostgreSQL 은 `(:p is null or id <> :p)` 의 타입 없는 null 매개변수를 못 정한다(`revokeAll` — 새로 쓴 DB 시험이 먼저 빨갰다); MySQL 의 기본 정렬은 대소문자를 구분하지 않아 로그인 수단 `subject` 는 `utf8mb4_bin`; 로그인 한도가 같은 컨텍스트에서 매 시험마다 로그인하는 통합 시험을 스스로 막았다(시험 · 로컬 e2e 는 한도를 넉넉히 설정).
- 문서: `docs/accounts.md`, `docs/modules/{account,account-jdbc,auth-session,auth-session-jdbc,auth-magic-link}.md`, `docs/config/modules/{account,auth-session,auth-magic-link}.yml`, `docs/deploy.md` · `deploy/app.yaml`(비밀 · 가드), `docs/errors.md`, `docs/minimal-composition.md`.

### 홈서버 배포 계약 · 배포 가드 (2026-10-06)

앱을 **이미지 하나 + 선언 한 장**으로 내놓는다. 운영용 compose · Caddyfile · cloudflared · 백업 선언은 두지 않는다(플랫폼이 선언에서 만든다). 전체: `docs/deploy.md`.

- **`Dockerfile`**: `ARG APP=api|sample` (워크벤치는 모든 모듈 데모라 빌드를 거부한다), 런타임은 busybox `wget` 이 있는 alpine 그대로(플랫폼 헬스체크 `wget -S -q -O /dev/null http://localhost:<port><health>`), 0.0.0.0:8080, stdout 로깅. `scripts/test-deploy-contract.sh` 가 앱마다 이미지를 빌드해 **계약의 환경변수만** 주어 일회용 postgres 와 띄우고 컨테이너 안 wget 헬스체크 · 다른 컨테이너에서의 도달(0.0.0.0) · 로그 stdout · 쓰지 않는 모듈 설정 비요구 · 보호 환경 가드 실패 화면을 증명한다(CI `.github/workflows/deploy-contract.yml`).
- **`deploy/app.yaml`** (배포 선언 템플릿): "tag 를 올리는 것이 배포", 선택한 모듈이 요구하는 환경변수와 빠졌을 때의 동작. `new-project.sh` 가 name · image(`OWNER` 는 직접) · `env_prefix` · `db` · `redis`(redis 모듈을 골랐으면 true)를 채우고 고르지 않은 모듈의 설명을 지운다. `rename-skeleton.sh` 는 `env_prefix: SKELETON`(밑줄이 없어 기존 규칙이 못 잡던 것)도 바꾼다.
- **`.github/workflows/image.yml`**: `v*` 태그 푸시에서만 GHCR 이미지를 `v<버전>` · `sha-<커밋>` 불변 태그로 올린다(latest 없음, 이미 있는 태그는 덮어쓰지 않고 실패). 스켈레톤 레포(GitHub 템플릿)에서는 `is_template` 조건으로 돌지 않는다 — 스켈레톤의 버전 태그가 앱 이미지를 publish 하지 않게.
- **배포 가드 SPI (`platform` · `common/deploy`)**: `DeployGuard`(`problems()` 는 기동 실패 · `warnings()` 는 로그, 메시지는 환경변수 · 속성 이름만), `DeployGuardRunner`(모든 빈이 만들어진 직후), `DeployGuardFailureAnalyzer`(읽을 수 있는 실패 화면), 기동 로그 요약(액추에이터 없이 가드 목록). 스위치 `skeleton.env`=`local|stage|prod`(환경변수 `SKELETON_ENV`, 찍으면 `<PREFIX>_ENV`)는 **옵트인** — 비우면 중립이고 스프링 프로필을 건드리지 않는다. 모르는 값은 기동 실패(값은 싣지 않는다), `skeleton.deploy.require-env=true` 면 빈 값도 실패, 스위치와 프로필 `prod` 가 어긋나면 경고. 설정 접두사 `skeleton.deploy`(platform).
- **기존 가드를 SPI 로 접었다(규칙 그대로)**: `auth` — `AuthStartupValidator.problems` 한 곳에 규칙이 있고 `AuthDeployGuard` 가 내놓는다(던지는 예외는 `DeployGuardViolationException` ⊂ `IllegalStateException`, 문제가 하나면 메시지는 예전과 같고, 이제 문제를 모두 나열한다). 스위치가 stage · prod 면 프로필 없이도 같은 규칙. `migration` — 규칙은 `MigrationCleanGuardRules`, DB 에 닿기 전 차단은 그대로 `EnvironmentPostProcessor`, 가드 빈(`migration-clean`)은 목록에 같은 규칙을 보인다. `migration` 이 `platform` 에 의존한다.
- **환경변수 접두사 증명**: `RedisEnvironmentVariablesTest`(`SKELETON_REDIS_HOST/PORT/SSL_ENABLED/KEY_PREFIX/PASSWORD`) · `RedisLockEnvironmentVariablesTest`(`…_REDIS_LOCK_ENABLED=false`)가 진짜 `systemEnvironment` 속성 원본 모양으로 바인딩을 본다 — 찍은 프로젝트에서는 rename 이 접두사를 바꿔 같은 테스트가 `<PREFIX>_REDIS_*` 로 돈다(`test-new-project.sh --full` 조합 7). `ProdRedisSslEnvironmentTest`: 워크벤치 prod 프로필의 `redis.ssl.enabled: true` 를 `<PREFIX>_REDIS_SSL_ENABLED=false` 가 이긴다.
- **`JWT_SECRET` 별칭**: `apps/api` · `apps/sample` yml 이 `skeleton.auth.jwt.secret: ${JWT_SECRET:<개발용 기본값>}` 을 읽는다(워크벤치와 같다) — 배포 선언의 `secrets: [JWT_SECRET]` 이 그대로 닿는다. 개발용 기본값은 보호 환경에서 여전히 기동 실패다.
- 문서: `docs/deploy.md`, `docs/modules/platform.md` · `migration.md`, `docs/config/modules/platform.yml`(`skeleton.deploy`), README · CLAUDE.md.

### Ovation 하드닝 이식 (2026-10-06)

Ovation 이 운영 설계(`hardening-design`)로 고친 것 중 범용인 것을 스켈레톤에 옮겼다. 각 항목은 고치기 전에 실패하는 테스트로 문제를 먼저 보였다.

- **`job-queue-jdbc` — 정확성 수정 3건 (동작이 바뀌는 변경)**: (1) `markDone`/`markRetry`/`markDead` 가 이제 `status='RUNNING' and locked_by and attempts` 가 내 청구와 같을 때만 반영된다 — 스테일 복구로 남에게 넘어간 잡을 옛 워커가 늦게 덮어쓰던 것을 막는다(시그니처에 `workerId`·`attempts` 가 늘고 `Boolean` 을 돌려준다). (2) 묶음 안의 잡은 차례가 올 때 `renew` 로 임대를 새로 잡고, 이미 남에게 넘어갔으면 돌리지 않는다 — 묶음이 한 `locked_at` 을 공유해 뒤 잡이 두 번 도는 문제. (3) 스테일 복구가 시도를 다 쓴 잡(`attempts >= max_attempts`)은 PENDING 이 아니라 DEAD 로 보낸다 — 워커를 죽이는 독 작업이 영영 되살아나지 않게. `recoverStale` 은 `StaleRecovery(recovered, dead)` 를 돌려주고, `UPDATE … RETURNING` 대신 한 트랜잭션의 select 후 update 라 MySQL 에서도 같다
- **`job-queue-jdbc` — 새 기능**: `JobDeadListener` 고리(재시도 소진 · 영구 실패 · 처리기 없음 · 멈춘 채 소진), `skeleton_jobs.log_context` 칼럼(양 방언 마이그레이션 `V20261005175044`)과 `JobContextPropagator` — `skeleton.job-queue.propagated-mdc-keys`(기본 `[traceId]`)의 MDC 값이 잡 줄에 실려 워커가 돌리는 동안 복원되고 `jobId`/`jobKind` 가 붙는다, `skeleton.job-queue.retention.*` — DONE/DEAD 줄 정리(**기본 꺼짐**: 줄 삭제는 보관 정책이라 앱이 고른다). 두 방언 모두 `postgresTest`/`mysqlTest` 로 검증
- **`platform` — 클라이언트 실수가 500 이 아니다**: 없는 메서드는 `405 COMMON.METHOD_NOT_ALLOWED`(+`Allow` 헤더), 받지 않는 본문 형식은 `415 COMMON.UNSUPPORTED_MEDIA_TYPE`, 못 주는 `Accept` 는 본문 없는 `406`(이전엔 ERROR 로그를 남겼다), 빠진 필수 쿼리 파라미터는 `400 COMMON.PARAMETER_VALIDATION_FAILED`(`errors[].code=Missing`). **동작이 바뀌는 변경**(405/415 가 `COMMON.INTERNAL_SERVER_ERROR` 500 이었다)
- **`platform` — 인메모리 rate limit 저장소 수정**: 청소가 **호출한 쪽의 창 길이**로 남의 카운터를 판단해, 짧은 창으로 부른 호출이 긴 창 카운터를 지우고 한도를 우회시켰다. 카운터마다 자기 창의 끝을 들고 청소는 초당 한 번, `sweep(now)` · `size()` 추가. 기본 저장소는 `TimeProvider` 시계를 본다
- **`platform` — `TimeProvider.asClock()`**: `Clock` 을 받는 코드가 같은 시계를 보게 하는 다리(UTC 고정)
- **`platform` — CORS 기본 `allowed-headers` 에 `X-Time-Zone`**: 프론트 api-client 가 모든 요청에 붙이는 헤더(`modules/time` 의 `zone-header` 기본값)라, 이전 기본값으로는 CORS 를 켜자마자 preflight 가 막혔다. CORS 는 기본 꺼짐이고 목록을 덮어쓴 앱은 영향 없다
- **`platform` — 출력 끝 로그 마스킹 `LogMasker` (opt-in)**: logback 변환기(`%m` · `%wEx`)와 구조 로그 커스터마이저가 완성된 줄에서 Authorization/Cookie 헤더 줄 · `Bearer …` · JWT 모양 · token/secret/password/apikey 쌍을 지운다. **앱이 `logback-masking.xml` 조각을 include 해야 켜진다**(기본 동작 불변). `skeleton.redaction.output.patterns`(앱 정규식, `(?<value>…)` 그룹만 가리기) · `mask-emails`(기본 false). 사용법 `docs/logging.md`
- **클라이언트 IP `ClientIps` (`skeleton.web.client-ip`)**: 한도 · 멱등 범위의 "누가 호출했나"가 `remoteAddr` 직접 읽기에서 한 규칙으로 옮겨 갔다 — `mode` = `direct`/`proxy`/`cloudflare`, 믿는 프록시 CIDR(기본 루프백), `X-Forwarded-For` 오른쪽부터 걷기, IPv6 한도 키는 /64, 믿을 수 없는 Cloudflare 범위 주소 거부. mode 를 정하면 `ClientIpFilter` 가 `ForwardedHeaderFilter` **앞**에서 한 번 풀어 둔다. 세 호출 지점 전환: platform `ClientIpRateLimitKeyResolver` · `redis-rate-limit` `PrincipalAwareRateLimitKeyResolver` · `idempotency` `PrincipalIdempotencyScopeResolver`. **보안 주의 — 기본(mode 미설정)은 기존 동작 그대로라 스푸핑 가능**: `ForwardedHeaderFilter`(기본 켜짐)가 `X-Forwarded-For` 로 `remoteAddr` 를 덮어써서 직접 붙은 호출자가 한도 키를 고른다(`ClientIpChainTest` 가 증명). 프록시 뒤 배포는 그 동작으로 실제 IP 가 보이므로 기본을 바꾸지 않았고 기동 시 WARN 을 남긴다 — 공개 서비스는 mode 를 명시한다. `docs/client-ip.md`
- **`storage` · `storage-s3` — `PresignedUploadRequest.cacheControl`**: presign 업로드가 `Cache-Control` 을 서명에 넣는다(정적 자산 immutable 캐시). 기본 null = 서명 안 함
- **새 모듈 `alert` (+ `alert-jdbc`) — 주인 경보, 스켈레톤의 에러 수집 답(Sentry 가 아니다)**: `OwnerAlerts.emit(kind, key, detail)` 는 업무 흐름에 던지지 않고, 트랜잭션 안이면 커밋 뒤에(`immediate` 는 즉시), (종류 · 키)로 접어 접은 수를 다음 알림에 싣는다. 채널은 Discord 호환 웹훅과(`notification-mail` 이 있으면) 메일 — 채널마다 가상 스레드에서 3번 재시도, 서로 막지 않는다. 기록(DB)이 막혀도 `directFallback` 종류(5xx 몰림)는 웹훅으로 **직접** 한 번. 트리거: 5xx 몰림(`ServerErrorSurgeFilter`), 기동 실패(`ApplicationFailedEvent`, 웹훅 직접 전송), 죽은 작업(`job-queue-jdbc` 의 `JobDeadListener`, 예외 클래스만 싣는다). `AlertKind` 는 닫힌 enum 이 아니라 인터페이스 — 앱이 자기 종류를 더한다. **웹훅 주소(`skeleton.alert.webhook-url`)가 없으면 꺼진다**(`OwnerAlerts` 는 로그만). `job-queue-jdbc` · `notification-mail` 은 `compileOnly` 라 없어도 뜬다(`noOptionalTest`). `alert-jdbc`: `skeleton_alerts`(양 방언 마이그레이션) 한 문장 조건부 갱신으로 여러 인스턴스 · 재시작을 가로질러 접는다(8 스레드 동시 시험, PostgreSQL · MySQL), 오래된 줄 정리는 기본 꺼짐. `apps/sample`(주소 없으면 꺼짐) · `apps/workbench`(웹훅 수신 통합 테스트) 에 얹었고 `new-project.sh` 모듈 선택 · `test-new-project.sh` MySQL 조합에 `alert-jdbc` 가 들어갔다. `docs/alert.md`
- **`persistence-jdbc` 문서**: `SqlDialect.insertIgnore` 의 갱신 행 수는 MySQL 에서 이미 있는 행에도 1 이다(`on duplicate key update`) — 넣었는지 판정하는 데 쓰지 않는다 (alert-jdbc 가 MySQL 에서 실제로 걸렸다)

### 게시판 모듈 `board` + `board-jdbc` — 글 · 대댓글 · 설정으로 늘리는 반응 (2026-10-05)

- **`modules/board`**(계약 · 서비스 · 정책 · HTTP · 설정 `skeleton.board`)와 **`modules/board-jdbc`**(PostgreSQL · MySQL 저장소 + 방언별 마이그레이션 `V20261005142218__skeleton_board.sql`, 테이블 `skeleton_boards` · `skeleton_board_posts` · `…_post_attachments` · `…_comments` · `…_reactions`). `notification`/`notification-jdbc` 와 같은 분리 — `board` 는 저장소를 모르므로 `--modules board,board-jdbc` 로 둘 다 적는다 (`board` 만 적으면 `new-project.sh` 가 알려 주고, 저장소 포트가 없으면 시작이 실패한다). 메모리 구현은 두지 않았다.
- **HTTP `/api/v1/boards`**(`skeleton.board.http.base-path`, `http.enabled=false` 로 끈다, 익명 읽기는 `http.allow-anonymous-read`): 설정 보고 `GET /config` · 게시판 · 글(목록 `sort=latest|reactions|comments` · `q` · `reaction=<코드>` 정렬 · 고정 글 먼저, 상세, 쓰기 · 고치기 · 소프트 삭제, 운영자 `moderation`) · 댓글(최상위만 페이지 + **모든 자손을 `root_id` 한 쿼리로** 평평하게) · 반응. 에러 `BOARD.*` 10 개. 계약은 react-skeleton `@skeleton/board` 와 같다.
- **반응은 문자열 코드** `skeleton.board.reaction.types`(기본 `[LIKE, DISLIKE]`) — 종류를 늘리는 데 코드 · 스키마 변경이 없다. `mode: SINGLE`(계정당 하나, 바꾸면 교체) | `PER_TYPE`. 유니크 키 `(target_type, target_id, account_id, reaction_type)` 하나로 두 모드를 다 돌린다. **동시성**: 모든 반응 변경이 대상 행을 `FOR UPDATE` 로 먼저 잠근다 — 같은 계정의 동시 LIKE · DISLIKE 가 둘 다 들어가거나(PG 중복 키) 교착(MySQL)이 나는 것을 `JdbcConcurrencyDbTest` 가 두 DB 에서 40 스레드로 확인하고, 잠금을 빼면 실패한다. 카운터(`comment_count` · `reaction_count` · `view_count`)는 같은 트랜잭션의 `x = x + :delta`, 종류별 개수는 쪽마다 `GROUP BY` 한 번. 잠금 순서는 글 → 댓글 하나.
- **댓글 트리**: `parent_id` + `root_id` + `depth`, `max-comment-depth` 기본 2(넘으면 422 `BOARD.COMMENT_TOO_DEEP`). 지우기 · 숨기기는 소프트 — 스레드 모양이 남고 본문만 `null`. 목록은 N+1 이 없다(행이 3 개든 30 개든 SQL 문장 수가 같다 — `JdbcQueryCountDbTest`).
- **권한**: `BoardPolicy`(기본: 작성자 · `skeleton.board.moderator-role`=`MODERATOR`), 내용 규칙(길이 · 제어문자 · 방향 덮어쓰기 문자 제거, **HTML 은 해석하지 않는다**), `BoardRateLimiter`(`rate-limit.enabled`, platform 의 `RateLimitStore` — redis-rate-limit 이 있으면 Redis).
- **선택 통합(컴파일 전용)**: `notification` 이 있으면 내 글에 댓글 · 내 댓글에 답글이 달릴 때 알림(`BoardNotificationFormatter` 로 문구 교체), `idempotency` 가 있으면 글 · 댓글 만들기에 `Idempotency-Key` 필수. `modules/board/src/noOptionalTest` 가 둘 다 클래스패스에 없을 때를 증명한다.
- **샘플 앱**: `board` + `board-jdbc` 를 더하고 `reaction.types: [LIKE, DISLIKE, EMPATHY]` · `seed-boards: general` 만 설정했다. 데모 계정에 `moderator@example.com`(`MODERATOR`) — `SampleAccounts.kt`. 통합 테스트 `BoardIntegrationTest`. 워크벤치도 두 모듈을 얹는다.
- **`new-project.sh`**: `src/dbTest` 가 있는 모듈은 모두 `dbTestModules` 에 넣는다(이전에는 두 이름을 하드코딩). `test-new-project.sh` 에 `--modules board,board-jdbc` 조합(`--full` 6번째). `ModuleDocumentationTest` 의 프론트 짝 목록에 `@skeleton/board`.
- 문서: `docs/modules/board.md`("Decisions and rejected alternatives": 모듈 분리 · 깊이 · 반응 유니크 키 · 카운터 · 소프트 삭제 · 알림 · 검색), `board-jdbc.md`, `docs/config/modules/board.yml`, 최소 구성 가이드 표.

### 샘플 앱 "Notes" — 새 기능을 어떻게 얹는지 보여 주는 제품 모양 예시 (2026-10-05)

- **`apps/sample`**(Gradle `:apps:sample`): 로그인한 사람이 노트(제목 · 본문 · 상태 · 고정 · 첨부 1개)를 관리한다. 스타터 + `idempotency` · `notification-jdbc`/`-sse` · `storage-s3` · `job-queue-jdbc` + 도메인 하나. REST `/api/v1/notes`(멱등 생성 · 목록 검색/필터/페이지 · 요약 · 통째 교체 · 삭제 · 내보내기 202), 검증 에러는 `errors[].field`, 소유자 아닌 접근은 404 `NOTES.NOT_FOUND`, 만들기/수정/삭제/내보내기 완료가 받은편지함 + SSE 알림(토픽 `notes`), 내보내기는 잡이 마크다운을 저장소에 올린다. 통합 테스트 29개(진짜 PostgreSQL · 보안 체인, 저장소만 메모리). 파일 단위 설명과 조립 순서: `docs/sample.md`, 에이전트 안내: CLAUDE.md "새 기능의 정본 예시"
- **`scripts/new-project.sh` 는 샘플을 기본으로 넣지 않는다**: `--with-sample` 일 때만 `apps/sample` · `docs/sample.md` · 실행 스크립트 · 안내 문서의 샘플 구역이 남고, 샘플이 쓰는 모듈이 닫힘에 더해진다(PostgreSQL 전용 — `--db mysql` 과 같이 못 쓴다). `test-new-project.sh` 에 `--with-sample` 조합(`--full` 5번째)과 기본 조합의 부재 단언이 늘었다
- **로컬 실행**: `scripts/dev.sh` 가 `APP=sample` 을 받는다 · `scripts/dev-sample.sh`(DB + S3 → 백엔드 → `../react-skeleton/apps/sample`) · `scripts/sample-e2e-backend.sh start|stop`(전용 compose 프로젝트로 깨끗한 DB, `/health` UP 이면 `READY` 출력). compose 의 호스트 포트가 `DB_PORT`(5432) · `S3_PORT`(8333) 로 바뀐다 — 샘플 앱 yml 도 같은 이름을 읽는다
- **`scripts/dev.sh` 는 앱의 `build.gradle.kts` 로 올릴 인프라를 고른다**: 이전에는 `modules/db-mysql` 폴더가 있으면 MySQL 을 올렸는데, 스켈레톤 레포는 두 DB 모듈이 다 있어 PostgreSQL 앱(`apps/api` · `apps/sample`)에도 MySQL 컨테이너를 올렸다. 이제 앱이 `implementation(project(":modules:db-mysql"))` · `…:storage-s3` 를 적었는지로 정한다(주석에 적힌 이름은 세지 않는다). `DEV_DRY_RUN=1` 은 무엇을 올릴지만 찍고 끝낸다 — `test-new-project.sh` 가 그것으로 확인한다
- **`notification-sse`: 받는 사람이 정해진 알림은 그 사람의 연결에만 흐른다 — 동작이 바뀌는 변경.** 이전에는 토픽만 맞으면 모든 연결이 모든 알림(제목 · payload · `recipientIds` 포함)을 받았다. 이제 `recipientIds` 가 비어 있지 않은 이벤트는 연결한 호출자(`Principal.name`)가 거기 있을 때만 전달되고, 받는 사람이 없는 이벤트(전체 공지)는 모두에게 간다. 인증 없는 연결(`public-endpoint=true`)은 후자만 받는다
- **`platform`: 값을 타입으로 바꿀 수 없는 요청은 500 이 아니라 400**: `?status=NOPE` · `?page=abc` 같은 `MethodArgumentTypeMismatchException` 이 `COMMON.PARAMETER_VALIDATION_FAILED` + `errors[{field, code: TypeMismatch}]` 가 된다

### 새 프로젝트 드릴 — 모듈이 받은편지함 · 업로드 HTTP 를 연다 · 한 줄 로컬 실행 (2026-10-05)

`new-project.sh` 로 찍은 풀스택(job-queue-jdbc · notification-jdbc · notification-sse · storage-s3 · scheduler + 프론트 realtime · notifications · storage)을 브라우저에서 눌러 보며 막힌 곳을 고쳤다.

- **받은편지함 엔드포인트가 `modules/notification` 으로 옮겨 왔다** (워크벤치의 `NotificationInboxController` 삭제): `GET /api/v1/notifications` · `PATCH …/{eventId}/read` · `PATCH …/read-all`. 서블릿 웹 앱 + Spring Security 가 있을 때만 `NotificationInboxWebAutoConfiguration` 이 등록한다(`@ConditionalOnMissingBean`, `skeleton.notification.inbox.enabled=false` 로 끔). 호출자는 `Authentication.name`, 남의 알림은 404, 인증 없으면 401. **`notification` 이 이제 `platform` 에 의존한다** (envelope · 에러 · 페이지). 기존 앱이 같은 경로의 자기 컨트롤러를 두었다면 `inbox.enabled=false` 를 준다 — **동작이 바뀌는 변경**
- **업로드 엔드포인트가 `modules/storage` 에 생겼다**: `POST /api/v1/storage/validate` · `/presign` · `/presign-download` · `/multipart/start|part|complete|abort`. `PresignedStorage` 빈(= 버킷 설정)이 있을 때만 등록(`StorageWebAutoConfiguration`), 인증 필수, 키는 서버가 `<key-prefix>/<계정 id>/<uuid>/<파일 이름>` 으로 정하고 남의 접두사 키는 404(`STORAGE.OBJECT_NOT_FOUND`), 검증 거절은 400 `STORAGE.FILE_REJECTED`(`data.errors`). 설정 `skeleton.storage.web.enabled` · `key-prefix`. `storage` 도 `platform` 에 의존한다
- `auth`: `AuthPrincipalAuthentication.getName()` 이 계정 id 를 돌려준다(기본 구현은 `toString()` 이었다) — 다른 모듈이 auth 에 의존하지 않고 호출자를 가리키는 값
- **스타터가 컨트롤러를 쓰는 데 필요한 의존을 가진다**: `apps/api` 에 `spring-boot-starter-validation` · `spring-security-core` · `springdoc-openapi-starter-webmvc-api` — 모듈의 `implementation` 의존은 앱 컴파일에 보이지 않아 `@Valid` · `Authentication` · `@Operation` 이 "Unresolved reference" 였다 (`ControllerAuthoringClasspathTest`)
- **로컬 S3**: compose 에 `s3`(SeaweedFS, profile `s3`)와 `s3-init`(버킷 `app` + 브라우저 직접 PUT 용 CORS) 추가, postgres 에 healthcheck. `new-project.sh --modules storage-s3` 는 `application-local.yml` 에 로컬 S3 설정을 붙인다 (storage-s3 문서는 SeaweedFS 를 권하면서 compose 에는 없었다)
- **`scripts/dev.sh`**: 한 줄 로컬 실행 — DB(+ S3) 컨테이너 → (옆 `../web` 이 있으면 `pnpm dev`) → `bootRun --spring.profiles.active=local`, `down` 으로 내림. 새 프로젝트는 `<작업 폴더>/api` · `<작업 폴더>/web` 으로 나란히 둔다
- `scripts/new-project.sh`: 레포 안에서 `../내-프로젝트` 처럼 상대 경로로 부르면 "target must be outside the skeleton repo" 로 거부되던 것을 고쳤다(경로를 정리한 뒤 비교). 같은 수정이 react-skeleton 에도 있다. compose 에 `name:`(새 이름)이 들어가 컨테이너 · 볼륨이 폴더 이름(`api`)에 기대지 않는다
- 결제(`payment`): 컨트롤러를 모듈로 올리지 않았다 — 금액 검증이 앱의 주문 저장소에 있어서다. 열려면 필요한 계약은 `docs/modules/payment.md` "왜 HTTP 엔드포인트가 없나"

### 모듈 캡슐화 마무리 — 선택 의존 · 모듈별 한 쪽 문서 (2026-10-05)

- **`storage-s3` → `crypto` 가 컴파일 전용(`compileOnly`)이 됐다.** 평범한 S3/R2 앱은 `crypto` 를 런타임에 받지 않는다 (`runtimeClasspath` 에서 사라짐). OPAQUE 공개 URL 은 새 `S3OpaquePublicUrlAutoConfiguration`(`@ConditionalOnClass` 로 crypto 가 있을 때만)이 등록하고, `crypto` 없이 OPAQUE 를 요청하면 `:modules:crypto` 를 짚는 메시지로 시작에 실패한다. OPAQUE 를 쓰는 앱은 `implementation(project(":modules:crypto"))` 한 줄을 더한다 — **OPAQUE 를 쓰던 앱이 받는 동작 변경**. `src/noCryptoTest`(crypto 가 정말 없는 클래스패스) 묶음이 `check` 에 걸린다. `new-project.sh` 는 compileOnly 의존의 소스는 따라오게 하되 앱 런타임에는 넣지 않고 그렇게 안내한다.
- **`persistence-jooq` 의 `main` 은 런타임 부품만.** 예시 DDL(`jooq-probe-*.sql`)과 거기서 생성한 클래스는 test 소스 세트로 옮겼다 (jar 에 더는 안 들어간다). 형제 모듈 마이그레이션을 읽는 입력도 테스트 전용이다. `testImplementation(project(":modules:db-postgresql"))` 를 없애고 PostgreSQL 드라이버만 테스트 런타임에 둔다 — `--db mysql --modules persistence-jooq` 가 더는 `db-postgresql` 을 끌고 오지 않는다. `JooqModuleLayoutTest`(Docker 불필요)가 이를 지킨다.
- **`event-kafka` / `notification-jdbc` → `json` 은 그대로.** 둘 다 `JsonCodec` 을 쓰고, `json` 이 더하는 웹 스택은 없다 (webflux · validation · springdoc 은 `platform` 이 이미 가져오고 두 모듈 모두 `platform` 을 거친다 — `dependencyInsight` 로 확인). 비용은 `platform` 의 것이며 `docs/modules/` 에 적었다.
- **모듈마다 `docs/modules/<module>.md` 한 쪽** + 색인 `docs/modules/README.md`. `modules/platform` `ModuleDocumentationTest` 가 문서와 코드(설정 접두사 · 교체 지점 타입 · 의존성 · 경로 · 색인)의 어긋남을 빌드에서 막는다. `new-project.sh` 는 가지친 모듈의 문서와 색인 행도 지운다. `scripts/test-new-project.sh --full` 에 jOOQ 조합이 늘었다 (네 조합).

### 이식 안내 — 한 줄로 찍는 프로젝트 · 설정 캡슐화 · 인증 안전 기동 (2026-10-05)

하위 앱(rename-skeleton 으로 찍은 레포)이 위에서 아래로 따라 하면 된다. 새 프로젝트는 이제 `scripts/new-project.sh` 한 줄이다 (README "새 프로젝트 시작", `docs/minimal-composition.md` §5).

**A. 인증은 보호 프로필에서 안전하지 않게 뜨지 않는다 (`modules/auth`) — 동작이 바뀌는 변경.**
`skeleton.auth.protected-profiles`(기본 `[prod, staging]`) 중 하나가 활성이면 기동이 실패한다 (메시지가 손볼 속성 · 빈 이름을 적는다):
(a) `skeleton.auth.jwt.secret` 이 비었거나, 내장 기본값(`dev-local-jwt-secret-change-me-32-bytes`)이거나, 32바이트(HS256)보다 짧다 — `JwtTokenService` 가 만들어지기 전에 막는다.
(b) 내장 메모리 `AuthAccountRepository`(시드 `user/password`, `admin/password`)가 쓰인다 — 앱이 자기 `AuthAccountRepository` 빈을 둔다.
`local` · `dev` · `test` · 프로필 없음은 그대로다. 검증은 `ApplicationRunner` 에서 `SmartInitializingSingleton` 으로 옮겨 컨텍스트 refresh 안에서 실패한다 (컨텍스트 러너로 시험된다).
`prod`/`staging` 으로 도는 앱은 `AuthAccountRepository` 빈이 없으면 뜨지 않는다. `apps/workbench` 의 `BreakGlassIntegrationTest`(prod 프로필)는 자기 저장소를 넣도록 고쳤다.

**B1. 앱 yml 은 모듈 기본값과 다른 값만.** `apps/workbench` `application*.yml` 400줄 → 203줄. 기본값을 되풀이하던 `${SKELETON_X:기본값}` 자리표시자는 없앴다 — 느슨한 바인딩이 같은 환경변수 이름을 이미 준다.
달라진 환경변수 이름: `SKELETON_NOTIFICATION_WEBSOCKET_AUTH_ENABLED` → `SKELETON_NOTIFICATION_WEBSOCKET_AUTHENTICATION_ENABLED`, `SKELETON_NOTIFICATION_WEBSOCKET_SOCKJS_ENABLED` → `…_ENDPOINT_SOCK_JS_ENABLED`,
`…_TOPIC_PREFIX` / `…_USER_DESTINATION` / `…_BRIDGE_ENABLED` → `…_BROKER_NOTIFICATION_DESTINATION_PREFIX` / `…_BROKER_USER_NOTIFICATION_DESTINATION` / `…_BROKER_BRIDGE_ENABLED`, `SKELETON_PAYMENT_*_ROUTE_ENABLED` 는 `skeleton.payment.providers.<id>.enabled` 의 느슨한 이름, 벤더 표준 이름(`JWT_SECRET`, `AWS_PROFILE`, `TOSS_PAYMENTS_SECRET_KEY`, `STRIPE_SECRET_KEY`, …)은 그대로 별칭이 남아 있다.
모듈마다 **모든 키 + 기본값 + 한 줄 설명**을 `docs/config/modules/<module>.yml` 에 두었다 (27개) — 필요한 블록만 복사한다. `apps/workbench` `ModuleConfigSnippetsTest` 가 각 파일의 키가 모듈 `@ConfigurationProperties` 에 바인딩되고 값이 기본값과 같은지, 속성이 빠지지 않았는지 본다.

**B2. 모듈을 얹으면 켜진다 — 인프라 · 필수 설정 없이 못 뜨는 것만 예외.** 규칙과 모듈별 "뜨는 데 필요한 것" 표는 `docs/minimal-composition.md` §3. 감사 결과:
- `redis-lock`: 기동 때 Redis 에 붙던 것(Redisson 즉시 시작 + 기본 켜진 시작 점검)을 **지연 연결**로, 시작 점검은 **옵트인**(`skeleton.redis-lock.startup-check.enabled`, 기본 `false` — 운영에서는 켠다). 락을 끄지 않으므로 `@DistributedLock` 이 조용히 무시되지 않는다 (Redis 가 없으면 첫 사용 때 크게 실패). 워크벤치의 `local` · `dev` · `staging` · `prod` yml 은 점검을 켠다.
- `config-aws-ssm`: `dev` · `staging` · `prod` 에서 `paths` 가 비어 있으면 "SSM is enabled but paths is empty" 로 기동이 실패하던 것을, `paths`(또는 `credential-profile`)가 설정돼야 읽도록 바꿨다. 명시적 `enabled=true` + 빈 `paths` 는 그대로 실패. 실패 안내는 더 이상 `./gradlew :apps:api:bootRun` 을 적지 않는다.
- `scheduler`: `skeletonTaskScheduler` 가 아무 `TaskScheduler` 빈이 있으면 물러나서, `notification-websocket`(TaskScheduler 둘)과 함께 얹으면 레지스트라 주입이 모호해져 기동이 실패했다. 이제 이름(`skeletonTaskScheduler`)으로만 물러난다 — 앱이 스케줄러를 바꾸려면 **같은 이름의 빈**을 둔다 (이름 없는 `TaskScheduler` 빈으로는 더 이상 대체되지 않는다).
- 이미 안전하게 degrade 하던 `redis-core` · `redis-cache`(FAIL_OPEN) · `redis-rate-limit`(FAIL_OPEN) · `storage-s3`(버킷이 없으면 저장소 빈 없음) · `notification-websocket` · `event-kafka`(로깅 전송기) · `notification-mail` · `captcha-turnstile` · `payment-*` · `notification-slack` 는 동작을 안 바꿨다. 모듈마다 "모듈 + 선언된 의존만, 설정 없음, 인프라 없음" 컨텍스트가 뜬다는 `*BootWithoutConfigurationTest` 를 더했다. 워크벤치는 `redis-rate-limit`(인메모리 저장소 시험)과 `notification-websocket`(구독자가 늘어난다)만 끈 채 둔다.
- 주의: `notification-websocket` 은 얹으면 켜지고 엔드포인트(`/ws/notifications`, 허용 Origin `*`)는 `authentication.enabled=true` 전까지 열려 있다 — 기본값은 바꾸지 않았다.

**B3. 설정 접두사 표** (`docs/minimal-composition.md` §2) 에 빠져 있던 `skeleton.idempotency` · `skeleton.openapi` · `skeleton.config.aws.ssm` · `skeleton.migration` 을 채웠고, `modules/platform` `ConfigPrefixDocumentationTest` 가 `@ConfigurationProperties` 접두사 + `Binder` 로 읽는 접두사와 표를 비교한다 (표에 있어도 디렉토리가 없는 모듈은 건너뛰므로 모듈을 덜어 낸 프로젝트에서도 통과). 접두사 이름은 하나도 바꾸지 않았다.

**C. `scripts/new-project.sh <target-dir> <root-package> <config-prefix> <ClassPrefix> [--modules a,b,c] [--db postgresql|mysql] [--with-workbench]`.**
복사 → 모듈 닫힘(`project(":modules:x")` 의존) → 안 고른 모듈 · 설정 블록 · settings include · `dbTestModules` · 스타터 테스트의 부재 단언 정리 → `--modules` 의존성 한 줄 + `application.yml` 끝의 주석 설정 블록 → (`--db mysql`) 방언 · URL · compose · Testcontainers · 첫 마이그레이션 → `rename-skeleton.sh`.
검증: `scripts/test-new-project.sh` (`--quick` 은 `./gradlew check` 에 걸려 있고, `--full` 은 세 조합을 찍어 각각 `./gradlew build` — `.github/workflows/new-project.yml`).
`docker-compose.yml` 에 `profiles:` 뒤의 선택 서비스 `redis` · `kafka` · `mail`(Mailpit)을 더했다 (`docker compose up` 만으로는 뜨지 않는다). `.env.example` 은 모듈별 주석 구역으로 다시 짰다.
`./gradlew check` 의 루트 `base` 플러그인 + `newProjectChecks` 작업이 새로 생겼다.

### 이식 안내 — 스타터 / 워크벤치 분리 + 모듈은 스캔되지 않는다 (2026-10-05)

하위 앱(rename-skeleton 으로 찍은 레포)이 위에서 아래로 따라 하면 된다. 경로 · 패키지는 스켈레톤 기준 — 하위 앱은 자기 접두사로 읽는다.

**0. 무엇이 바뀌었나.** `apps/api` 는 이제 **스타터**(최소 조립, 모듈 7개)이고, 이전 `apps/api` 전체(모든 모듈 + 샘플 컨트롤러 + 통합 테스트)는 `apps/workbench` 로 갔다.
앱은 루트의 하위 패키지(`…app.api`, `…app.workbench`)로 옮겨 `@SpringBootApplication` 이 모듈 패키지를 스캔하지 않는다. 모듈은 빈을 AutoConfiguration 으로만 등록한다.
REST 경로 · 응답 모양은 그대로다 (`/api/v1/skeleton/**` — react-skeleton 워크벤치 UI 계약).

**1. 앱 패키지를 루트 하위로.** `@SpringBootApplication` 클래스와 앱 코드를 `dev.sumin.skeleton.app.<앱>` (rename 후 `<root>.app.<앱>`) 아래로 옮긴다.
루트 패키지에 두면 앱이 모듈 패키지까지 스캔한다. 하위 앱 중 `apps/api` 를 그대로 쓰는 쪽은 클래스 이동만 하면 되고, 모듈 쪽 수정은 없다.

```
dev/sumin/skeleton/KotlinSkeletonApplication.kt  →  dev/sumin/skeleton/app/api/ApiApplication.kt   (이름은 자유)
dev/sumin/skeleton/api/*                         →  dev/sumin/skeleton/app/api/*
src/test/.../dev/sumin/skeleton/**                →  src/test/.../dev/sumin/skeleton/app/api/**      (@SpringBootTest 는 테스트 패키지에서 위로 올라가며 @SpringBootApplication 을 찾는다)
```

**2. 스타터와 워크벤치 중 무엇을 따를지.** 기능이 몇 개인 서비스는 `apps/api` 를 출발점으로 두고 필요한 모듈을 한 줄씩 얹는다 (`docs/minimal-composition.md`).
모든 모듈을 계속 얹는 앱(데모)은 이전 `apps/api` 가 `apps/workbench` 로 옮겨졌으니 이름만 바꾼다. `settings.gradle.kts` 에 `include(":apps:workbench")`, Dockerfile · `docker-compose.yml` 은 계속 `:apps:api` (스타터)를 빌드한다.
`./gradlew newMigration` 의 기본 모듈은 계속 `apps/api`.

**3. 모듈 쪽에서 바뀐 것 (복사해 갱신하는 디렉토리 단위).**

| 모듈 | 변경 |
|---|---|
| `platform` | `TraceIdFilter`, `RequestLoggingFilter` 의 `@Component` 제거 — `PlatformWebAutoConfiguration` 의 `@Bean` 으로만 등록. `GlobalExceptionHandler` 는 `@RestControllerAdvice` 유지(Spring MVC 가 애너테이션으로만 찾는다) + 같은 `@Bean`. `ModuleRegistrationRulesTest` 추가(모든 모듈 `main` 소스 검사, `build.gradle.kts` 의 `tasks.test` 블록 포함) |
| `notification-websocket` | `NotificationWebSocketBrokerConfiguration` 의 `@Configuration` 제거 — AutoConfiguration 의 `@Import` 로만 들어온다 |
| `db-postgresql`, `db-mysql` | 중첩 `DataJdbcConversions` 의 `@Configuration` 제거 (`@Bean` 메서드가 있는 중첩 클래스는 AutoConfiguration 이 그대로 처리) |
| `auth`, `notification-sse` | 컨트롤러는 `@RestController` 유지(MVC 핸들러 표지는 대체할 수 없다 — 클래스 레벨 `@RequestMapping` 만으로는 404). 이미 AutoConfiguration 의 `@Bean` 으로 등록돼 있고, 테스트가 그것을 강제한다 |

**4. 어댑터 모듈이 계약 모듈을 `api` 로 노출한다.** 의존성 한 줄이면 된다. 앱 `build.gradle.kts` 에서 중복된 줄을 지워도 컴파일된다 (남겨도 무해).
`payment-toss`/`-stripe` → `payment`, `notification-jdbc`/`-sse`/`-slack`/`-websocket` → `notification`, `storage-s3` → `storage`, `auth-social` → `auth`,
`auth-social-google`/`-kakao`/`-naver` → `auth-social`, `redis-lock`/`-cache`/`-rate-limit` → `redis-core`.

**5. 확인.** `./gradlew build` 와 `scripts/rename-skeleton.sh dev.sumin.ovation ovation Ovation` 후 `./gradlew build` 를 복사본에서 돌려 통과를 확인했다.
`skeleton.notification.sse.enabled=false` 처럼 모듈을 끄면 이제 기동이 깨지지 않는다 (`apps/workbench` `ModuleDisabledIntegrationTest`).

### 이식 안내 — PostgreSQL 기본 + 방언 조립식 + Flyway 충돌 방지 (2026-10-01)

하위 앱(rename-skeleton 으로 찍은 레포)이 위에서 아래로 따라 하면 된다. 설계: `docs/superpowers/specs/2026-10-01-postgresql-flyway-design.md`.

**0. 기준.** 마지막 MySQL 전용 커밋은 `5cf377b`. MySQL 로 남을 앱은 1 단계에서 `db-mysql` 을 끼우면 동작이 같다.
아래 경로 · 이름은 스켈레톤 기준(`dev.sumin.skeleton`, `skeleton.*`) — 하위 앱은 자기 접두사로 읽는다.

**1. 모듈 조립 (`apps/<app>/build.gradle.kts`).**

```kotlin
implementation(project(":modules:db-postgresql"))     // 또는 :modules:db-mysql — 정확히 하나
implementation(project(":modules:migration-flyway"))   // 공통 :modules:migration 을 api 로 끌고 온다
implementation("org.springframework.boot:spring-boot-starter-flyway")
// 삭제: implementation("org.flywaydb:flyway-mysql"), runtimeOnly("com.mysql:mysql-connector-j")  — 방언 모듈이 가져온다
// 테스트: testcontainers-mysql → testImplementation("org.testcontainers:testcontainers-postgresql")
```

`settings.gradle.kts` 에 `include(":modules:db-postgresql")`, `include(":modules:db-mysql")`, `include(":modules:migration")`, `include(":modules:migration-flyway")`.
방언 모듈이 없거나 둘이거나 연결된 DB 와 다르면 `SqlDialectVerifier` 가 원인을 적고 기동을 실패시킨다.

**2. 새 파일 · 옮긴 파일 · 지운 파일.**

| 모듈 | 파일 | 비고 |
|---|---|---|
| persistence-jdbc | `SqlDialect.kt`, `SqlDialectVerifier.kt`, `SqlDialectAutoConfiguration.kt` | 새 파일. imports 에 `SqlDialectAutoConfiguration` |
| persistence-jdbc | `JdbcUtcAutoConfiguration.kt`, `META-INF/spring.factories` | **삭제** (MySQL 전용 → db-mysql) |
| db-postgresql (새 모듈) | `PostgresSqlDialect.kt`, `PostgresTimeConversions.kt`, `PostgresDialectAutoConfiguration.kt`, imports | 패키지 `…persistence.postgresql` |
| db-mysql (새 모듈) | `MySqlSqlDialect.kt`, `MySqlDialectAutoConfiguration.kt`, imports, `spring.factories` | 패키지 `…persistence.mysql` |
| db-mysql | `MySqlTimeConversions.kt` ← persistence-jdbc `UtcInstantConversions.kt` | object 이름만 바뀜 |
| db-mysql | `MySqlTimeZoneEnvironmentPostProcessor.kt` ← persistence-jdbc `JdbcTimeZoneEnvironmentPostProcessor.kt` | property source `skeleton-db-mysql-defaults` |
| persistence-jooq | `JooqTimeZoneEnvironmentPostProcessor.kt`, `META-INF/spring.factories` | **삭제** (같은 MySQL 세션 강제 — db-mysql 이 한다) |
| persistence-jooq | `db/skeleton-jooq-schema.sql` → `db/jooq-probe-mysql.sql`, 새 `db/jooq-probe-postgresql.sql` | 예시 DDL |
| migration (새 모듈, 공통) | `MigrationProperties`(`skeleton.migration`), `MigrationCleanGuardEnvironmentPostProcessor` (Flyway clean · Liquibase drop-first 포함 가드) | 패키지 `…migration` |
| migration-flyway (새 모듈, Flyway 구현) | `CleanOnValidationErrorMigrationStrategy`, `MigrationFlywayAutoConfiguration`, `MigrationFileRules` + `RepositoryMigrationsTest` | 패키지 `…migration.flyway`, `api(project(":modules:migration"))` |
| job-queue-jdbc | `JdbcJobRepository(jdbc, transactions, dialect: SqlDialect)` | 생성자 인자 추가, 생성 키는 `update(keys, "id")` |
| notification-jdbc | `JdbcNotificationInboxRepository(jdbc, jsonCodec, dialect: SqlDialect)` | 중복 삽입 = `SqlDialect.insertIgnore` |
| 루트 `build.gradle.kts` | `dbTestModules` 묶음 등록, `newMigration` 작업 | §6 · §7 |

경계: `migration` 에는 도구와 무관한 것만 둔다 (설정 `skeleton.migration`, DB 를 지우는 설정의 프로필 가드). 파일 규칙 검사(`MigrationFileRules` — 위치 · 방언 폴더 짝 · 중복 · 타임스탬프)와 `newMigration` 도 개념상 공통이지만 지금은 Flyway 하나뿐이라 `migration-flyway` · 루트 Gradle 에 두고, `migration-liquibase` 가 생길 때 공통으로 끌어올린다. outOfOrder · baseline · repair 는 Flyway 전용.

**3. 설정 · 속성.**

| 키 | 값 | 어디서 |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/app` (쿼리 파라미터 없음) | 앱 yml, `.env.example` |
| `spring.flyway.locations` | `classpath:db/migration/{vendor}` | 앱 yml — **필수** (모듈이 방언별 폴더를 씀) |
| `spring.flyway.out-of-order` | `true` | 앱 yml — 선택 (apps/api 의 취향. 스켈레톤 모듈은 Flyway 기본값을 바꾸지 않는다) |
| `spring.flyway.validate-migration-naming` | `true` | 앱 yml — 선택 |
| `skeleton.migration.clean-on-validation-error` | 기본 `false`, `application-local.yml` 에서 `true` | 새 속성 |
| `skeleton.migration.clean-allowed-profiles` | 기본 `[local]` | 새 속성 |
| Gradle `-Pskeleton.jooq.dialect` | `postgresql`(기본) \| `mysql` | persistence-jooq 코드 생성 |
| (삭제) Hikari `connectionTimeZone`, `forceConnectionTimeZoneToSession`, `preserveInstants` | — | db-mysql 을 끼울 때만 자동으로 들어간다 |

docker-compose: `postgres:18` 서비스(`127.0.0.1:5432`, 볼륨 `/var/lib/postgresql`), `mysql:8.4` 는 `profiles: [mysql]`.

**4. 새 시간 규칙.** "SQL 파라미터는 `Instant` 대신 UTC `LocalDateTime`" 은 **폐기**. 두 DB 에 공통으로 맞는 바인딩 타입이 없다
(실측 2026-10-01, JVM 서울, `2026-03-01T00:30:00Z`):

| 바인딩 | PG `timestamptz` | MySQL `datetime(6)` |
|---|---|---|
| UTC `OffsetDateTime` / `Timestamp.from` | 정확 | JVM 벽시계(09:30)로 저장 |
| `Instant` | 드라이버 거부 | — |
| UTC `LocalDateTime` | **−9h** | 정확 |

- JdbcClient / NamedParameterJdbcTemplate: 쓰기 `dialect.instantParam(instant)`, 읽기 `dialect.readInstant(rs, "col")`. `Instant` · `Timestamp` · `LocalDateTime` 을 직접 바인딩하지 않는다.
- Spring Data JDBC: 방언 모듈이 `JdbcCustomConversions` 를 등록 (자체 빈을 만들면 `PostgresTimeConversions.all` / `MySqlTimeConversions.all` 포함).
- 칼럼: 시점 `timestamptz`, 달력 날짜 `date`, 벽시계(`ZonedMoment.local`) `timestamp`. `docs/time.md`.

**5. jOOQ 코드 생성 (`apps/<app>/build.gradle.kts`, PG).** `docs/persistence-jooq.md` 전문.

```kotlin
database {
    name = "org.jooq.meta.extensions.ddl.DDLDatabase"
    properties {
        property { key = "scripts"; value = "src/main/resources/db/migration/postgresql" }
        property { key = "sort"; value = "flyway" }
        property { key = "unqualifiedSchema"; value = "none" }
        property { key = "defaultNameCase"; value = "lower" }
    }
    forcedTypes {
        forcedType { name = "INSTANT"; includeExpression = "(?i:.*_at)"; includeTypes = "(?i:timestamp.*with.*time.*zone)" }
    }
}
```

`UtcInstantConverter` · `parseIgnoreComments` 는 MySQL 경로용 (`parseIgnoreComments` 는 PG 에 켜 둬도 무해 — persistence-jooq 는 방언과 무관하게 켠다). `jsonb` 는 jOOQ 에서 `JSON` 으로 생성된다.
여러 폴더를 합칠 땐 `scripts` 에 쉼표 목록이 안 되므로 `Sync` 작업으로 한 디렉토리에 모은다 (persistence-jooq `collectModuleDdl`).

**6. Flyway — 버전 · outOfOrder · clean · 가드.** `docs/schema-management.md` 전문.

- 위치 · 이름: `src/main/resources/db/migration/<vendor>/V<UTC yyyyMMddHHmmss>__<snake_case>.sql`. 만들기: `./gradlew newMigration -Pname=add_x [-Pmodule=apps/api] [-Pvendor=postgresql]`.
- 검사: `modules/migration-flyway` `RepositoryMigrationsTest` 가 `./gradlew build` 에서 형식 · vendor 폴더 · 레포 전체 중복 버전 · 두 vendor 짝 불일치를 막는다 (`tasks.test { systemProperty("skeleton.repoRoot", rootDir.absolutePath) }`).
- outOfOrder 는 앱의 선택 — apps/api 는 모든 환경 `true` 로 yml 에 적는다 (모듈은 기본값을 안 바꿈). 켠다면 규칙: **마이그레이션은 서로 독립 — 다른 브랜치의 미적용 마이그레이션에 기대지 않는다.**
- 로컬 clean: `local` 프로필에서 `skeleton.migration.clean-on-validation-error=true` 면 체크섬 불일치 · 적용 파일 사라짐(최신 적용분보다 이른 것)일 때 clean 후 재적용. 미적용(pending) 파일, 앱의 `ignore-migration-patterns`(기본 `*:future` — 다른 브랜치가 적용한 더 늦은 파일)에 걸리는 것은 밀지 않는다. 앱이 `out-of-order=true` 로 돈다는 전제 (Flyway 12.4 에 `cleanOnValidationError` 가 없어 `FlywayMigrationStrategy` 로 구현).
- 가드 (`migration` 공통 모듈): 허용 프로필 밖에서 위 옵션, `spring.flyway.clean-disabled=false`, `spring.liquibase.drop-first=true` 중 하나라도 켜지면 기동 실패 (활성 프로필 없음 = `default`, 허용 안 됨).
- **재명명** (이미 MySQL 에 적용된 DB 는 이력과 어긋난다 → 새 DB 로 시작하거나 `flyway_schema_history` 를 손으로 맞춘다):

| 전 | 후 |
|---|---|
| job-queue `db/migration/V2026091001__skeleton_jobs.sql` | `db/migration/{postgresql,mysql}/V20260910010000__skeleton_jobs.sql` |
| notification `db/migration/V2026061701__notification_inbox.sql` | `db/migration/{postgresql,mysql}/V20260617010000__skeleton_notification_inbox.sql` |
| 앱 `db/migration/V1__init.sql` (주석뿐) | 삭제 |
| 앱 테스트 `db/migration/V9000__utc_probe.sql` | `db/migration/postgresql/V20260101000000__utc_probe.sql` |

**7. Testcontainers.** `org.testcontainers.postgresql.PostgreSQLContainer(DockerImageName.parse("postgres:18"))`, 의존성 `testcontainers-postgresql`.
방언을 타는 모듈(job-queue-jdbc, notification-jdbc)은 루트 `dbTestModules` 로 `postgresTest` · `mysqlTest` 두 묶음을 갖는다 —
공통 소스 `src/dbTest/{kotlin,resources}`, 묶음별 `src/<suite>/kotlin` (컨테이너 정의), 둘 다 `check` 에 걸림. 한 묶음엔 방언 모듈 하나만.

**8. MySQL → PostgreSQL SQL 차이 (하위 앱이 다시 써야 할 것).**

| MySQL | PostgreSQL |
|---|---|
| `insert … on duplicate key update c = values(c)` | `insert … on conflict (key) do update set c = excluded.c` |
| 중복 무시 (`insert ignore`, 예외 잡아 삼키기) | `on conflict (key) do nothing` = `SqlDialect.insertIgnore`. **예외 삼키기 금지** — PG 는 실패한 문장이 트랜잭션 전체를 abort |
| `LAST_INSERT_ID()`, `GeneratedKeyHolder` 그대로 | `returning id` 또는 `update(keys, "id")` (키 칼럼 지정 안 하면 모든 칼럼이 키로 온다) |
| `datetime(6)` | 시점 `timestamptz`, 벽시계 `timestamp` |
| `bigint auto_increment` | `bigint generated by default as identity` |
| `engine=`, `charset=`, `collate` | 삭제 |
| `create table (…, index idx (a, b))` | `create index if not exists idx on t (a, b)` |
| `bigint unsigned` | `bigint` (+ 필요하면 `check (x >= 0)`) |
| 식별자 대소문자 | 따옴표 없으면 소문자로 접힘 — 대문자 이름은 `"Name"` 으로만 |
| `update/delete … limit n` | `where id in (select id … limit n)` |
| `json` | `jsonb` (jOOQ 생성 타입은 `JSON`) |
| `tinyint(1)` | `boolean` |
| `ifnull(a, b)` | `coalesce(a, b)` |
| `concat(a, b)` | 되지만 `a || b` 권장 (null 이면 결과 null 주의) |
| `@@session.time_zone`, `database()` | `show timezone`, `current_schema()` |
| `information_schema.statistics` (인덱스) | `pg_indexes` |

**9. 동작 수정.** notification-jdbc 가 같은 알림을 같은 트랜잭션에서 두 번 저장하면 PG 에서 트랜잭션이 깨지던 문제
(`DuplicateKeyException` 삼키기) → `insertIgnore`. 두 DB 테스트(`saving the same event twice inside one transaction…`)로 고정.

### 이식 안내 — 마이그레이션 규칙 검사 범위 · 앱 기동 규칙 순서 (2026-10-01, Ovation 이식에서 발견)

`9593d55` 를 이미 옮긴 앱이 따라 하면 된다. 아래 두 가지 모두 Ovation(`7161171` · `0440714`)에서 먼저 고치고 확인한 것.

**1. `MigrationFileRules` 가 레포 안 워크트리 · 레포 · `node_modules` 를 훑지 않게.** `9593d55` 는 `Files.walk` 로 루트 아래를 다
훑고 나서 경로에 `build` 등이 있는지 걸렀다. 그래서 (가) 레포 안에 다른 git 워크트리가 있으면 — Claude Code 가 만드는
`.claude/worktrees/<이름>/` 등 — 그 안의 마이그레이션 사본이 「duplicate version」으로 `./gradlew build` 를 깨고,
(나) `node_modules` 안에 읽을 수 없는 폴더가 있으면 `AccessDeniedException` 으로 깨진다.

| 파일 | 바꿀 것 |
|---|---|
| `modules/migration-flyway/…/MigrationFileRules.kt` | `Files.walk` → `Files.walkFileTree` (`sqlFiles(root)`). `preVisitDirectory` 에서 루트가 아니고 (`SKIP_DIRS` 에 있거나 `dir/.git` 이 있으면) `SKIP_SUBTREE` — 들어가지도 않는다. `SKIP_DIRS` 에 `.claude` 추가 |
| `modules/migration-flyway/build.gradle.kts` | `tasks.test` 입력 `fileTree` 의 `exclude` 에 주요 건너뛸 곳을: `"**/build/**", "**/node_modules/**", ".claude/**", "**/.git/**"` |
| `MigrationFileRulesTest` | 테스트 셋 추가: 중첩 `.git` 이 있는 폴더 · `.claude/worktrees` 는 훑지 않음, `node_modules` 안 읽기 금지 폴더로 들어가지 않음 |

**2. 앱이 따로 둔 기동 규칙은 Flyway 빈보다 먼저.** 스켈레톤 가드(`MigrationCleanGuardEnvironmentPostProcessor`)는
`EnvironmentPostProcessor` 라 빈보다 먼저 돈다 — 코드 변경 없음. 하지만 앱이 그 위에 **보통 빈**으로 기동 규칙을 더하면
(예: Ovation `DeployGuards` — stage · prod 에선 허용 프로필과 무관하게 clean 설정 거부) Spring 이 Flyway 빈을 먼저 만들 수
있고, 그러면 Flyway 빈을 받는 `FlywayMigrationInitializer` 의 `migrate()`(로컬 clean 전략 포함)가 DB 를 지운 **뒤에야** 규칙이 기동을 막는다
(Ovation 실측: jOOQ → Flyway 가 먼저 생성). 둘 중 하나:

- 규칙이 `Environment` 만 읽으면 `EnvironmentPostProcessor` 로 만든다 (`META-INF/spring.factories`).
- 빈으로 두려면 static `BeanFactoryPostProcessor` 로 모든 `Flyway` 빈이 규칙 빈에 기대게 한다 (Ovation 방식,
  `ApplicationContextRunner` 에 Flyway 설정을 먼저 등록하고 「규칙으로 기동 실패 + Flyway 빈 미생성」을 확인하는 테스트로 고정):

```kotlin
companion object {
    @JvmStatic
    @Bean
    fun flywayAfterDeployGuards(): BeanFactoryPostProcessor = FlywayAfterDeployGuards()
}
private class FlywayAfterDeployGuards :
    AbstractDependsOnBeanFactoryPostProcessor(Flyway::class.java, "deployGuardsChecked")   // 규칙 빈 이름 = @Bean 메서드 이름
```

규칙 빈에 `@DependsOn` 을 붙이는 건 반대 방향이라 소용없고, 자체 `FlywayMigrationStrategy` 에 넣으면 스켈레톤 로컬 clean
전략(`@ConditionalOnMissingBean`)을 대체해 버린다. `docs/schema-management.md` 「Guard」 절.


### Changed
- Spring Boot 4.0.5 → **4.1.1**, Kotlin 2.2.21 → **2.3.21** (Boot-managed: jOOQ 3.21.7, MySQL Connector/J 9.7.0, Testcontainers 2.0.5, Spring Security 7.1.1). One source change: `JwtTokenService` treats a missing `sub` claim as authentication failure (subject is nullable in Spring Security 7.1)
- `storage-s3`: static key-pair credentials (`credentials.access-key-id` / `secret-access-key`) for R2/MinIO with fail-fast on half-specified pairs; `region: auto` supported; `UploadObjectRequest.cacheControl` / `contentDisposition` passed to `PutObject`; `StorageService.deleteAll` (S3: `DeleteObjects` in batches of 1000). `docs/storage-s3.md` R2 section

### Added
- `storage-s3`: `skeleton.storage-s3.presign.endpoint-override` — a separate endpoint for presigned URLs (browser-facing) when the server reaches S3 through a compose service name such as `http://s3:8333`; unset keeps the shared `endpoint-override`. Requested from the Ovation local SeaweedFS setup. `docs/storage-s3.md` "Local container"
- `modules/persistence-jooq`: jOOQ with code generation from a DDL file (`DDLDatabase`, no DB at build), `UtcInstantConverter` (`*_at` → `Instant`, UTC-fixed), `JooqAuditRecordListener`, UTC session defaults; Testcontainers MySQL test with JVM zone forced to Seoul. `docs/persistence-jooq.md`
- `modules/job-queue-jdbc`: MySQL table retry queue — `JobQueue.enqueue`, `JobHandler` by type, `FOR UPDATE SKIP LOCKED` claiming, exponential backoff, `max-attempts` → DEAD, `PermanentJobFailureException`, stale RUNNING recovery, no Redis; 6 Testcontainers tests. `docs/job-queue-jdbc.md`
- `modules/notification-mail`: SMTP `MailSender` on top of `spring.mail.*`, off by default. `docs/notification-mail.md`
- `modules/captcha-turnstile`: `TurnstileVerifier` via platform outbound HTTP, hostname/action checks, off by default. `docs/captcha-turnstile.md`
- Schema without Flyway: `spring.flyway.enabled=false` + `spring.sql.init.mode=always` + `schema.sql`, proven by `SchemaSqlInitIntegrationTest`; migration path back to Flyway in `docs/schema-management.md`
- HTML pages next to the API: `apps/api` `PagesController` + page-scoped `HtmlPageErrorAdvice` + `PublicEndpointContributor`, proven by `HtmlPageCoexistenceIntegrationTest` (public `text/html`, HTML errors instead of JSON envelope, `/api/**` still protected)
- `scripts/rename-skeleton.sh`: rewrites root package, `skeleton.*` config prefix, `SKELETON_*` env placeholders and `Skeleton*` class/file names; `docs/minimal-composition.md` with a generated module → config-prefix table and the no-Redis defaults (rate limit, idempotency, scheduler lock)
- `modules/time`: global-time capability — `TimeContext` (account preference → `X-Time-Zone`/`Accept-Language` → `skeleton.time.default-*`), `ZonedMoment` (local time + IANA zone as source of truth, derived `at`; DST gap/overlap policy documented and tested), `TimeFormatter.dual` (event zone + viewer zone, `GMT+9`-style labels), `CountryTimeZones` generated from tzdata `zone.tab` with representative defaults for multi-zone countries, `UserTimePreferences` SPI. 12 tests
- `modules/persistence-jdbc`: `JdbcTimeZoneEnvironmentPostProcessor` forces the MySQL session to UTC via Hikari driver properties (`connectionTimeZone`, `forceConnectionTimeZoneToSession`), and `UtcInstantConversions` writes `Instant`/`LocalDate`/`LocalDateTime` as `JdbcValue` literals and reads `LocalDateTime` as UTC — measured against Connector/J: it converts `Timestamp`/`Date` parameters by the JVM zone but returns `DATETIME` as a wall-clock `LocalDateTime`, so a non-UTC JVM shifted instants by hours and moved `LocalDate` by a day. `apps/api` proves the round trip with the JVM default zone set to `Asia/Seoul`
- `docs/time.md`: the three temporal kinds and how to store/format each

### Changed
- `apps/api` datasource URL no longer needs `connectionTimeZone`/`forceConnectionTimeZoneToSession` parameters
- Testcontainers MySQL pinned to `mysql:8.4` (was `mysql:latest`, i.e. 9.x) to match `docker-compose.yml`
- Dockerfile builder image `gradle:8.11` → `eclipse-temurin:21-jdk` (the wrapper downloads Gradle anyway; the image tag was misleading); `.dockerignore` added
- Springdoc OpenAPI UI: `/api/v1/docs`, `/api/v1/docs/ui`
- `HelloControllerIntegrationTest`: `X-Request-Id` → `X-Trace-Id` 전파, 표준 `ApiError`, 요청 로그 traceId 흐름 검증
- W3C `traceparent` 기반 trace context: traceId는 전체 플로우로 승계, 각 BE 요청은 새 spanId 생성
- 로그 correlation 패턴에 `traceId`, `spanId`, `parentSpanId` 모두 출력
- 에러 응답과 응답 헤더에 `spanId` 포함
- Standard success response envelopes: single `{value, meta}`, list `{values, meta}`, page `{values, pagination, meta}`
- `Response` helper and envelope DTOs: `BasicResponse`, `DataResponse`, `ListResponse`, `PageResponse`, and `CursorResponse`
- Standard REST operation contracts: `201 Created` with `Location`, `202 Accepted`, `204 No Content`, and reusable `PageQuery`
- Web platform capability: public endpoint registry for `permitAll`, forwarded/security headers, CORS scaffold, rate-limit scaffold, and WebClient-based outbound HTTP facade
- Module-composed OpenAPI docs: platform contributes standard schemas/trace/error responses, auth contributes bearer JWT security, and auth-social contributes its functional route docs
- Standard request validation errors via Jakarta Bean Validation and `ApiError.errors[]`
- `modules/auth` stateless auth capability:
  - `POST /api/v1/auth/login` password login and `GET /api/v1/auth/me`
  - HS256 JWT issue/authenticate with `CurrentPrincipal`
  - local/dev header login via `X-Dev-Account-Id`, `X-Dev-Username`, `X-Dev-Email`
  - production break-glass access via secret, reason, and account allowlist
  - overridable Spring Boot auth auto-configuration defaults for app-specific repositories and security chains
- `modules/auth-social` optional social-login capability with provider-neutral OAuth contracts, account-link resolution, fake-provider integration tests, and `/api/v1/auth/social/{provider}/login`
- Optional social OAuth provider client modules: `modules/auth-social-google`, `modules/auth-social-kakao`, and `modules/auth-social-naver`
- `modules/idempotency` optional command endpoint protection with `@IdempotentOperation`, required `Idempotency-Key`, request fingerprinting, replay headers, and replaceable `IdempotencyStore`
- `ExternalHttpClient.postForm(...)` and per-call `baseUrl(...)` overrides for OAuth/payment-style external APIs
- `modules/notification` optional notification contracts with a replaceable in-memory broker
- `modules/notification-sse` optional Spring MVC SSE delivery through `GET /api/v1/notifications/sse`
- Persistence audit timestamp modules:
  - platform `TimeProvider` auto-configuration and `BaseAuditTimestamps` for UTC `Instant` + MySQL `DATETIME(6)` precision
  - optional `modules/persistence-jpa` with JPA `AuditTimestamps` and `BaseJpaEntity`
  - optional `modules/persistence-jdbc` with JDBC `AuditTimestamps`, `JdbcAuditable`, and audit callback auto-configuration

### Fixed
- 방언 모듈을 안 끼운 앱이 `NoSuchBeanDefinitionException: SqlDialect` 만 보던 경우에도 "db-postgresql 또는 db-mysql 을 끼워라" 안내가 나온다 (`SqlDialectFailureAnalyzer`, persistence-jdbc `spring.factories`). `db-postgresql` / `db-mysql` 자동설정은 Boot 의 Data JDBC 자동설정보다 먼저 돌도록 `beforeName` 으로 명시 (이름 순서에 기대지 않음). `newMigration` 작업 그룹 이름을 `migration` 으로 (rename 후 `skeleton` 이 남던 것)
- Copying a module migration into `schema.sql` broke jOOQ codegen (reported from Ovation): `DDLDatabase` cannot parse MySQL inline `index` clauses, and a separate `create index` is not idempotent on MySQL 8.4. `job-queue-jdbc` and `notification-jdbc` migrations now wrap their index clauses in `/* [jooq ignore start] */ … /* [jooq ignore stop] */` (comma inside the span), `persistence-jooq` sets `parseIgnoreComments=true` and generates code from those migrations on every build, and `SchemaSqlInitIntegrationTest` runs a `schema.sql` containing the `skeleton_jobs` DDL twice and checks the indexes. `docs/persistence-jooq.md` documents the pattern
- `scripts/rename-skeleton.sh` (reported from the Ovation rename): the YAML root key `skeleton:` was left as is, so every `<prefix>.*` block in `application*.yml` was silently ignored after a rename (e.g. `storage-s3.enabled=false` lost, module booted enabled); `.env.example` kept `SKELETON_*` while the yml switched to the new prefix; visible defaults kept the skeleton name (OpenAPI title/description, `skeleton-job-queue` thread, `skeleton-code-enum` Jackson module, `skeleton-async-`/`skeleton-scheduler-` prefixes, Kafka headers, Redis key prefix, JWT issuer, SSM paths, `skeleton-jooq-schema.sql`). `CountryTimeZones` looked up its TSV by the absolute path `/dev/sumin/skeleton/time/…` while the resource directory moved with the package (3 `modules:time` tests failed after a rename) — it now loads the resource relative to its own class. The script rewrites all of the above (plus the slash-form `dev/sumin/skeleton` in `.py`/`.sh`) and ends with a leftover scan that fails the run if any `skeleton` trace remains
- Modules no longer depend on the app component-scanning `dev.sumin.skeleton`: `platform` registers `TraceIdFilter`, `RequestLoggingFilter` and `GlobalExceptionHandler` via `PlatformWebAutoConfiguration`, `auth` registers `AuthController` in `AuthAutoConfiguration` (all `@ConditionalOnMissingBean`, so scanning apps keep their beans). Found by assembling a monorepo app in `dev.sumin.app1`: auth endpoints were 404 and responses had no trace id. Guarded by `ModuleSelfRegistrationIntegrationTest`, which boots a root configuration that scans nothing. `CodeEnumOpenApiCustomizer` no longer filters DTOs by the `dev.sumin.skeleton` package prefix (it skips JDK/Kotlin/Spring/Jackson types instead), so code-enum descriptions appear for DTOs in any package
- 매핑되지 않은 API 경로를 `500`이 아니라 표준 `404 ApiError`로 응답
- break-glass/dev-login authentication is no longer overwritten by a later bearer-token filter when both headers are present

## v1.2.0 - Multi-module foundation

- Converted the backend skeleton to a coarse-grained Gradle multi-module layout.
- Added `apps/api` as the executable Spring Boot application.
- Added `modules/platform` for shared web/error/observability infrastructure.
- Added `modules/auth` as the authentication capability module foundation.
- Kept the existing trace-aware `/api/v1/hello` behavior and integration tests.

### 기능 카탈로그 — LLM 이 읽는 「이미 준비된 것」 목록 (2026-10-06)

새 프로젝트를 시킬 때 에이전트가 무엇이 준비됐는지 찾아 알맞은 모듈 · 조각을 고르게 한다. react-skeleton 의 카탈로그와 모듈 이름 · 패키지 이름 · 항목 id 로 만난다.

- **`capabilities.json`**(정본, 스키마 `docs/capabilities.schema.json`) — 모듈 43 · 앱 3 · 스크립트 8 항목: 요약 · 상태 · `newProjectFlag` · 자동으로 따라오는 모듈(`autoIncludes`, Gradle 의존에서 계산) · `needs` · 설정 접두사와 `docs/config/modules/*.yml` · HTTP 경로 · 비밀(`docs/deploy.md` §7 의 이름과 빠졌을 때의 동작) · 문서 · 짝 프런트(`frontend.capabilities` ↔ react 항목 id, `packages` ↔ react 패키지) · 쓰지 않는 경우 · 한국어/영어 키워드. 맨 위에 `starterModules` · `newProject` 인터페이스 · 결정표 `decisions` · 작업 예 `examples`.
- **생성물**: `docs/capabilities.md`(결정표 + 전체 목록 + 항목 상세) · 루트 `llms.txt` — `perl scripts/build-capabilities.pl`(`--check` 는 검사만; perl core 모듈뿐, `new-project.sh` 와 같은 전제). `./gradlew check` 의 `CapabilitiesCatalogTest` 와 CI 가 최신 여부를 본다.
- **가드**(`modules/platform` · `Capabilities*Test`): 모듈 · 앱 · 스크립트 ↔ 항목 양방향(**없으면 붙일 객체를 그대로 출력**), 스키마, `newProjectFlag` 를 `new-project.sh --dry-run` 이 받아들이는지 · `autoIncludes` 가 실제 의존 닫힘과 같은지, `config.prefixes` ↔ `@ConfigurationProperties`, `basePaths` ↔ 컨트롤러 · 함수형 라우트 매핑, 문서 경로 · 모듈 문서의 「프론트 짝」, `secrets` ↔ `docs/deploy.md` §7, 키워드(한/영) · `TODO` 잔여, 결정표 · 레시피 명령, 형제 react-skeleton 이 옆에 있으면 패키지 · 항목 id · 그쪽이 말하는 백엔드 모듈이 여기 있는지. 가드가 정말 무는지는 일부러 틀린 카탈로그를 먹이는 `CapabilitiesGuardsTest`.
- **`docs/new-project-recipe.md`**: 제품 한 문단 → 고를 것 → 명령 → 설정 · 비밀 → `scripts/dev.sh` → 검증 → `deploy/app.yaml`, 작업 예 3개(커뮤니티 · 유료 SaaS(`--db mysql`) · 콘텐츠 SSR). 예의 명령은 카탈로그의 `examples` 에서 계산한 것이고 `scripts/test-new-project.sh` 가 `--dry-run` 으로 받아들이는지(빠른 검사), `--full` 이 실제로 찍어 `./gradlew build` 한다(조합 8~10). react-skeleton 의 레시피와 서로 링크한다.
- **`scripts/new-project.sh`**: `--dry-run`(고른 모듈과 따라온 이유만 보이고 아무것도 쓰지 않는다). 찍은 프로젝트에는 `capabilities.json` 을 고른 모듈 · 앱만 남긴 stamped 모드로 걸러 쓰고(빠진 것은 `stampedFrom.omitted` 로 스켈레톤을 가리킨다) 생성물을 새 이름으로 다시 만든다. `rename-skeleton.sh` 는 끝에서 생성물을 다시 만든다.
- **문서 어긋남 수정**: `docs/modules/payment*.md` · `captcha-turnstile.md` 가 「프론트 짝: 없음」 이라 했지만 `@skeleton/payment` · `@skeleton/captcha-turnstile` 이 있다 — 고쳤고 이제 모듈 문서의 「프론트 짝」 과 카탈로그가 어긋나면 빌드가 실패한다. `ModuleDocumentationTest` 의 알려진 패키지 목록도 갱신.
- CLAUDE.md: 만들기 전에 `llms.txt` 부터 읽는다. README: 「고른다」 단계에 포인터.

## [1.1.1] - 2026-04-20

### Added
- `RequestLoggingFilter`: 요청 시작/종료를 `→` `←` pair 로그로 출력 (같은 traceId로 묶임)
- `application.yml` `connectionTimeZone=UTC` — DB 타임존 JVM과 무관하게 UTC 고정
- `application.yml` Flyway MySQL 버전 경고 억제 (ERROR 레벨)

### Changed
- traceId 관리는 `TraceIdFilter` (커스텀) 유지 결정 — Spring Boot 4 + Micrometer Brave autoconfig 가 우리 환경에서 안정적으로 붙지 않아서. 단일 서비스 PoC 에선 차이 없고, 멀티 언어(Python/JS) 환경에서 오히려 단순 (X-Request-Id 는 누구나 다룸)
- 로그 correlation 패턴은 `[traceId]` 만 출력 (app 이름은 기본 APPLICATION_NAME 자리에서 한 번만)

## [1.1.0] - 2026-04-20

### Added
- **traceId 기반 관찰가능성**: `TraceIdFilter`가 요청마다 MDC `traceId` 심고 응답 헤더 `X-Trace-Id`로 반환. 클라이언트 `X-Request-Id` 헤더 오면 승계
- **표준 에러 응답**: `ApiError` (RFC 7807 Problem Details 변형 + traceId + timestamp), `GlobalExceptionHandler` 가 전역 예외 캐치
- **도메인 예외 베이스**: `ApplicationException` — 상속해서 throw하면 HTTP status + title이 자동 매핑
- **로깅 패턴**: 모든 로그 라인에 `[appName,traceId]` 프리픽스 출력
- **패키지 구조 정립**: `api/`, `domain/`, `infra/`, `common/`, `config/` 경계와 책임 CLAUDE.md에 명시
- **샘플 `HelloController`** (`/api/v1/hello`) — 컨벤션 시연용

## [1.0.0] - 2026-04-20

### Added
- Spring Boot 4.0 + Kotlin 2.2 + JDK 21 기본 구성
- MySQL 드라이버, Flyway, Spring Data JDBC
- Spring Boot Actuator (`/health` 노출)
- Testcontainers 테스트 지원
- 멀티 스테이지 Dockerfile
- `docker-compose.yml` 로컬 dev 환경 (app + MySQL)
- GitHub Actions CI 워크플로
