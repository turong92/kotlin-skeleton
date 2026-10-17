# board

게시판: 게시판(코드로 구분) · 글 · **대댓글이 되는 댓글 트리** · **종류를 설정으로 늘리는 반응**(좋아요/싫어요 → 공감 · 슬픔 …). 서비스 · 정책 · HTTP · 설정이 이 모듈이고,
저장은 어댑터 `board-jdbc`(PostgreSQL · MySQL)가 한다 — 이 모듈에는 메모리 구현이 없다. 설계 근거는 아래 "Decisions and rejected alternatives".

HTTP 는 `skeleton.board.http.base-path`(기본 `/api/v1/boards`)에 열린다 (서블릿 웹 앱 + Spring Security 가 있을 때. 호출자는 `Authentication.name` = 계정 id, 운영자는 `ROLE_<moderator-role>` 권한):

| 메서드 · 경로 | 하는 일 | 누가 |
|---|---|---|
| `GET /config` | 반응 종류 · 모드 · 깊이 상한 · 길이 상한 · `canModerate` · `authorRequired` | 로그인 |
| `GET /` · `GET /{code}` · `POST /` | 게시판 목록(글 수 포함) · 하나 · 만들기 | 로그인 · 로그인 · 운영자 |
| `GET /{code}/posts?page&size&sort=latest\|reactions\|comments&q&reaction&status&mine` | 글 목록(페이지 envelope). 고정 글이 항상 먼저. `reaction=<코드>` 는 그 종류의 개수로 정렬. `q` 는 제목 · 본문 | 로그인 |
| `GET /{code}/posts/{id}` | 글 상세 (호출마다 조회수 +1). 숨김 · 삭제 · 임시저장은 작성자 · 운영자 외에는 404 | 로그인 |
| `POST` · `PATCH` · `DELETE /{code}/posts[/{id}]` | 쓰기(201 + Location, `Idempotency-Key`) · 고치기 · 소프트 삭제(204) | 로그인 · 작성자/운영자 · 작성자/운영자 |
| `PUT /{code}/posts/{id}/moderation` | `{status?: PUBLISHED\|HIDDEN\|DELETED, pinned?}` | 운영자 |
| `GET` · `POST /{code}/posts/{id}/comments` | 최상위 댓글 페이지 + 각 스레드의 **모든 자손**(평평하게, 작성 순) · 댓글/답글(`parentId`) 쓰기 | 로그인 |
| `PATCH` · `DELETE /…/comments/{cid}` · `PUT …/moderation` | 고치기(작성자) · 소프트 삭제(작성자/운영자) · `{status}` 숨김/복구/삭제(운영자) | |
| `PUT` · `DELETE /…/posts/{id}/reactions` · `…/comments/{cid}/reactions` | `{type}` 로 반응 · 떼기(`?type=` 없으면 내 반응 전부). 응답 `{counts, myReactions}` | 로그인 |

`status`(운영자) · `mine=true`(내 글 — 임시저장 포함) 목록 필터, 에러 코드는 `BOARD.*` ([에러 표](../errors.md)에 같은 모양): `NOT_FOUND` · `POST_NOT_FOUND` · `COMMENT_NOT_FOUND` 404 ·
`FORBIDDEN` · `AUTHOR_NOT_ALLOWED` 403 · `REACTION_TYPE_INVALID` · `CONTENT_INVALID` · `AUTHOR_REQUIRED` 400 · `COMMENT_TOO_DEEP` 422 · `POST_NOT_COMMENTABLE` · `CODE_TAKEN` 409 · `RATE_LIMITED` 429.

**작성자 이름**: 글 요약 · 글 상세 · 댓글 응답에는 `authorId`(공개 작성자 id — 아래 「공개 작성자 id」) · `authorDeleted` · `mine` 과 함께 **`authorName: string | null`** · **`authorTag: string | null`**(4자리 숫자 문자열)이 실린다(키는 늘 있다). 이름은 platform 의 `AuthorDirectory` 가 준다 —
`account` 가 있으면 그 닉네임 · 꼬리표(기본 구현), 앱이 같은 타입의 빈을 두면 그것(예: 돌판마다 다른 닉네임 — `AuthorContext(source="board", scope=<게시판 코드>)` 로 어느 게시판인지 안다), 아무것도 없으면 `null`. board 는 `account` 를 모르고 그 반대도 같다.
**요청 하나에 조회 한 번**: 목록 · 스레드는 모든 작성자 id 를 모아(중복 제거) 한 번만 묻는다(`AuthorNamesTest` 가 호출 횟수를 센다) — 글마다 묻지 않는다. 지워진 작성자(톰스톤)는 묻지 않고 이름이 `null` + `authorDeleted: true`.
고리가 던지거나 느려도 응답은 이름만 비워 나간다(경고 로그). 댓글 알림의 payload 에는 **이름을 싣지 않는다**(`authorId` — 공개 작성자 id — 만) — 받은편지함의 payload 는 작성자가 탈퇴(익명화)한 뒤에도 남의 편지함에 남으므로, 거기 적힌 닉네임은 지워지지 않는다. 알림 화면이 이름이 필요하면 **읽는 쪽이 `authorId` 로 푼다**(탈퇴한 작성자는 이름이 없다). 제목 문구는 그대로(`BoardNotificationFormatter`) — 앱이 문구에 이름을 넣으려면 `BoardCommentNotice.authorName` 을 쓸 수 있으나 그 문구도 같은 이유로 편지함에 남는다.

