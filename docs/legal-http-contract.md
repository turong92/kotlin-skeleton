# Legal documents and consent — HTTP contract (kotlin-skeleton backend)

Status: **DRAFT-2** (written before the code; the backend tests are written against this file; changes since DRAFT-1 are in the change log at the bottom).
Audience: the frontend agent (react-skeleton `@skeleton/legal` / `@skeleton/auth` additions). Backend modules: `legal`, `legal-jdbc` (+ two small hooks in `account`).
Background and the legal-versus-module split: [legal.md](legal.md). Account conventions (envelope, error body, tokens): [account-http-contract.md](account-http-contract.md) section 0 — unchanged here.

## 0. Conventions

- Base path `/api/v1/legal` (`skeleton.legal.http.base-path`). Opt-in like every module endpoint: present only when the app has the `legal` module; removable with `skeleton.legal.http.enabled=false`.
- Envelope and errors are the platform ones: `{ value | values, pagination?, meta }`, errors `{ code, title, status, detail?, traceId, spanId, timestamp, errors?, data? }`. Branch on `code`.
- A **document type** is a lowercase string code (`terms`, `privacy` by default; an app adds `marketing`, `age`, `refund`, …). Pattern `^[a-z][a-z0-9-]{1,31}$`. Never hard-code the list: read it from `GET /documents`.
- A **version** is an opaque string (`2026-10-01`, `2026-10-01-b`, `v3`). Compare for equality only, never for order — the server decides which one is current.
- A **locale** is a lowercase language tag (`ko`, `en`). A document may exist in several; asking for a locale that a version lacks falls back to the app's default locale (the answer says which one it is).
- Instants are ISO-8601 UTC (`...Z`).
- A **subject** is who agreed. For every signed-in endpoint below it is the signed-in account (subject type `account`, id = account id). A frontend never sends a subject.

## 1. Public reading (no sign-in, cacheable)

### GET /api/v1/legal/documents
`Cache-Control: public, max-age=300` (configurable), `ETag`. Same answer for everyone. One entry per (type, locale) of the **current** version:

```json
{ "values": [
  { "type": "terms", "locale": "ko", "version": "2026-10-01", "effectiveFrom": "2026-10-01T00:00:00Z",
    "title": "이용약관", "sha256": "9f…64 hex…",
    "required": true, "requiredAtSignUp": true,
    "template": false,
    "next": { "version": "2026-12-01", "effectiveFrom": "2026-12-01T00:00:00Z" } },
  { "type": "marketing", "locale": "ko", "version": "2026-10-01", "effectiveFrom": "2026-10-01T00:00:00Z",
    "title": "마케팅 정보 수신 동의", "sha256": "…", "required": false, "requiredAtSignUp": false, "template": false, "next": null }
] }
```

- `required`: the service may not be used without a current agreement (see section 4, re-consent). `requiredAtSignUp`: a sign-up is refused without it (section 3). An optional document (`required: false`) may still be offered at sign-up as an unchecked box; send it in `consents` only when the user ticked it.
- `next`: a published version that takes effect later (announce "terms change on …"); `null` when none is scheduled. It cannot be agreed to before it takes effect.
- `template: true` means the document is the module's example text — the backend refuses to run that way in stage / prod unless the operator acknowledged it, so a frontend normally never sees it in production. Showing a small "sample text" banner for it is fine.
- Types whose current version does not exist in a locale are simply absent for that locale.

### GET /api/v1/legal/documents/{type}?version=&locale=
`version` omitted = the current version; given = any **already effective** version (older versions stay readable, they are what people agreed to). `locale` omitted = the app's default.
`200`:

```json
{ "value": { "type": "terms", "version": "2026-10-01", "locale": "ko", "requestedLocale": "en",
             "effectiveFrom": "2026-10-01T00:00:00Z", "current": true, "template": false,
             "title": "이용약관", "sha256": "…", "required": true, "requiredAtSignUp": true,
             "markdown": "# 이용약관\n\n…" } }
```

