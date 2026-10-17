# 게시판 — 비회원 작성 · 승인 대기(고친 판 포함) · 신고 · 댓글 첨부 · 확장 자리 — 설계

- 날짜: 2026-10-17
- 요청: 앱(팬덤 축하 페이지 서비스) 세션 → 지휘 세션. 축전 = board 의 **댓글**(글 = 축전 페이지). 주인 원문: 「게시판도 비회원이라고 글 못쓰거나 하는 구조면 안되잖아」 · 「스켈레톤에 넣고. 사진 첨부는 기본적으로 다 되는거겠지?」
- 기준 커밋: kotlin-skeleton `b8dd8d9c`(브랜치 `feat/board-guest`) · react-skeleton `c0ad1e8`
- 상태: **승인됨(2026-10-17, 형 「들어가」)** — 결정은 모두 §1.1. 구현은 §12 순서
- 경로 줄임: `B/` = `modules/board/src/main/kotlin/dev/sumin/skeleton/board/` · `BJ/` = `modules/board-jdbc/src/main/` · `R:` = react-skeleton 의 `packages/board/src/`

## 1. 목적 · 범위 · 비목표

**목적**: 로그인하지 않은 사람도 글 · 댓글을 쓰고(닉네임 + 비밀값으로 자기 것을 고치고 지운다), 게시판 · 글마다 「승인해야 공개」를 걸 수 있고, **글 주인이 자기 글의 댓글을 승인 · 가린다.** 승인된 것을 고치면 옛 공개본은 그대로 두고 고친 판만 대기한다. 댓글에도 사진을 붙이고, 앱은 커밋 뒤 이벤트로 자기 일을 붙인다.

**원칙(주인 결정 — 이것이 이긴다)**: 새 기능은 모듈 기본값에서 **꺼진 채** 시작하고, 켜는 것은 앱 yml · 게시판 설정의 명시값이다. 기본 설정에서 기존 사용처의 동작이 바뀌지 않는다(덧붙는 응답 키는 §8 에 전수). 견본 앱은 예외로 **기존 게시판에 새 기능을 켠다**(형 결정 9 — §11). 잠긴 마이그레이션은 고치지 않고 새 V 파일만 더한다.

| 차수 | 내용 |
|---|---|
| **1차(축전 흐름 끝에서 끝까지)** | 이벤트 자리(§6) → 비회원 작성(§2) → 승인 대기 + 글 주인 관리(§3.1–3.4) → 고친 판(§3.5) → 댓글 첨부(§5) → react 타입 · API · 화면 → 견본 `general` 업그레이드(§11) |
| **2차** | 신고(§4) — 1차와 독립 |
| **다음에 넓힐 것** | 게시판 전체의 승인 대기 댓글 큐(운영자) · 비회원 반응 · 신고 수 자동 가리기의 기본 구현 · 비회원 이중 등록의 DB 유니크 · 프로젝트별 board yml 키(`ProjectConfigKeys`) · 같은 비회원의 글을 묶어 보이기 · 고친 판의 이력(지금은 대기 판 하나만) |

**비목표**: 업로드 자체(저장 키만 든다 — 업로드 방식은 앱) · 비회원 알림 받기 · 비회원의 「내 글」 목록 · 비밀값 찾기 · 앱 칸을 board 표에 싣기(jsonb 등, §6.2) · 신고자에게 메일 보내기 · 기존 `BoardNotifier` 를 이벤트 위로 옮기기 · **읽기 API 를 끄는 설정**(형: 「그냥 열어놓고 화면에서 안부르면 되는거 아니야?」 — 읽기는 지금처럼 열어 두고, 안 쓰는 앱은 화면에서 부르지 않는다).

### 1.1 형 결정 (2026-10-17) — 앞선 추천안보다 우선한다

| # | 결정 | 형 원문 · 메모 | 반영 |
|---|---|---|---|
| 1 | 비밀 해시 기본 `PASSWORD`(bcrypt). 앱은 `TOKEN` 을 명시 | 추천대로 | §2.3 |
| 2 | 비회원 캡차 **기본 꺼짐**. 앱이 켜야 걸린다 | 추천(켜면 필수)과 다름 → 보안 절에 스팸 경고 + 켜는 예시 | §7 · §10 |
| 3 | 승인된 것을 고치면 재승인. **옛 승인본은 계속 공개, 고친 내용은 대기 판으로** | 「글 수정하면 승인전꺼 보여주고 고친건 대기로 놓자」 | §3.5 |
| 4 | 신고 이메일은 **`crypto` 로 암호화해 보관** | — | §4 |
| 5 | 이중 등록은 지금 `idempotency` 그대로(IP 범위) | 추천대로 | §7 |
| 6 | **읽기 끄기 설정을 만들지 않는다** | 「그냥 열어놓고 화면에서 안부르면 되는거 아니야?」 | 비목표 |
| 7 | 비회원 첨부는 **회원처럼 허용**(기본 `canAttach` 와 같게), 앱이 `BoardPolicy` 로 좁힌다 | 추천(거절)과 다름 → 남는 위험과 좁히는 예시 | §2.4 · §10 |
| 8 | 신고는 2차 | 추천대로 | §4 |
| 9 | 견본은 **기존 견본 게시판(`general`)에 새 기능을 켠다**(새 `guestbook` 없음) | 「기존 있던게 업글돼야하는거 아닐까? 게시판 견본이잖아 애초에」 | §11 |
| 10 | 비회원 작성자는 다시 읽을 때 자기 승인 대기 글 · 고친 판을 **보지 못한다** — 만들기 · 고치기 응답을 화면이 들고 있는다 | 추천대로(읽기마다 비밀 확인 · 해시를 돌리지 않는다) | §3.5 |
| 11 | 신고 이메일 보관 기한은 **설정만 두고 기본 꺼짐** — 앱이 켜야 주기 작업이 지운다 | 추천(기본 90일)과 다름 | §4 |
| 12 | 기존 DB 의 `general` 에 설정을 넣는 길은 **견본 앱 마이그레이션 한 장** | 추천대로 | §9 · §11 |
| 14 | (지휘 세션 결정) 게시판 승인을 OFF 로 바꿔도 **이미 대기 중인 글 · 댓글은 대기에 남는다.** 작성자가 스스로 공개할 수 없고 운영자(댓글은 관리하는 글 주인도)가 처리한다 — 닫힌 쪽, 업계 관행 | — | §3.2 |
| 15 | (지휘 세션 결정, 리뷰 B1 · B2) 판의 **버전**은 판의 `updated_at`(응답의 `pendingRevision.submittedAt`)이다. 승인 · 거절은 운영자가 본 버전을 함께 보내고, 잠근 뒤 같을 때만 한다 — 다르면 `409 BOARD.REVISION_CHANGED`(보는 사이 작성자가 덮어쓴 판이 몰래 공개되지 않는다). 판 만들기는 잠근 뒤 대상이 PUBLISHED 가 아니면 판을 넣지 않고 `404`(더 이상 고칠 수 없는 대상) | — | §3.5 |
| 13 | (지휘 세션 결정, 리뷰 I2) **한 번도 공개된 적 없는 댓글은 거절 · 대기 중 삭제 뒤에도** 관리자(운영자 · 관리하는 글 주인) · 쓴 회원 말고는 스레드에 자리조차 보이지 않는다. 「공개된 적 있음」은 `board_comments.published_at`(처음 PUBLISHED 가 된 순간)으로 남기고 옛 PUBLISHED · HIDDEN · DELETED 행은 공개된 적 있음으로 채운다. 글은 가려진 글이 원래 작성자 · 운영자 말고는 404 라 같은 규칙이 필요 없다 | — | §3.4 · §9 |
| 16 | (형 결정 「지금 1차에 넣기」, 지휘 세션이 번호를 붙임 - 앞의 15 와 겹치지 않게) **비회원 사진 업로드를 1차에 넣는다.** storage 가 익명 업로드를 받되 **기본 꺼짐**, 앱 yml 이 명시해야 켜진다. 익명은 이미지 업로드 발급만 받고 다른 경로는 그대로다 | 업계 관행 · 닫힌 쪽 | §5.1 |
| 17 | (형 결정 「공개 주소 + 승인 전은 비공개」) 게시판 사진은 **공개된 글 · 댓글(PUBLISHED)만 공개 주소**로 보인다 - 저장소의 공개 영역 + CDN. 승인 대기 · 거절 · 가림 · 삭제 · 고친 판의 사진은 비공개에 남고 관리하는 사람 · 작성자만 짧은 서명 주소로 본다. **기본 꺼짐** | 업계 관행 · 닫힌 쪽 | §5.2 |

## 2. 비회원 작성자

