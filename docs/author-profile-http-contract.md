# Author profiles — HTTP contract (kotlin-skeleton backend)

Status: **FINAL-1** (written against the code of `author-profile`, `author-profile-jdbc`, `board`, `storage` and `account` as of 2026-10-08; the backend tests — `AuthorProfileWebTest`, `AuthorProfileWebLimitsTest`, `AuthorProfileAdminWebTest`, `BoardAuthorIdentitiesWebTest`, `BoardIntegrationTest`, `AuthorLinkLeakIntegrationTest` — are written against it).
Audience: the frontend (react-skeleton `@skeleton/author-profile`, `@skeleton/board`). Backend modules: `author-profile` (+ `author-profile-jdbc`), `board`, `storage`; one new account event type.
Background, threat model and the full list of leak paths: `docs/superpowers/specs/2026-10-07-author-profiles-design.md`. Module pages: `docs/modules/author-profile.md`, `docs/modules/board.md`. Account conventions (envelope, error body, tokens): `docs/account-http-contract.md` section 0 — unchanged here.

The point of the feature: **one account can write under several names (profiles) and nothing a *reader* can see ties two profiles together.** Everything below follows from that. A frontend must not rebuild the tie from its own side (see section 8).

## 0. Conventions

- Base path `/api/v1/author-profiles` (`skeleton.author-profile.http.base-path`). Present only when the app has the `author-profile` module; removable with `skeleton.author-profile.http.enabled=false`. **Every endpoint needs a signed-in user** (`Authorization: Bearer …`); no public path exists. The caller is the token's account.
- Envelope and errors are the platform ones: `{ value | values, meta }`, errors `{ code, title, status, detail?, traceId, spanId, timestamp, errors?, data? }`. Branch on `code`, never on `detail`.
- A **profile id** (`prf_` + 32 lowercase hex, 128 random bits) is an **opaque string**. It is not derived from the account id, carries no time or order, and is the only author identifier a reader ever sees. Never parse it, never sort by it.
- A **public author id** is whatever `authorId` holds in board responses: a profile id (`prf_…`), or — only for a row not adopted yet — `pending:p123` (a post) / `pending:c45` (a comment), or — only in an app **without** this module — the account id. Treat it as an opaque grouping key ("same author").
- Instants are ISO-8601 UTC (`...Z`).
- Errors that look the same on purpose (the frontend must not try to tell them apart): see section 3, "identical answers".

## 1. Concepts the screens need

- A **profile** = public id + `name` + `tag`. The name follows the app's nickname rules (`skeleton.account.display-name.*`: 1 to 60 code points, no `#` / `@`, reserved words refused, …). The **tag** is a four-digit string (`"0417"`) that tells two profiles with the same name apart; an app that runs with `uniqueness: NONE` has no tags (`null`).
- Same name, different profile: the board shows `name#tag` for colliding names (the existing board frontend rule, keyed by `authorId`). **Two profiles of one person look like two strangers** — that is the feature.
- **There is no "default profile" on the server.** The first profile is only the first one; it has no special rights, can be renamed or deleted like any other, and nothing follows it around. A frontend that wants a preselected profile remembers it on the device (section 8).
- A new account has **no profile row until the first `GET /author-profiles`**: the server then creates the **first profile** with the account's nickname and tag copied once (an account without a nickname — or, in a `UNIQUE` app, one whose name has no free slot left — gets a first profile with `name: null`). If the account already wrote on the board before the app turned the module on, that same call (or the app's startup) also moves those old posts and comments onto the first profile. So **always `GET /` before offering a profile choice**, and never create the first profile with `POST /`.
- Deleting a profile is **immediate and for good**. Its posts and comments stay and read as a deleted user (`authorDeleted: true`, no name). They are not moved to another profile. The owner keeps editing and deleting them (`mine` follows the account).

## 2. Endpoints

All responses are `200` unless stated. All paths below are relative to the base path.

### GET /config
```json
{ "value": { "maxPerAccount": 5, "nameMaxLength": 60 }, "meta": { … } }
```
`maxPerAccount` counts living profiles, the first one included. `nameMaxLength` is in code points.

