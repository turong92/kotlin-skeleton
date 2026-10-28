# Role management — HTTP contract (kotlin-skeleton backend)

Audience: the frontend (react-skeleton `@skeleton/auth/admin` role screen). Backend modules: `authz`, `authz-jdbc` (see [modules/authz.md](modules/authz.md)).
Conventions (envelope, error body, tokens): [account-http-contract.md](account-http-contract.md) section 0 — unchanged here.

## 0. Conventions

- Base path `/api/v1/admin`. **Off by default**: the routes exist only when the app has `authz` + `authz-jdbc` and sets `skeleton.authz.admin.enabled=true`; otherwise every route below is `404`.
- Envelope and errors are the platform ones: `{ value | values, meta }`, errors `{ code, title, status, detail?, traceId, spanId, timestamp, errors?, data? }`. Branch on `code`.
- Signed-in only: no token is `401`, a signed-in caller without the needed permission is `403 COMMON.FORBIDDEN`. The caller's permissions come from `GET /api/v1/auth/me` (`principal.permissions`, `["*"]` = full access) — hide what the caller cannot use, the server checks anyway.
- A **permission key** is `<module>.<verb>` (`payment.refund`). A **role name** matches `^[A-Z][A-Z0-9_]{1,31}$`. `*` is full access and belongs to the one fixed role (`skeleton.authz.super-role`, default `ADMIN`) only.

## 1. Permission catalog

### GET /api/v1/admin/permissions
Needs `authz.roles`. The permissions declared by the modules on the classpath (a module that is not part of the app does not appear):

```json
{ "values": [ { "key": "account.read", "module": "account" }, { "key": "payment.refund", "module": "payment" } ] }
```

Sorted by key. Use it to draw the checkbox groups (group by `module`).

## 2. Roles

### GET /api/v1/admin/roles
Needs `authz.roles` **or** `account.roles` (the account screen lists the roles it may hand out).

```json
{ "values": [
  { "name": "ADMIN", "description": "Full access", "permissions": ["*"], "includes": [], "effectivePermissions": ["*"], "protected": true, "holders": 2 },
  { "name": "CS", "description": "Support", "permissions": ["payment.read", "payment.refund"], "includes": [], "effectivePermissions": ["payment.read", "payment.refund"], "protected": false, "holders": 1 },
  { "name": "STAFF", "description": null, "permissions": ["board.moderate"], "includes": ["CS"], "effectivePermissions": ["board.moderate", "payment.read", "payment.refund"], "protected": false, "holders": 0 }
] }
```

- `permissions` is sorted and means the permissions the role holds **directly**. `includes` = the roles it inherits from (one step; sorted). `effectivePermissions` = `permissions` plus everything inherited, transitively (what the role really allows; for display — a screen locks and labels the inherited ones). `/auth/me` `roles` stay the assigned roles; its `permissions` are the expanded set.
- `protected: true` = the fixed full-access role: no edit, no delete.
- `holders` = active accounts holding the role; `null` when the app has no account module.

### POST /api/v1/admin/roles
Needs `authz.roles`. Body `{ "name": "CS", "description": "Support", "permissions": ["payment.read"], "includes": ["VIEWER"] }` (`description` optional, up to 200 chars; `permissions` required, may be empty; `includes` optional, up to 50 existing role names). → `201` with `{ "value": <role> }` and a `Location` header.

### PUT /api/v1/admin/roles/{name}
Needs `authz.roles`. Body `{ "description": "…", "permissions": [ … ], "includes": [ … ] }` — replaces them; the name cannot change. **`includes` omitted = the role keeps what it inherits now** (an old screen that does not know about the hierarchy must not wipe it); `"includes": []` clears it. → `200` with `{ "value": <role> }`.

### DELETE /api/v1/admin/roles/{name}
Needs `authz.roles`. → `204`.

## 3. Errors

| Status | `code` | When |
|---|---|---|
| 400 | `COMMON.VALIDATION_FAILED` | bad role name (`errors[0].field = name`), description too long, a required field missing |
| 400 | `AUTHZ.PERMISSION_UNKNOWN` | a permission no module on the classpath declares |
| 400 | `AUTHZ.INCLUDE_UNKNOWN` | `includes` names a role that does not exist |
| 401 | `COMMON.UNAUTHORIZED` | no / invalid token |
| 403 | `COMMON.FORBIDDEN` | the caller lacks `authz.roles` (or `account.roles` for the list) |
| 403 | `AUTHZ.ESCALATION` | the change adds **or removes** a permission the caller does not hold, or `DELETE` of a role that has one (keeping what a role already has is fine). Adding or removing an include is judged by the included role's **effective** permissions the same way |
| 404 | `AUTHZ.ROLE_NOT_FOUND` | no such role |
| 409 | `AUTHZ.ROLE_EXISTS` | `POST` with a name that exists |
| 409 | `AUTHZ.ROLE_PROTECTED` | editing / deleting the full-access role, giving `*` to any other role, or including the full-access role |
| 409 | `AUTHZ.ROLE_CYCLE` | the `includes` would make roles include each other (directly or through others); `message` names the loop |
| 409 | `AUTHZ.ROLE_INCLUDED` | deleting a role another role still includes (remove it there first) |
| 409 | `AUTHZ.ROLE_IN_USE` | deleting a role some active account still holds |
| 409 | `AUTHZ.SELF_LOCKOUT` | the change would remove the caller's own `authz.roles` (edit or delete of a role that is what gives it to them — also when they inherit it through `includes`) |

Note: if a module was removed from the app, the keys it declared may still sit on a role. They are no longer in the catalog, so taking such a key off a role is also judged as escalation (the caller must hold it) — in practice only the full-access role can clear them.

## 4. When a change takes effect

A role edit applies from the **next request** on the same server instance; other instances pick it up within `skeleton.authz.cache-ttl` (default 30 s). A change to an included role reaches every role above it the same way. Nobody has to sign in again — the token only names the account, the permissions are read per request. A screen showing `principal.permissions` refetches `/auth/me` to see its own change.

## Change log

- DRAFT-2 (2026-10-28): role hierarchy — `includes` / `effectivePermissions` on the role, `includes` in `POST` / `PUT` (omitted on `PUT` = keep), errors `AUTHZ.ROLE_CYCLE` · `AUTHZ.INCLUDE_UNKNOWN` · `AUTHZ.ROLE_INCLUDED`.
- DRAFT-1 (2026-10-24): first version.