**계정 삭제**: `account` 가 계정을 지울 때(platform 의 `AccountErasureListener`) 글 · 댓글 작성자와 반응의 계정이 계정마다 하나인 톰스톤(`deleted:<해시>`)으로 바뀐다 — 행 · 카운터는 남고 응답에 `authorDeleted: true` 가 실려 화면이 "삭제된 사용자" 를 보인다.
앱이 자기 저장소를 두면 `BoardErasureRepository` 도 구현한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:board"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | `notification`, `idempotency`, `captcha-turnstile` |
| 설정 접두사 | `skeleton.board` — [docs/config/modules/board.yml](../config/modules/board.yml) |
| 기본 동작 | 켜짐. 게시판은 `seed-boards` 로 심거나 운영자가 만든다 — 아무것도 심지 않으면 비어 있다. 반응은 LIKE · DISLIKE, SINGLE 모드. 알림 · 한도는 없는 것과 같다. |
| 부팅에 필요한 것 | 저장소 포트 네 개(`BoardRepository` · `PostRepository` · `CommentRepository` · `ReactionRepository`) — `board-jdbc` 가 내거나 앱이 직접 구현한다. 없으면 시작이 실패하고 메시지가 빠진 빈 이름을 적는다. |
| 교체 지점 | `BoardErasureRepository`, `BoardPolicy`(새 기본 메서드: `canGuestPost` · `canGuestComment` · `canGuestAttach` · `needsApproval` · `canManageComments`), `BoardRateLimiter`, `BoardNotifier`, `BoardNotificationFormatter`, `AuthorDirectory`, `AuthorNames`, `BoardAuthorRules`, `BoardRepository`, `PostRepository`, `CommentRepository`, `ReactionRepository`, `BoardRevisionRepository`, `BoardEventListener`(여러 개), `BoardCaptcha`, `BoardGuestSecrets`, `BoardGuestLimiter`, `BoardController`, `PostController`, `CommentController` |
| 마이그레이션 | 없음 (스키마는 `board-jdbc`) |
| 프론트 짝 | `@skeleton/board` |
| 테스트 | `modules/board/src/test`, `modules/board/src/noOptionalTest` (`notification` · `idempotency` 가 클래스패스에 없을 때) |

## 공개 작성자 id · `mine` · 작성 프로필 (`AuthorIdentities`)

한 사람이 여러 닉네임(프로필)을 갖고 글 · 댓글마다 골라 쓰는 앱을 위한 자리다 — 두 프로필이 같은 사람이라는 사실이 다른 사용자에게 드러나지 않게. board 는 프로필 모듈(`author-profile`)도 `account` 도 **모른다**: platform 의 `AuthorIdentities` 빈(이 계정이 이 작성자 id 로 써도 되는가) 하나가 있느냐로 켜진다.
빈이 없으면 동작 · 응답은 이 기능 이전과 같다(덧붙는 키 `mine` · `authorRequired: false` 만 다르다). 설계 · 전수표: `docs/superpowers/specs/2026-10-07-author-profiles-design.md`. 프론트가 읽는 계약(쓰기 `authorId` · `mine` · 오류 코드 · 알림 payload · 화면 규칙): `docs/author-profile-http-contract.md` §5(`author-profile` 을 고른 프로젝트에만 남는다), 누출 스캔 시험: `apps/sample` 의 `AuthorLinkLeakIntegrationTest`(`docs/modules/author-profile.md` 「누출 스캔 시험과 검증 기록」).

**공개 id 규칙은 `BoardAuthors.publicId` 한 곳**이고 응답 DTO(글 요약 · 상세 · 댓글) · 댓글 알림 payload · `AuthorNames` 조회가 모두 이것을 쓴다:

```
공개 id = author_ref
          ?: author_id                    (AuthorIdentities 없음)
          ?: "pending:<p|c><행 id>"        (AuthorIdentities 있음 + 아직 프로필로 넘겨받지 않은 옛 행 — `pending:p123` 글 · `pending:c45` 댓글)
```