### GET /
The caller's living profiles, oldest first. Creates the first profile if there is none and adopts old rows (section 1).
```json
{ "values": [
  { "id": "prf_8c1e5d0b3a7f4e29b6d4a1c20f93e7ab", "name": "Minsu", "tag": "0417", "createdAt": "2026-10-08T01:02:03.456789Z" },
  { "id": "prf_41f0c8a2d9e64b3f8a57c0de12b9f6a3", "name": "mins", "tag": "5231", "createdAt": "2026-10-08T01:10:00.000000Z" }
] }
```
- These **four keys are all there is** — no account id, no "is first", no status, no adoption time. `createdAt` is for the owner's own list only.
- `name: null` only for an unnamed first profile (an account that had no nickname). Show a "name this profile" prompt; a null name never appears for a profile created with `POST`.
- `tag: null` when the app has no tags.
- A suspended account that already has profiles can still read the list; an account that has never had one gets `404 AUTHOR_PROFILE.NOT_FOUND` (nothing is created for a non-active account).

### POST /
Body `{ "name": "Minsu" }`. Needs the `Idempotency-Key` header when the app has the `idempotency` module (section 4).
`201`, `Location` = the absolute URL `…/api/v1/author-profiles/{id}` (there is no `GET /{id}`; the body already is the profile), body `{ "value": { id, name, tag, createdAt } }` (same four keys).
Errors: section 3. The tag is drawn independently for this profile — it is **not** the account's tag and not a sibling's.
A caller with no profile yet who `POST`s gets the first profile created implicitly as well — it counts toward `maxPerAccount` but not toward the create limit (which counts only profiles the user made). Avoid the surprise by calling `GET /` first.

### PATCH /{id}
Body `{ "name": "New name" }` → `200` `{ "value": { id, name, tag, createdAt } }`. **Past posts show the new name at once** (names are looked up when a post is read). The tag stays when the comparison key of the name stays (only case or width changed), otherwise a new tag is drawn. Sending the **same name** again returns the profile unchanged and does not spend the rename limit. The same errors as `POST`, plus the rename limit (section 3). `{id}` that is not the caller's living profile → `404 AUTHOR_PROFILE.NOT_FOUND`; this is checked **before** the name, so a wrong id with a bad name is also that `404`.

### DELETE /{id}
`204`, no body. Immediate and permanent. Another account's, a missing, an already deleted or a malformed id → the same `404 AUTHOR_PROFILE.NOT_FOUND`. Deleting the last profile is allowed; the next `GET /` on an account that once had one does **not** make a new first profile (the first profile row stays as a deleted marker, so old rows are never adopted twice) and returns `values: []` — the user creates a new one with `POST`.

## 3. Errors

Body shape as in `docs/account-http-contract.md` section 0. Field errors: `400 COMMON.VALIDATION_FAILED` with `errors: [{ "field": "name", "code": "…", "message": "…" }]`.

