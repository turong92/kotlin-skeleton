# Account lifecycle — HTTP contract (kotlin-skeleton backend)

Status: **FINAL-4** (= FINAL-3 + the additions listed under "FINAL-4 change list" below — additive: no field, endpoint or error code of FINAL-3 was removed; matches the implemented code after the second security review; the integration tests `apps/api` / `apps/sample` AccountJourneyIntegrationTest and `modules/account` AccountWebTest / AccountSocialWebTest / SessionBodyDeliveryWebTest exercise every flow below).
Audience: the frontend agent (react-skeleton `@skeleton/auth` additions). Backend modules: `account`, `account-jdbc`,
`auth-session`, `auth-session-jdbc`, `auth-magic-link` (+ extensions of `auth`, `auth-social`).

## FINAL-4 change list (additive; FINAL-3 below stays valid)

| # | Where | What changed |
|---|---|---|
| A1 | `POST /account/sign-up` (202) | body gains **`expiresAt`** and **`resendAvailableAt`** (ISO-8601 instants, e.g. `2026-10-07T08:00:00.123456Z`): when the mailed code stops working (now + 10 min) and when "send again" is allowed (now + 30 s). Identical in shape and computation for a new, an in-flight and an already-registered address, and when no mail was sent (address over its mail budget). With `email-verification=false` (201 `CREATED`) there is no attempt, so both fields are absent. |
| A2 | `POST /account/verification/resend` (202) | body is now **`{ status:"ACCEPTED", expiresAt, resendAvailableAt }`**. A mailed new code -> its expiry and "now + 30 s". Inside the cooldown / after the 3rd resend / over the mail budget nothing is mailed and the values are the **current code's** (expiry) and `lastSent + 30 s`. An unknown or already-purged attempt answers the same 202 with plausible values (now + 10 min / now + 30 s) — they are a countdown hint only; `verify-email` is the truth (`410 ACCOUNT.CODE_EXPIRED` -> restart). |
| A3 | `POST /account/email/change` (202), `POST /account/reauth/confirmation` (202), `POST /account/delete/confirmation` (202) | body is **`{ status, expiresAt, resendAvailableAt }`** (`status` stays `VERIFICATION_SENT` / `ACCEPTED`). Same values whether or not a mail went out (taken target address, over budget, account without an address). For these three `resendAvailableAt` is a **UI hint** (the server does not enforce a cooldown; the per-account hourly cap does). |
| A4 | Server clock | `Access-Control-Expose-Headers` now includes **`Date`** (platform CORS default). A frontend served from another origin can read the response `Date` header, compute `skew = serverDate - Date.now()` once per response and run the countdown as `expiresAt - (Date.now() + skew)`. Same-origin deployments never needed it. |
| B1 | Resend of an **expired** sign-up attempt | `POST /account/verification/resend` now **revives** an attempt whose code expired: a new code is mailed, **`signUpId` stays the same**, expiry/attempts restart. The same limits apply (3 resends per attempt, 30 s cooldown, 3 mails per address and hour, 40 guesses per address and hour). It works while the attempt row exists (`skeleton.account.cleanup.expired-retention`, default 1 day after expiry); after that — or after the 3rd resend — the old rule holds: restart the sign-up. Keep the old behaviour in the UI as fallback: `410 CODE_EXPIRED` from `verify-email` after a resend means "start over". |
| B2 | Code lifetime | **Every 6-digit code is valid 10 minutes**: sign-up (unchanged), **email change, re-authentication and delete confirmation (were 30 min)**. The reset **link** (30 min) and the magic link (15 min) are unchanged. The mail text says the minutes. |
| B3 | "You already have an account" mail | no HTTP change (the 202 above is the same), but the mail now carries: a link to the app's login page (`link-base-url` + `mail.login-path`, default `/login`), how the account was created (one sentence per method: "구글로 가입되어 있어요." / "It is signed up with Google."), a **password-reset link** (the normal reset token and the shared per-address reset budget; also offered to accounts without a password — the reset sets one) and, when `auth-magic-link` is in the app, a **one-time sign-in link** (`/magic-link?token=`). Lines are omitted when the account is suspended or a budget is spent. The reset / magic-link pages are therefore also entered from this mail. |
| C1 | `POST /auth/magic-link/redeem`, social login | an owner in the deletion grace with **no `magic_link` method yet** (signed up with a password) now gets `403 AUTH.ACCOUNT_DELETION_PENDING` (+ `data.restoreToken`) like the others — it was `410`. No mail is sent for a grace that already ended. A **suspended** account answers `403 AUTH.ACCOUNT_SUSPENDED` without any login being recorded or a method attached. |
| C2 | `POST /account/email/change/confirm`, `POST /account/identities/social/{provider}` | `403 ACCOUNT.REGISTRATION_BLOCKED` when the new address / provider account is on the re-registration block list (shown only after the mailbox / provider account was proven). |
| C3 | `POST /admin/accounts/{id}/erase` | `503 ACCOUNT.ERASURE_RETRY` when another module's erasure failed (the account is left suspended — call again); `409 ACCOUNT.NOT_SUSPENDED` also when the status changed meanwhile. A claimed account (erase running) cannot be unsuspended / restored. |
| C4 | `PATCH /account/me` with an access token that outlived the erasure | `404 ACCOUNT.NOT_FOUND`, nothing is written. |
| C5 | Cancel of a deletion without an email-verification step (`sign-up.email-verification=false`) | the account returns `ACTIVE` (it was `PENDING_VERIFICATION` before, which that app cannot leave). |

## FINAL-3 change list (before -> after)

