# Account lifecycle — HTTP contract (kotlin-skeleton backend)

Status: DRAFT-1 (written before the code; kept current as the code lands — see "Changelog" at the bottom).
Audience: the frontend agent (react-skeleton `@skeleton/auth` additions). Backend modules: `account`, `account-jdbc`,
`auth-session`, `auth-session-jdbc`, `auth-magic-link` (+ extensions of `auth`, `auth-social`).

## 0. Conventions (platform, unchanged)

- Success envelope: `{ "value": <dto>, "meta": { traceId, spanId, timestamp } }`; no value: `{ "meta": {...} }`; `204` has no body.
- Error body (`ApiError`): `{ code, title, status, detail?, traceId, spanId, timestamp, errors?: [{field, code, message}], data? }`.
  Branch on `code`. Validation: `400 COMMON.VALIDATION_FAILED` + `errors[]`.
- Instants are ISO-8601 UTC (`...Z`). Account ids are opaque strings (`acc_...`). Emails are compared case-insensitively (trimmed, lower-cased).
- Auth header for protected calls: `Authorization: Bearer <accessToken>` (JWT, 15 min). `401` = missing/invalid/expired access token
  (`COMMON.UNAUTHORIZED`) -> try `/auth/refresh` once, else sign out. `403` = authenticated but not allowed (`COMMON.FORBIDDEN`,
  `AUTH.*` blocks below) -> do NOT sign out, show the message.
- `Retry-After` (seconds) accompanies every `429`. `429` bodies carry `data: { retryAfterSeconds }`.
- Every module endpoint is opt-in via its module dependency and removable with `skeleton.<module>.http.enabled=false`.

## 1. Token delivery

`AuthTokenResponse` (login / refresh / magic-link redeem / social login) — a superset of today's shape; old clients keep working:

```json
{ "value": {
    "accessToken": "eyJ...", "tokenType": "Bearer", "expiresAt": "2026-10-06T01:15:00Z",
    "principal": { "accountId": "acc_...", "username": "a@b.c", "email": "a@b.c", "roles": ["USER"], "sessionId": "ses_..." },
    "refreshToken": "r1.<opaque>",          // body mode only (default). Absent in cookie mode and when auth-session is not installed
    "refreshExpiresAt": "2026-11-05T01:00:00Z",
    "sessionId": "ses_..."                  // present whenever auth-session is installed
} }
```

- Default delivery = `body` (`skeleton.auth-session.delivery=body`): the SPA keeps `refreshToken` and sends it in the JSON body of `/auth/refresh` and
  `/auth/logout`. No cookie => no CSRF surface. Keep it in memory or `sessionStorage`; rotate on every use (below).
- Opt-in `cookie`: refresh token travels as `__Host-refresh` (HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age). The JSON omits
  `refreshToken`. `/auth/refresh` and `/auth/logout` then require the header `X-Requested-With: fetch` (CSRF guard; forces a CORS preflight cross-site)
  and the SPA calls them with `credentials: 'include'`.
- **Rotation**: every `/auth/refresh` returns a NEW refresh token; the old one is dead. Presenting a dead one again (reuse) revokes the whole token
  family (all devices of that login chain) and returns `401 AUTH.REFRESH_REUSED`. Two tabs refreshing at once: the loser gets `401 AUTH.REFRESH_REUSED`
  only if it presents a token that was already rotated more than the grace window ago (`skeleton.auth-session.reuse-grace`, default `0s`);
  the client should serialize refreshes (single-flight) and, on `AUTH.REFRESH_REUSED`/`AUTH.REFRESH_INVALID`, sign out.

## 2. Sign-up and email verification

### POST /api/v1/account/sign-up  (public)
Req: `{ "email": "a@b.c", "password": "...", "displayName"?: "Ann", "locale"?: "ko", "timeZone"?: "Asia/Seoul", "captchaToken"?: "..." }`
Res: **always `202`** `{ "value": { "status": "VERIFICATION_SENT" } }` — identical whether the email is new, already registered, or throttled.
(An existing address receives a "you already have an account" mail instead. With `skeleton.account.sign-up.email-verification=false` the apps accepts the
enumeration trade-off and answers `201` + `409 ACCOUNT.EMAIL_TAKEN`.)
Errors: `400 ACCOUNT.PASSWORD_POLICY` (`data: { violations: ["TOO_SHORT", ...] }`), `400 COMMON.VALIDATION_FAILED`, `400 ACCOUNT.CAPTCHA_FAILED`,
`403 ACCOUNT.SIGN_UP_CLOSED` (when `sign-up.enabled=false`), `429 ACCOUNT.RATE_LIMITED`.