### 2.1 두 갈래 — `author_id not null` 은 그대로, 접두사로 가른다

`deleted:` 톰스톤과 같은 **문자열 규칙**(모듈끼리 타입을 모르게)이다. 비회원 행의 `author_id` 는 `guest:<ULID>` 이고 행마다 새 값이다(platform `SortableIds`). 계정 id(`acc_…`)와 겹칠 수 없으므로 기존의 `caller.accountId == authorId` 비교가 비회원 행에는 늘 거짓이다.

| 항목 | 회원 | 비회원 |
|---|---|---|
| `author_id` | 계정 id → 탈퇴 때 `deleted:<해시>` | `guest:<ULID>` — 탈퇴 고리(`where author_id = :계정`)에 걸리지 않는다 |
| `author_ref` | `AuthorIdentities` 규칙 그대로 | 항상 NULL. **넘겨받기 대상에서 뺀다**(`JdbcBoardAuthorAdoption.pendingAccounts` 가 지금은 `guest:` 행을 계정으로 착각한다 — `not like 'guest:%'` 추가) |
| 공개 id(`BoardAuthors.publicId`) | 지금 규칙 | **맨 앞 갈래**: `guest:` 면 `author_id` 그대로. 행마다 새 값이라 같은 비회원의 두 글이 이어지지 않고 계정과도 무관 |
| `authorName` · `authorTag` | `AuthorDirectory` | `guest_name` · `null`. 디렉터리에 묻지 않는다(톰스톤 · `pending:` 과 같은 취급) |
| `authorDeleted` / 새 키 `authorGuest` | 지금 규칙 / `false` | `false` / `true` — 화면은 「(비회원)」 표시로 회원 닉네임 사칭을 가른다 |
| `mine` | 계정 일치 | 항상 `false`(서버는 비회원을 모른다 — 화면은 자기가 가진 비밀로 판단) |
| 고치기 · 지우기 | `BoardPolicy` | 비밀 일치 + 같은 상태 규칙. 비회원 글은 `DRAFT` 불가(다시 찾을 목록이 없다) |
| 알림 받기 | 지금처럼 | 받지 않는다 — 받는 사람이 `guest:` 면 건너뛴다(`CommentService.notify`) |
| 반응 | 가능 | 1차 불가(로그인 필요 그대로) |
| 한도 키 | 계정 | `guest:<ClientIps.limitKey>` |
| `AuthorIdentities` 있을 때 | `authorId` 필수 | `authorId` 를 보내면 `403 BOARD.AUTHOR_NOT_ALLOWED`, 안 보내는 것이 정상 |

### 2.2 표(새 V 파일 — §9)

| 표 | 더하는 열 |
|---|---|
| `board_posts` · `board_comments` | `guest_name varchar(64) null` · `guest_secret_hash varchar(255) null` |
| `boards` | `guest_write varchar(16) not null default 'NONE'` — `NONE` · `COMMENTS` · `ALL` |

**해시는 모델에 싣지 않는다.** `Post` · `Comment` 는 `data class` 라 `toString` · 이벤트 · 로그로 샌다. 확인할 때만 새 포트 메서드 `PostRepository.guestSecretHash(id)` · `CommentRepository.guestSecretHash(id)` 로 읽는다. 기본 구현은 `null` 이라 늘 불일치다 — 자기 저장소를 둔 앱에서 **닫힌 쪽으로** 실패한다.

### 2.3 비밀값 — 해시 · 전달

| `skeleton.board.guest.secret` | 받는 값 | 저장 | 근거 |
|---|---|---|---|
| **`PASSWORD`(기본 — 형 결정 1)** | 사람이 치는 짧은 값, 4자 이상 · 72바이트 이하 | `{bcrypt}` + bcrypt(cost 10, `spring-security-crypto` — 의존 없는 작은 jar, `account` 와 같은 인코더) | 엔트로피가 낮은 값에는 느린 해시가 관행이다(OWASP Password Storage). 한국 비회원 게시판의 「비밀번호」가 이 모양 |
| `TOKEN`(앱) | 기계가 만든 무작위 값, `[A-Za-z0-9_-]{32,128}`(43자 base64url = 256비트) | `{sha256}` + 16진수 SHA-256, 비교는 `MessageDigest.isEqual` | 256비트 무작위 값은 무차별 대입이 불가능해 느린 해시가 지킬 것이 없다. API 토큰 저장의 관행 |

- 접두사가 방식을 적으므로 설정을 바꿔도 옛 행은 저장된 방식대로 확인된다. 교체 지점은 `BoardGuestSecrets`(`hash` · `matches`) — argon2 등을 앱이 끼운다.
- **비밀은 헤더 `X-Board-Guest-Secret` 로만 받는다**(만들기 · 고치기 · 지우기 · 글 주인 관리 · 판 결정). 헤더인 이유: `DELETE` 는 본문이 없고, 멱등 지문은 본문만 담으므로 비밀이 지문 저장소에 남지 않고, 요청 로그는 헤더를 싣지 않는다. 쿼리 문자열과 쿠키는 쓰지 않는다(주소 기록에 남고, 쿠키는 CSRF 위험). platform CORS 기본 `allowed-headers` 에 이 이름을 더한다(`X-Break-Glass-Secret` 과 같은 모양).
- 비밀이 틀린 경우, 비회원 행이 아닌 경우, 헤더가 없는 경우 모두 **같은 `403 BOARD.FORBIDDEN`** 으로 답한다(문구도 같다).

### 2.4 HTTP · 정책

- 경로는 그대로다. 만들기 본문에 `guestName` 이 늘고, 비밀은 헤더로 온다. **로그인한 호출자가 `guestName` 을 보내면 `400 BOARD.CONTENT_INVALID`** — 조용히 회원으로 쓰면 화면과 어긋난다.
- `skeleton.board.guest.enabled=true` 일 때만 `PublicEndpointContributor` 가 쓰기 경로(`POST` 글 · 댓글, `PATCH` · `DELETE`, 댓글 `PUT …/moderation`, `PUT …/revision`)를 인증 없이 연다. 그래도 **게시판의 `guest_write` 가 실제로 정한다** — `NONE` 인 게시판에 익명이 쓰면 지금과 같은 `401 COMMON.UNAUTHORIZED`. 열린 경로에서도 인증된 요청은 지금처럼 인증된다. 읽기는 지금처럼 `allow-anonymous-read` 가 정한다(비회원이 읽게 하려면 앱이 함께 켠다).
- 프로젝트 여럿: board yml 은 앱 전체에 하나다(`ProjectConfigKeys.SUPPORTED` 밖). 반면 `guest_write` 등 게시판 설정은 **DB(프로젝트마다 스키마)** 에 있어 프로젝트마다 갈린다 — 게시판 설정을 표에 두는 주된 이유다.
- `BoardPolicy` 에 **본문이 있는 기본 메서드**를 더한다(구현한 앱은 다시 컴파일만 하면 된다): `canGuestPost(board)` = `guest_write == ALL` · `canGuestComment(board, post)` = `!= NONE` · **`canGuestAttach(keys)` = `true`**(형 결정 7 — 회원의 `canAttach` 기본과 같다. 남는 위험과 좁히는 예시는 §10).

## 3. 승인 대기 · 글 주인의 댓글 관리 · 고친 판

### 3.1 설정 — 게시판에 기본, 글에 덮어쓰기

| 표 · 열(새 V 파일) | 값 | 뜻 |
|---|---|---|
| `boards.post_approval` | `OFF`(기본) · `GUESTS` · `ALL` | 승인을 거쳐야 하는 글 작성자 |
| `boards.comment_approval` | `OFF`(기본) · `GUESTS` · `ALL` | 승인을 거쳐야 하는 댓글 작성자 |
| `boards.owner_moderates_comments` | `false`(기본) | 글 주인이 자기 글의 댓글을 승인 · 가린다 |
| `board_posts.comment_approval` | NULL(= 게시판 값) · 위 셋 | 글 주인이 만들기 · 고치기에서 고른다. `owner_moderates_comments` 인 게시판만 받고, 아니면 `400` |
| `board_posts.moderated_by` · `board_comments.moderated_by` | NULL · `OWNER` · `MODERATOR` | 마지막으로 상태를 바꾼 쪽(§3.3) |

- 운영자가 설정을 바꾸는 길: 새 `PATCH /{code}` `{name?, description?, settings?}`(운영자). `seed-boards[].settings` 는 지금처럼 **만들 때만** 적용된다. 운영자 자신은 승인을 거치지 않는다.
- 「승인 대상 작성자」 판정은 `BoardPolicy.needsApproval(board, post?, writer)` 한 곳이다. 기본: `ALL` 은 운영자 외 모두, `GUESTS` 는 비회원만. 댓글을 관리하는 글 주인은 자기 글의 댓글에 대해 대상이 아니다.