| # | Endpoint | Before | After |
|---|---|---|---|
| 1 | `POST /account/sign-up` | 202 `{status}` ; PENDING account row + mailed link | 202 `{ status:"VERIFICATION_SENT", signUpId:"<opaque 43 chars>" }`. **No account exists yet.** The same answer for new / already-in-flight / already-registered address. Mail = 6-digit code (never a link). `verification=false` mode unchanged (201 `{status:"CREATED"}` / 409 EMAIL_TAKEN, no signUpId) |
| 2 | `POST /auth/verify-email` | `{token}` -> 200 `{status:"VERIFIED"}` (no login) | `{ signUpId, code }` -> **200 AuthTokenResponse (signed in at once, same shape as login; header `X-Device-Name` honoured)**. Creates the account with the password typed in THIS sign-up attempt. Errors: `400 ACCOUNT.CODE_INVALID` `data:{attemptsLeft:n}` (wrong code; n counts down 4..1); `410 ACCOUNT.CODE_EXPIRED` (unknown id, expired (10 min), attempts exhausted (5), already used, or the address got registered meanwhile — ONE body: restart sign-up or resend); `429 ACCOUNT.RATE_LIMITED`. `400 COMMON.VALIDATION_FAILED` (code not 6 digits). |
| 3 | `POST /account/verification/resend` | `{email, captchaToken?}` | `{ signUpId, captchaToken? }` -> always 202 `{status:"ACCEPTED"}`. New code for the SAME attempt (expiry +10 min, attempts back to 5); silently ignored inside the 30 s cooldown, after 3 resends, or over the per-address mail budget (FINAL-4: an expired attempt is no longer ignored — it is revived). `429` only for the client IP limit. |
| 4 | `POST /account/email/change` | link mailed to NEW address | Same request body plus the re-auth fields below. 202 `{status:"VERIFICATION_SENT"}`. A **6-digit code** is mailed to the NEW address (taken address: nothing sent, same 202, same `pendingEmail`). Old address still gets the notice. |
| 5 | `POST /account/email/change/confirm` | (did not exist) | **auth.** `{ code }` -> `204`. Bound to the session that requested. `400 ACCOUNT.CODE_INVALID {attemptsLeft}`, `410 ACCOUNT.CODE_EXPIRED` (none open / expired / exhausted / other session), `409 ACCOUNT.EMAIL_TAKEN`. Effect: email switched, all OTHER sessions revoked (the confirming session stays signed in). |
| 6 | `POST /auth/confirm-email-change` | public link endpoint | **REMOVED** — no such endpoint any more; no mailed email-change link exists. |
| 7 | `POST /account/reauth/confirmation` | mailed one-time link | mails a **6-digit code** to the account address (bound to account + session, 10 min [FINAL-4; was 30], 5 attempts; a new request replaces the open one). 202 always; nothing for an account without address. |
| 8 | field `confirmationToken` (email change, first password `password/change`, social link, delete) | token from the mailed link | **renamed `confirmationCode`** (the 6 digits). Wrong -> `400 ACCOUNT.CODE_INVALID {attemptsLeft}`; none open / expired / exhausted / other session -> `410 ACCOUNT.CODE_EXPIRED`; missing while required -> `403 ACCOUNT.REAUTH_REQUIRED` (unchanged). `ACCOUNT.REAUTH_FAILED` is now only for a failed social re-auth / delete proof. |
| 9 | `POST /account/delete/confirmation` + `POST /account/delete` | link token | code mailed (bound to account + session); `delete` body `{ currentPassword?, confirmationCode?, socialReauth? }` |
| 10 | `DELETE /account/identities/{id}` | no body, no re-auth | **optional JSON body `{ currentPassword?, confirmationCode?, socialReauth? }`, re-auth REQUIRED** (same rule as social link): password account -> `currentPassword` (`400 CURRENT_PASSWORD_INVALID`); passwordless with address -> `confirmationCode` (`403 REAUTH_REQUIRED` / `400 CODE_INVALID` / `410 CODE_EXPIRED`); no address -> `socialReauth`. |
| 11 | accounts WITHOUT an email (Naver, unverified-provider sign-ups) | exempt from re-auth, could not delete | field **`socialReauth: { provider, authorizationCode, redirectUri? }`** accepted on email change, social link, unlink, delete: a FRESH authorization code of a provider already linked to this account. Missing -> `403 ACCOUNT.REAUTH_REQUIRED`; code of another account / invalid -> `400 ACCOUNT.REAUTH_FAILED`; provider error -> existing `502`-class social errors. Account deletion is therefore possible for them. Frontend: run the provider consent again (same redirect as login), pass the code here. |
| 12 | `POST /account/identities/social/{provider}` | `confirmationToken` | `confirmationCode` / `socialReauth` as above |
| 13 | password reset, magic link | link | **unchanged (links)**: no session exists in those flows |
| 14 | `AUTH.EMAIL_NOT_VERIFIED` on login | seen for fresh sign-ups | practically never: unverified accounts no longer exist in the default mode (code kept) |

New error codes: `ACCOUNT.CODE_INVALID` 400 (`data.attemptsLeft`), `ACCOUNT.CODE_EXPIRED` 410. Removed from the flows above: `ACCOUNT.TOKEN_INVALID` stays for reset / magic link only.

### FINAL-3 clarifications (2026-10-07 — no endpoint, field or error code added or removed; only behaviour under load / abuse)
- `POST /account/email/change/confirm` can now answer **`429 ACCOUNT.RATE_LIMITED`** (`Retry-After`, `data.retryAfterSeconds`): per client IP (same bucket as `POST /auth/verify-email`, 30 / hour by default; IPv6 counts per /64) and per target address (all code guesses against one mailbox, sign-up and email change together, 40 / hour). A 429 spends no attempt and does not reveal anything about the address — show "try again later".
- `POST /auth/verify-email` can also answer `429` for the per-address guess cap (it already did for the IP cap); the IP buckets of sign-up, resend, verify, forgot, magic link and login are keyed per IPv6 /64 now (IPv4 unchanged).
- `POST /account/email/change` is still always `202`, also when the target address is over its code budget or belongs to someone else: `GET /account/me` shows the same `pendingEmail` / `pendingEmailExpiresAt`, entering a code counts down the same `attemptsLeft`, and such a request simply never receives a code. Treat "no code arrived" as the only signal.
- `POST /account/email/change/confirm` answers `410 ACCOUNT.CODE_EXPIRED` also when the mailbox of the account was proven (password reset / sign-up code) after the change was requested - ask again.
- A late social link (`POST /account/identities/social/{provider}`) whose re-authentication was overtaken by such a proof answers `400 ACCOUNT.REAUTH_FAILED` (existing code) and links nothing - retry from the start.
- `POST /account/email/change` for an address that equals a bootstrap-admin address never grants ADMIN (only a sign-up code verification, a mailbox proof by reset / magic link / verified social, or an admin grant does).
- Mail subjects no longer contain the 6-digit code (it is only in the body) - do not parse subjects.
- Resend (`POST /account/verification/resend`) behaves identically (attempts reset, expiry +10 min, cooldown) whether or not a verified account owns the address; clients must not infer anything from `attemptsLeft` after a resend.

### Codes vs links
- **Codes** (a signed-in or same-browser session exists and the code is entered there): sign-up verification, email change, re-auth for passwordless accounts, delete confirmation.
- **Links** (no session exists / user may open the mail on another device): password reset, magic-link sign-in.
- Mail templates: ko/en; code mails say "ignore if you did not request this / never tell anyone this code".
| 15 | `POST /auth/refresh` | `401`s only | adds **`429 AUTH.TOO_MANY_REFRESHES`** (`Retry-After`, `data.retryAfterSeconds`): one session may rotate at most 30 times per 10 minutes. The session is NOT revoked — do not sign out; wait and retry (single-flight already does one refresh per 15 min). |
| 16 | `POST /account/password/change` | field `confirmationToken` | field **`confirmationCode`** (6 digits) |
| 17 | `GET /account/me` `pendingEmail` | code-less link state | unchanged shape; it now means "a code was mailed to that address and waits for `POST /account/email/change/confirm`" |

