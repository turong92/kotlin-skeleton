# board HTTP contract — guests, approval, edited versions, comment pictures

What a screen needs to know about the new `board` features. Everything here is **off until the app turns it on**; with the defaults every response only gains the keys listed under 「Keys that appear」. Server design and reasons: `docs/superpowers/specs/2026-10-17-board-guest-moderation-design.md`, module notes: [modules/board.md](modules/board.md).

Paths are under `skeleton.board.http.base-path` (default `/api/v1/boards`).

## Keys that appear

| Where | Keys |
|---|---|
| `GET /config` | `guestEnabled` · `guestSecret` (`PASSWORD` \| `TOKEN`) · `guestNameMaxLength` · `captchaRequired` · `commentMaxAttachments` (0 = comment pictures off) |
| board | `settings: {guestWrite: NONE\|COMMENTS\|ALL, postApproval: OFF\|GUESTS\|ALL, commentApproval: OFF\|GUESTS\|ALL, ownerModeratesComments}` |
| comment | `canManage` (only for callers who manage comments — operators and the post owner where owner moderation is on; absent otherwise. `false` = this comment's status can no longer be changed by you, e.g. an owner cannot restore what an operator hid — do not draw the restore button) |
| post summary / detail, comment | `authorGuest` (draw 「(비회원)」 — `authorName` is the guest's typed name, not an account nickname) |
| post detail | `canManageComments` · `commentApproval` (`null` = board value) · `pendingRevision` |
| comment | `pendingRevision` · `attachments` (`[]` unless the comment is visible) |
| post summary | `pendingRevision` only in the `?revision=pending` list |

Status values gain `PENDING` (post and comment). A guest row's `authorId` is `guest:<id>`, `mine` is always `false` — the server does not know guests; the screen decides who may edit from the secret it holds.

## Guest writes

No login. Create/edit/delete with the same paths as members:

- body: `guestName` (required on create, at most `guestNameMaxLength`), plus `captchaToken` when `captchaRequired`;
- header **`X-Board-Guest-Secret: <secret>`** on create, edit, delete, comment approval and revision decisions. Never put it in the URL, a cookie or the body. `PASSWORD`: the person types at least 4 characters, at most 72 bytes (do not store it); `TOKEN`: the app generates 32..128 characters of `[A-Za-z0-9_-]` (43 chars of base64url) and keeps it.
- a board answers an anonymous write with `401 COMMON.UNAUTHORIZED` unless its `guestWrite` allows it (`COMMENTS`: comments only; `ALL`: posts too). A signed-in caller that sends `guestName` gets `400`; a guest cannot send `authorId` (`403`) or save a draft.
- errors: wrong secret / someone else's row / a member's row / no header → the same `403 BOARD.FORBIDDEN` (do not try to tell them apart); too many guesses or writes → `429 BOARD.RATE_LIMITED`; captcha → `400 BOARD.CAPTCHA_FAILED`.
- A comment that was never public (waiting, rejected, deleted while waiting) is `404` for everyone but its writer and the managers — also for edit, delete and reactions; a guest's wrong secret on such a row reads `404`, not `403`.
- A guest cannot read their own waiting post/comment back (reads never look at the secret): **keep the create/edit response** and show it.
- Create is idempotent per `Idempotency-Key`; send a fresh key per user action (a new `captchaToken` on a retry still counts as the same request).

## Approval

A new post/comment from a writer the board makes wait comes back with `status: PENDING`. Show 「승인 대기」 and the body you just sent; other people will not see it at all (no placeholder). Managers (`canManageComments`, moderators) approve/hide with the existing `PUT …/comments/{cid}/moderation {status}` (`PUBLISHED` approves, `HIDDEN` rejects; owners cannot choose `DELETED` and cannot restore what a moderator hid → `403`). Lists:

- `GET /{code}/posts?status=PENDING` (moderator) — new posts; `…/posts/{id}/comments?status=PENDING` (managers, flat, oldest first).
- moderators set board settings with `PATCH /{code}` `{name?, description?, settings?: {…}}` (only the keys sent change).

## Edited versions

If the writer must wait, editing something already public does **not** change the public text: `PATCH` returns the public item plus `pendingRevision: {title?, body, attachments, submittedAt}`. Say 「고친 내용은 승인 뒤에 보여요」. A second edit overwrites the waiting one. Managers compare public vs `pendingRevision` and decide with `PUT …/posts/{id}/revision` or `PUT …/comments/{cid}/revision` `{decision: "APPROVE"|"REJECT", submittedAt}` — **`submittedAt` is the version of the edit on screen** (`pendingRevision.submittedAt`, required). If the writer overwrote the edit meanwhile the answer is `409 BOARD.REVISION_CHANGED` and nothing happens: show the new edit and decide again. Editing a post or comment that was hidden or deleted in the meantime is `404` (no revision is stored). Lists: `GET /{code}/posts?revision=pending` (moderator; summary + `pendingRevision`), `GET …/comments?revision=pending` (managers). Hiding/deleting the public item throws the waiting edit away.

## Comment pictures

When `commentMaxAttachments > 0`, create/edit comment bodies accept `attachments: string[]` (storage keys the app uploaded itself; at most that many). Render with the app's own key → URL function. Hidden/deleted comments carry `[]`.

Guests (signed out) may attach pictures too when the server enables guest uploads: first `POST /api/v1/storage/guest/presign` `{fileName, contentType, sizeBytes, captchaToken?}` (one image, jpeg/png/webp/gif, ≤ 10 MB, no SVG; `401` when guest uploads are off, `429 STORAGE.RATE_LIMITED`, `400 STORAGE.FILE_REJECTED` / `STORAGE.CAPTCHA_FAILED`), `PUT` the file to the returned URL with the returned headers, then send the returned `key` in `attachments`. The server accepts only keys it issued for guests - a member's key is `403`. Unattached uploads are deleted after about a day.
