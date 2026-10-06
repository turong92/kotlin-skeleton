# Account lifecycle — HTTP contract (kotlin-skeleton backend)

Status: FINAL-2 (matches the implemented code after the security review; the integration tests `apps/sample` AccountJourneyIntegrationTest and `modules/account` AccountWebTest / SessionBodyDeliveryWebTest exercise every flow below).
Audience: the frontend agent (react-skeleton `@skeleton/auth` additions). Backend modules: `account`, `account-jdbc`,
`auth-session`, `auth-session-jdbc`, `auth-magic-link` (+ extensions of `auth`, `auth-social`).

## 0. Conventions (platform, unchanged)

- Success envelope: `{ "value": <dto>, "meta": { traceId, spanId, timestamp } }`; no value: `{ "meta": {...} }`; `204` has no body.
- Error body (`ApiError`): `{ code, title, status, detail?, traceId, spanId, timestamp, errors?: [{field, code, message}], data? }`.
  Branch on `code`. Validation: `400 COMMON.VALIDATION_FAILED` + `errors[]`.
- Instants are ISO-8601 UTC (`...Z`). Account ids are opaque strings (`acc_...`). Emails are normalised once (trimmed, Unicode NFC, lower-cased with Locale.ROOT; dots and `+tags` are NOT touched) and then compared exactly — a look-alike address (accent variant) is a different address.
- Auth header for protected calls: `Authorization: Bearer <accessToken>` (JWT, 15 min). `401` = missing/invalid/expired access token
  (`COMMON.UNAUTHORIZED`) -> try `/auth/refresh` once, else sign out. `403` = authenticated but not allowed (`COMMON.FORBIDDEN`,
  `AUTH.*` blocks below) -> do NOT sign out, show the message.
- `Retry-After` (seconds) accompanies every `429`. `429` bodies carry `data: { retryAfterSeconds }`.
- Every module endpoint is opt-in via its module dependency and removable with `skeleton.<module>.http.enabled=false`.
- Commands marked **[idem]** (`POST /account/email/change`, `POST /account/delete`) carry `@IdempotentOperation`: when the `idempotency` module is installed
  (it is in apps/sample) they REQUIRE an `Idempotency-Key` header (UUID) — missing -> `400 COMMON.IDEMPOTENCY_ERROR`.
- Sign-in `username` of accounts created here equals the email; the default role set is `["USER"]`.

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
- Opt-in `cookie`: refresh token travels as cookie `skeleton_refresh` (HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age). The JSON omits
  `refreshToken`. `/auth/refresh` and `/auth/logout` then require the header `X-Requested-With: fetch` (CSRF guard; forces a CORS preflight cross-site;
  missing -> `403 AUTH.CSRF_HEADER_REQUIRED`) and the SPA calls them with `credentials: 'include'`. In cookie mode a `refreshToken` in the body is ignored.
- Optional header on login / magic-link redeem: `X-Device-Name: <free text, <=80 chars>` — shown in the session list. (There is no `deviceName` body field.)
- **Rotation**: every `/auth/refresh` returns a NEW refresh token; the old one is dead. Presenting a dead one again (reuse) revokes that session (the refresh-token family of that login) and emits an audit/alert event and returns `401 AUTH.REFRESH_REUSED`. Two tabs refreshing at once: the loser gets `401 AUTH.REFRESH_REUSED`
  only if it presents a token that was already rotated more than the grace window ago (`skeleton.auth-session.reuse-grace`, default `0s`);
  the client should serialize refreshes (single-flight) and, on `AUTH.REFRESH_REUSED`/`AUTH.REFRESH_INVALID`, sign out.

## 2. Sign-up and email verification