Corrections to the draft that was sent earlier: the 429 and field-rename rows (15, 16) are new; everything else matches it. Names the frontend already built against are kept: `signUpId`, `verifySignUpCode({signUpId, code})` (tokens on success), `ACCOUNT.CODE_INVALID` + `data.attemptsLeft`, `ACCOUNT.CODE_EXPIRED`, `resendSignUpCode({signUpId})`, `confirmationCode`, `socialReauth`, `unlinkIdentity(id, reauth)`, `confirmEmailChangeCode`. **Differences:** unlink is `DELETE /account/identities/{id}` with an optional JSON body (not a new path); email-change confirmation is `POST /account/email/change/confirm`.

### Answers the frontend asked for (each is a tested contract)
1. **`POST /auth/logout` with a rotated-out (previous) refresh token closes the session** (the token is looked up by hash whether or not it was already used) — `SessionServiceTest` "logging out with a rotated-out refresh token still closes the session…". The pruning of old rows is NOT used for bounding (rows are bounded by the rotation rate limit instead), so a previous token is always still known.
2. **Social link checks the re-auth proof BEFORE exchanging the authorization code with the provider** (a wrong password / missing / wrong code never calls the provider, so the single-use provider code is not burned and the frontend may retry with the same code) — `SocialLinkServiceTest` "the re-authentication proof is checked BEFORE the authorization code is exchanged…". The proof is then spent before the identity is linked (one code authorizes one link).
3. **The re-auth proof is bound server-side to the account and to the session** (`sid` of the access token; the code is rejected with `410 ACCOUNT.CODE_EXPIRED` from another session or another account) and expires after 10 min / 5 guesses (FINAL-4; was 30 min). It is **not** bound to a specific action (a `reauth/confirmation` code works for email change, first password, social link and unlink — one use); the delete code is separate (`delete/confirmation`). If the app runs without `auth-session` there is no `sid` and the binding is to the account only.
4. **Nothing in the backend relies on `Referer`.** The cookie-mode CSRF guard checks the `X-Requested-With: fetch` header (a custom header forces a CORS preflight), plus SameSite=Strict and the CORS allow-list; **the backend does not check `Origin` itself** (CORS does for browsers) — so `Referrer-Policy: no-referrer` is safe, and nothing requires the frontend to send `Origin`.
5. **PKCE** (`code_challenge` / `codeVerifier`, `AUTH.SOCIAL_PKCE_FAILED`, `pkce` in `GET /auth/methods`): **implemented — see "FINAL-3 + social PKCE (addendum)" at the end of this file.** The OAuth `state` check (section 7) stays the first login-CSRF defence; PKCE is the second where the provider supports it.


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
  **Lost response (the page navigated away while `/auth/refresh` was in flight)**: apps/api and apps/sample set `reuse-grace: 10s` — presenting the immediately previous token again within 10 s returns the SAME successor token (idempotent rotation: no second token, no revoked session). After the grace the replay is treated as theft. Do not rely on more than a few seconds; the module default is `0s`.

## 2. Sign-up and email verification (by a 6-digit code bound to the sign-up attempt)

A sign-up creates an **attempt**, not an account: (attempt id, email, the password typed in THIS attempt, code). Nothing about the account's credentials is stored until a code is verified.
The code goes only to the mailbox; the attempt id (`signUpId`) only to the browser that signed up. Several attempts for one address coexist and never overwrite each other.

### POST /api/v1/account/sign-up  (public)
Req: `{ "email": "a@b.c", "password": "...", "displayName"?: "Ann", "locale"?: "ko", "timeZone"?: "Asia/Seoul", "captchaToken"?: "...", "consents"?: [{ "type", "version", "locale"? }] }` — `consents` (max 8) is used only when the app has the `legal` module: a missing / stale claim is `400 LEGAL.CONSENT_REQUIRED` (same for every address), the accepted claims are recorded when the code is verified, in the transaction that creates the account — see [legal-http-contract.md](legal-http-contract.md) section 3.
Res: **always `202`** `{ "value": { "status": "VERIFICATION_SENT", "signUpId": "<43 chars, opaque>", "expiresAt": "2026-10-07T08:10:00.123456Z", "resendAvailableAt": "2026-10-07T08:00:30.123456Z" } }` — identical in shape whether the address is new, has another attempt in flight, or is already registered
(a registered address receives an "already registered" mail instead of a code, and entering codes for that attempt behaves like wrong guesses on a new address). Keep `signUpId` in memory/sessionStorage for the next step. `expiresAt` / `resendAvailableAt` drive the countdown and the "send again" button (read the response `Date` header when the API is on another origin — it is exposed); they are the same for every address, mailed or not.
With `skeleton.account.sign-up.email-verification=false` the app accepts the enumeration trade-off: new -> `201 {status:"CREATED"}` (no `signUpId`; the account is active at once), existing -> `409 ACCOUNT.EMAIL_TAKEN`.
Errors: `400 ACCOUNT.PASSWORD_POLICY` (`data: { violations: ["TOO_SHORT", ...] }`), `400 COMMON.VALIDATION_FAILED`, `400 ACCOUNT.CAPTCHA_FAILED`, `403 ACCOUNT.SIGN_UP_CLOSED`, `429 ACCOUNT.RATE_LIMITED`.
Mail: a 6-digit code (valid 10 minutes) — **no link**.

### POST /api/v1/auth/verify-email  (public)
Req `{ "signUpId": "...", "code": "123456" }` (`code` must be exactly 6 digits, else `400 COMMON.VALIDATION_FAILED`)
Res `200` **AuthTokenResponse** (section 1) — the account is created with the password typed in this attempt, the email is verified, and the browser is **signed in at once** (header `X-Device-Name` honoured as on login; no separate login call).
Errors: `400 ACCOUNT.CODE_INVALID` `data: { attemptsLeft: n }` (wrong code; 5 guesses per code — n goes 4, 3, 2, 1; the 5th wrong guess answers 410), `410 ACCOUNT.CODE_EXPIRED` (ONE body for: unknown id, expired after 10 min, guesses used up, already used, address taken meanwhile — restart the sign-up or resend), `429 ACCOUNT.RATE_LIMITED` (per IP, 30 code entries per hour).