### 3.2 상태 전이 — 새 글 · 새 댓글

`PostStatus` · `CommentStatus` 에 `PENDING` 을 더한다(열은 `varchar(16)` 그대로라 스키마 변경 없음). **거절 = 가리기(`HIDDEN`)** 이고 새 상태는 만들지 않는다.

| 지금 → 다음 | 누가 · 어떻게 |
|---|---|
| (만들기) → `PENDING` | 작성자가 승인 대상일 때. `comment_count` 를 올리지 않는다(지금도 `PUBLISHED` 만 센다 — 삽입은 글 행을 그대로 잠그고 `+0`) |
| `PENDING` → `PUBLISHED` | 승인. 글은 운영자가, 댓글은 운영자 · 관리하는 글 주인이 한다. **기존 `PUT …/moderation {status: PUBLISHED}`** 를 쓴다 — 새 경로 없음 |
| `PENDING` · `PUBLISHED` → `HIDDEN` | 거절 · 가리기. 같은 경로 |
| `PENDING` 을 작성자가 고침 | 그 자리에서 고친다(아직 공개본이 없다). 상태는 `PENDING` 그대로 |
| `PUBLISHED` 를 승인 대상 작성자가 고침 | **상태는 바꾸지 않고 고친 판을 만든다 — §3.5** |
| 그 밖 | 지금과 같다(`DELETED` 는 작성자 · 운영자) |

### 3.3 권한 — 글 주인

- `BoardPolicy.canManageComments(caller, post, board)` 의 기본 = `caller.moderator || (board.ownerModeratesComments && caller.accountId == post.authorId)`. **비회원 글 주인**은 서비스가 `X-Board-Guest-Secret` 이 글의 비밀과 맞는지로 같은 판단을 한다.
- 글 주인은 `PUBLISHED` · `HIDDEN` 만 고를 수 있다(`DELETED` 는 운영자 · 작성자). **`moderated_by = MODERATOR` 인 가려진 댓글은 되살리지 못한다** — 운영자가 가린 악성 댓글을 주인이 푸는 사고를 막는다. 다른 글의 댓글이거나 관리가 꺼진 게시판이면 `403 BOARD.FORBIDDEN`.
- 글 상세 응답에 `canManageComments: boolean`(새 키)을 싣는다 — 화면이 승인 · 가리기 단추를 이것으로 그린다.

### 3.4 보이기 · 대기 목록 — 새 글 · 새 댓글

| 대상 | 누구에게 보이나 | 그 밖의 사람에게 |
|---|---|---|
| `PENDING` 글 | 운영자 · 회원 작성자(`mine=true` 목록 기본 상태에 `PENDING` 추가) | 지금 `canViewPost` 규칙 그대로 404, 목록에 없음 |
| `PENDING` 댓글 | 운영자 · 관리하는 글 주인 · 회원 작성자 — **본문 포함**(읽어야 승인한다) | **스레드에 자리도 없다.** `HIDDEN` 처럼 자리 표시로 남기면 개수와 존재가 샌다. 최상위 페이지 쿼리가 거르고(`rootPage` 에 보는 사람 조건) 자손은 서비스가 거른다. `replyCount` 도 뺀 수. **거절(PENDING → HIDDEN) · 대기 중 삭제 뒤에도 같다**(한 번도 공개된 적 없는 행 — `published_at` 이 NULL — 은 자리 표시도 없다. 한 번이라도 공개된 댓글을 가리면 자리 표시가 남는 옛 동작 그대로) |
| 대기 목록 | 글: 기존 `GET /{code}/posts?status=PENDING`(운영자). 댓글: `GET …/posts/{id}/comments?status=PENDING` → 관리자에게 평평한 페이지(오래된 순) | `403` |

- 알림: 댓글이 **`PUBLISHED` 가 될 때**(바로 공개되거나 승인될 때) 지금의 `comment.created` · `comment.replied` 를 보낸다. `PENDING` 으로 들어오면 관리하는 글 주인에게만 `comment.pending`(새 type, 같은 payload)을 보낸다. 받는 사람이 비회원이면 건너뛴다.
- 새 인덱스 `idx_board_comments_status (post_id, status, created_at, id)` — 대기 목록용.

### 3.5 고친 판(revision) — 「승인전꺼 보여주고 고친건 대기로」

**모델**: 공개본은 원래 행(`board_posts` · `board_comments`) 그대로 두고, 대기 중인 고친 내용은 **별도 표에 대상마다 하나** 둔다. 판의 상태 칸은 없다 — 판이 있으면 대기 중이다.

| 새 표(새 V 파일) | 열 |
|---|---|
| `board_revisions` | `target_type`(POST · COMMENT) · `target_id` — **기본 키 둘(대상마다 판 하나)** · `board_code` · `post_id` · `title varchar(255) null`(글만) · `body`(PG `text`, MySQL `mediumtext`) · `attachment_count` · `created_at` · `updated_at`. 인덱스 `(board_code, target_type, updated_at)` · `(post_id, target_type, updated_at)` |
| `board_revision_attachments` | `target_type` · `target_id` · `sort_order` · `storage_key` — 기본 키 셋. 글 첨부 표와 같은 모양 |

- 원래 행에 칸(`pending_body` …)을 두는 안은 버렸다. 공개 읽기마다 큰 칸을 끌고 다니고, 첨부는 어차피 따로 표가 필요하다. 판 표로 두면 **공개 읽기 경로가 전혀 바뀌지 않는다.** 판 이력(고칠 때마다 행)도 버렸다 — 요구는 대기 판 하나다.

| 일 | 동작 |
|---|---|
| 판 만들기 | 승인 대상 작성자가 `PUBLISHED` 대상을 `PATCH` 하면, 공개본(판이 있으면 판) 위에 바꾼 칸을 얹어 판에 저장한다. 원래 행 · `updated_at` · `comment_count` 는 그대로. 응답 `200` 은 공개본 + `pendingRevision` |
| 판 대기 중에 또 고침 | **판을 덮어쓴다**(같은 기본 키). 대상 행을 `FOR UPDATE` 로 잠근 뒤 지우고 넣는다 — upsert 문법이 방언마다 달라서다. 잠금 순서 규칙(글 → 댓글)은 그대로 |
| 승인 | `PUT …/posts/{id}/revision` · `PUT …/comments/{cid}/revision` `{decision: APPROVE}`. 한 트랜잭션에서 대상을 잠그고 판의 제목 · 본문 · 첨부를 원래 행에 옮긴 뒤 `updated_at` 을 고치고 판을 지운다. 권한은 새 것 승인과 같다(글 = 운영자, 댓글 = 운영자 · 관리하는 주인) |
| 거절 | 같은 경로 `{decision: REJECT}` — 판만 지운다. 공개본은 그대로 |
| 대상이 `PUBLISHED` 를 떠남(가리기 · 지우기) | 판을 같은 트랜잭션에서 버린다(가려진 것의 판을 나중에 승인해 되살리는 길을 막는다) |
| 승인 대상이 아닌 사람이 고침 | 지금처럼 원래 행을 바로 고친다. 판이 있으면 함께 버린다(운영자의 수정이 이긴다) |
| 설정을 `OFF` 로 바꿈 | 이미 있는 판은 남고 승인 · 거절할 수 있다. 새 수정은 바로 반영된다 |

| 보는 사람 | 보이는 것 |
|---|---|
| 모두 | 공개본만. 판이 있다는 표시도 없다(§3.4 와 같은 이유) |
| 회원 작성자 · 운영자 · 관리하는 글 주인 | 글 상세 · 댓글 응답에 `pendingRevision: {title?, body, attachments, submittedAt} \| null`(새 키 — 남에게는 늘 `null`) |
| 비회원 작성자 | `PATCH` 응답의 `pendingRevision` 만(화면이 들고 있는다). 다시 읽을 때는 남과 같게 공개본만 보인다 — 읽기 경로는 비밀 헤더를 보지 않는다(형 결정 10). 승인 대기 새 글 · 새 댓글도 같다(만들기 응답만) |

**대기 목록 — 「새 글」과 「고친 판」을 따로**: 하나로 섞으면 두 표를 합친 쪽 나누기가 되어 쿼리가 무겁다. 그래서 두 목록으로 둔다.

| 목록 | 경로 | 한 줄 |
|---|---|---|
| 새 글(운영자) | `GET /{code}/posts?status=PENDING`(기존) | 글 요약 |
| 고친 글(운영자) | `GET /{code}/posts?revision=pending` | 글 요약 + `pendingRevision`(공개본과 나란히 보여 비교한다) |
| 새 댓글(관리자) | `GET …/posts/{id}/comments?status=PENDING` | 댓글 |
| 고친 댓글(관리자) | `GET …/posts/{id}/comments?revision=pending` | 댓글(공개본) + `pendingRevision` |