- `AuthorIdentities` 가 있으면 `author_id`(계정 id · 톰스톤)는 **어떤 응답에도 나가지 않는다.** `author_ref` 가 없는 옛 행은 톰스톤이어도 `pending:` 이다 — 화면 손실은 없다(`authorDeleted` 는 `author_id` 에서 따로 계산해 `true`). 빈이 없을 때만 톰스톤 값이 그대로 나간다(지금과 같다). `pending:` 과 톰스톤은 `AuthorDirectory` 에 묻지 않는다(platform 의 약속: 계정 톰스톤은 넘기지 않는다. 지워진 **프로필** id 는 넘겨도 되고 카드 `deleted=true` 로 답한다).
- `authorDeleted = author_id 가 톰스톤 || 카드.deleted`(디렉터리가 돌려준 카드가 지워진 프로필 — 그때 `authorName` 은 `null`).
- **`mine: boolean`**(글 요약 · 상세 · 댓글, 목록 · 상세 · 한 댓글 응답 전부) = 호출자 != null && 호출자 계정 == `author_id`. **`AuthorIdentities` 유무와 무관하게 항상 실린다** — 프론트가 `accountId === authorId` 로 "내 글" 을 판단하던 것은 공개 id 가 프로필 id 가 되면 늘 거짓이다. 익명 · 운영자(작성자가 아닐 때)는 `false`.
- `GET /config` 의 **`authorRequired`** = `AuthorIdentities` 빈 유무. 프론트는 이것으로 작성 프로필 선택기를 보인다.

**쓰기 규칙**(글 · 댓글 생성 본문의 `authorId`):

| `AuthorIdentities` | 본문 `authorId` | 결과 |
|---|---|---|
| 없음 | 없음 | 지금과 같다 — `author_ref = NULL` |
| 없음 | 있음 | `403 BOARD.AUTHOR_NOT_ALLOWED` |
| 있음 | 없음(빈 문자열 포함) | `400 BOARD.AUTHOR_REQUIRED` |
| 있음 | 있음 | `allows(호출자 계정, authorId, AuthorContext("board", 게시판 코드))` 가 참이면 `author_ref = authorId`, 거짓이면 `403 BOARD.AUTHOR_NOT_ALLOWED` — 남의 · 없는 · 지워진 id 를 같은 문구로 답한다 |

- `author_id` 는 언제나 호출자 계정 — 권한(`BoardPolicy`) · `mine=true` 목록 · 알림 수신자 · 자기 글 알림 생략 · 한도 · 반응은 지금처럼 **계정 기준**이다(프로필 A 로 쓴 글에 B 로 답해도 자기에게 알림이 가지 않는다).
- **수정에는 `authorId` 칸이 없다**(`PATCH` 본문의 모르는 칸은 조용히 무시된다). 운영자가 고쳐도, PUBLISHED ↔ DRAFT 를 오가도 `author_ref` 는 바뀌지 않는다.
- 첨부: `AuthorIdentities` 가 있을 때 첨부 키에 **호출자 계정 id 가 들어 있으면** `400 BOARD.CONTENT_INVALID`(글 만들기 · 고치기 — 고칠 때는 **새로 붙는 키만** 본다: 글이 이미 가진 옛 키를 그대로 다시 보내면 통과). board 는 storage 를 모른 채 문자열 포함만 본다 — 계정 id 가 든 키가 공개 `attachments` 로 나가면 두 프로필의 글이 이어진다. 키에 계정 id 가 없게 만드는 쪽은 storage 의 몫이다. 이 막기는 **계정 id 가 충분히 길고 고유해야** 뜻이 있다(`acc_` + 무작위 128비트 같은 것; 짧거나 흔한 id 는 다른 키의 일부와 우연히 겹쳐 정상 키를 거절하거나 놓친다). **잔여 위험**: 같은 `OPAQUE` 첨부 키를 API 직접 호출로 두 프로필의 글에 모두 실으면 키 값이 같아 이어진다(서버는 키를 한 글에만 쓰게 막지 않는다) — 화면이 글마다 새로 올린 키를 쓰는 한 생기지 않는다.
- 알림 payload 의 `authorId` 는 공개 id 다. 수신자(`recipientIds`)는 계정이다.
- **자기 저장소(`PostRepository` · `CommentRepository`)를 두는 앱**은 `authorRef` 를 쓰기에 저장하고 읽기마다 돌려줘야 하고(`Post` · `PostListItem` · `Comment` · `NewPost` · `NewComment` 의 마지막 칸), 작성자 프로필을 켜는 앱이면 옛 행을 넘겨받도록 `AuthorAdoptionListener`(`pendingAccounts(limit, after)` · `adopt`)도 제공해야 한다 — 없으면 켜기 전의 글이 영영 `pending:` 으로 남는다. `board-jdbc` 가 두 가지를 이미 한다.
- `BoardPolicy.canAttach` 의 기본 구현은 모든 키를 허용한다(접두사를 비교하지 않는다). 샘플 앱은 아직 `BoardPolicy` 를 바꾸지 않는다 — `StorageKeyOwnership` 으로 켜는 것은 앱의 몫이다(아래 「앱이 바꾸는 곳」).
- **알림 문구를 바꾸는 앱**(`BoardNotificationFormatter`): `notice.comment.authorId` 는 계정 id 다 — 문구에 넣으면 계정 id 가 **남의 편지함**에 영영 남는다. 작성자 id 가 필요하면 `notice.publicAuthorId`(필수 인자, 기본값 없음)를 쓴다.