### POST /api/v1/account/sign-up  (public)
Req: `{ "email": "a@b.c", "password": "...", "displayName"?: "Ann", "locale"?: "ko", "timeZone"?: "Asia/Seoul", "captchaToken"?: "..." }`
Res: **always `202`** `{ "value": { "status": "VERIFICATION_SENT" } }` — identical whether the email is new, already registered, or throttled.
(An existing address receives a "you already have an account" mail instead. With `skeleton.account.sign-up.email-verification=false` the app accepts the
enumeration trade-off: new -> `201 {status:"CREATED"}` and the account is active at once; existing -> `409 ACCOUNT.EMAIL_TAKEN`.)
Errors: `400 ACCOUNT.PASSWORD_POLICY` (`data: { violations: ["TOO_SHORT", ...] }`), `400 COMMON.VALIDATION_FAILED`, `400 ACCOUNT.CAPTCHA_FAILED`,
`403 ACCOUNT.SIGN_UP_CLOSED` (when `sign-up.enabled=false`), `429 ACCOUNT.RATE_LIMITED`.

### POST /api/v1/account/verification/resend  (public)
Req `{ "email", "captchaToken"? }` -> **always `202 {status:"ACCEPTED"}`** (silent when unknown / already verified / over the per-email limit, default 3 per hour). `429 ACCOUNT.RATE_LIMITED` when the client IP itself is over its limit (10 per hour). Sign-up and resend share that per-address budget.