- **첨부도 판에 들어간다.** 사진을 바꿔치는 것이 가장 흔한 악용이다. 키 검사(`rejectAccountIdKeys` · `canAttach` · `canGuestAttach`)는 공개본에도 판에도 없던 새 키만 본다. 거절된 판의 키는 그냥 버려진다(파일 정리는 앱 · storage 몫 — 지금 글 첨부를 바꿀 때와 같다).
- 알림: 판이 생기거나 덮어써지면 관리하는 글 주인에게 `comment.pending`(payload `revision: true`)을 보낸다. 승인 · 거절은 알림 없음.
- 포트: 새 `BoardRevisionRepository` 를 `board-jdbc` 가 구현한다. 자기 저장소를 둔 앱이 이것 없이 승인을 켜면, 판이 필요한 첫 수정에서 「board-jdbc 를 두거나 구현하라」 오류가 난다.

## 4. 신고 (2차)

| 항목 | 설계 |
|---|---|
| 켜기 | `skeleton.board.report.enabled=false`(기본 — 꺼지면 경로가 404) · `report.reasons: [SPAM, ABUSE, SEXUAL, PRIVACY, ILLEGAL, OTHER]`(반응 종류처럼 대문자 코드 설정) · `report.detail-max-length: 1000` · `report.contact-email: true`(이메일 칸 받기) |
| 표 `board_reports` | `id` · `board_code` · `post_id` · `target_type` · `target_id` · `reporter_id`(계정, 비회원은 NULL) · `reason` · `detail` · **`contact_email_enc varchar(512) null`** · `contact_cleared_at` · `status`(OPEN · RESOLVED · DISMISSED) · `resolved_by` · `resolved_at` · `created_at`. 유니크 `(target_type, target_id, reporter_id)` — NULL 은 두 방언 모두 여러 개를 허용하므로, 회원은 한 대상에 한 번이고 비회원은 캡차 · 한도로 막는다. 인덱스 `(board_code, status, created_at, id)` |
| 쓰기 | `POST /{code}/posts/{id}/reports` · `…/comments/{cid}/reports` `{reason, detail?, contactEmail?, captchaToken?}` → **늘 `202 {status: ACCEPTED}`**(중복이어도 같은 답). 못 보는 대상은 404(보기 규칙 그대로). 비회원은 그 게시판이 `guest_write != NONE` 일 때만 |
| 운영자 | `GET /{code}/reports?status=OPEN&page&size`(대상 요약 + 복호화한 이메일) · `PUT /{code}/reports/{id}` `{status?: RESOLVED\|DISMISSED, hideTarget?: boolean, clearContact?: boolean}`. 경로를 게시판 아래에 두는 이유: 최상위 `/reports` 는 기존 `GET /{code}` 와 겹쳐 `reports` 를 예약어로 만들어야 하고, 그 이름의 게시판이 깨진다 |
| 자동 가리기 | 없음(앱의 정책). 자리는 이벤트 `ReportCreated(report, openCount)` — 앱 리스너가 수를 보고 가린다 |

**이메일 암호화(형 결정 4)**

- **키**: `crypto` 모듈의 `TextEncryptor` 를 쓴다(앱의 `skeleton.crypto.primary-key-id` · `keys`). 프로젝트 모드에서는 지금 프로젝트의 키로 넘어가므로 A 의 암호문을 B 가 열지 못한다. 암호문 봉투에 키 id 가 있어 키를 돌려도 옛 행이 열린다. 구독 빌링키와 같은 길이다. `board` 는 `crypto` 를 `compileOnly` + `ObjectProvider` 로 안다. **`report.enabled` 이고 `contact-email` 이 켜졌는데 `TextEncryptor` 가 없으면 기동 실패**한다(구독과 같은 규칙).
- **나가는 곳**: 운영자 신고 목록에서만 복호화한다. 이벤트 · 알림 · 로그 · 모델 `toString` 에는 암호문도 싣지 않는다(`hasContact: boolean` 만).
- **지우는 길**(`contact_email_enc = NULL`, `contact_cleared_at` 기록): ① 운영자의 `clearContact: true` ② 신고자 계정 탈퇴 — `anonymizeAuthor` 가 `reporter_id` 를 톰스톤으로 바꾸면서 함께 지운다(비회원 신고는 계정이 없어 ①·③만) ③ 보관 기한(형 결정 11) — `report.contact-retention`(기본 비어 있음 = 꺼짐). 앱이 `P90D` 처럼 켜면 처리(`RESOLVED` · `DISMISSED`) 뒤 그 기간이 지난 행을 주기 작업(`@SkeletonScheduled` + `ProjectRegistry.forEachProject` — 프로젝트마다 그 스키마에서)이 지운다. 꺼져 있으면 ①·②만 지우므로, 개인정보 파기 기한이 있는 서비스는 앱이 켜야 한다(`board.md` 에 적는다).

## 5. 댓글 첨부

- 새 표 `board_comment_attachments(comment_id, sort_order, storage_key, pk(comment_id, sort_order))` + `board_comments.attachment_count int not null default 0`. 글 첨부와 같은 모양이다(저장 키 문자열만, 업로드는 앱).
- 설정 `skeleton.board.comment-max-attachments: 0`(기본 = 꺼짐 — 보내면 `400 BOARD.CONTENT_INVALID`). 앱과 견본은 `4`. 검사는 글과 같은 `BoardContentRules.attachments` · `canAttach` · `rejectAccountIdKeys` 이고, 비회원은 `canGuestAttach` 다.
- 스레드 응답은 쪽의 모든 댓글 키를 **쿼리 한 번**(`comment_id in (…)`)으로 붙인다 — `JdbcQueryCountDbTest` 의 쿼리 수 약속을 지킨다. `CommentResponse.attachments: string[]`(새 키)는 **`PUBLISHED` 가 아니면 `[]`** 다 — 본문이 `null` 인 것과 같은 이유로, 가린 댓글의 사진이 새면 안 된다.
- 수정: `PATCH …/comments/{cid}` 가 `attachments?` 를 받는다(승인 대상이면 §3.5 의 판으로 간다).

## 5.1 비회원 업로드 (storage, 결정 16)

- **설정** `skeleton.storage.web.guest.*` - `enabled`(기본 **false**), `secret`(키 MAC 의 서버 비밀, 켜면 32자 이상 - 프로젝트마다 달라야 한다. 환경변수 `<P>_STORAGE_WEB_GUEST_SECRET`), `allowed-content-types`(기본 jpeg · png · webp · gif, 이 넷 안에서만 고를 수 있다 - **SVG 는 설정으로도 못 연다**), `max-size-bytes`(기본 10 MB), `rate-limit`(IP 별 20 / 1시간), `captcha`(OFF · REQUIRED, REQUIRED 면 검증기 빈이 없을 때 기동 실패), `cleanup.*`(아래).
- **경로** `POST /api/v1/storage/guest/presign` 하나뿐이다(본문 `fileName` · `contentType` · `sizeBytes` · `captchaToken?`, 요청 하나에 파일 하나). 켜져 있을 때만 `PublicEndpointContributor` 가 이 경로를 익명에 연다 - **다운로드 · 멀티파트 · 검증 등 다른 경로는 지금처럼 로그인이 필요하다.** 꺼져 있으면 지금과 같은 401.
- **키** `<prefix>/g/<uuid>.<mac>/file.<ext>`, `mac = HMAC-SHA256(guest.secret, "guest\n" + uuid)` 의 앞 32자, 확장자는 **클라이언트 파일 이름이 아니라 허용한 Content-Type 에서** 정한다(jpg · png · webp · gif). 파일 이름 확장자가 Content-Type 과 맞지 않으면 거절한다. presign 은 Content-Type · 크기를 서명에 넣으므로 저장소가 선언과 다른 업로드를 거절한다. **매직 바이트 검사는 브라우저가 저장소로 직접 올리는 길이라 서버가 할 수 없다** - 대신 위 서명 + 이미지 Content-Type 만 허용 + SVG 금지가 한계선이다.
- **소유 확인** `StorageKeyOwnership` 에 익명 꼴이 더해진다(`isGuestKey`: 꼴과 MAC 이 맞아야 참). 회원의 `owns` 는 익명 접두사(`<prefix>/g/`)를 소유로 인정하지 않는다. 비회원 글 · 댓글의 기본 `canGuestAttach` 는 **익명 키 꼴이고 MAC 이 맞는 것만** 허용한다 - 회원 키를 비회원이 다시 싣는 길이 막힌다. 결정 7 「회원처럼 허용」과 어긋나 보이지만, 업로드 소유 확인이 생겼으므로 회원이 `canAttach`(자기 키만)로 받는 확인과 같은 수준이다(저장소 연결이 없는 앱은 `canGuestAttach` 가 지금처럼 `true`: storage 의 익명 키 확인 빈이 없으면 그대로).
- **고아 회수** 익명 키가 `cleanup.ttl`(기본 24시간) 안에 글 · 댓글(고친 판 포함)에 붙지 않으면 주기 작업이 지운다. 기본 꺼짐, `cleanup.enabled=true` 일 때만 돈다(`scheduler` 모듈의 `skeletonTaskScheduler` 에 예약, 프로젝트마다). 붙었는지는 platform 의 포트 `UploadedKeyUsage` 가 알려 준다 - 구현이 없으면(board-jdbc 없음) 아무것도 지우지 않는다. 사용 중인 키는 지우지 않는다.
- **시험** MAC 위조 키 거절 · 회원 키를 비회원이 실으면 거절 · SVG · 크기 초과 거절 · 꺼진 기본 설정에서 익명 발급 401 · 한도 초과 429 · 회수가 붙은 키를 안 지움.