- `markdown` is a safe subset (headings, paragraphs, emphasis, lists, block quotes, horizontal rules, tables, links with `https:` / `mailto:` / relative / `#anchor` destinations). No raw HTML, no images, no scripts — the server refuses to start with a source that has them. Facts (company name, contact…) are already filled in; render it with any CommonMark renderer **without** enabling raw HTML. `sha256` is the hash of the stored source (what a consent record points to), not of the rendered text.
- `Cache-Control: public, max-age=300`, `ETag` (`If-None-Match` → `304`).
- Errors: `404 LEGAL.DOCUMENT_NOT_FOUND` (unknown type, unknown or not yet effective version).

## 2. Signed-in status and agreeing (`Authorization: Bearer …`)

### GET /api/v1/legal/consents/me
```json
{ "value": {
  "blocked": false,
  "items": [
    { "type": "terms", "required": true, "requiredAtSignUp": true,
      "state": "CURRENT",
      "current": { "version": "2026-10-01", "effectiveFrom": "2026-10-01T00:00:00Z", "locales": ["ko", "en"] },
      "agreed": { "version": "2026-10-01", "agreedAt": "2026-10-02T03:04:05Z", "source": "sign-up" },
      "graceUntil": null },
    { "type": "marketing", "required": false, "requiredAtSignUp": false, "state": "WITHDRAWN",
      "current": { "version": "2026-10-01", "effectiveFrom": "2026-10-01T00:00:00Z", "locales": ["ko"] },
      "agreed": { "version": "2026-10-01", "agreedAt": "2026-10-02T03:04:05Z", "source": "sign-up" }, "graceUntil": null }
  ],
  "missing": []
} }
```

- `state` per type: `CURRENT` (agreed to the current version) · `GRACE` (agreed to the previous version; a newer one is in force but still inside the grace window — `graceUntil` says when it ends, the user is **not** blocked yet) · `OUTDATED` (agreed to an older version, grace over) · `MISSING` (never agreed) · `WITHDRAWN` (optional document, the user withdrew).
- `blocked`: at least one **required** type is `MISSING` or `OUTDATED`. `missing`: those types as `{ "type", "version": <the version to agree to>, "reason": "NOT_AGREED" | "STALE" }`. This list is exactly what to put into `POST /consents`.
- `agreed` is the latest event's version, also for `WITHDRAWN` (the withdrawal moment is in the history).