### POST /api/v1/auth/verify-email  (public)
Req `{ "token": "<from mail link>" }` -> `200 { "value": { "status": "VERIFIED" } }` (does not log in).
`410 ACCOUNT.TOKEN_INVALID` for unknown / expired / already-used (one body, no distinction). Single use: two concurrent calls -> one 200, one 410.
The account row exists as soon as the `202` is answered (a lost mail is recovered with resend). The link is bound to the sign-up password it was issued for; if the mailbox owner signs in by magic link / social first, the unproven sign-up password is discarded and the link no longer works.
Mail link shape: `<link-base-url>/verify-email?token=<token>`; opening it must NOT call the API by itself — the page posts on a button / on mount
of the SPA route (mail scanners prefetch GETs, they do not run the SPA's POST).

## 3. Login (existing endpoint, extended)

### POST /api/v1/auth/login  (public)
Req unchanged: one of `accountId | username | email` + `password` (optional header `X-Device-Name`). `username` of an account created here equals its email.
Res `200` AuthTokenResponse. Errors: `401 AUTH.INVALID_CREDENTIALS` (unknown account, wrong password, deleted account — same body and comparable
timing), `403 AUTH.EMAIL_NOT_VERIFIED` (correct password, address not verified: offer "resend"), `403 AUTH.ACCOUNT_SUSPENDED` (correct password),
`429 AUTH.TOO_MANY_ATTEMPTS` (`Retry-After`; per client IP and per address — `email`, `username` and `accountId` of one account share one bucket — also for unknown identifiers).

### POST /api/v1/auth/social/{provider}/login  (existing, public)
Req `{ authorizationCode, redirectUri? }` -> AuthTokenResponse. New outcomes: first social sign-in creates an account when
`skeleton.account.social.sign-up=true`; if the provider's email matches an existing account -> `409 ACCOUNT.SOCIAL_EMAIL_CONFLICT`
(never auto-merged; user signs in with the existing method and links the provider from account settings, section 7).

### POST /api/v1/auth/magic-link/request  (public; module `auth-magic-link`)
Req `{ "email", "captchaToken"? }` -> **always `202`** `{ "value": { "status": "SENT" } }` (silent when over the per-address limit / unknown email while `skeleton.auth-magic-link.sign-up=false`, the default; an EXISTING account always gets the link and can redeem it even with sign-up closed; `429 ACCOUNT.RATE_LIMITED` only when the client IP itself is over its limit).
Mail link: `<link-base-url>/magic-link?token=<token>` (15 min, single use).
### POST /api/v1/auth/magic-link/redeem  (public)
Req `{ "token" }` -> `200` AuthTokenResponse (creates the account on first use if `skeleton.auth-magic-link.sign-up=true`; marks the email verified).
`410 ACCOUNT.TOKEN_INVALID` (also: an address without an account while sign-up is closed); `403 AUTH.ACCOUNT_SUSPENDED`. Redeeming on a still-unverified account discards the password someone set at sign-up and closes that account's sessions (password login then fails with `401 AUTH.INVALID_CREDENTIALS` until a password is reset or set).

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

### POST /api/v1/account/password/forgot  (public) — Req `{ "email", "captchaToken"? }` -> **always `202`** `{ "value": { "status": "ACCEPTED" } }` (except `429 ACCOUNT.RATE_LIMITED` when the client IP itself is over its hourly limit — that says nothing about the address).
No enumeration: the request thread only validates, rate-limits (per IP, per email; silent over limit) and enqueues; lookup + token + mail run off-thread.
Mail link: `<link-base-url>/reset-password?token=<token>` (30 min, single use, a new request invalidates older ones).
### POST /api/v1/account/password/reset  (public) — Req `{ "token", "newPassword" }` -> `204`; `410 ACCOUNT.TOKEN_INVALID`; `400 ACCOUNT.PASSWORD_POLICY`.
Effect: password replaced (hash upgraded to current encoder), ALL sessions revoked, pending sensitive links invalidated, email marked verified (the mailbox was proven), "password changed" mail sent.
### POST /api/v1/account/password/change  (auth) — Req `{ "currentPassword", "newPassword" }` -> `204`.
`currentPassword` is required when the account has a password. A social/magic-link-only account SETs a first password with `confirmationToken` (from `POST /account/reauth/confirmation`, below) instead; email must be verified. Missing token -> `403 ACCOUNT.REAUTH_REQUIRED`, wrong/used/foreign token -> `400 ACCOUNT.REAUTH_FAILED`.
Errors: `400 ACCOUNT.CURRENT_PASSWORD_INVALID` (deliberately 400, not 401/403: the client must not treat it as "session expired"), `400 ACCOUNT.PASSWORD_POLICY`.
Effect: all OTHER sessions revoked; "password changed" mail; pending email-change / delete-confirmation / reauth / magic links are invalidated. `429 ACCOUNT.RATE_LIMITED` (per account).
### GET /api/v1/account/password/policy  (public) -> `{ "value": { "minLength": 10, "maxBytes": 72, "requireLetter": true, "requireDigit": true, "requireSymbol": false, "forbidEmailLocalPart": true } }` (to render hints; the limit is UTF-8 BYTES, not characters — the code name is `maxBytes`).

## 6. Profile, email change (auth)

### GET /api/v1/account/me
`{ "value": { "id", "email", "emailVerified", "displayName", "locale", "timeZone", "roles": [], "status": "ACTIVE", "createdAt",
"methods": [ { "id": "idn_...", "method": "password|magic_link|google|kakao|naver|...", "subject": "a@b.c|null", "verified": true, "createdAt", "lastUsedAt", "removable": true } ],
"hasPassword": true } }` (`subject` is shown for email-like methods only; social subjects are not exposed).
### PATCH /api/v1/account/me — Req any of `{ displayName (1..60), locale (syntactically valid BCP-47 tag, no allowed list), timeZone (IANA) }` -> `200` same as GET. `400 COMMON.VALIDATION_FAILED`.
### POST /api/v1/account/email/change **[idem]** — Req `{ "newEmail", "currentPassword"? }` (password required when the account has one) -> `202 { "value": { "status": "VERIFICATION_SENT" } }`.
Mail to the NEW address with `<link-base-url>/confirm-email-change?token=...` (30 min). The email does not change until confirmed. If the new address belongs to
someone else the answer is still `202` and no link is sent. The OLD address gets a "change requested" mail now in BOTH cases and a "changed" mail on confirmation.
### POST /api/v1/auth/confirm-email-change  (public; the token is the credential) — Req `{ "token" }` -> `204`; `410 ACCOUNT.TOKEN_INVALID`; `409 ACCOUNT.EMAIL_TAKEN` (taken in between).
Effect: email switched, ALL sessions revoked (including the one that confirmed — sign in again). `429 ACCOUNT.RATE_LIMITED` on the request (per account).

## 7. Sign-in methods and social linking (auth)

### GET /api/v1/account/identities -> `{ "values": [ ...same objects as me.methods ] }`
### POST /api/v1/account/identities/social/{provider} — Req `{ "authorizationCode", "redirectUri"?, "currentPassword"?, "confirmationToken"? }` -> `201 { value: identity }` and a notice mail to the account address.
**Re-authentication is required** (server-enforced): `currentPassword` when the account has a password (`400 ACCOUNT.CURRENT_PASSWORD_INVALID`), otherwise `confirmationToken` (`403 ACCOUNT.REAUTH_REQUIRED` / `400 ACCOUNT.REAUTH_FAILED`). This is the server's defence if a callback page forgets the state check; it does not replace it:
**The frontend MUST generate a random OAuth `state` before redirecting to the provider, keep it (sessionStorage) and refuse the callback when the returned `state` does not match — for login AND for linking.** The server cannot see `state`; without the check a victim's browser can be made to submit the attacker's authorization code.
`409 ACCOUNT.IDENTITY_TAKEN` (that provider account belongs to another account), `404 AUTH_SOCIAL.PROVIDER_NOT_FOUND`, `409 ACCOUNT.IDENTITY_EXISTS` (already linked).
### POST /api/v1/account/reauth/confirmation (auth) -> `202 { value: { status: "ACCEPTED" } }`
Mails a one-time link `<link-base-url>/confirm-reauth?token=` (30 min) to the account address; the page hands the token to the action it was for (`confirmationToken` above). Same answer with no address. `429 ACCOUNT.RATE_LIMITED` (5 per hour per account).

### DELETE /api/v1/account/identities/{id} -> `204` (the account's OTHER sessions are revoked); `409 ACCOUNT.LAST_SIGN_IN_METHOD`; `404 ACCOUNT.IDENTITY_NOT_FOUND`.
(The password identity is removed with this too. Removing it is allowed only while another sign-in method remains.)

## 8. Deleting the account (auth)

### POST /api/v1/account/delete/confirmation — (for accounts WITHOUT a password) mails a one-time confirmation link `<link-base-url>/confirm-delete?token=` -> `202`.
### POST /api/v1/account/delete **[idem]** — Req `{ "currentPassword"? , "confirmationToken"? }` (exactly one: password if the account has one, else the mailed token) -> `202 { "value": { "status": "DELETION_SCHEDULED", "purgeAfter": "2026-11-05T..Z" } }`.
Wrong credential -> `400 ACCOUNT.REAUTH_FAILED`; the only ADMIN -> `409 ACCOUNT.LAST_ADMIN`. Calling again while already deleted returns the same `purgeAfter`.
Effect: status DELETED (cannot sign in, sessions revoked, identities frozen); after `skeleton.account.deletion.grace` (default `30d`) the purge job
runs every `AccountErasureListener` (board: author shown as deleted user; notification inbox deleted) and removes the account. Admin may `restore` within the grace.
Data export: no endpoint; `AccountDataExporter` is an interface only (not wired).

## 9. Admin (module `account`, `skeleton.account.admin.enabled=true`; role `skeleton.account.admin.role`, default `ADMIN`; others -> `403 COMMON.FORBIDDEN`; the role is checked against the stored account on every call, so a suspended or demoted admin is refused even with an unexpired token)

- `GET /api/v1/admin/accounts?email=&status=&page=0&size=20` (`page>=0`, `1<=size<=100`, else `400 COMMON.VALIDATION_FAILED`) -> page envelope `{ values: [...], pagination, meta }` of `{ id, email, status, roles, displayName, createdAt, lastLoginAt, suspendedReason, purgeAfter }`
- `GET /api/v1/admin/accounts/{id}` -> `{ value: <same fields> }`
- `POST /api/v1/admin/accounts/{id}/suspend` `{ "reason"? }` -> `204` (sessions revoked) ; `POST .../unsuspend` -> `204`; `POST .../restore` (undo deletion within grace) -> `204`
- `PUT /api/v1/admin/accounts/{id}/roles/{role}` (grant) / `DELETE ...` (revoke) -> `204`. `409 ACCOUNT.LAST_ADMIN` when revoking/suspending the last ADMIN; `409 ACCOUNT.SELF_ACTION_FORBIDDEN` for suspending yourself.

## 10. Error code table

| code | status | when |
|---|---|---|
| AUTH.INVALID_CREDENTIALS | 401 | login: unknown / wrong / deleted |
| AUTH.EMAIL_NOT_VERIFIED | 403 | login with right password, unverified email |
| AUTH.ACCOUNT_SUSPENDED | 403 | login / refresh / magic link for a suspended account |
| AUTH.TOO_MANY_ATTEMPTS | 429 | login throttle |
| AUTH.REFRESH_INVALID | 401 | refresh token unknown/expired/revoked |
| AUTH.REFRESH_REUSED | 401 | rotated-away refresh token replayed (family revoked) |
| AUTH.SESSION_NOT_FOUND | 404 | revoke of a session that is not yours / does not exist |
| AUTH.CSRF_HEADER_REQUIRED | 403 | cookie mode refresh / logout without `X-Requested-With` |
| ACCOUNT.TOKEN_INVALID | 410 | one-time token unknown / expired / used |
| ACCOUNT.PASSWORD_POLICY | 400 | password rejected (`data.violations`) |
| ACCOUNT.CURRENT_PASSWORD_INVALID | 400 | change password / email with wrong current password |
| ACCOUNT.REAUTH_FAILED | 400 | deletion confirmation wrong |
| ACCOUNT.CAPTCHA_FAILED | 400 | captcha rejected (only with `skeleton.account.captcha.required=true`; fails closed if no verifier) |
| ACCOUNT.REAUTH_REQUIRED | 403 | passwordless account did an email change / first password / social link without `confirmationToken` |
| ACCOUNT.EMAIL_TAKEN | 409 | (verification disabled) sign-up / confirm-email-change collision |
| ACCOUNT.SIGN_UP_CLOSED | 403 | `sign-up.enabled=false` |
| ACCOUNT.SOCIAL_EMAIL_CONFLICT | 409 | social email matches an existing account |
| ACCOUNT.IDENTITY_TAKEN / IDENTITY_EXISTS / IDENTITY_NOT_FOUND | 409/409/404 | linking |
| ACCOUNT.LAST_SIGN_IN_METHOD | 409 | unlink the last method |
| ACCOUNT.LAST_ADMIN / SELF_ACTION_FORBIDDEN | 409 | admin guards |
| ACCOUNT.NOT_FOUND | 404 | admin target missing |
| ACCOUNT.RATE_LIMITED | 429 | per-IP (sign-up, resend, forgot, magic link) or per-account (email change, password change, delete, delete confirmation, reauth confirmation, social link) throttle; per-address limits on resend / forgot / magic link are silent |
| ACCOUNT.PASSWORD_REQUIRED | 400 | set-first-password / change with no password identity while the email is unverified |
| ACCOUNT.METHOD_UNKNOWN | 400 | linking an unknown sign-in method code |

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
- FINAL-2 (security review): `maxBytes` (not `maxLength`); `POST /account/reauth/confirmation` + `confirmationToken` on email change / first password / social link, `currentPassword` on social link, `403 ACCOUNT.REAUTH_REQUIRED`; resend `429`; magic link redeems for existing accounts with sign-up closed; a mailbox proof discards an unproven sign-up password; the old address is told of every email-change request; unlink revokes other sessions; admin list paging validated and admin role re-checked on the stored account; login bucket per address; OAuth `state` requirement written down.
- DRAFT-1: initial contract (pre-implementation).
- FINAL-1: reconciled with the code — cookie name `skeleton_refresh`, `X-Device-Name` header (no body field), IP-limit 429 on forgot/magic-link, email-change to a taken address sends nothing, `[idem]` commands, 201 `CREATED` sign-up when verification is off, admin response shapes, extra error codes.