## 5.2 사진 공개 (결정 17)

- **설정** `skeleton.storage.web.public.*` - `enabled`(기본 **false**), `secret`(공개 키 HMAC 비밀, 32자 이상, 프로젝트마다 달라야 한다), `prefix`(`public`, 업로드 접두사와 겹치면 기동 실패), `base-url`(공개 영역 앞의 CDN 주소 - 필수), `cache-control`(공개 객체에 붙는 기본 `public, max-age=3600`), `access-ttl`(관리하는 사람의 서명 주소 수명, 기본 5분). 꺼져 있으면 응답에 `attachmentUrls` 가 없고 지금 동작 그대로다.
- **공개 키** `<prefix>/b/<hmac32>.<ext>` (`b/` = board 의 하위 접두사, 전체 대조는 그 안만 본다), `hmac32 = HMAC-SHA256(public.secret, "public\n" + 비공개 키)` 의 앞 32자. 비공개 키(계정 id · 비회원 표시 · 원래 이름)가 드러나지 않고 비밀 없이는 추측할 수 없다(결정 때의 「uuid」와 같은 효과이면서, 키에서 다시 계산되므로 따로 대응표 없이 지울 수 있다 - 이탈).
- **공개 조건** 글은 `PUBLISHED`, 댓글은 `PUBLISHED` 이고 그 글도 `PUBLISHED`. 판(고친 내용)의 첨부는 어떤 경우에도 비공개 - 승인되어 원래 행의 첨부가 되는 순간 조건에 든다.
- **맞추기** board 가 platform 포트 `PublicUploads`(storage 가 구현)로 공개 영역을 비공개 상태에 맞춘다. 표 `board_public_attachments(target_type, target_id, key_hash, storage_key)` 가 「공개 영역에 복사했을 수 있는 것」을 기억한다(**복사 전에 먼저 적는다**). 세 겹이다(아웃박스): (1) 공개 여부에 영향을 주는 쓰기는 **같은 트랜잭션에서** `board_public_attachment_queue` 에 대상 한 줄을 적고(공개 영역이 꺼져 있으면 적지 않는다), **커밋 뒤 이벤트**가 그 항목을 곧바로 맞추고 줄을 지운다(요청이 끝날 때는 맞춰져 있다). (2) **매분** 남은 줄(리스너 실패 · 프로세스 종료)을 다시 맞춘다(전체를 훑지 않는다, `for update skip locked` 로 인스턴스끼리 겹치지 않는다). (3) **드문 전체 대조**(기본 하루 한 번, `board_public_attachment_runs` 의 원자적 비교-교체로 **한 인스턴스만**): 조건에서 빠진 줄을 지우고, 모자란 복사를 채우고, 객체가 없는 줄을 다시 복사하거나 거두고, **줄이 가리키지 않는 공개 객체를 지운다**(공개 접두사 LIST 와 줄들의 `publicKeyOf` 집합 비교 - 객체를 먼저 나열한 뒤 줄을 읽어 방금 만든 객체를 지우지 않는다). 복사 뒤에는 공개 조건을 **다시 읽어** 그 사이 가려졌으면 곧바로 거둔다. 복사가 시간 초과로 끊겨도 줄은 지우지 않는다(객체가 있을 수 있다). 같은 비공개 키를 쥔 다른 줄이 남아 있으면 객체는 지우지 않는다. **지우는 쪽이 먼저**다.
- **영구 실패 · purge**: 큐 줄은 시도 횟수를 세어 간격을 늘리고(지수, 최대 1시간) `alert-after-attempts`(기본 10)부터 주인에게 알린다. CDN purge 는 요청 스레드에서 하지 않는다 - 객체 삭제(동기)가 끝난 뒤 비울 공개 키만 `board_public_purge_queue` 에 적어 비동기로 돌린다. 공개 영역의 켜짐은 부를 때마다 지금 프로젝트의 설정으로 묻는다.
- **형식** 복사 전에 원본 HEAD 로 Content-Type 을 읽어 그대로 쓰고, 허용 목록(image/jpeg · png · webp · gif · avif · heic, application/pdf) 밖이면 공개하지 않는다(SVG 포함). 공개 객체에 `X-Content-Type-Options: nosniff` 를 붙이는 것은 CDN · 버킷 설정의 몫이다.
- **응답** 글 상세 · 댓글에 `attachmentUrls: {키: 공개 주소}` 를 덧붙인다(공개 조건에 드는 항목만, 기능이 켜졌을 때만). `attachments`(키)는 그대로다.
- **열람** `GET …/posts/{id}/attachments?index=N[&from=edit]` · `…/comments/{cid}/attachments?index=N[&from=edit][&as=owner|author]` -> `{url, expiresInSeconds}`. 첨부 **순번**으로 가리킨다(`from=edit` 이면 대기 판의 순번). 운영자 · 댓글을 관리하는 글 주인 · 작성자 본인에게 `access-ttl` 짜리 서명 주소를 준다. 비회원은 비밀 헤더와 함께 `as` 로 증명할 비밀을 **하나** 정한다(`owner`: 글의 비밀, `author`: 댓글의 비밀, 기본 `author`) - 비밀을 먼저 확인하므로 증명된 사람에게는 승인 대기 댓글도 보이고, 비회원 쓰기 한도는 먹지 않는다(비밀 대입 한도만). **가린 · 지운 글 · 댓글의 사진은 운영자만** 받는다(본문 규칙과 같다). 못 보는 항목 · 없는 순번은 404, 권한이 없으면 403.
- **CDN** 가린 뒤에도 CDN 에 남는 시간은 `cache-control` 의 max-age 이내다. 즉시 제거는 `PublicUploadPurger` 구현(CDN 의 purge API)을 앱이 끼운다.
- **시험** 가린 · 지운 뒤 공개 영역에 객체가 없다 · 대기 사진은 공개 영역에 없다 · 판 사진은 승인 전에 공개되지 않는다 · 공개 키에 계정 id 가 없다(누출 시험) · 남이 열람 엔드포인트로 대기 사진을 못 받는다.

## 6. 확장 자리

### 6.1 커밋 뒤 이벤트 — `BoardEventListener`

```kotlin
fun interface BoardEventListener { fun onEvent(event: BoardEvent) }
sealed interface BoardEvent { val boardCode: String; val postId: Long; val actor: BoardActor }   // actor = MEMBER(계정) | GUEST | OWNER | MODERATOR
// PostCreated · PostUpdated · PostStatusChanged(from, to) · CommentCreated · CommentUpdated · CommentStatusChanged(from, to)
// RevisionSubmitted(target, replaced) · RevisionDecided(target, outcome = APPROVED | REJECTED | DISCARDED) · ReportCreated(openCount)
```