## 반응 종류를 늘리는 법

코드 · 스키마 변경 없이 설정만: `skeleton.board.reaction.types: [LIKE, EMPATHY, SAD]` (대문자 코드). 서버는 `GET /config` 의 `reactionTypes` 로 프론트에 알리고, 모르는 코드는 `400 BOARD.REACTION_TYPE_INVALID`.
설정에서 뺀 종류의 옛 반응은 DB 에 남지만 응답의 `counts` · `myReactions` 에는 나오지 않는다 (합계 `reaction_count` 에는 남는다). `mode: PER_TYPE` 으로 바꾸면 종류마다 하나씩 여러 종류를 함께 누를 수 있다 (역방향 전환은 SINGLE 이 다음 반응 때 그 사람의 다른 종류를 지운다).

## 비회원 작성 · 승인 대기 · 고친 판 · 댓글 첨부 (새 기능은 모두 꺼진 채 시작한다)

설계 · 근거: `docs/superpowers/specs/2026-10-17-board-guest-moderation-design.md`. **기본 설정에서 기존 사용처의 동작은 바뀌지 않는다** — 응답에 덧붙는 키만 있다(`authorGuest` · `canManageComments` · `commentApproval` · `pendingRevision` · 댓글 `attachments` · 게시판 `settings` · `GET /config` 의 `guestEnabled` · `guestSecret` · `guestNameMaxLength` · `captchaRequired` · `commentMaxAttachments`). 프론트가 읽는 계약: [board-http-contract](../board-http-contract.md).

**비회원 작성** (`skeleton.board.guest.enabled=true` + 게시판 설정 `guestWrite`: `NONE`(기본) · `COMMENTS` · `ALL`). 로그인 없이 글 · 댓글을 쓰고 닉네임(`guestName`)과 비밀값으로 자기 것을 고치고 지운다. 비회원 행의 `author_id` 는 `guest:<ULID>`(행마다 새 값 — 같은 비회원의 두 글이 이어지지 않고, 탈퇴 고리 · 넘겨받기에 걸리지 않는다).
- **비밀은 헤더 `X-Board-Guest-Secret` 로만** 받는다(만들기 · 고치기 · 지우기 · 댓글 승인 · 판 결정). 본문 · 쿼리 · 쿠키에는 두지 않는다. 저장은 해시만(`PASSWORD`(기본): 사람이 치는 4자 이상 · 72바이트 이하, bcrypt / `TOKEN`: 앱이 만든 `[A-Za-z0-9_-]{32,128}`, SHA-256 — 접두사가 방식을 적어 설정을 바꿔도 옛 행은 그대로 확인된다). 해시는 모델 · 응답 · 이벤트 · 로그 · `toString` 에 없고 `PostRepository.guestSecretHash` · `CommentRepository.guestSecretHash` 로만 읽는다(기본 구현은 null = 늘 불일치, 닫힌 쪽).
- 비밀이 틀린 경우 · 다른 비회원의 행 · 회원 행 · 헤더 없음은 **같은 `403 BOARD.FORBIDDEN`**(같은 문구). 아직 공개되지 않은 행(승인 대기 · 가림)은 존재를 알리지 않으려고 404.
- 남용: 비밀 대입은 **해시를 계산하기 전에 시도를 먼저 세고(원자적), 맞으면 `RateLimitStore.release` 로 되돌린다**(행마다 + IP 마다, `guest.secret-attempts`, 기본 10 / 10분 → `429 BOARD.RATE_LIMITED`; 맞는 비밀로 승인 · 관리를 거듭해도 닳지 않는다. 대기 목록 읽기는 쓰기 한도도 먹지 않는다). `release` 를 지원하지 않는 저장소(기본 구현은 아무 일도 안 한다)에서는 맞는 시도도 닳는다 — 같은 비밀로 동시에 오는 요청이 모두 해시를 계산하는 틈은 없다(인메모리 · Redis 는 `release` 를 구현). **남는 위험**: 남이 한 행에 틀린 비밀을 10번 보내면 그 행의 주인도 10분 동안 429 를 받는다(행 단위 잠금 — 비밀을 모르는 사람이 비회원 글의 수정 · 삭제를 일시적으로 막을 수 있다). IP 단위 한도도 같은 IP 의 다른 사용자에게 번진다. 쓰기는 `guest.rate-limit`(기본 5 / 1분, `guest:<IP>`)로 — `rate-limit.enabled` 와 무관하게 `guest.enabled` 면 늘 걸린다. 같은 `Idempotency-Key` 는 익명이면 IP 범위로 묶인다(`captchaToken` 은 지문에서 뺀다). 재시도 사이에 IP 가 바뀌면(모바일 망 전환) 두 장이 될 수 있다.
- 로그인한 호출자가 `guestName` 을 보내면 `400`, 비회원이 `authorId` 를 보내면 `403 AUTHOR_NOT_ALLOWED`. 비회원 글은 임시저장 불가, 반응은 불가(로그인 필요 그대로), 알림은 받지 않는다(받는 사람이 `guest:` 면 건너뜀).
- 쓰기 경로는 `guest.enabled=true` 일 때만 인증 없이 열린다(그래도 게시판의 `guestWrite` 가 정한다 — `NONE` 이면 지금과 같은 `401`). 읽기는 `http.allow-anonymous-read` 가 정한다(비회원 화면을 쓰려면 앱이 함께 켠다). platform CORS 기본 허용 헤더에 `X-Board-Guest-Secret` 이 추가됐다.