### POST /api/v1/account/verification/resend  (public)
Req `{ "signUpId": "...", "captchaToken"?: "..." }` -> **always `202 { status:"ACCEPTED", expiresAt, resendAvailableAt }`**: a new code for the SAME attempt (expiry restarts, guesses back to 5) — **also for an attempt whose code expired** (the attempt is revived under the same `signUpId`; FINAL-4). Silently ignored (values = the current code's) inside the 30 s cooldown, after 3 resends, for an unknown / purged attempt (plausible values), or over the per-address mail budget (3 mails per hour, shared with sign-up). `429 ACCOUNT.RATE_LIMITED` only when the client IP is over its limit (10 per hour).

Brute-force bound (docs/accounts.md): at most ~40 guesses per address per hour -> <= 0.004 %.

## 3. Login (existing endpoint, extended)

### POST /api/v1/auth/login  (public)
Req unchanged: one of `accountId | username | email` + `password` (optional header `X-Device-Name`). `username` of an account created here equals its email.
Res `200` AuthTokenResponse. Errors: `401 AUTH.INVALID_CREDENTIALS` (unknown account, wrong password, deleted account — same body and comparable
timing), `403 AUTH.EMAIL_NOT_VERIFIED` (only for legacy unverified accounts; fresh sign-ups have no account until verified — a login before verification is `401 AUTH.INVALID_CREDENTIALS`), `403 AUTH.ACCOUNT_SUSPENDED` (correct password),
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
`410 ACCOUNT.TOKEN_INVALID` (also: an address without an account while sign-up is closed); `403 AUTH.ACCOUNT_SUSPENDED`. Redeeming on a still-unverified account (verification-off apps only) removes every sign-in method someone else planted and closes that account's sessions and open codes (password login then fails with `401 AUTH.INVALID_CREDENTIALS` until a password is reset or set).

### GET /api/v1/auth/methods  (public, no auth; `Cache-Control: public, max-age=300`; the same answer for everyone)
`200 { "value": { "methods": ["password", "magic_link"], "signUp": { "password": true, "emailVerification": true, "social": true }, "social": [ { "provider": "google", "clientId": "…"|null, "redirectUri": "…"|null } ], "captchaRequired": false, "refreshDelivery": "body"|"cookie"|null } }`
`methods` lists the non-social methods the backend registered (`password` first); `social` the enabled providers with the PUBLIC values needed to start the redirect (the client id is in the authorization URL anyway; no secret, no account data); `refreshDelivery` is null when `auth-session` is not installed. Use it instead of hand-synced env vars.

## 4. Sessions (module `auth-session`)

### POST /api/v1/auth/refresh  (public; credential = refresh token)
Req `{ "refreshToken": "r1...." }` (body mode) | cookie (cookie mode). Res `200` AuthTokenResponse with a rotated refresh token.
Errors: `401 AUTH.REFRESH_INVALID` (unknown/expired/revoked), `401 AUTH.REFRESH_REUSED` (rotated-away token replayed -> family revoked),
`403 AUTH.ACCOUNT_SUSPENDED`, `429 AUTH.TOO_MANY_REFRESHES` (this session rotated more than 30 times in 10 minutes; `Retry-After`; the session stays valid — do not sign out). Roles in the new access token are the account's CURRENT roles.
### POST /api/v1/auth/logout  (public; idempotent) -> always `204`. Req `{ "refreshToken"? }` / cookie. Revokes that session (also when the token presented was already rotated away — a client that lost the rotation race can still sign out); clears the cookie.
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
Effect: password replaced (hash upgraded to current encoder), ALL sessions revoked, open codes and links invalidated, email marked verified (the mailbox was proven; in verification-off apps every sign-in method planted by a stranger is removed), "password changed" mail sent.
### POST /api/v1/account/password/change  (auth) — Req `{ "currentPassword", "newPassword" }` -> `204`.
`currentPassword` is required when the account has a password. A social/magic-link-only account SETs a first password with `confirmationCode` (the 6 digits from `POST /account/reauth/confirmation`, below) instead; email must be verified. Missing code -> `403 ACCOUNT.REAUTH_REQUIRED`, wrong -> `400 ACCOUNT.CODE_INVALID` (`data.attemptsLeft`), none open / expired / used up / other session -> `410 ACCOUNT.CODE_EXPIRED`.
Errors: `400 ACCOUNT.CURRENT_PASSWORD_INVALID` (deliberately 400, not 401/403: the client must not treat it as "session expired"), `400 ACCOUNT.PASSWORD_POLICY`.
Effect: all OTHER sessions revoked; "password changed" mail; open email-change / delete / reauth codes and magic links are invalidated. `429 ACCOUNT.RATE_LIMITED` (per account).
### GET /api/v1/account/password/policy  (public) -> `{ "value": { "minLength": 10, "maxBytes": 72, "requireLetter": true, "requireDigit": true, "requireSymbol": false, "forbidEmailLocalPart": true } }` (to render hints; the limit is UTF-8 BYTES, not characters — the code name is `maxBytes`).

## 6. Profile, email change (auth)

### GET /api/v1/account/me
`{ "value": { "id", "email", "emailVerified", "displayName", "locale", "timeZone", "roles": [], "status": "ACTIVE", "createdAt",
"methods": [ { "id": "idn_...", "method": "password|magic_link|google|kakao|naver|...", "subject": "a@b.c|null", "verified": true, "createdAt", "lastUsedAt", "removable": true } ],
"hasPassword": true, "pendingEmail": "new@b.c" | null, "pendingEmailExpiresAt": "...Z" | null } }` (`subject` is shown for email-like methods only; social subjects are not exposed). `pendingEmail`/`pendingEmailExpiresAt` are set while an email change waits for the new address' confirmation (until confirmed, superseded or expired) — use them to restore the "check the new address" state after a reload. A change to an address that belongs to someone else shows nothing pending (no oracle).
### PATCH /api/v1/account/me — Req any of `{ displayName (1..60), locale (syntactically valid BCP-47 tag, no allowed list), timeZone (IANA) }` -> `200` same as GET. `400 COMMON.VALIDATION_FAILED`.
### POST /api/v1/account/email/change **[idem]** — Req `{ "newEmail", "currentPassword"?, "confirmationCode"?, "socialReauth"? }` -> `202 { "value": { "status": "VERIFICATION_SENT", "expiresAt": "..Z", "resendAvailableAt": "..Z" } }`.
Re-authentication (server enforced): `currentPassword` when the account has a password (`400 ACCOUNT.CURRENT_PASSWORD_INVALID`); otherwise, with an address, `confirmationCode` from `POST /account/reauth/confirmation` (`403 REAUTH_REQUIRED` / `400 CODE_INVALID` / `410 CODE_EXPIRED`); an account WITHOUT an address uses `socialReauth` (below).
A **6-digit code** is mailed to the NEW address (valid 10 min [FINAL-4; was 30], 5 guesses, bound to the requesting session). Nothing changes until it is entered. The pending change is stored before the `202` is returned: an immediate `GET /account/me` shows `pendingEmail`. If the new address belongs to someone else the answer is still `202`, `pendingEmail` shows the same, and no code is sent (entering codes counts down like a wrong guess). The OLD address gets a "change requested" mail in BOTH cases and a "changed" mail on confirmation.
`4xx` answers to this call are **not** stored under the `Idempotency-Key` (a mistyped password does not stick to the key); a `2xx` is replayed.
### POST /api/v1/account/email/change/confirm  (auth) — Req `{ "code": "123456" }` -> `204`.
Must be the session that requested it. `400 ACCOUNT.CODE_INVALID` (`data.attemptsLeft`), `410 ACCOUNT.CODE_EXPIRED` (none open, expired, used up, other session), `409 ACCOUNT.EMAIL_TAKEN` (taken in between). Effect: email switched, the OTHER sessions revoked (the confirming session stays signed in; its token still carries the old email claim until the next refresh), open reauth / delete codes and the old address's magic links are closed. `429 ACCOUNT.RATE_LIMITED` on the request (per account).
(`POST /auth/confirm-email-change` no longer exists.)

## 7. Sign-in methods and social linking (auth)

### GET /api/v1/account/identities -> `{ "values": [ ...same objects as me.methods ] }`
### POST /api/v1/account/identities/social/{provider} — Req `{ "authorizationCode", "redirectUri"?, "currentPassword"?, "confirmationCode"?, "socialReauth"? }` -> `201 { value: identity }` and a notice mail to the account address.
**Re-authentication is required** (server-enforced, checked BEFORE the provider code is exchanged, spent before linking): `currentPassword` when the account has a password (`400 ACCOUNT.CURRENT_PASSWORD_INVALID`); otherwise `confirmationCode` (`403 ACCOUNT.REAUTH_REQUIRED` / `400 ACCOUNT.CODE_INVALID` / `410 ACCOUNT.CODE_EXPIRED`); an account without an address: `socialReauth`.
**`socialReauth`: `{ "provider": "naver", "authorizationCode": "...", "redirectUri"?: "..." }`** — a FRESH authorization code of a provider that is already linked to THIS account (run the provider consent again, same redirect as login). A code of another account's provider identity, an unlinked provider, or a refused code -> `400 ACCOUNT.REAUTH_FAILED`; missing -> `403 ACCOUNT.REAUTH_REQUIRED`. Provider outages surface as the existing social gateway error. It is accepted only for accounts that have no email (Naver, sign-ups whose provider did not vouch for the address).
**The frontend MUST generate a random OAuth `state` before redirecting to the provider, keep it (sessionStorage) and refuse the callback when the returned `state` does not match — for login AND for linking.** The server cannot see `state`; without the check a victim's browser can be made to submit the attacker's authorization code.
`409 ACCOUNT.IDENTITY_TAKEN`, `404 AUTH_SOCIAL.PROVIDER_NOT_FOUND`, `409 ACCOUNT.IDENTITY_EXISTS`.
### POST /api/v1/account/reauth/confirmation (auth) -> `202 { value: { status: "ACCEPTED", expiresAt, resendAvailableAt } }`
Mails a **6-digit code** to the account address (valid 10 min, 5 guesses, usable only by this session, a new request replaces the open one). Use it as `confirmationCode` for email change, first password, social link and unlink. Same answer with no address. `429 ACCOUNT.RATE_LIMITED` (5 per hour per account).

### DELETE /api/v1/account/identities/{id} — optional JSON body `{ "currentPassword"?, "confirmationCode"?, "socialReauth"? }` -> `204` (the account's OTHER sessions are revoked); `409 ACCOUNT.LAST_SIGN_IN_METHOD`; `404 ACCOUNT.IDENTITY_NOT_FOUND`.
**Re-authentication is required** (same proof rules as social link; an absent body gets the error that fits the account: `400 CURRENT_PASSWORD_INVALID` / `403 REAUTH_REQUIRED`). The password identity is removed with this too, allowed only while another sign-in method remains.

## 8. Deleting the account (auth)

### POST /api/v1/account/delete/confirmation — (for accounts WITHOUT a password) mails a **6-digit code** (valid 10 min, 5 guesses, this session only) -> `202 { status:"ACCEPTED", expiresAt, resendAvailableAt }`. Nothing is sent to an account without an address (use `socialReauth`).
### POST /api/v1/account/delete **[idem]** — Req `{ "currentPassword"?, "confirmationCode"?, "socialReauth"? }` (the proof that fits the account: password; else the mailed code; an account without an address: `socialReauth`) -> `202 { "value": { "status": "DELETION_SCHEDULED", "purgeAfter": "2026-11-05T..Z" } }`.
A SUSPENDED account cannot delete itself -> `403 ACCOUNT.SUSPENDED_CANNOT_DELETE` (also for `delete/confirmation`).
Wrong password / wrong social proof -> `400 ACCOUNT.REAUTH_FAILED`; missing -> `403 ACCOUNT.REAUTH_REQUIRED`; wrong code -> `400 ACCOUNT.CODE_INVALID`, none/expired -> `410 ACCOUNT.CODE_EXPIRED`; the only ADMIN -> `409 ACCOUNT.LAST_ADMIN`. Calling again while already deleted returns the same `purgeAfter`. `4xx` answers are not stored under the `Idempotency-Key`.
Effect: status DELETED (cannot sign in, sessions revoked, identities frozen); after `skeleton.account.deletion.grace` (default `30d`) the purge job
runs every `AccountErasureListener` (board: author shown as deleted user; notification inbox deleted) and then, by `skeleton.account.deletion.mode`:
`ANONYMIZE` (default) keeps the row with status **`ERASED`** (id + created_at only; email, name, locale, time zone, sign-in methods, roles, tokens, codes and the IP/detail of audit rows are gone) —
`DELETE` removes the row. Admin may `restore` within the grace; an erased account is never restorable (`410 ACCOUNT.ERASED`). The same email can sign up again and gets a **new** account id; the same provider subject signing in gets a new account too.
`me` of an erased account -> `404 ACCOUNT.NOT_FOUND`.

### Self-service cancel of a pending deletion — only with `skeleton.account.deletion.self-restore=true` (default `false`)
Frontend flow for a "탈퇴를 취소할까요?" screen:
1. The owner signs in with ANY valid method (password `POST /auth/login`, magic link `POST /auth/magic-link/redeem`, social login) while the account is DELETED and the grace is not over.
   A wrong password / unknown code is the usual `401 AUTH.INVALID_CREDENTIALS` (nothing about the account is revealed). A **correct** proof does NOT open a session; it answers
   `403 { "code": "AUTH.ACCOUNT_DELETION_PENDING", "data": { "purgeAfter": "2026-11-05T..Z", "restoreToken": "<opaque>", "restoreTokenExpiresAt": "..Z" } }`.
   No `accessToken` / `refreshToken`, no login is recorded. Not given for SUSPENDED accounts (they get `403 AUTH.ACCOUNT_SUSPENDED`) nor after the grace (`401`).
2. Show "삭제 예정일 = purgeAfter — 취소할까요?". On yes: `POST /api/v1/account/delete/cancel` (public) Req `{ "restoreToken": "<token>" }` ->
   `200 { "value": <AuthTokenResponse> }` — exactly the login response: the account is ACTIVE again, tokens are issued, a notice mail is sent (`DELETION_CANCELLED`), the event `DELETION_CANCELLED` is published.
   On no: just drop the token (it dies after `skeleton.account.deletion.self-restore-ttl`, default `15m`; asking for the state again — signing in again — replaces it).
3. Errors: `410 ACCOUNT.TOKEN_INVALID` (unknown, used, expired, made for anything else, the grace ended, or the account got suspended meanwhile — start again from the sign-in), `400 COMMON.VALIDATION_FAILED` (no token), `429 ACCOUNT.RATE_LIMITED` (per client address, same budget as login).
The token is single-use, works only for this endpoint and is created only AFTER a successful authentication. With the setting off, `delete/cancel` answers `410 ACCOUNT.TOKEN_INVALID` and a deleted account signs in as `401` like before.
Data export: no endpoint; `AccountDataExporter` is an interface only (not wired).

## 9. Admin (module `account`, `skeleton.account.admin.enabled=true`; role `skeleton.account.admin.role`, default `ADMIN`; others -> `403 COMMON.FORBIDDEN`; the role is checked against the stored account on every call, so a suspended or demoted admin is refused even with an unexpired token)

- `GET /api/v1/admin/accounts?email=&status=&page=0&size=20` (`page>=0`, `1<=size<=100`, else `400 COMMON.VALIDATION_FAILED`) -> page envelope `{ values: [...], pagination, meta }` of `{ id, email, status, roles, displayName, createdAt, lastLoginAt, suspendedReason, purgeAfter, erasedAt }`.
  `status` is one of `ACTIVE | PENDING_VERIFICATION | SUSPENDED | DELETED | ERASED`. **Without `status`, `ERASED` accounts are left out**; `?status=ERASED` lists them — an erased row has `email`, `displayName`, `lastLoginAt`, `suspendedReason`, `purgeAfter` all `null` and `roles: []`, `erasedAt` set.
- `GET /api/v1/admin/accounts/{id}` -> `{ value: <same fields> }` (an erased account is still readable by id)
- `POST /api/v1/admin/accounts/{id}/suspend` `{ "reason"? }` -> `204` (sessions revoked; an account in its deletion grace can be suspended too — it is then NOT erased when the grace ends) ; `POST .../unsuspend` -> `204` (an account that was leaving returns to DELETED with a fresh grace); `POST .../restore` (undo deletion within grace) -> `204`.
  `410 ACCOUNT.ERASED` for restore / suspend / grant on an erased account (it cannot be restored, ever); `404 ACCOUNT.NOT_FOUND` for restore of an account that is not DELETED.
- `POST /api/v1/admin/accounts/{id}/erase` `{ "reason"? }` -> `204` — erase a **SUSPENDED** account at once (no grace): same erasure as the purge job (`deletion.mode`) plus **re-registration blocks** (below). `409 ACCOUNT.NOT_SUSPENDED` for any other status, `410 ACCOUNT.ERASED` when already erased, `409 ACCOUNT.SELF_ACTION_FORBIDDEN` for yourself.
- `GET /api/v1/admin/accounts/blocks?page=0&size=20` -> page of `{ id, kind: "email"|"identity", reason, createdAt, expiresAt, createdBy, accountId }` (newest first; **no hash, no address**); `DELETE /api/v1/admin/accounts/blocks/{id}` -> `204` / `404 ACCOUNT.NOT_FOUND`.
  A block makes `verify-email` (sign-up code), magic-link redeem and social sign-in for that email / provider account answer `403 ACCOUNT.REGISTRATION_BLOCKED`; the sign-up REQUEST itself still answers the same `202` (so existence and blocks cannot be probed without proving the mailbox / provider account). With `sign-up.email-verification=false` a blocked address answers like a taken one (`409 ACCOUNT.EMAIL_TAKEN`).
- `PUT /api/v1/admin/accounts/{id}/roles/{role}` (grant) / `DELETE ...` (revoke) -> `204`. `409 ACCOUNT.LAST_ADMIN` when revoking/suspending the last ADMIN; `409 ACCOUNT.SELF_ACTION_FORBIDDEN` for suspending yourself.

## 10. Error code table

| code | status | when |
|---|---|---|
| AUTH.INVALID_CREDENTIALS | 401 | login: unknown / wrong / deleted |
| AUTH.EMAIL_NOT_VERIFIED | 403 | login with right password, unverified email |
| AUTH.ACCOUNT_SUSPENDED | 403 | login / refresh / magic link for a suspended account |
| AUTH.ACCOUNT_DELETION_PENDING | 403 | (`deletion.self-restore=true`) correct sign-in of an account in its deletion grace — `data.purgeAfter`, `data.restoreToken`; see §8 |
| AUTH.TOO_MANY_ATTEMPTS | 429 | login throttle |
| AUTH.REFRESH_INVALID | 401 | refresh token unknown/expired/revoked |
| AUTH.REFRESH_REUSED | 401 | rotated-away refresh token replayed (family revoked) |
| AUTH.SESSION_NOT_FOUND | 404 | revoke of a session that is not yours / does not exist |
| AUTH.CSRF_HEADER_REQUIRED | 403 | cookie mode refresh / logout without `X-Requested-With` |
| ACCOUNT.TOKEN_INVALID | 410 | one-time **link** token unknown / expired / used (password reset, magic link, deletion restore token) |
| ACCOUNT.CODE_INVALID | 400 | wrong 6-digit code (`data.attemptsLeft`) — sign-up, email change, re-auth, delete |
| ACCOUNT.CODE_EXPIRED | 410 | code unknown / expired / used up / other session / sign-up address taken meanwhile |
| AUTH.TOO_MANY_REFRESHES | 429 | one session refreshed more than 30 times in 10 min (session stays valid) |
| ACCOUNT.PASSWORD_POLICY | 400 | password rejected (`data.violations`) |
| ACCOUNT.CURRENT_PASSWORD_INVALID | 400 | change password / email with wrong current password |
| ACCOUNT.REAUTH_FAILED | 400 | delete with a wrong password, or a failed `socialReauth` |
| ACCOUNT.CAPTCHA_FAILED | 400 | captcha rejected (only with `skeleton.account.captcha.required=true`; fails closed if no verifier) |
| ACCOUNT.REAUTH_REQUIRED | 403 | passwordless account did an email change / first password / social link / unlink / delete without `confirmationCode` (or an address-less account without `socialReauth`) |
| ACCOUNT.EMAIL_TAKEN | 409 | (verification disabled) sign-up / confirm-email-change collision |
| ACCOUNT.SIGN_UP_CLOSED | 403 | `sign-up.enabled=false` |
| ACCOUNT.SOCIAL_EMAIL_CONFLICT | 409 | social email matches an existing account |
| ACCOUNT.IDENTITY_TAKEN / IDENTITY_EXISTS / IDENTITY_NOT_FOUND | 409/409/404 | linking |
| ACCOUNT.LAST_SIGN_IN_METHOD | 409 | unlink the last method |
| ACCOUNT.LAST_ADMIN / SELF_ACTION_FORBIDDEN | 409 | admin guards |
| ACCOUNT.NOT_FOUND | 404 | admin target missing; `me` / restore of a row that is gone or not DELETED |
| ACCOUNT.ERASED | 410 | admin restore / suspend / grant / erase on an erased account |
| ACCOUNT.SUSPENDED_CANNOT_DELETE | 403 | a suspended account asked to delete itself |
| ACCOUNT.NOT_SUSPENDED | 409 | admin erase of an account that is not SUSPENDED |
| ACCOUNT.REGISTRATION_BLOCKED | 403 | a proven mailbox / provider account is on the re-registration block list (sign-up code, magic link, social sign-in, **email-change confirmation, social link**) or belongs to a suspended unproven account |
| ACCOUNT.ERASURE_RETRY | 503 | admin erase: another module's erasure failed, the account is left as it was — call again |
| ACCOUNT.RATE_LIMITED | 429 | per-IP (sign-up, resend, forgot, magic link) or per-account (email change, password change, delete, delete confirmation, reauth confirmation, social link) throttle; per-address limits on resend / forgot / magic link are silent |
| ACCOUNT.PASSWORD_REQUIRED | 400 | set-first-password / change with no password identity while the email is unverified |
| ACCOUNT.METHOD_UNKNOWN | 400 | linking an unknown sign-in method code |

## 11. Flows (sequence lists)

**Sign up + verify**: POST sign-up(202 + signUpId) -> mail(6-digit code) -> user types the code -> POST verify-email{signUpId, code}(200 + tokens, signed in). Wrong code -> 400 CODE_INVALID (show attemptsLeft); 410 CODE_EXPIRED -> back to the sign-up form; "send again" -> POST verification/resend{signUpId}(202).
**Silent refresh**: on 401 from API -> (single-flight) POST refresh{refreshToken} -> 200 new pair -> retry once; 401 AUTH.REFRESH_* -> clear tokens, go to login; 429 AUTH.TOO_MANY_REFRESHES -> wait Retry-After, keep the session.
**Forgot**: POST forgot(202) -> mail link -> `/reset-password?token` -> POST reset{token,newPassword}(204) -> go to login (all sessions are gone).
**Change password**: POST change(204) -> keep using the current session; other devices are signed out.
**Magic link**: POST magic-link/request(202) -> mail link -> `/magic-link?token` -> POST redeem(200 tokens).
**Change email**: POST email/change{newEmail, proof}(202) -> code mailed to the NEW address -> POST email/change/confirm{code}(204), in the same signed-in session.
**Passwordless action (change email / first password / link / unlink)**: POST reauth/confirmation(202) -> code mailed -> the action with `confirmationCode`.
**Address-less account (Naver ...) doing the same**: run the provider consent again -> the action with `socialReauth{provider, authorizationCode, redirectUri?}`.
**Link Google**: (logged in) provider consent -> POST identities/social/google{code, proof}(201) -> GET identities.
**Delete**: password account: POST delete{currentPassword}(202) -> sign out locally. Passwordless: POST delete/confirmation(202) -> code mailed -> POST delete{confirmationCode}(202). Address-less: POST delete{socialReauth}(202).

## Changelog
- FINAL-4: `expiresAt` / `resendAvailableAt` on sign-up, resend, email change, reauth confirmation and delete confirmation; resend revives expired sign-up attempts (same `signUpId`); every 6-digit code is valid 10 minutes (email change / re-auth / delete confirmation were 30); richer "already registered" mail (login link, methods, reset link, optional magic link); `Date` exposed over CORS; `ACCOUNT.ERASURE_RETRY` 503; `REGISTRATION_BLOCKED` also on email-change confirmation and social link; magic-link / social sign-in of a deleted-grace password-only account gives the pending state; suspended accounts are frozen on every sign-in path. See the FINAL-4 change list at the top.
- FINAL-3 (second security review): sign-up creates an attempt (`signUpId`) and mails a 6-digit code; `verify-email` takes `{signUpId, code}` and signs in; resend takes `{signUpId}`; email change, re-auth and delete confirmation are codes entered in the signed-in session (`POST /account/email/change/confirm`; `POST /auth/confirm-email-change` removed); `confirmationToken` renamed `confirmationCode`; `socialReauth` for accounts without an address (email change, link, unlink, delete); unlink needs re-authentication (`DELETE /account/identities/{id}` + optional body); new codes `ACCOUNT.CODE_INVALID` / `ACCOUNT.CODE_EXPIRED` / `AUTH.TOO_MANY_REFRESHES`; 4xx of `email/change` and `delete` are not stored under the Idempotency-Key. See the change list at the top.
- FINAL-2b: `GET /auth/methods`; `me.pendingEmail`/`pendingEmailExpiresAt`; 10 s refresh reuse grace with idempotent rotation (apps); social merge on a verified provider email is ON in apps/api and apps/sample (see accounts.md): the user is signed in to the existing account (200) instead of `409 ACCOUNT.SOCIAL_EMAIL_CONFLICT`, and the account address gets a notice mail; with merging off (module default) the 409 stays.
- FINAL-2 (security review): `maxBytes` (not `maxLength`); `POST /account/reauth/confirmation` + `confirmationToken` on email change / first password / social link, `currentPassword` on social link, `403 ACCOUNT.REAUTH_REQUIRED`; resend `429`; magic link redeems for existing accounts with sign-up closed; a mailbox proof discards an unproven sign-up password; the old address is told of every email-change request; unlink revokes other sessions; admin list paging validated and admin role re-checked on the stored account; login bucket per address; OAuth `state` requirement written down.
- DRAFT-1: initial contract (pre-implementation).
- FINAL-1: reconciled with the code — cookie name `skeleton_refresh`, `X-Device-Name` header (no body field), IP-limit 429 on forgot/magic-link, email-change to a taken address sends nothing, `[idem]` commands, 201 `CREATED` sign-up when verification is off, admin response shapes, extra error codes.

---

## FINAL-3 + social PKCE (addendum)

Audience: the frontend agent. Providers added with this addendum: `line`, `x` (and any provider configured in `auth-social-oidc`). Existing providers (`google`, `kakao`, `naver`) follow the same rules; their `pkce` is stated below. Nothing in FINAL-3 changes except the fields and errors listed here; every field below is **optional on the wire** (an old client keeps working against providers whose `pkce` is not `REQUIRED`).

### A. `GET /api/v1/auth/methods` — `social[]` objects grow

```
"social": [ {
  "provider": "line",                 // path segment for login / link / socialReauth; also the identity `method`
  "clientId": "1234567890"|null,      // public
  "redirectUri": "https://app.example.com/auth/callback"|null,   // the server's configured one; the frontend may use its own, but it MUST be byte-identical in the authorize URL and in `redirectUri` of the login request
  "pkce":  "REQUIRED" | "SUPPORTED" | "UNSUPPORTED",
  "nonce": "REQUIRED" | "SUPPORTED" | "UNSUPPORTED",
  "authorize": { "url": "https://access.line.me/oauth2/v2.1/authorize", "scopes": ["openid","profile","email"], "params": { "response_type": "code" } } | null
} ]
```
Provider values today: `google` pkce SUPPORTED nonce UNSUPPORTED; `line` (preset) pkce REQUIRED nonce REQUIRED; `x` pkce REQUIRED nonce UNSUPPORTED; `kakao` / `naver` pkce UNSUPPORTED nonce UNSUPPORTED, `authorize` null (keep the frontend's own settings for these two). Providers configured through `auth-social-oidc` default to pkce SUPPORTED nonce SUPPORTED unless the project says otherwise. A provider appears here only when the backend has it enabled (client id configured).

### B. Building the authorize URL (frontend, per provider, from the object above)
1. Generate per attempt and keep in `sessionStorage` until the callback: `state` (random, >= 16 bytes), `codeVerifier` (PKCE: 43-128 chars of `[A-Za-z0-9-._~]`, e.g. 32 random bytes base64url = 43 chars), `nonce` (random, 8-256 printable ASCII without spaces; base64url of 16+ random bytes) — the verifier and nonce are **per attempt**, never reused.
2. `code_challenge = BASE64URL(SHA-256(ASCII(codeVerifier)))` (no padding). Method is always `S256` (the backend supports no `plain`).
3. URL = `authorize.url` + `?` + `client_id`, `redirect_uri`, `scope` (the `authorize.scopes` joined by a single space), `state`, every entry of `authorize.params` (e.g. `response_type=code`), plus: if `pkce` is `REQUIRED` or `SUPPORTED`: `code_challenge`, `code_challenge_method=S256`; if `nonce` is `REQUIRED` or `SUPPORTED`: `nonce`. When `pkce` is `UNSUPPORTED` send neither (some providers reject unknown parameters). All values URL-encoded.
4. On the callback: compare `state` first (reject on mismatch, do not call the backend), then **immediately** call the backend with the code — authorization codes are single use and short lived (LINE 10 minutes; **X 30 seconds**; others unspecified).

### C. Request bodies — two new optional fields everywhere an authorization code is sent
| Endpoint | New fields |
|---|---|
| `POST /api/v1/auth/social/{provider}/login` | `codeVerifier?`, `nonce?` next to `authorizationCode`, `redirectUri?` |
| `POST /api/v1/account/identities/social/{provider}` (link) | `codeVerifier?`, `nonce?` (top level, next to `authorizationCode`) |
| `socialReauth` object on email change, link, unlink, delete, reauth | `socialReauth: { provider, authorizationCode, redirectUri?, codeVerifier?, nonce? }` |

Rules (server-enforced, evaluated by the target provider's mode, BEFORE the provider is called and BEFORE any re-authentication proof is spent — so a retry with the same authorization code and the same proof works after fixing the request):
- `pkce = REQUIRED` and no `codeVerifier` -> `400 AUTH.SOCIAL_PKCE_FAILED`.
- `codeVerifier` present but not 43-128 chars of `[A-Za-z0-9-._~]` -> `400 AUTH.SOCIAL_PKCE_FAILED` (validated whenever present, in every mode).
- `pkce = SUPPORTED`: forwarded to the provider when present, optional. `pkce = UNSUPPORTED`: ignored, never forwarded.
- `nonce = REQUIRED` and none (or malformed: not 8-256 printable ASCII without spaces) -> `400 AUTH.SOCIAL_NONCE_FAILED`. `SUPPORTED`: checked against the provider's ID token when present. `UNSUPPORTED`: ignored.
- A verifier that does not match the challenge of the authorization request is detected by the provider, not by us: it answers `invalid_grant` and the backend answers `401 AUTH_SOCIAL.INVALID_AUTHORIZATION_CODE` (indistinguishable from a wrong or already used code; for `socialReauth` it is `400 ACCOUNT.REAUTH_FAILED`). When the provider (LINE, X, any OIDC provider; not Google, Kakao, Naver) error text names the verifier, the backend answers `400 AUTH.SOCIAL_PKCE_FAILED` instead.
- For `socialReauth` a missing/malformed verifier or nonce is `400 AUTH.SOCIAL_PKCE_FAILED` / `AUTH.SOCIAL_NONCE_FAILED` (a client bug), not `ACCOUNT.REAUTH_FAILED`.

### D. Errors added
| Code | Status | When |
|---|---|---|
| `AUTH.SOCIAL_PKCE_FAILED` | 400 | see C |
| `AUTH.SOCIAL_NONCE_FAILED` | 400 | see C |
| `AUTH.SOCIAL_ID_TOKEN_INVALID` | 401 | an OpenID Connect provider's ID token failed validation (signature, `iss`, `aud`, `exp`, `nonce`). Via `socialReauth` it is `400 ACCOUNT.REAUTH_FAILED` like any rejected proof. The reason is in the server log only |
Unchanged: `401 AUTH_SOCIAL.INVALID_AUTHORIZATION_CODE` (provider refused the code: wrong, expired, used twice), `404 AUTH_SOCIAL.PROVIDER_NOT_FOUND` (not enabled), `502 AUTH_SOCIAL.PROVIDER_GATEWAY_ERROR` (provider down / rate limited / our client credentials rejected).

### E. Address-less accounts (LINE, X, and any provider whose email is missing or not vouched for)
- LINE: the email exists only with the `email` scope AND the console's email permission; even then LINE states no verification, so the backend treats it as **not verified**: the account is created **without an address** (same as Naver) and a LINE email never merges into an existing account. X: email only with the `users.email` scope and the app's email permission; treated as not verified. The flows of FINAL-3 apply as written: sign-up via social works (no address on the account), link/unlink/delete/email change need `socialReauth` with a FRESH code of an already linked provider — **and the PKCE `codeVerifier` of that fresh authorization request**.
- The frontend runs the provider consent again for a `socialReauth`: new `state`, new `codeVerifier`, new `nonce`, new code.
- Provider subject: LINE `sub` is stable per LINE **provider** (the console grouping), not per channel; moving the app to a channel under another provider yields different subjects, i.e. different identities (documented in `docs/modules/auth-social-oidc.md`). Subjects are never exposed in responses.

### F. Frontend checklist
`GET /auth/methods` -> per provider build the URL (B) -> callback: check `state` -> `POST .../login` with `{authorizationCode, redirectUri, codeVerifier, nonce}` (omit what the provider does not use) -> on `400 AUTH.SOCIAL_PKCE_FAILED` / `AUTH.SOCIAL_NONCE_FAILED` fix the request (it is a client bug, nothing was spent) -> `401 AUTH_SOCIAL.INVALID_AUTHORIZATION_CODE` / `AUTH.SOCIAL_ID_TOKEN_INVALID`: restart the consent. Remove the stored `state` / `codeVerifier` / `nonce` after the first use.