- **언제**: 트랜잭션 동기화가 열려 있으면 `afterCommit` 에 건다. 앱이 자기 `@Transactional` 안에서 board 서비스를 부른 경우이고, board 저장소의 `TransactionTemplate` 은 기본 전파 `REQUIRED` 라 바깥 트랜잭션에 합류한다. 동기화가 없으면 저장소 호출이 커밋하고 돌아온 바로 뒤에 부른다. 롤백되면 부르지 않는다.
- **무엇이 나가나**: 새 것의 승인 · 가리기 · 지우기 · 복구는 `…StatusChanged(from → to)` 로 나간다. **판**: 고치면 `RevisionSubmitted`(덮어쓰면 `replaced=true`), 승인이면 `RevisionDecided(APPROVED)` **와 `…Updated`**(공개본이 바뀌었으므로 — 내용을 옮겨 두는 리스너는 `…Updated` 만 들으면 된다), 거절이면 `RevisionDecided(REJECTED)`, 대상이 가려지거나 지워지거나 운영자가 고쳐 판이 버려지면 `RevisionDecided(DISCARDED)`.
- 리스너는 여러 개일 수 있다(`ObjectProvider` 순서). 리스너가 던져도 요청은 성공한다(경고 로그) — 알림과 같은 규칙이다. 리스너는 같은 스레드에서 돈다. **`afterCommit` 안에서 DB 를 쓰려면 `REQUIRES_NEW` 여야 한다**(끝난 트랜잭션이 아직 묶여 있다는 Spring 규칙 — 문서에 적는다). 내구성은 없다. 커밋과 리스너 사이에 죽으면 잃으므로, 반드시 남아야 하는 앱 데이터는 §6.2 의 같은 트랜잭션 길로 넣는다.
- 이벤트의 `Post` · `Comment` 에는 비밀 해시가 없다. `authorId` 는 계정 id 일 수 있으니, 공개 자리에 옮길 때는 `publicAuthorId` 칸을 쓴다(알림 문구와 같은 주의).
- 버린 것: *Spring `ApplicationEvent` + `@TransactionalEventListener`* — board 는 알림 때 이미 같은 이유로 버렸다. 명시 인터페이스여야 교체 지점 표에 남는다.

### 6.2 댓글에 앱 칸(번호 seq · 받는 멤버 · 언어) — **앱 표 1:1, board id 를 기본 키 · FK 로**

| 안 | 판단 |
|---|---|
| **앱 표** `app_cheers(comment_id bigint primary key references board_comments(id), seq, member_id, lang, …)`. 앱이 자기 `@Transactional` 안에서 `CommentService.create` 를 부르고 같은 트랜잭션에서 행을 넣는다 | **권고.** 두 방언에서 같은 DDL · 유니크(`(post_id, seq)`) · FK · 인덱스를 앱이 마음대로 건다. board 는 앱 칸을 모른다(조립식 원칙). 원자성은 트랜잭션 합류로, 부가 일은 §6.1 로 |
| board 가 1:1 빈 표(`board_comment_ext`)를 둔다 | 칸 이름 · 타입을 board 가 정할 수 없어 결국 key/value 나 jsonb 가 된다 |
| `board_comments.extra` jsonb(MySQL `JSON`) | PostgreSQL `jsonb` 와 MySQL `JSON` 의 연산자 · 인덱스(생성 열) 문법이 갈린다. 유니크 · FK(받는 멤버)를 못 걸고, 모든 응답 · 모델이 불투명 덩어리를 싣는다 |

- 앱이 자기 컨트롤러로 감싸도 비회원 확인 · 캡차 · 한도 · 판 규칙은 **서비스 안**에 있어 그대로 걸린다(컨트롤러에 두지 않는 이유). `Idempotency-Key` 는 앱 경로에 `@IdempotentOperation` 을 다시 단다.

### 6.3 교체 지점(새것만) — 고른 이유

| 지점 | 이유 |
|---|---|
| `BoardPolicy` 새 기본 메서드(`canGuest*` · `needsApproval` · `canManageComments`) | 「누가 무엇을」은 레포 관례상 정책 한 곳에 둔다 |
| `BoardEventListener` | 요구 5 |
| `BoardCaptcha`(`fun verify(token, ip, action): Boolean`) + `captcha-turnstile` 이 있으면 잇는 자동설정 | `account` 의 `AccountCaptcha` 와 같은 수법. board 는 Turnstile 을 `compileOnly` 로만 안다 |
| `BoardGuestSecrets` | 해시 방식(§2.3)을 앱이 바꿀 자리 |
| `BoardGuestLimiter` | 비회원 한도는 `rate-limit.enabled` 와 무관하게 늘 켜져야 한다(§7). 같은 타입 빈이 둘이면 `@ConditionalOnMissingBean` 이 깨지므로 따로 둔다 |
| 포트 새 메서드(`guestSecretHash` · 댓글 첨부 · 대기 목록) · 새 포트 `BoardRevisionRepository` · `BoardReportRepository` | 본문 있는 기본 구현(닫힌 쪽 실패 · 「board-jdbc 를 두거나 구현하라」 오류)을 두어, 자기 저장소를 둔 앱도 그대로 컴파일된다 |

## 7. 같은 사람이 두 번 보내도 한 장 · 남용 막기

| 무엇 | 지금 | 비회원에서 |
|---|---|---|
| `Idempotency-Key`(형 결정 5) | 글 · 댓글 만들기에 `@IdempotentOperation` — `idempotency` 가 있으면 필수 | **그대로 선다.** `PrincipalIdempotencyScopeResolver` 가 익명을 `anonymous:<ClientIps.limitKey>`(+ 프로젝트 `scopedKey`)로 묶는다. 비밀은 헤더라 지문 · 저장소에 없다. `ignoredBodyFields = ["captchaToken"]` 을 더한다(다시 보낼 때 새 토큰이어도 같은 요청으로 본다). **남는 틈**: 재시도 사이에 IP 가 바뀌면(모바일 망 전환) 범위가 달라져 두 장이 되고, 인메모리 저장소는 재기동하면 비워진다 — 회원과 같은 수준으로 받아들인다 |
| 캡차(형 결정 2) | 없음 | `skeleton.board.guest.captcha: OFF`(**기본**) · `REQUIRED`. `REQUIRED` 면 비회원의 만들기 · 신고에 본문 `captchaToken` 이 필요하고, 실패하면 새 코드 `400 BOARD.CAPTCHA_FAILED`. `REQUIRED` 인데 `BoardCaptcha` 가 없으면 **기동 실패**. 켜는 예시와 경고는 §10 |
| 쓰기 한도 | `BoardRateLimiter`(기본 꺼짐, 계정 키) | `BoardGuestLimiter` — `guest.enabled` 면 **늘 켜짐**. `guest.rate-limit {capacity: 5, window: 1m}` 를 `guest:<IP>` 로 센다. platform `RateLimitStore` 를 쓴다(Redis 가 있으면 Redis, 키는 프로젝트별) |
| 비밀 대입 | — | `guest.secret-attempts {capacity: 10, window: 10m}` 를 **행마다**(`row:p<id>` · `row:c<id>`) + IP 마다 센다. **해시 계산 전에** 세서 bcrypt 로 CPU 를 태우는 공격을 막는다. 넘으면 `429 BOARD.RATE_LIMITED` |
| 순서 | | 만들기: 본문 검사 → 캡차 → 한도 → 해시. 고치기 · 지우기: 한도 → 해시 확인 |

## 8. 기존 동작과 호환 — 모듈 기본 설정에서 바뀌는 곳 전수

| 곳 | 바뀜 | 기존 사용처 |
|---|---|---|
| `GET /config` | 키 추가: `guestEnabled`(false) · `guestSecret`(`PASSWORD`) · `guestNameMaxLength` · `captchaRequired`(false) · `commentMaxAttachments`(0) · `reportReasons`([] — 꺼짐) | 덧붙음만 |
| 게시판 응답 | `settings: {guestWrite: NONE, postApproval: OFF, commentApproval: OFF, ownerModeratesComments: false}` | 덧붙음만 |
| 글 요약 · 상세 · 댓글 | `authorGuest: false` · 상세에 `canManageComments`(운영자만 true) · `commentApproval: null` · 상세 · 댓글에 `pendingRevision: null` · 댓글에 `attachments: []` | 덧붙음만(`mine` 을 늘 실은 선례와 같다) |
| 상태 값 · 목록 인자 | `PENDING` 은 승인을 켠 게시판에서만 나온다. `?revision=pending` 은 새 인자 | 없음 |
| 새 경로 | `PATCH /{code}`(운영자) · `PUT …/revision` 둘 · 신고 넷(꺼지면 404) | `WriteGateCensusTest`: 새 변경 경로는 주소 없는 계정에게 막히는 쪽이라 「열린 목록」이 그대로여야 한다 — 시험이 확인한다 |
| 비회원 첨부 | **storage(`GuestUploadKeys`)가 있으면 기본 `canGuestAttach` 가 비회원 업로드로 발급한 키만 허용한다**(없으면 지금처럼 모두). storage 가 있고 비회원 업로드가 꺼져 있으면 비회원은 사진을 붙일 수 없다 - 기존에 임의 키를 실던 앱은 `canGuestAttach` 를 직접 둔다 | 비회원 쓰기를 켠 앱만(기본 꺼짐) |
| 쓰기 경로의 인증 | `guest.enabled=false` 면 공개 경로를 더하지 않는다 — 지금과 같다 | 없음 |
| platform CORS 기본 `allowed-headers` | `X-Board-Guest-Secret` 추가 | 덧붙음만 |
| 알림 | 승인 · 판이 없으면 지금과 같다. `comment.pending` 은 승인 켠 게시판에서만 | 없음 |
| 스키마 | 새 열은 모두 NULL 허용이거나 `not null default` — 옛 행은 기본값(꺼짐). 새 표 넷 | 업그레이드 시험이 확인(§10) |
| 넘겨받기 | `guest:` 행 제외(비회원 행이 없으면 결과가 같다) | 없음 |
| 의존 | `board` 에 `implementation(spring-security-crypto)` · `compileOnly(:modules:captcha-turnstile)` · `compileOnly(:modules:crypto)` | 없음 |