> **경고 — 캡차 없이 비회원 쓰기를 켜면 스팸 위험이 크다(기본은 캡차 꺼짐).** IP 한도는 봇넷 · 프록시를 넘지 못하고, 승인 대기(`GUESTS`)는 공개를 막을 뿐 들어오는 양은 막지 못한다. 공개 서비스에서는 캡차를 함께 켠다:
>
> ```yaml
> skeleton:
>   captcha-turnstile:
>     enabled: true
>     secret-key: ${SKELETON_CAPTCHA_TURNSTILE_SECRET_KEY}   # 프로젝트 모드는 skeleton.projects.<id>.config.captcha-turnstile.secret-key
>   board:
>     guest:
>       enabled: true
>       captcha: REQUIRED      # 비회원의 만들기에 captchaToken 필수 → 실패는 400 BOARD.CAPTCHA_FAILED. BoardCaptcha 빈이 없으면 기동 실패
> ```

> **남는 위험 — 비회원 첨부는 회원처럼 허용된다(`canGuestAttach` 기본 true).** 비회원은 소유를 확인할 계정이 없어 **아무 키나** 실을 수 있다. 공개된 남의 첨부 키를 복사해 다시 실을 수 있어 원래 글이 가려진 뒤에도 그 그림이 비회원 댓글로 되살아나고, `ACCOUNT_ID` 키 방식에서는 남의 비공개 업로드 키를 맞히면 그것도 실린다. 비회원 업로드를 받는 앱은 정책으로 좁힌다:
>
> ```kotlin
> @Bean fun boardPolicy(tickets: GuestUploadTickets): BoardPolicy = object : BoardPolicy by DefaultBoardPolicy() {
>     // 비회원 업로드는 앱이 발급한 guest-uploads/ 아래 키만, 그리고 이 업로드 표가 아직 아무 글에도 쓰이지 않은 키만
>     override fun canGuestAttach(keys: List<String>) = keys.all { it.startsWith("guest-uploads/") && tickets.claim(it) }
> }
> ```

**승인 대기 · 글 주인의 댓글 관리** (게시판 설정 `postApproval` · `commentApproval`: `OFF`(기본) · `GUESTS` · `ALL`, `ownerModeratesComments`; 글마다 `commentApproval` 로 게시판 값을 덮어쓸 수 있다 — 댓글 관리가 켜진 게시판만, 아니면 `400`). `PATCH /{code}`(운영자)로 고친다; `seed-boards[].settings` 는 만들 때만 적용된다.
- 「승인 대상 작성자」 판정은 `BoardPolicy.needsApproval` 한 곳(운영자는 늘 면제, 댓글을 관리하는 글 주인은 자기 글의 댓글에 대해 대상이 아님). 대상이 쓰면 `PENDING`, 승인 = 기존 `PUT …/moderation {status: PUBLISHED}`, 거절 = 가리기(`HIDDEN`).
- `PENDING` 글은 운영자 · 회원 작성자만 본다(그 밖은 404 · 목록에 없음). `PENDING` 댓글은 운영자 · 관리하는 글 주인 · 회원 작성자에게만 보이고(본문 포함) **그 밖의 사람에게는 스레드에 자리도 없고 `replyCount` · `comment_count` 에도 들어가지 않는다.** 목록: 글 `GET /{code}/posts?status=PENDING`(운영자), 댓글 `GET …/comments?status=PENDING`(관리자, 오래된 순 평평하게).
- 한 번도 공개된 적 없는 댓글(대기 → 거절, 대기 중 삭제)은 **그 뒤에도** 관리자 · 쓴 회원 말고는 스레드에 자리조차 없다(`published_at`). 한 번이라도 공개된 댓글을 가리면 자리 표시가 남는다.
- 글 주인은 `PUBLISHED` · `HIDDEN` 만 고르고 `DELETED` 는 못 한다. **주인이 직접 가린 것만** 되살릴 수 있다 — 운영자가 가렸거나 누가 가렸는지 기록이 없는 옛 행은 목표 상태와 무관하게 `403`(`moderated_by`는 상태가 실제로 바뀔 때만 쓴다). 대기 중인 글을 쓴 회원이 스스로 `PUBLISHED` 로 바꿀 수는 없다(대기 그대로). 비회원 글 주인은 글의 비밀로 같은 일을 한다. 글 상세의 `canManageComments` 가 단추를 그리는 기준이고, 댓글마다의 `canManage`(관리하는 사람에게만 실린다)가 그 댓글의 복원 · 숨김 단추를 그리는 기준이다 — 주인에게 `false` 면 운영자가 가렸거나 지워진 댓글이다.
- 알림: 댓글이 `PUBLISHED` 가 될 때(바로 공개되거나 승인될 때) 기존 `comment.created` · `comment.replied`. 승인 대기로 들어오면 댓글을 관리하는 글 주인에게만 `comment.pending`(`BoardNotifier.commentPending` — 기본 구현은 보내지 않는다).