### POST /api/v1/legal/consents
Req: `{ "consents": [ { "type": "terms", "version": "2026-10-01", "locale": "ko" } ], "source": "re-consent" }`
- Each item says: "I saw **this version** in **this locale** and agree." `locale` = the language the user actually read (default: the app's default). `source` optional, `^[a-z][a-z0-9-]{1,31}$`, default `consent`, the value `sign-up` is reserved for the server (`400 COMMON.VALIDATION_FAILED`). Between 1 and 20 items; a type may appear once.
- Accepts the current version, or the **previous** version while inside the grace window (a page that was open during a deploy still works). Anything else: `409 LEGAL.VERSION_STALE` with `data: { "stale": [ { "type", "requiredVersion" } ] }` — reload `GET /documents`, show the new text, ask again. A version that does not take effect yet is stale too.
- Idempotent: agreeing again to what is already the user's current agreement records nothing new and answers `200` as usual (a double click is harmless).
- `404`-like: `400 LEGAL.UNKNOWN_DOCUMENT` (`data: { "types": ["…"] }`) for a type the app does not have.
- Res `200`: the same `value` as `GET /consents/me` after recording. All-or-nothing: if any item is stale nothing is recorded.
- The server stores, per item: subject, type, version, the source hash, the locale, the time, the `source`, and — when the app has not switched it off — the client IP and user agent. Nothing of this is sent back except the fields shown above.

### POST /api/v1/legal/consents/{type}/withdraw
No body. Withdraws an **optional** agreement (marketing …) and records the withdrawal as its own event (history keeps the agreement and the withdrawal). To agree again use `POST /consents`.
- `200` same `value` as `GET /consents/me`. Idempotent: withdrawing something that is not currently agreed to changes nothing and answers `200`.
- `409 LEGAL.WITHDRAWAL_NOT_ALLOWED` for a `required` type (the way out is deleting the account: `POST /account/delete`). `400 LEGAL.UNKNOWN_DOCUMENT` for an unknown type.

### GET /api/v1/legal/consents/me/history?page=&size=
Newest first, standard page envelope (`values`, `pagination`). `values[]`: `{ "type", "version", "action": "AGREED" | "WITHDRAWN", "locale", "source", "at" }` — only the caller's own events, with no IP.

## 3. Sign-up: consents ride on the sign-up attempt

The consent is **bound to the sign-up attempt** and written only when that attempt is verified, in the same transaction as the account — an abandoned or never-verified attempt leaves no consent record, and nothing about the consent can be set after the fact by anyone who lacks the mailed code.

### POST /api/v1/account/sign-up  (existing endpoint, one new optional field)
Req: `{ email, password, displayName?, locale?, timeZone?, captchaToken?, "consents"?: [ { "type": "terms", "version": "2026-10-01", "locale": "ko" }, … ] }`

- `consents` has the same item shape as `POST /legal/consents` (`locale` optional, `type`/`version` ≤ 32 chars, `locale` ≤ 35), **at most 8 items** (more → `400 COMMON.VALIDATION_FAILED`). List **every document the user ticked**: the required-at-sign-up ones (the form must not submit without them) and any optional ones (`marketing`).
- When the app has the `legal` module: the list is checked **before** anything is stored or mailed. Missing a required-at-sign-up type, or naming a stale / not-yet-effective version, or an unknown type → `400 LEGAL.CONSENT_REQUIRED` with
  `data: { "missing": [ { "type": "privacy", "version": "<version to agree to>", "reason": "NOT_AGREED" | "STALE" | "UNKNOWN" } ] }` (for `UNKNOWN`, `version` is `null`). Re-read `GET /documents`, show the new text, resubmit.
- The check looks only at the request and the document set, never at the address, so the response for new / in-flight / already-registered addresses stays identical (the existing enumeration guarantees of section 2 of the account contract are unchanged). The error is only about documents.
- When the app does **not** have the `legal` module, `consents` is ignored (a frontend may always send it).
- Otherwise the answer is unchanged: `202 { status: "VERIFICATION_SENT", signUpId }` (or `201 CREATED` when email verification is off: then the consent is recorded immediately, together with the account).
- Re-using the same `signUpId` / resend does not change the consents of the attempt; they are fixed when the attempt is opened.

### POST /api/v1/auth/verify-email  (existing, unchanged request)
On success the account is created and the consents of the attempt are recorded (`source: "sign-up"`, the version the user was shown at sign-up and the hash of that text, and the client IP and user agent of the **sign-up request** — where the boxes were ticked; the user agent is kept to its first 120 characters). If recording fails, the account is not created either (one transaction) and the call fails with a normal `5xx`; the code is spent — the user starts the sign-up again.

### First sign-in that creates the account (social, magic link)
These paths create an account without a form of ours, so they have **no consent at creation**. Decision: the account is created and the sign-in succeeds, and the missing consents are collected **before the session is usable**:
1. After **every** successful sign-in response (login, verify-email, magic-link redeem, social login, refresh not needed) call `GET /api/v1/legal/consents/me` once. `blocked: true` → show the consent screen for `missing` (render each document from `GET /documents/{type}?version=`), then `POST /legal/consents` with `source: "first-sign-in"`. Then continue.
2. Enforcement does not rely on the frontend: when the app turns the re-consent filter on (the starter and the sample do), every other API call of a blocked account answers `403 LEGAL.RECONSENT_REQUIRED` (section 4) until step 1 is done.
3. The consent screen needs no new endpoint, so the same screen serves the re-consent case.
Rejected: requiring `consents` on the social / magic-link request. The page that redeems a magic link or receives the provider redirect cannot know whether the account exists (showing checkboxes only to new users would reveal it), and asking everyone every time is wrong. The cost of the chosen way: for a short moment an account exists without a recorded consent; it cannot do anything but consent, log out, or delete itself.

## 4. Re-consent: `403 LEGAL.RECONSENT_REQUIRED`

Opt-in (`skeleton.legal.reconsent.enabled`; on in the starter and the sample). For a signed-in caller of a protected endpoint the filter answers

`403 LEGAL.RECONSENT_REQUIRED`, `data: { "missing": [ { "type": "terms", "version": "2026-12-01", "reason": "NOT_AGREED" | "STALE" } ] }`

when a **required** document is `MISSING` or `OUTDATED` (a newer version has been in force for longer than the grace window, or the account never agreed — first sign-in via social / magic link). In `GRACE` the call goes through.
- This is `403`, not `401`: **do not sign out**, do not refresh. Show the consent screen (the same as section 3), `POST /legal/consents`, retry the call.
- Never blocked (so a user can fix it, leave or delete themselves): `/api/v1/legal/**`, `/api/v1/auth/**`, `/api/v1/account/**` (configurable `skeleton.legal.reconsent.exclude-paths`). Anonymous requests are never filtered (they get the normal `401`).
- Cost: one indexed read per protected request. It does not change any response body of calls that go through.

## 5. Admin reading (role `ADMIN`; `skeleton.legal.admin-role`)
`403 COMMON.FORBIDDEN` without the role, `401` anonymous.

### GET /api/v1/legal/admin/consents?subjectType=&subjectId=&type=&action=&page=&size=
Page envelope, newest first. `values[]`: `{ "id", "subjectType", "subjectId", "type", "version", "sha256", "locale", "action", "source", "referenceId", "ip", "userAgent", "at" }` (`ip` / `userAgent` are `null` when not stored or already scrubbed; a subject erased with the account shows `subjectId: "deleted:<hash>"`).

### GET /api/v1/legal/admin/documents
`values[]` = every version in the manifest (also drafts and scheduled): `{ "type", "version", "status": "DRAFT" | "REVIEWED", "effectiveFrom", "locales", "template", "current", "sha256": { "ko": "…" }, "pinned": true|false, "missingFacts": ["company-name"] }`.

## 6. Error codes

| Code | Status | When | `data` |
|---|---|---|---|
| `LEGAL.CONSENT_REQUIRED` | 400 | sign-up without a current required-at-sign-up consent, or with a stale / unknown one | `missing[]` |
| `LEGAL.RECONSENT_REQUIRED` | 403 | filter: required document missing / outdated | `missing[]` |
| `LEGAL.VERSION_STALE` | 409 | `POST /consents` names a version that is not the agreeable one | `stale[]` |
| `LEGAL.WITHDRAWAL_NOT_ALLOWED` | 409 | withdraw of a required type | — |
| `LEGAL.UNKNOWN_DOCUMENT` | 400 | unknown type in a body | `types[]` |
| `LEGAL.DOCUMENT_NOT_FOUND` | 404 | unknown type / version in the public read | — |
| `LEGAL.RATE_LIMITED` | 429 | too many consent events by one subject per day (default 200) | `retryAfterSeconds` |

Validation errors are the usual `400 COMMON.VALIDATION_FAILED` + `errors[]`.

## 7. What a frontend implements (checklist)

1. Sign-up form: `GET /documents` → one checkbox per `requiredAtSignUp` document (links to `GET /documents/{type}`), optional ones unchecked; submit `consents` with the shown `version` and `locale`.
2. On `400 LEGAL.CONSENT_REQUIRED`: reload documents, show again.
3. After any sign-in: `GET /consents/me` → if `blocked`, consent screen, `POST /consents`.
4. Any `403 LEGAL.RECONSENT_REQUIRED` anywhere: consent screen, retry; never sign out on it.
5. Settings: list `items`, a toggle for optional ones (`POST /consents` to agree, `POST /consents/{type}/withdraw` to withdraw), a link to `/consents/me/history`.
6. Footer links to `GET /documents/{type}` (public, cacheable).

## Change log
- DRAFT-1: first version.
- DRAFT-2: sign-up `consents` limited to 8 items; the recorded IP / user agent are those of the sign-up request (not the verify call), user agent cut to 120 characters.