견본 앱은 기본값이 아니라 새 기능을 켠 설정으로 돈다 — 영향과 고칠 것은 §11.

## 9. 마이그레이션 — 새 V 파일만(두 방언, 같은 버전 · 이름)

잠긴 파일(`migrations.lock` 의 `board-jdbc` 두 쌍 `V20261005142218__board` · `V20261007150825__board_author_ref`)은 고치지 않는다 — `MigrationLockTest` 가 막는다. 새 파일은 잠긴 모든 버전(최신 `20261013180000`)보다 뒤여야 하고(사이에 끼면 실패), 만든 뒤 `perl scripts/migrations-lock.pl` 로 잠금에 더한다. 버전은 손으로 치지 않는다: `./gradlew newMigration -Pname=<이름> -Pmodule=modules/board-jdbc`. 조각마다 자기 파일을 두고, 파일끼리 서로 기대지 않는다(outOfOrder 규칙).

| 조각 | 이름(안) | 내용 |
|---|---|---|
| S1 | `V…__board_guest_authors.sql` | 글 · 댓글 `guest_name` · `guest_secret_hash`, `boards.guest_write` |
| S2 | `V…__board_moderation.sql` | `boards` 승인 셋 · 글 `comment_approval` · 글 · 댓글 `moderated_by` · `idx_board_comments_status` |
| S3 | `V…__board_revisions.sql` | `board_revisions` · `board_revision_attachments`(기존 표는 건드리지 않는다) |
| S4 | `V…__board_comment_attachments.sql` | 새 표 + `board_comments.attachment_count` |
| S5 | `V…__board_reports.sql` | 새 표 + 인덱스 |
| 리뷰 | `V…__board_comment_published_at.sql` | `board_comments.published_at` + 옛 행 백필 (결정 13) |
| 견본 | `apps/sample/…/postgresql/V…__sample_board_general_settings.sql` | 기존 DB 의 `general` 에 설정을 켠다(형 결정 12) |

- MySQL 은 새 표의 인라인 인덱스를 `[jooq ignore]` 표시 안에 둔다(기존 파일과 같은 모양). MySQL 8.4 에는 `add column if not exists` 가 없으므로 Flyway 가 한 번만 돈다.
- `schema.sql` 모드는 `create table` 사본에 새 열을 접어 넣으라고 `board-jdbc.md` 에 적는다(기존 주의와 같다).

## 10. 보안 · 검증

**보안 요약**: 비밀은 해시만 저장한다(§2.3). 모델 · 응답 · 이벤트 · 로그 · `toString` 에는 해시도 평문도 없다. 비밀은 헤더로만 받는다. 비밀 틀림 · 남의 행 · 회원 행은 같은 403 이다. 대입은 해시 전에 행 + IP 한도로 막는다. 승인 대기와 고친 판은 남에게 자리도 보이지 않는다. 판은 대상이 가려지면 버려진다. 신고는 늘 202 로 답하고, 신고 이메일은 암호화해 운영자 목록에서만 연다. `guest:` id 는 행마다 새 값이라 같은 비회원끼리도 이어지지 않는다. `PASSWORD` 4자리는 행마다 10회/10분이면 한 행을 뚫는 데 며칠이 걸린다 — 비회원 게시판의 일반 수준이며, 더 막으려면 `TOKEN`.

> **경고 — 캡차 없이 비회원 쓰기를 켜면 스팸 위험이 크다(형 결정 2: 기본 꺼짐).** IP 한도는 봇넷 · 프록시를 넘지 못한다. 공개 서비스에서 비회원 쓰기를 켤 때는 캡차를 함께 켜라. 승인 대기(`GUESTS`)는 공개를 막을 뿐 들어오는 양은 막지 못한다.
>
> ```yaml
> skeleton:
>   captcha-turnstile:
>     enabled: true
>     secret-key: ${SKELETON_CAPTCHA_TURNSTILE_SECRET_KEY}   # 프로젝트 모드는 skeleton.projects.<id>.config.captcha-turnstile.secret-key
>   board:
>     guest:
>       enabled: true
>       captcha: REQUIRED      # 비회원의 만들기 · 신고에 captchaToken 필수, 검증기 없으면 기동 실패
> ```

> **남는 위험 — 비회원 첨부는 회원처럼 허용된다(형 결정 7).** 비회원은 소유를 확인할 계정이 없어 **아무 키나** 실을 수 있다. 공개된 남의 첨부 키를 복사해 다시 실을 수 있어, 원래 글이 가려진 뒤에도 그 그림이 비회원 댓글로 되살아난다. `ACCOUNT_ID` 키 방식에서는 남의 비공개 업로드 키를 맞히면 그것도 실린다. 비회원 업로드를 받는 앱은 정책으로 좁힌다:
>
> ```kotlin
> @Bean fun boardPolicy(tickets: GuestUploadTickets): BoardPolicy = object : BoardPolicy by DefaultBoardPolicy() {
>     // 비회원 업로드는 앱이 발급한 guest-uploads/ 아래 키만, 그리고 이 업로드 표가 아직 아무 글에도 쓰이지 않은 키만
>     override fun canGuestAttach(keys: List<String>) = keys.all { it.startsWith("guest-uploads/") && tickets.claim(it) }
> }
> ```

**시험(사고 나는 동작만)** — 서비스 논리는 기존 `FakeRepositories` 로, 두 DB 시험은 저장소에 새로 생긴 SQL 만 본다:

| 사고 | 시험 |
|---|---|
| 남의 글 고치기 | 비회원 A 의 비밀로 B 행 · 회원 행을 고치거나 지우면 403 이고 행은 그대로. 회원이 비회원 행을 고쳐도 403. 헤더가 없어도 403 |
| 비밀 틀림 · 대입 | 틀린 비밀 → 403(맞는 경우와 문구만 빼고 같은 모양) · 11번째 → 429 · 한도 전에는 해시를 부르지 않음 · `PASSWORD` ↔ `TOKEN` 설정을 바꿔도 옛 행 확인 |
| 비밀 누출 | 만들기 응답 · 목록 · 이벤트 · 알림 payload · `Post.toString()` 에 비밀 · 해시 문자열이 없다(`LeakSweep` 에 비회원 행 추가) |
| 승인 전 노출 | `PENDING` 글 · 댓글이 익명 · 다른 회원의 목록 · 상세 · 스레드 · `replyCount` · `comment_count` · 알림 수신자에 없다. 관리하는 주인 · 운영자 · 회원 작성자에게는 본문까지 보인다. 승인 순간 `comment_count +1` 과 알림 한 번 |
| 고친 판 | 판 대기 중 남에게 공개본만 보이고 `pendingRevision` 은 `null` · 판의 새 사진이 공개 응답에 없음 · 거절 뒤 공개본 그대로 · 승인 뒤 판 내용 · 첨부로 바뀌고 판은 없음 · 대기 중 다시 고치면 판이 하나(덮어씀) · 대상을 가리면 판이 버려지고 그 뒤 승인 시도는 404 · 판 덮어쓰기와 승인이 동시에 와도 깨진 상태가 없음(두 DB, 대상 행 잠금) |
| 주인 권한 넘기 | 글 A 주인이 글 B 의 댓글 · 판을 승인 → 403 · 관리 꺼진 게시판 → 403 · 운영자가 가린 댓글을 주인이 공개 → 403 |
| 가린 댓글의 사진 | `HIDDEN` · `DELETED` · (남에게) `PENDING` 댓글의 `attachments` 가 `[]` |
| 이중 등록 | 같은 IP 같은 `Idempotency-Key` 두 번 → 한 행, 재생 응답 같음 · 다른 IP 같은 키 → 두 행(범위 분리 확인) |
| 캡차 · 기본값 | 기본은 캡차를 부르지 않음 · `REQUIRED` + 검증기 없음 → 기동 실패 · 토큰 없음 → 400 · 회원 쓰기는 캡차를 부르지 않음 · `guest.enabled=false` 면 익명 쓰기 401(지금과 같음) |
| 신고 이메일(2차) | 저장 열에 평문이 없음 · 목록에서만 열림 · 이벤트 · 로그에 없음 · `clearContact` · 탈퇴 뒤 NULL · 키 없이 켜면 기동 실패 · 프로젝트 A 의 암호문을 B 가 못 엶 |
| 이벤트 | 앱 트랜잭션이 롤백되면 이벤트 없음 · 커밋 뒤 한 번 · 리스너가 던져도 201 · 판 승인은 `RevisionDecided(APPROVED)` + `…Updated` |
| 마이그레이션 업그레이드 | `MigrationUpgradeIntegrationTest`(PG · MySQL): 기준선 행이 새 파일 뒤에도 읽히고 게시판 설정이 꺼짐으로 읽힌다 · 넘겨받기가 `guest:` 행을 고르지 않는다 |
| 모듈 기본 동작 불변 | `modules/board` 의 기존 `BoardWebTest` · `noOptionalTest`(captcha-turnstile · crypto 없이도 부팅)가 고치지 않은 채 통과 |