**고친 판 (revision)** — 판의 **버전**은 판의 `updated_at`(`pendingRevision.submittedAt`)이고 덮어쓸 때마다 커진다. 승인 · 거절은 본 버전을 `submittedAt` 으로 함께 보내며, 잠근 뒤 같지 않으면 `409 BOARD.REVISION_CHANGED`(보는 사이 덮어쓴 판이 몰래 공개되지 않는다). 판 만들기는 잠근 뒤 대상이 PUBLISHED 가 아니면 판을 넣지 않고 `404`. 직접 수정(운영자 포함)은 같은 트랜잭션에서 대기 중인 판을 버린다. **승인 설정을 끈 뒤에도 이미 대기 중인 글 · 댓글은 대기에 남고**(스스로 공개할 수 없다 — 운영자, 댓글은 관리하는 글 주인이 처리), 읽기 경로는 판이 있을 수 있는 게시판(승인 설정이 켜진)에서만 판을 묻는다(꺼진 뒤 남은 판은 `?revision=pending` 목록 · 결정 경로로만 다룬다). 쿼리가 느는 곳(§8): 승인이 켜진 게시판에서 로그인한 호출자의 글 상세(판 1회) · 댓글 목록(판 1회) · 댓글 수정 응답(판 1회).

승인된 것을 승인 대상 작성자가 고치면 **옛 공개본은 그대로 두고 고친 내용만 대기**한다(대상마다 판 하나, `board_revisions`). 대기 중에 또 고치면 판을 덮어쓴다. 판 승인 · 거절은 `PUT …/posts/{id}/revision`(운영자) · `PUT …/comments/{cid}/revision`(관리자) `{decision: APPROVE|REJECT}`; 대기 목록은 `GET /{code}/posts?revision=pending` · `GET …/comments?revision=pending`. 공개본이 `PUBLISHED` 를 떠나면(가림 · 삭제) 판은 버려지고, 운영자의 직접 수정이 이기며(판을 버린다), 승인 시 판의 내용 · **첨부**가 원래 행으로 옮겨진다. 모두에게는 공개본만 보이고 `pendingRevision` 은 쓴 회원 · 운영자 · 관리하는 글 주인에게만(비회원 작성자는 고친 응답에서만). 판 저장소 포트 `BoardRevisionRepository` 는 `board-jdbc` 가 구현하고, 자기 저장소를 둔 앱이 이것 없이 승인을 켜면 판이 필요한 첫 수정에서 「board-jdbc 를 두거나 구현하라」 오류가 난다.

**댓글 첨부** — `skeleton.board.comment-max-attachments`(기본 0 = 꺼짐, 키를 보내면 `400`). 글 첨부와 같은 모양(저장 키 문자열만, 업로드는 앱). 쪽의 모든 댓글 키를 쿼리 한 번으로 읽고, **가린 · 지운 댓글의 `attachments` 는 `[]`**. 검사는 글과 같다(`canAttach` · `rejectAccountIdKeys`, 비회원은 `canGuestAttach`).

**커밋 뒤 이벤트 `BoardEventListener`** — 앱이 이 타입의 빈을 만들면(여러 개 가능) 쓰기마다 `PostCreated` · `PostUpdated` · `PostStatusChanged` · `CommentCreated` · `CommentUpdated` · `CommentStatusChanged` · `RevisionSubmitted` · `RevisionDecided`(승인이면 `…Updated` 도 함께)를 받는다. 트랜잭션 동기화가 열려 있으면(앱이 자기 `@Transactional` 안에서 서비스를 불렀을 때) `afterCommit`, 아니면 저장소 호출이 커밋하고 돌아온 바로 뒤에 같은 스레드에서 부른다. 롤백되면 부르지 않는다. 리스너가 던져도 요청은 성공한다(경고 로그). **`afterCommit` 안에서 DB 를 쓰려면 `REQUIRES_NEW`** 여야 한다(끝난 트랜잭션이 아직 묶여 있다는 Spring 규칙). 내구성은 없다(커밋과 리스너 사이에 죽으면 잃는다) — 반드시 남아야 하는 앱 데이터는 아래 「댓글에 앱 칸」 길로 같은 트랜잭션에 넣는다. 이벤트의 `Post` · `Comment` 에 비밀 해시는 없고, `authorId` 는 계정 id 일 수 있으니 공개 자리에는 `BoardAuthors.publicId` 를 쓴다.