| Status | `code` | Where | When | What the screen does |
|---|---|---|---|---|
| 400 | `COMMON.VALIDATION_FAILED`, `errors[].field = "name"`, `errors[].code = Required` | POST, PATCH | missing, empty or blank name | field error |
| 400 | … `Size` | POST, PATCH | longer than `nameMaxLength` (or a body-sized string) | field error |
| 400 | … `Pattern` | POST, PATCH | forbidden characters (`#`, `@`, control and invisible characters, the `deleted:` prefix, …) | field error |
| 400 | … `Reserved` | POST, PATCH | a reserved word (`skeleton.account.display-name.reserved`) | field error |
| 400 | `COMMON.IDEMPOTENCY_ERROR` | POST | the app has the `idempotency` module and the header is missing / malformed | frontend bug |
| 401 | `AUTH.*` | all | no or expired token | normal sign-in handling |
| 404 | `AUTHOR_PROFILE.NOT_FOUND` | PATCH, DELETE; **POST for any non-active account (suspended, deletion pending — even one that has profiles)**; GET for a non-active account that never had a profile | not the caller's / missing / deleted / malformed id | refresh the list |
| 409 | `AUTHOR_PROFILE.NAME_TAKEN` | POST, PATCH | the app runs `uniqueness: UNIQUE` (or `TAGGED` with all 9999 tags of the name used) and somebody holds the name | field error "taken" |
| 409 | `AUTHOR_PROFILE.LIMIT_REACHED` | POST | the caller already has `maxPerAccount` living profiles | "delete one first" |
| 429 | `AUTHOR_PROFILE.RATE_LIMITED` | POST (create limit), PATCH (rename limit) | too many creations (`create-limit`, default 5 per 30 days — **deleted profiles count**) or renames (the account's nickname-change allowance, default 10 per day, **all profiles together**) | show "try again later" |

- `429` carries the `Retry-After` header (seconds) and `data: { "retryAfterSeconds": 2592000 }` — the same shape as `ACCOUNT.RATE_LIMITED`. For the **create** limit the value is the **whole window** (the limit is counted from the table, not from a timer), so it is an upper bound, not "your next try is possible in exactly this long": show "later", not a countdown. The rename limit's value is exact.
- **Identical answers.** `AUTHOR_PROFILE.NOT_FOUND` is one status, one title, one detail, one body for all four causes (someone else's / missing / deleted / not an id) — also for `PATCH` with a bad name on somebody else's id. `BOARD.AUTHOR_NOT_ALLOWED` (section 5) likewise. The frontend must show one message ("this profile is not available") and never branch on the reason; there is no reason to find.
- Rate limits and caps are per **account**, never per profile — switching profiles does not buy more attempts.

## 4. Idempotency-Key (`POST /`)

`POST /` is an idempotent operation: when the app has the `idempotency` module the header `Idempotency-Key: <uuid>` is **required** (`400 COMMON.IDEMPOTENCY_ERROR` without it), and a second request with the same key does not create a second profile — it replays the first answer (response header `X-Idempotency-Replayed: true`). Generate one key per user action and reuse it when you retry after a network error. Without the module the header is ignored and nothing is replayed (a double click creates two profiles). `PATCH` and `DELETE` have no key.

## 5. What changes in the board (`/api/v1/boards`)

Only for an app that has the `author-profile` module (more precisely: an `AuthorIdentities` bean). Without it, the board behaves as before, with two **always present** additions (section 5.5).

### 5.1 `authorId` now holds a profile id
In post summary, post detail and comment responses `authorId` is `prf_…`. The key name, `authorName`, `authorTag` and `authorDeleted` stay. The account id is **not in any board response** any more. A row written before the module was turned on and not adopted yet shows `pending:p123` / `pending:c45` (no name, `authorDeleted` as it was) until the adoption has run; treat it as an author id like any other.

| Situation | `authorId` | `authorName` | `authorTag` | `authorDeleted` |
|---|---|---|---|---|
| live profile | `prf_…` | profile name (current — a rename shows at once) | tag or `null` | `false` |
| unnamed first profile | `prf_…` | `null` | `null` | `false` |
| deleted profile | `prf_…` (unchanged) | `null` | `null` | `true` |
| author's account deleted (all its profiles) | `prf_…` (unchanged) | `null` | `null` | `true` |
| row not adopted yet | `pending:p123` | `null` | `null` | `false` (`true` if the account is gone) |

`authorName === null && !authorDeleted` is an unnamed or not-yet-resolved author: show a neutral placeholder, not "deleted user". Suspended accounts look like active ones (no mark, on purpose: a mark would appear on all their profiles at once).

### 5.2 Writing: `authorId` is required
`POST /{code}/posts` and `POST /{code}/posts/{id}/comments` take `"authorId": "prf_…"` in the body.
```json
{ "title": "Hello", "body": "…", "attachments": ["uploads/o/3f9c….a1b2…/file.png"], "authorId": "prf_8c1e5d0b3a7f4e29b6d4a1c20f93e7ab" }
```
| Case | Answer |
|---|---|
| `authorId` missing or blank | `400 BOARD.AUTHOR_REQUIRED` |
| not the caller's living profile (someone else's, unknown, deleted, malformed — also `pending:…`) | `403 BOARD.AUTHOR_NOT_ALLOWED` — same status, title and detail for every cause |
| the caller's living profile | `201` as before; the response `authorId` is that profile |

`GET /boards/config` tells whether to send it: `authorRequired: true`. `PATCH` has **no** `authorId`: the author of a post or comment can never be changed, not by editing, not by a moderator, not by moving a post between `DRAFT` and `PUBLISHED` (a stray `authorId` in a PATCH body is ignored, not rejected). Replying under a different profile than the one you wrote the parent with is allowed — to everybody else it is a different person.

Attachments: with this module the board refuses (`400 BOARD.CONTENT_INVALID`) a **new** attachment key that contains the caller's account id. Use the keys `POST /storage/presign` returns — with the app's `key-owner: OPAQUE` they look like `uploads/o/<uuid>.<mac>/file.png` (opaque, no account id, no original file name; only a known extension survives). Do not build, parse or display them.

### 5.3 `mine`
Every post summary, post detail and comment response has **`mine: boolean`** — "this was written by the caller's account", whichever profile it was written under. It is the **only** way to decide on edit / delete buttons: `authorId === accountId` is false for every row in an app with this module. Anonymous readers get `false`. Several profiles of one account all show `mine: true` for the owner; for everybody else all `false`. `?mine=true` on the post list is also account-based.

### 5.4 Notifications
A comment notification (`topic: board`, in the inbox and on the SSE stream) carries `payload.authorId` = the **public** author id (profile id). The name is never in the payload; resolve it when rendering from the board data, or show "someone". The author never gets a notification for a reply to their own post or comment, even when it was written under a different profile (the check is per account).

### 5.5 Without the module
`authorId` stays the account id, a body `authorId` is refused (`403 BOARD.AUTHOR_NOT_ALLOWED`), and the responses gain only `mine` (posts, comments) and `GET /config` → `authorRequired: false`. Nothing else is different.

### 5.6 Errors added to the board
| Status | `code` | Meaning |
|---|---|---|
| 400 | `BOARD.AUTHOR_REQUIRED` | an `AuthorIdentities` bean is present and the body has no `authorId` |
| 403 | `BOARD.AUTHOR_NOT_ALLOWED` | see 5.2 (also: a body `authorId` sent to an app without the module) |

## 6. Account side

- `GET` / `PATCH /account/me` are unchanged. `displayName` / `displayTag` there are the account's **private label** (own screens, admin, mail greeting); they are not what the board shows once profiles exist, and changing them does not touch any profile.
- Account deletion removes all the caller's profile rows; their posts and comments stay as a deleted user (all of them become `authorDeleted: true` at the same moment — documented residual risk, spec §4 19 / §14).
- New account event type `AUTHOR_LINK_VIEWED` (section 7); no existing event changed.

## 7. Admin: who is behind a profile

Only when `skeleton.author-profile.admin.enabled=true` (default off; the paths do not exist otherwise). **`ADMIN` only** — a board moderator (`MODERATOR`) gets `403`; role is re-read on every call. Every successful call writes the account event `AUTHOR_LINK_VIEWED` (target account, `detail = { by: <admin account id> }`; no profile id).

| Endpoint | Answer |
|---|---|
| `GET /api/v1/admin/author-profiles/{id}` | the profile's account and all of that account's profiles |
| `GET /api/v1/admin/author-profiles?accountId=` | the same by account (`400` without `accountId`) |

```json
{ "value": { "accountId": "acc_…", "profiles": [
  { "id": "prf_…", "name": "Minsu", "tag": "0417", "status": "ACTIVE", "createdAt": "…", "updatedAt": "…", "deletedAt": null },
  { "id": "prf_…", "name": null, "tag": null, "status": "DELETED", "createdAt": "…", "updatedAt": "…", "deletedAt": "…" }
] } }
```
Deleted profiles are included (`status: DELETED`, no name). Errors: `401`, `403`, `404 AUTHOR_PROFILE.NOT_FOUND` (unknown profile or account — no event is written for a miss; an account with no profiles is `200` with `profiles: []`). This is an operator tool: do not put it in a user-facing screen, and never log the answer.

## 8. Rules for the frontend (so the screen does not rebuild the link)

1. **Never show or store an account id next to a profile id.** The board gives you no account id; do not fetch `/account/me` and put its `id` in the same object, URL, analytics event or error report as an `authorId`.
2. **Remember the last used profile on the device only**, under a key that contains the account id and a value that is only the profile id, and delete it on sign-out. Do not send it to the server.
3. Show **one** profile (the selected `name#tag`) next to the submit button; open the list only on a click. Do not render all of the user's profile names on a screen that may be shared.
4. A thread-local default is fine (the profile the user used last in this thread, taken from the comments with `mine: true`), but warn only when the user *switches* away from it, not permanently — a standing warning is itself a hint.
5. Decide "my post" with `mine`, never with `authorId`. Group "same author" by `authorId` only.
6. Handle `authorName === null` for live profiles (unnamed first profile) and `authorDeleted` as two different states (5.1).
7. Treat every `404 AUTHOR_PROFILE.NOT_FOUND` / `403 BOARD.AUTHOR_NOT_ALLOWED` as "this profile is not available — reload the list".
8. Do not cache board responses in a shared cache: `mine` and `myReactions` are per caller (the backend sends `Cache-Control: no-cache, no-store`; keep it).

## 9. Heads-up for an app that turns the module on later

Posts written before the switch carried the **account id** as their public author id. Those ids were public, so after the switch the old posts are tied to the **first profile** and nothing can undo that. A user who needs secrecy must use a **new** profile for new activity. An app that can start with the module on from its first deploy has no such history. (Spec §11; changelog entry.)

## Change log

- **FINAL-1 (2026-10-08)** — first complete version.