## 11. 견본 앱 — 기존 `general` 을 업그레이드(형 결정 9)

**켜는 값**(`apps/sample` `application.yml` · `seed-boards[general].settings`): `guest.enabled: true` · `http.allow-anonymous-read: true`(비회원이 못 읽는 게시판에는 비회원 쓰기를 시연할 수 없다) · `guestWrite: ALL` · `postApproval: GUESTS` · `commentApproval: GUESTS` · `ownerModeratesComments: true` · `comment-max-attachments: 4`. 캡차는 끈 채(형 결정 2, 견본에 Turnstile 키가 없다)로 두고 yml 에 §10 경고를 주석으로 남긴다. **승인을 `GUESTS` 로 고른 이유**: 회원의 글 · 댓글 · 수정은 지금처럼 바로 공개되므로 기존 회원 흐름 시험이 그대로 통과한다.

| 흔들리는 곳 | 왜 | 고칠 방법 |
|---|---|---|
| 기존 로컬 · 스테이지 DB 의 `general` | `seed-boards` 는 있는 게시판을 건드리지 않아 설정이 꺼진 채 남는다 | 견본 앱 마이그레이션 한 장이 `update boards set … where code = 'general'` (새 DB 에서는 0행, 그 뒤 시드가 설정째 만든다) — 형 결정 12 |
| `AuthorLinkLeakIntegrationTest` · `LoginIdLeakIntegrationTest`(`LeakSweep`) | ① 응답에 새 어휘(`NONE` · `ALL` · `GUESTS` · `OFF` · `PASSWORD` · `PENDING` · `comment.pending`)가 나와 `LeakAllow.strings` 밖 문자열로 잡힌다 ② 익명 읽기를 켜면 Z(비로그인)의 읽기가 401 에서 200 이 되어 훑는 응답이 는다 ③ `pendingRevision` · `canManageComments` 가 보는 사람마다 다르다 | ① 새 어휘를 `allow.strings` 에 더한다(모두에게 같은 어휘) ② 401 을 기대하던 단언이 있으면 200 기준으로 바꾸고, 늘어난 응답도 같은 누출 검사를 받게 둔다(오히려 넓어진다) ③ 프로필 id 를 바꿔 보내는 `probes` 비교에서 같은 값인지 확인 — 보는 사람 기준이라 프로필과 무관해야 한다 |
| 누출 시험의 세계(`LeakWorld`) | 비회원 행 · 판이 없으면 새 길이 훑이지 않는다 | 비회원 댓글 하나 · 회원 글의 대기 판 하나를 더하고, 비밀 · 해시 문자열이 어느 응답에도 없는지 보는 줄을 더한다 |
| `WriteGateCensusTest` | 비회원 쓰기 경로가 공개 경로가 된다 — 주소 없는 계정이 **로그인한 채** 부르면 게이트가 여전히 막는지 | 「열린 목록」이 그대로인지 시험이 본다. 공개 경로에서 게이트가 빠지면 이 시험이 깨지므로, 그때는 게이트를 인증된 요청 기준으로 고친다(로그인 = 막음, 익명 = 비회원 규칙) |
| `BoardIntegrationTest` · `MigrationUpgradeIntegrationTest` | 회원 흐름이라 그대로여야 한다. 업그레이드는 새 파일 넷과 견본 파일 한 장이 기준선 위로 돈다 | 손대지 않고 통과하는지 확인. 업그레이드 시험에 `general` 설정이 켜짐으로 읽히는 줄을 더한다 |
| react `apps/sample/e2e/board.e2e.ts` | 회원으로 로그인한 흐름이라 그대로여야 한다. 댓글 칸에 사진 고르기가, 글 상세에 주인 관리 단추가 늘어 화면 모양이 바뀐다 | 역할 · 이름 선택자라 대부분 그대로. 깨지면 선택자를 좁힌다. **새 e2e 하나**: 로그아웃 → 비회원 댓글(이름 · 비밀번호 · 사진) → 「승인 대기」 → 글 주인 로그인 → 대기 목록에서 승인 → 공개, 그리고 주인이 남의 승인 대기 판을 거절 |
| `nickname.e2e.ts` · `authorProfiles.e2e.ts` · `journey.e2e.ts` 등 `general` 을 쓰는 e2e | 응답 키 · 화면 요소가 늘어난다 | 돌려 보고 깨지는 선택자만 고친다(동작은 회원 기준이라 같다) |

## 12. 작업 목록

| # | 조각 | 크기 | 비고 |
|---|---|---|---|
| S0 | 이벤트 자리(`BoardEvent` · 커밋 뒤 디스패처 · 기존 쓰기에서 발행) | S | 먼저 — 뒤 조각이 처음부터 발행한다 |
| S1 | 비회원 작성(V 파일 · 모델 · `BoardAuthors` 갈래 · 비밀 · 헤더 · 공개 경로 · 캡차 다리(기본 꺼짐) · `BoardGuestLimiter` · 넘겨받기 수정 · 알림 건너뛰기) | L | |
| S2 | 승인 대기 + 글 주인 관리(V 파일 · `PENDING` · 보기 쿼리 · `PATCH /{code}` · moderation 확장 · `moderated_by` · 알림 규칙) | L | S1 뒤(`GUESTS` 판정에 비회원이 필요) |
| S3 | 고친 판(V 파일 · `BoardRevisionRepository` · 판 만들기 · 덮어쓰기 · 승인 · 거절 · 버림 · 대기 목록 둘 · `pendingRevision` · 이벤트) | M~L | S2 뒤. 판 첨부는 글에 바로 넣고, 댓글 판의 첨부는 S4 가 잇는다 |
| S4 | 댓글 첨부(V 파일 · 포트 · 스레드 한 쿼리 · 숨김 걸러내기 · 댓글 판 첨부) | M | S3 뒤 |
| S6 | 문서(`board.md` · `board-jdbc.md` · `config/modules/board.yml` · `errors.md` · 새 `board-http-contract.md` 의 비회원 · 승인 · 판 절, §10 경고 둘) + 업그레이드 시험 | S | 백 1차 끝 — 전체 스위트는 여기서 한 번 |
| F1 | react 타입 · API · 훅: `PENDING` · `authorGuest` · `attachments` · `pendingRevision` · `canManageComments` · 설정 키 · `guest?: {name, secret}` · `guestSecret` 옵션(헤더) · `captchaToken` · 대기 목록 넷 · `decideRevision` · `ErrorCodes.BOARD_CAPTCHA_FAILED`(`@skeleton/api-client`) | M | S6 계약 뒤 |
| F2 | react 화면: `CommentForm` · `PostEditor` 비회원 칸(`PASSWORD` 면 사람이 치고 저장하지 않음, `TOKEN` 이면 앱이 준 값) · 캡차 자리(render prop) · 「(비회원)」 표시 · 대기 배지 · 「고친 내용은 승인 뒤에 보여요」 안내 · 주인 승인 · 가리기 · 판 비교(공개본 ↔ 판) 단추 · 비회원 고치기 · 지우기의 비밀 묻는 창 · 댓글 사진 보이기(`renderAttachment` prop) | M~L | |
| X1 | 견본 업그레이드(§11): yml · 견본 마이그레이션 · 누출 시험 고치기 · `WriteGateCensusTest` 확인 · e2e 새 하나 + 깨진 선택자 | M | F2 뒤. 전체 e2e 는 여기서 한 번 |
| S5 · F3 | 2차: 신고(백 + `crypto` 암호화 · 지우는 길 셋 · react 신고 창 · 운영자 목록) | M · M | 1차와 독립 |

## 정해야 할 것

없음(2026-10-17 모두 정함 — §1.1).