**댓글에 앱 칸(번호 · 받는 멤버 · 언어 …)** — board 표에 싣지 않는다. 앱 표를 `board_comments(id)` 를 기본 키 · FK 로 1:1 로 두고(`ovation_cheers(comment_id bigint primary key references board_comments(id), seq, member_id, lang, …)`), 앱이 자기 `@Transactional` 안에서 `CommentService.create` 를 부르고 같은 트랜잭션에서 행을 넣는다. 앱이 자기 컨트롤러로 감싸도 비회원 확인 · 캡차 · 한도 · 판 규칙은 서비스 안에 있어 그대로 걸린다. `Idempotency-Key` 는 앱 경로에 `@IdempotentOperation` 을 다시 단다.

## 앱이 바꾸는 곳

- 누가 무엇을 하는지: `BoardPolicy` (기본: 작성자 · `moderator-role`). 상태 규칙(숨겨진 글엔 댓글 불가 등)은 서비스에 있다.
- 첨부 키 검사: `BoardPolicy.canAttach` — 기본은 허용이다. 키 규칙은 앱의 저장소 설정이 안다. **`AuthorIdentities` 가 없을 때만** `storage` 의 키(`<접두사>/<계정 id>/…`)를 `startsWith` 한 줄로 막으면 된다. 있으면 키에 계정 id 가 없어야 하므로(`skeleton.storage.web.key-owner=OPAQUE`) `storage` 가 내는 `StorageKeyOwnership` 빈의 `owns(caller.accountId, key)` 로 소유를 확인한다 — `OPAQUE` 키는 접두사 비교가 안 되고, 이 빈은 `key-owner` 방식(옛 `ACCOUNT_ID` 꼴 포함)을 알아서 따른다. 예: `override fun canAttach(caller: BoardCaller, keys: List<String>) = keys.all { ownership.owns(caller.accountId, it) }` (`storage`).
- 글 · 댓글 텍스트: **HTML 은 해석하지 않는다.** 제어문자(줄바꿈 · 탭 제외) · 글자 방향 덮어쓰기 문자를 지우고 공백을 다듬어 저장하며, 그릴 때 클라이언트가 이스케이프한다.
- 한도: `skeleton.board.rate-limit.enabled=true` 면 platform 의 `RateLimitStore`(redis-rate-limit 이 있으면 Redis)에 계정 · 동작별 고정 창. 한도 로직을 바꾸려면 `BoardRateLimiter`.
- 비회원 · 승인: `BoardPolicy.canGuestPost` · `canGuestComment` · `canGuestAttach` · `needsApproval` · `canManageComments`(본문 있는 기본 메서드 — 구현한 앱은 다시 컴파일만 하면 된다). 해시 방식은 `BoardGuestSecrets`(argon2 등을 끼운다), 비회원 한도는 `BoardGuestLimiter`, 캡차는 `BoardCaptcha`(`captcha-turnstile` 이 켜져 있으면 자동으로 이어진다).
- 알림 문구: `BoardNotificationFormatter` (제목 · 본문). 보내는 곳 자체는 `BoardNotifier`.
- 검색은 제목 · 본문 `LIKE '%q%'`(와일드카드 이스케이프, 대소문자 무시)다. 데이터가 커지면 PostgreSQL `tsvector`/GIN 이나 MySQL `FULLTEXT`, 또는 외부 검색으로 `PostRepository.page` 만 바꾸면 된다.

## Decisions and rejected alternatives

**모듈 분리: `board` + `board-jdbc`.** `notification`/`notification-jdbc`, `storage`/`storage-s3` 와 같은 모양 — 계약 · 서비스 · HTTP 는 저장 방식을 모르고, 어댑터가 포트를 구현한다. 앱이 JPA · jOOQ 로 저장하고 싶으면 포트 네 개만 구현하면 된다.
버린 것: *한 모듈*(PostgreSQL · MySQL SQL 이 서비스와 섞이고, 저장소를 못 바꾼다), *메모리 기본 구현*(게시판은 영속이 본질이라 메모리 기본값은 "켰는데 재시작하면 사라짐" 이라는 함정이 된다. 알림과 달리 안전하게 퇴화할 수 있는 기능이 아니다 — 그래서 어댑터가 없으면 시작이 실패한다).
`board` 는 `board-jdbc` 에 의존하지 않으므로 `new-project.sh` 에는 `--modules board,board-jdbc` 라고 둘 다 적는다.

**댓글 깊이: 트리를 저장하고 상한은 설정.** `parent_id` + `root_id` + `depth`. 최상위 댓글만 페이지로 나누고 그 모든 자손은 `root_id IN (…)` 한 쿼리로 가져와 서버에서 평평하게 내려준다 (클라이언트가 `parentId`/`depth` 로 접는다) — 목록 쿼리 수가 댓글 수와 무관하다 (`JdbcQueryCountDbTest`).
`max-comment-depth` 기본 2 (댓글 → 대댓글 → 대대댓글), 넘으면 422. 버린 것: *깊이 1 로 고정*(대댓글의 대댓글을 못 단다), *무한 중첩 + 재귀 CTE*(UI 가 못 그리고 PostgreSQL / MySQL 문법이 갈린다), *materialized path*(이동이 없는 게시판에는 과하다). 부모를 지우면 자식은 그대로 — 아래 소프트 삭제.