### POST /api/v1/account/verification/resend  (public)
Req `{ "email", "captchaToken"? }` -> **always `202`** (silent when unknown / already verified / over the per-email limit, default 3 per hour).

### POST /api/v1/auth/verify-email  (public)
Req `{ "token": "<from mail link>" }` -> `200 { "value": { "status": "VERIFIED" } }` (does not log in).
`410 ACCOUNT.TOKEN_INVALID` for unknown / expired / already-used (one body, no distinction). Single use: two concurrent calls -> one 200, one 410.
Mail link shape: `<link-base-url>/verify-email?token=<token>`; opening it must NOT call the API by itself — the page posts on a button / on mount
of the SPA route (mail scanners prefetch GETs, they do not run the SPA's POST).

## 3. Login (existing endpoint, extended)

### POST /api/v1/auth/login  (public)
Req unchanged: one of `accountId | username | email` + `password`; new optional `deviceName`. `username` of an account created here equals its email.
Res `200` AuthTokenResponse. Errors: `401 AUTH.INVALID_CREDENTIALS` (unknown account, wrong password, deleted account — same body and comparable
timing), `403 AUTH.EMAIL_NOT_VERIFIED` (correct password, address not verified: offer "resend"), `403 AUTH.ACCOUNT_SUSPENDED` (correct password),
`429 AUTH.TOO_MANY_ATTEMPTS` (`Retry-After`; per client IP and per submitted identifier, also for unknown identifiers).

### POST /api/v1/auth/social/{provider}/login  (existing, public)
Req `{ authorizationCode, redirectUri? }` -> AuthTokenResponse. New outcomes: first social sign-in creates an account when
`skeleton.account.social.sign-up=true`; if the provider's email matches an existing account -> `409 ACCOUNT.SOCIAL_EMAIL_CONFLICT`
(never auto-merged; user signs in with the existing method and links the provider from account settings, section 7).

### POST /api/v1/auth/magic-link/request  (public; module `auth-magic-link`)
Req `{ "email", "captchaToken"? }` -> **always `202`** `{ "value": { "status": "SENT" } }` (silent when throttled / unknown email and sign-up closed).
Mail link: `<link-base-url>/magic-link?token=<token>` (15 min, single use).
### POST /api/v1/auth/magic-link/redeem  (public)
Req `{ "token", "deviceName"? }` -> `200` AuthTokenResponse (creates the account on first use if `skeleton.auth-magic-link.sign-up=true`; marks the email verified).
`410 ACCOUNT.TOKEN_INVALID`; `403 AUTH.ACCOUNT_SUSPENDED`.

## 4. Sessions (module `auth-session`)

### POST /api/v1/auth/refresh  (public; credential = refresh token)
Req `{ "refreshToken": "r1...." }` (body mode) | cookie (cookie mode). Res `200` AuthTokenResponse with a rotated refresh token.
Errors: `401 AUTH.REFRESH_INVALID` (unknown/expired/revoked), `401 AUTH.REFRESH_REUSED` (rotated-away token replayed -> family revoked),
`403 AUTH.ACCOUNT_SUSPENDED`. Roles in the new access token are the account's CURRENT roles.
### POST /api/v1/auth/logout  (public; idempotent) -> always `204`. Req `{ "refreshToken"? }` / cookie. Revokes that session; clears the cookie.
### GET /api/v1/auth/sessions  (auth)
`200 { "values": [ { "id": "ses_...", "deviceName": "Pixel", "userAgent": "...", "ip": "203.0.113.7", "createdAt", "lastUsedAt", "current": true } ] }`
(`ip` is the ClientIps-resolved address recorded at issue/last refresh; `current` = the session of the presented access token.)
### DELETE /api/v1/auth/sessions/{id}  (auth) -> `204`; `404 AUTH.SESSION_NOT_FOUND` (also for another account's session — no probing).
### DELETE /api/v1/auth/sessions?keepCurrent=true  (auth) -> `204` (revokes all, by default except the current one).
Access tokens already issued live until `expiresAt` (<= 15 min) after a revoke; refresh stops immediately.

## 5. Password (module `account`)

### POST /api/v1/account/password/forgot  (public) — Req `{ "email", "captchaToken"? }` -> **always `202`** `{ "value": { "status": "ACCEPTED" } }`.
No enumeration: the request thread only validates, rate-limits (per IP, per email; silent over limit) and enqueues; lookup + token + mail run off-thread.
Mail link: `<link-base-url>/reset-password?token=<token>` (30 min, single use, a new request invalidates older ones).
### POST /api/v1/account/password/reset  (public) — Req `{ "token", "newPassword" }` -> `204`; `410 ACCOUNT.TOKEN_INVALID`; `400 ACCOUNT.PASSWORD_POLICY`.
Effect: password replaced (hash upgraded to current encoder), ALL sessions revoked, email marked verified (the mailbox was proven), "password changed" mail sent.
### POST /api/v1/account/password/change  (auth) — Req `{ "currentPassword", "newPassword" }` -> `204`.
`currentPassword` is required when the account has a password; a social/magic-link-only account omits it to SET a first password (email must be verified).
Errors: `400 ACCOUNT.CURRENT_PASSWORD_INVALID` (deliberately 400, not 401/403: the client must not treat it as "session expired"), `400 ACCOUNT.PASSWORD_POLICY`.
Effect: all OTHER sessions revoked; "password changed" mail.
### GET /api/v1/account/password/policy  (public) -> `{ "value": { "minLength": 10, "maxLength": 72, "requireLetter": true, "requireDigit": true, "requireSymbol": false, "forbidEmailLocalPart": true } }` (to render hints).

## 6. Profile, email change (auth)

### GET /api/v1/account/me
`{ "value": { "id", "email", "emailVerified", "displayName", "locale", "timeZone", "roles": [], "status": "ACTIVE", "createdAt",
"methods": [ { "id": "idn_...", "method": "password|magic_link|google|kakao|naver|...", "subject": "a@b.c|null", "verified": true, "createdAt", "lastUsedAt", "removable": true } ],
"hasPassword": true } }` (`subject` is shown for email-like methods only; social subjects are not exposed).
### PATCH /api/v1/account/me — Req any of `{ displayName (1..60), locale (BCP47 from allowed list), timeZone (IANA) }` -> `200` same as GET. `400 COMMON.VALIDATION_FAILED`.
### POST /api/v1/account/email/change — Req `{ "newEmail", "currentPassword"? }` (password required when the account has one) -> `202 { "status": "VERIFICATION_SENT" }`.
Mail to the NEW address with `<link-base-url>/confirm-email-change?token=...` (30 min). The email does not change until confirmed. If the new address belongs to
someone else the answer is still `202` (nothing is sent to them but a notice). The OLD address gets a "change requested" mail now and a "changed" mail on confirmation.
### POST /api/v1/auth/confirm-email-change  (public; the token is the credential) — Req `{ "token" }` -> `204`; `410 ACCOUNT.TOKEN_INVALID`; `409 ACCOUNT.EMAIL_TAKEN` (taken in between).
Effect: email switched, other sessions revoked.

## 7. Sign-in methods and social linking (auth)

### GET /api/v1/account/identities -> `{ "values": [ ...same objects as me.methods ] }`
### POST /api/v1/account/identities/social/{provider} — Req `{ "authorizationCode", "redirectUri"? }` -> `201 { value: identity }`.
`409 ACCOUNT.IDENTITY_TAKEN` (that provider account belongs to another account), `404 AUTH_SOCIAL.PROVIDER_NOT_FOUND`, `409 ACCOUNT.IDENTITY_EXISTS` (already linked).
### DELETE /api/v1/account/identities/{id} -> `204`; `409 ACCOUNT.LAST_SIGN_IN_METHOD`; `404 ACCOUNT.IDENTITY_NOT_FOUND`.
(The password identity is removed with this too. Removing it is allowed only while another sign-in method remains.)

## 8. Deleting the account (auth)

### POST /api/v1/account/delete/confirmation — (for accounts WITHOUT a password) mails a one-time confirmation link `<link-base-url>/confirm-delete?token=` -> `202`.
### POST /api/v1/account/delete — Req `{ "currentPassword"? , "confirmationToken"? }` (exactly one: password if the account has one, else the mailed token) -> `202 { "value": { "status": "DELETION_SCHEDULED", "purgeAfter": "2026-11-05T..Z" } }`.
`Idempotency-Key` header is accepted and required when the `idempotency` module is installed. Wrong credential -> `400 ACCOUNT.REAUTH_FAILED`.
Effect: status DELETED (cannot sign in, sessions revoked, identities frozen); after `skeleton.account.deletion.grace` (default `30d`) the purge job
runs every `AccountErasureListener` (board: author shown as deleted user; notification inbox deleted) and removes the account. Admin may `restore` within the grace.
Data export: no endpoint; `AccountDataExporter` is an interface only (not wired).

## 9. Admin (module `account`, `skeleton.account.admin.enabled=true`; role `skeleton.account.admin.role`, default `ADMIN`; others -> `403 COMMON.FORBIDDEN`)

- `GET /api/v1/admin/accounts?email=&status=&page=0&size=20` -> page envelope of `{ id, email, status, roles, displayName, createdAt, lastLoginAt }`
- `GET /api/v1/admin/accounts/{id}` -> same + `methods`
- `POST /api/v1/admin/accounts/{id}/suspend` `{ "reason"? }` -> `204` (sessions revoked) ; `POST .../unsuspend` -> `204`; `POST .../restore` (undo deletion within grace) -> `204`
- `PUT /api/v1/admin/accounts/{id}/roles/{role}` (grant) / `DELETE ...` (revoke) -> `204`. `409 ACCOUNT.LAST_ADMIN` when revoking/suspending the last ADMIN; `409 ACCOUNT.SELF_ACTION_FORBIDDEN` for suspending yourself.

## 10. Error code table

| code | status | when |
|---|---|---|
| AUTH.INVALID_CREDENTIALS | 401 | login: unknown / wrong / deleted |
| AUTH.EMAIL_NOT_VERIFIED | 403 | login with right password, unverified email |
| AUTH.ACCOUNT_SUSPENDED | 403 | login / refresh / magic link for a suspended account |
| AUTH.TOO_MANY_ATTEMPTS | 429 | login/magic-link throttle |
| AUTH.REFRESH_INVALID | 401 | refresh token unknown/expired/revoked |
| AUTH.REFRESH_REUSED | 401 | rotated-away refresh token replayed (family revoked) |
| AUTH.SESSION_NOT_FOUND | 404 | revoke of a session that is not yours / does not exist |
| ACCOUNT.TOKEN_INVALID | 410 | one-time token unknown / expired / used |
| ACCOUNT.PASSWORD_POLICY | 400 | password rejected (`data.violations`) |
| ACCOUNT.CURRENT_PASSWORD_INVALID | 400 | change password / email with wrong current password |
| ACCOUNT.REAUTH_FAILED | 400 | deletion confirmation wrong |
| ACCOUNT.CAPTCHA_FAILED | 400 | captcha token rejected (only when `captcha-turnstile` is on) |
| ACCOUNT.EMAIL_TAKEN | 409 | (verification disabled) sign-up / confirm-email-change collision |
| ACCOUNT.SIGN_UP_CLOSED | 403 | `sign-up.enabled=false` |
| ACCOUNT.SOCIAL_EMAIL_CONFLICT | 409 | social email matches an existing account |
| ACCOUNT.IDENTITY_TAKEN / IDENTITY_EXISTS / IDENTITY_NOT_FOUND | 409/409/404 | linking |
| ACCOUNT.LAST_SIGN_IN_METHOD | 409 | unlink the last method |
| ACCOUNT.LAST_ADMIN / SELF_ACTION_FORBIDDEN | 409 | admin guards |
| ACCOUNT.NOT_FOUND | 404 | admin target missing |
| ACCOUNT.RATE_LIMITED | 429 | sign-up / email-change throttle (reset/resend/forgot are silent) |

## 11. Flows (sequence lists)

**Sign up + verify + login**: POST sign-up(202) -> mail(verify link) -> user opens `/verify-email?token` (SPA) -> POST verify-email(200) -> POST login(200 + tokens) -> store tokens.
Unverified login -> 403 AUTH.EMAIL_NOT_VERIFIED -> POST verification/resend(202).
**Silent refresh**: on 401 from API -> (single-flight) POST refresh{refreshToken} -> 200 new pair -> retry once; 401 AUTH.REFRESH_* -> clear tokens, go to login.
**Forgot**: POST forgot(202) -> mail -> `/reset-password?token` -> POST reset{token,newPassword}(204) -> go to login (all sessions are gone).
**Change password**: POST change(204) -> keep using the current session; other devices are signed out.
**Magic link**: POST magic-link/request(202) -> mail -> `/magic-link?token` -> POST redeem(200 tokens).
**Link Google**: (logged in) provider consent -> POST identities/social/google{code}(201) -> GET identities.
**Delete**: password account: POST delete{currentPassword}(202) -> sign out locally. Passwordless: POST delete/confirmation(202) -> mail -> `/confirm-delete?token` -> POST delete{confirmationToken}(202).

## Changelog
- DRAFT-1: initial contract (pre-implementation).