**반응: 문자열 코드 + 유니크 키 하나로 두 모드.** 테이블 `(target_type, target_id, account_id, reaction_type)` 가 기본 키(= 유니크 키)다. 종류는 열거형 · 칼럼 하나씩이 아니라 `VARCHAR` 코드라 설정(`reaction.types`)만 늘리면 된다.
버린 것: *`liked`/`disliked` 불리언 칼럼*(종류를 늘릴 때마다 스키마 변경), *계정당 한 행 `(target, account)` 유니크*(PER_TYPE 이 불가능 — 모드를 바꾸려면 마이그레이션), *종류별 테이블*.
`SINGLE` 은 같은 트랜잭션에서 `delete other types` 후 삽입한다. **동시성**: 같은 계정이 LIKE · DISLIKE 를 동시에 누르면 서로의 삭제를 못 봐서 둘 다 들어간다 → 모든 반응 변경이 **대상 행(글 또는 댓글)을 `FOR UPDATE` 로 먼저 잠근다**. 그 행은 카운터 UPDATE 가 어차피 잠그므로 새 경합이 아니다.
증명: `JdbcConcurrencyDbTest` 가 PostgreSQL · MySQL 에서 40 스레드로 돌고, 잠금을 빼면 중복 키 · 교착으로 실패한다 (변이 시험으로 확인).

**카운터: 비정규화 합계 + 종류별은 묶어서 센다.** `comment_count`(PUBLISHED 댓글 수) · `reaction_count`(모든 종류의 합) · `view_count` 는 글 · 댓글 행에 두고 `set x = x + :delta` 원자적 UPDATE 로, 바꾸는 행과 같은 트랜잭션에서 맞춘다 (정렬 `sort=reactions|comments` 가 이 칼럼을 쓴다).
종류별 개수는 쪽마다 `GROUP BY` 쿼리 한 번(`(target_type, target_id, reaction_type)` 인덱스)으로 센다 — 항상 정확하고, 종류가 설정이라 카운터 행이 종류마다 필요 없다. 버린 것: *종류별 카운터 테이블 `board_reaction_counts`*(upsert 문법이 방언마다 다르고 종류를 지우거나 모드를 바꿀 때 재계산이 필요하다. 특정 종류로 정렬하는 `reaction=` 은 상관 서브쿼리라 느리지만 선택 기능이다 — 커지면 이 테이블이 업그레이드 경로다),
*읽을 때마다 `count(*)`*(글 목록 정렬이 풀스캔).
교착 방지 규칙: 행 잠금 순서는 글 → 댓글 하나뿐이고, 댓글 삽입은 삽입 *전에* 글 카운터를 올린다 (InnoDB 의 FK 검사가 부모에 공유 락을 걸어 삽입 후 UPDATE 는 락 업그레이드 교착이 난다).

**소프트 삭제: 행을 남기고 본문만 가린다.** 글은 `status=DELETED`, 댓글은 `DELETED`/`HIDDEN` — 목록에 그대로 있고 `body` 만 `null`(이유는 `status`). 스레드 모양이 유지되고 대댓글이 고아가 되지 않는다. 운영자 복구도 상태만 되돌린다. 버린 것: *하드 삭제*(대댓글 · 반응이 매달린다), *자식이 있을 때만 소프트*(규칙이 둘이 된다).

**알림 훅: 클래스패스에 있을 때만.** `notification` 은 `compileOnly` + `@ConditionalOnClass` 자동설정(`BoardNotificationAutoConfiguration`)이 `BoardNotifier` 를 등록한다. 내 글에 댓글이 달리거나 내 댓글에 답글이 달리면(자기 자신 제외) 한 건. 문구는 `BoardNotificationFormatter`, 알림 실패는 댓글 작성을 막지 않는다.
`noOptionalTest` 가 `notification` · `idempotency` 없이 부팅과 요청을 시험한다. 버린 것: *하드 의존*(알림이 필요 없는 앱에 브로커가 따라온다), *Spring 이벤트로 발행*(받는 쪽이 따로 있어야 하고 사용 예가 한 줄 더 는다).

**멱등 · 한도: 선택 통합.** 글 · 댓글 만들기에 `@IdempotentOperation` 을 단다 — `idempotency` 가 있으면 `Idempotency-Key` 가 **필수**가 된다 (그 모듈에 선택 모드가 없다). 없으면 애너테이션은 JVM 이 건너뛴다. 한도는 새 인터페이스 `BoardRateLimiter` — `redis-rate-limit` 에 의존하지 않고 platform 의 `RateLimitStore` 만 쓴다.

**검색: LIKE.** 와일드카드(`%` `_`)와 이스케이프(`!`)를 글자 그대로 찾도록 이스케이프한다 (`escape '!'` 는 두 DB 에서 같다). 풀텍스트는 업그레이드 경로 (위).

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md) · [스키마 관리](../schema-management.md)
