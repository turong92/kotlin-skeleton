# 샘플 앱 "Notes" — 새 기능을 어떻게 얹는지 보여 주는 정본

`apps/sample` 은 **제품 모양의 작은 앱**이다: 로그인한 사람이 노트(제목 · 본문 · 상태 · 고정 · 첨부)를 관리한다.
스타터(`apps/api`)에 모듈 몇 줄과 도메인 하나(`notes`)만 얹었고, 모듈 내부는 건드리지 않았다. 새 부업 프로젝트에서 기능 하나를 만들 때 **그대로 따라 하는 예시**다.
짝 프론트는 react-skeleton 의 `apps/sample` 이다(화면 · 컴포넌트 매핑은 그쪽 README). 결제(응원하기)도 이 앱에 있다 — 아래 "결제 — 응원하기".

| 이 화면/기능이 필요하면 | 이 샘플에서 쓴 모듈 |
|---|---|
| 로그인 · JWT · 가입 · 세션 · 탈퇴 | `auth` + `account-jdbc`(진짜 계정 — 가입 · 이메일 확인 · 재설정 · 삭제) + `auth-session-jdbc`(리프레시 토큰) + `auth-magic-link` + `notification-mail`(메일은 로컬에서 compose `mail` 프로필의 mailpit `http://localhost:8025`). 로컬 · 시험 시드 계정 3 개 — `application-local.yml` / `src/test/resources/test-seeds.yml` 의 `skeleton.account.seed.accounts`: `user@example.com`(`acc_user`), `admin@example.com`(`acc_admin`, ADMIN), **`moderator@example.com`(`acc_moderator`, MODERATOR)** — 비밀번호는 모두 `password`. 흐름 전체는 `AccountJourneyIntegrationTest` · [계정 수명주기](accounts.md) |
| 표준 응답 · 에러 · 검증 · 페이지 | `platform` |
| 두 번 눌러도 한 번 만들기 | `idempotency` (`@IdempotentOperation`) |
| 알림 받은편지함 + 실시간 | `notification` + `notification-jdbc` + `notification-sse` |
| 첨부 업로드 (브라우저가 S3 로 직접) | `storage` + `storage-s3` |
| 오래 걸리는 일(내보내기) | `job-queue-jdbc` |
| 게시판(글 · 대댓글 · 공감 같은 반응 종류) | `board` + `board-jdbc` (아래 "게시판") |
| 한 계정이 닉네임(작성자 프로필)을 여러 개 만들어 글 · 댓글마다 골라 쓰기 — 두 프로필이 같은 사람이라는 사실이 남에게 드러나지 않게 | `author-profile` + `author-profile-jdbc` + `skeleton.storage.web.key-owner=OPAQUE` (아래 "작성자 프로필") |
| 돈 받기 · 환불하기 — 결제창 → 승인 → 내 결제 내역 → 환불 요청 → 운영자 환불 (샘플 상품은 「응원하기」) | `payment-jdbc`(결제 장부 + `payment`) + `payment-toss`(결제사, 꺼진 채 시작) + 이 앱의 `cheer/` (아래 "결제 — 응원하기") |
| 팀 · 조직 — 워크스페이스 만들기 · 역할 · 메일 링크 초대 · 팀 단위 데이터 · 구독이 정하는 좌석 | `workspace` + `workspace-jdbc` + 이 앱의 `workspace/` · `notes/` (아래 "워크스페이스") |
| 서버끼리 부르는 API 키 — 사용자가 범위 · 만료를 골라 발급(다시 인증)하고, 키로는 표시한 노트 경로만 닿는다 | `api-key` + `api-key-jdbc` + 이 앱의 `notes/NoteApiKeyScopes` (아래 "API 키") |
| DB · 마이그레이션 · 시간 | `persistence-jdbc` · `db-postgresql` · `migration-flyway` · `time` |

## 엔드포인트

모두 `Authorization: Bearer <JWT>` 필요(없으면 401). 성공은 표준 envelope(`{ value | values, pagination, meta }`), 실패는 `ApiError`.

| 메서드 · 경로 | 하는 일 | 응답 |
|---|---|---|
| `POST /api/v1/notes` | 만들기. `Idempotency-Key` 헤더 필수. `{title(1..80), body(≤5000), status, pinned}` | 201 + `Location` + `{value: Note}` |
| `GET /api/v1/notes?page&size&q&status&pinned` | 내 노트 목록. `q` = 제목 · 본문 부분 일치(대소문자 무시). 고정 → 최근 수정 순 | `{values, pagination}` |
| `GET /api/v1/notes/summary` | 개수 요약 `{total, pinned, withAttachment, draft, active, archived}` | `{value}` |
| `GET /api/v1/notes/{id}` | 하나 | `{value: Note}` · 없거나 남의 것이면 404 `NOTES.NOT_FOUND` |
| `PUT /api/v1/notes/{id}` | 통째로 바꾸기 `{title, body, status, pinned, attachmentKey, attachmentName}` | `{value: Note}` |
| `DELETE /api/v1/notes/{id}` | 지우기 (첨부 파일도 지운다) | 204 |
| `POST /api/v1/notes/{id}/export` | 마크다운으로 내보내기 시작 | 202 `{value: {jobId}}` — 끝나면 알림 `NOTE_EXPORTED` (`payload.exportKey` 를 `/storage/presign-download` 에 넘겨 받는다) |
| `/api/v1/notifications` · `…/unread-count` · `…/{eventId}/read` · `…/read-all` · `…/preferences` · `/sse` | 받은편지함 · 실시간 (`notification` · `notification-sse` 모듈이 연다) | |
| `/api/v1/storage/presign` · `presign-download` · `multipart/*` | 첨부 업로드 · 다운로드 (`storage` 모듈이 연다) | |

`Note` = `{id, title, body, status: DRAFT|ACTIVE|ARCHIVED, pinned, attachmentKey, attachmentName, createdAt, updatedAt}` (시각은 ISO-8601 `…Z`).
검증 실패는 400 `COMMON.VALIDATION_FAILED` + `errors: [{field, code, message}]` — `field` 는 JSON 속성 이름이다. 알 수 없는 `status` 값이나 `?status=NOPE` 는 400.
알림: 토픽 `notes`, 타입 `NOTE_CREATED | NOTE_UPDATED | NOTE_DELETED | NOTE_EXPORTED`, `payload.noteId`(딥링크) · `payload.noteTitle`. 받는 사람은 노트 주인뿐이다(SSE 도 그 사람에게만 흐른다).
첨부 규칙(앱의 선택, `application.yml`): png · jpg · gif · webp · pdf · txt, 5 MB 이하. 첨부 키는 **내가 올린 것**이어야 한다(아니면 `errors[attachmentKey]`) — 판정은 storage 모듈의 `StorageKeyOwnership.owns(계정, 키)` 빈이 한다. 이 앱은 `key-owner=OPAQUE` 라 업로드 키가 `uploads/o/<uuid>.<mac>/file[.<확장자>]` 이고 키만 봐서는 누구 것인지 알 수 없다(접두사 비교는 앱 코드에 없다). 옛 `uploads/<계정 id>/…` 꼴 키도 그대로 인정된다.
**표시 이름은 키가 아니라 노트가 저장한다**: OPAQUE 키에서는 클라이언트가 보낸 파일 이름이 키에서 사라지므로(`file.png`) `PUT` 의 `attachmentName`(예: `고양이.png`)을 노트가 그대로 저장해 `Note.attachmentName` 으로 돌려준다. 보내지 않으면 키의 마지막 구간(`file.png`)이 들어가니 프론트는 항상 파일 이름을 같이 보낸다(react-skeleton 의 apps/sample 은 이미 `attachmentName: file.name` 을 보낸다 — 계약 변화 없음). **한계**: 키에 원래 이름이 없으니 업로드 직후 `presign-download` 로 받는 파일 이름은 `file.<확장자>` 가 된다(표시 이름은 노트의 `attachmentName`). 내보내기 결과 키(`uploads/<계정 id>/exports/…`)는 서버가 정하는 키라 옛 꼴 그대로이고 노트 주인에게만 알림 payload 로 간다.

## 게시판 (`/api/v1/boards`)

노트와 별개로 `board` + `board-jdbc` 두 줄을 더한 데모다 — 이 앱 코드는 한 줄도 더하지 않았고 **설정(`application.yml`)과 시드 계정(`application-local.yml`)만** 이 앱의 선택이다:

- `skeleton.board.reaction.types: [LIKE, DISLIKE, EMPATHY]` — 모듈 기본은 `[LIKE, DISLIKE]`. 공감(`EMPATHY`)은 이 한 줄이 전부다 (코드 · 스키마 변경 없음). `GET /api/v1/boards/config` 가 프론트에 종류를 알려 준다.
- `skeleton.board.seed-boards` — 기동할 때 `general`(General) 게시판을 만든다.
- 운영자: `moderator@example.com` / `password` (`MODERATOR` 역할 — 게시판 권한 `board.moderate` · `board.manage` 의 기본 매핑). 글 숨기기 · 고정 · 남의 글/댓글 삭제 · 게시판 만들기. 일반 사용자는 `user@example.com` · `admin@example.com`.
- 알림 설정(이 앱의 선택, `application.yml`): 90일 지난 알림 정리(`retention`), 끌 수 있는 종류 넷(`preferences.types` — 노트 수정 · 댓글 · 답글 · 보상), SSE 심장박동 15초(`sse.heartbeat`).
- 댓글 알림: 내 글에 댓글이 달리면 토픽 `board`, 타입 `comment.created`(답글은 `comment.replied`)가 받은편지함 · SSE 로 온다 — `notification` 이 클래스패스에 있어서 켜진 것이다.
- 글 · 댓글 만들기는 `Idempotency-Key` 필수 (`idempotency` 가 있어서).
- 경로 · 모양 · 에러 코드 · 결정 근거: [board](modules/board.md). 통합 테스트: `apps/sample/src/test/…/board/BoardIntegrationTest.kt`.

## 소셜 가입과 닉네임

이 앱은 소셜 가입(`skeleton.account.social.sign-up: true`) 때 **제공자가 준 이름을 가져오지 않는다**(`import-display-name: false`) — Google 실명이 닉네임이 되지 않는다. 소셜 가입 직후 계정은 이름이 없고(`display-name.fallback: NONE`) 프런트의 「닉네임 정하기」 띠와 글쓰기 전 게이트가 닉네임을 받는다. 제공자 이름을 쓰는 앱은 이 한 줄을 지우면(기본 `true`) 된다. 근거 · 시험: [accounts](accounts.md) "닉네임", `GlobalSocialJourneyIntegrationTest`.

## 작성자 프로필 (`/api/v1/author-profiles`)

게시판 위에 `author-profile` + `author-profile-jdbc` 두 줄을 더했다 — 이 앱 코드는 더한 것이 **하나도 없다**(설정 · 시험만): 모듈 기본값을 그대로 쓰고(계정당 5개 · `adopt-on-startup` 켜짐) 닉네임 규칙은 위 `account.display-name` 을 따른다. 계약 · 설정: [author-profile](modules/author-profile.md).

- 게시판 쓰기(`POST …/posts` · `…/comments`)는 **`authorId`(내 프로필 id `prf_…`)가 필수**다 — 없으면 `400 BOARD.AUTHOR_REQUIRED`, 남의 · 없는 · 지워진 id 면 `403 BOARD.AUTHOR_NOT_ALLOWED`. 응답의 `authorId` 는 계정 id 가 아니라 프로필 id 이고 `mine` 이 내 글인지 알려 준다. `GET /api/v1/boards/config` 의 `authorRequired` 는 `true`.
- **시드 계정에 프로필을 만들어 둘 필요가 없다** — 첫 프로필은 계정 닉네임을 한 번 복사해 만들어진다: 옛 글이 있는 계정은 기동 때(`adopt-on-startup`), 없는 계정은 첫 `GET /api/v1/author-profiles` 때. 그래서 `application-local.yml` 의 시드 계정은 그대로이고, 프론트는 쓰기 전에 목록을 한 번 읽으면 된다.
- 이미 글이 있는 DB 에서 켜면 기동 때 옛 글 · 댓글이 계정의 첫 프로필로 넘겨받혀 같은 이름으로 보인다 — 증거: `MigrationUpgradeIntegrationTest`(기준선 데이터 → 최신 마이그레이션 → 기동 → 게시판 읽기 경로 `GET /boards/upgrade/posts/{id}` 에서 옛 글이 첫 프로필 이름 · `prf_` 작성자 id 로 보인다).
- **첨부 키에 계정 id 가 없다** — `skeleton.storage.web.key-owner: OPAQUE`(위 첨부 규칙). 비밀 `skeleton.storage.web.key-secret`(32자 이상)은 환경변수 `SKELETON_STORAGE_WEB_KEY_SECRET`(배포 선언의 `secrets:` — deploy/app.yaml 의 `[storage]` 줄, [deploy.md §7](deploy.md))로 받고 **기본값이 없다**: 비었으면 기동 실패. 로컬 · e2e 는 `application-local.yml` 에, 시험은 `src/test/resources/config/application.yml` 에 고정 더미 값이 있다 — **더미 비밀은 `local` 프로필 · 시험 전용**이다(같은 파일의 시드 계정이 stage/prod 에서 기동 가드에 막히므로 운영으로 새지 않는다). 게시판은 글 첨부 키에 호출자 계정 id 가 들어 있으면 `400 BOARD.CONTENT_INVALID` 로 막는다(옛 키 안전망).
- 계약: `docs/author-profile-http-contract.md`(`author-profile` 을 고른 프로젝트에만 남는다). 누출 스캔: `AuthorLinkLeakIntegrationTest`(Y · Z 가 읽는 모든 경로에 X 의 값이 없고 프로필끼리 값으로 겹치지 않음 — 삭제 · 정지 · 탈퇴 뒤에도, 시드는 `src/test/resources/leak-seeds.yml`) · `AuthorLinkColumnsMySqlTest`(MySQL 열 분류).
- 통합 시험: `BoardIntegrationTest`(쓰기 계약 · 읽는 사람 응답에 계정 id 없음 · 첨부 키), `NoteCrudIntegrationTest`(OPAQUE 키가 노트 첨부로 통과 · 남의 키 · 위조 mac), `AccountJourneyIntegrationTest`(탈퇴 뒤 글이 "삭제된 사용자").

## 로그인 아이디와 이메일은 쓰기 때 (`application.yml`의 선택 둘)

계정 쪽에서 이 앱이 **명시적으로 켠** 선택이 둘 있다(모듈 기본은 둘 다 꺼짐 / 로그인 직후 — 설정 한 곳에서 되돌린다). 규칙 · 위협 · 시험은 [accounts.md](accounts.md) "로그인 아이디" · "이메일을 받는 시점".

- **`skeleton.account.login-id.enabled: true`** — 이메일이 바뀌어도 안 바뀌는 **비공개** 로그인 아이디. 가입 양식에서 받고, 소셜 첫 로그인은 인증 직후 「아이디를 정해 주세요」 게이트(`403 ACCOUNT.LOGIN_ID_REQUIRED`)를 지난 뒤 약관으로 간다. 로그인은 **아이디 또는 이메일** + 비밀번호. 예약어(`reserved`): `admin` · `administrator` · `root` · `system` · `support` · `help` · `staff` · `moderator` · `notes`(기호 · 대소문자를 빼고 비교, 로컬 시드 계정은 예외).
  - **로컬 시드 아이디**(`application-local.yml`, 비밀번호는 모두 `password`): `user@example.com` → `sample-user`, `admin@example.com` → `sample-admin`, `moderator@example.com` → `sample-moderator`. 아이디를 안 주면 e2e 가 전부 아이디 게이트에 막힌다.
  - 배포(`SKELETON_ENV=stage|prod`)에서는 **`SKELETON_ACCOUNT_BLOCKS_SECRET` 이 있어야 기동한다**(탈퇴한 아이디의 재사용 금지 해시 열쇠 — 선언 `deploy/app.yaml` 의 `secrets:` 에 이름이 이미 있고 플랫폼이 무작위 값을 채운다). 계약 증명 스크립트의 견본 앱 구간도 이 값을 넘긴다([deploy.md](deploy.md)).
- **`skeleton.account.email.required: true` + `required-when: WRITE`** — 소셜로 이메일 없이 만들어진 계정(Kakao · Naver · X · LINE …)은 **읽기는 전부 통과하고, 쓰려 할 때** 이메일을 등록하게 한다(`403 ACCOUNT.EMAIL_REQUIRED`는 변경 요청에서만). 오래된 세션의 첫 등록은 제공자 재확인(`REAUTH_REQUIRED` → `socialReauth`), 방금 연 세션은 재인증 없이. 결제 모듈은 이 앱에 없지만 **결제 경로는 어떤 경우에도 열지 않는다**.
  - **`email.extra-exclude-paths` 세 줄**(기본이 "막힘"이라 여기 적은 것만 열린다 — 왜 이 셋인가):
    - `PATCH /api/v1/notifications/*/read` · `PATCH /api/v1/notifications/read-all` — 알림함을 여는 것은 읽기인데 화면이 읽음 표시를 보낸다. 이것이 막히면 읽기만 하는 계정에도 이메일 화면이 뜬다.
    - `POST /api/v1/storage/presign-download` — 글의 첨부를 보려면 필요하다. 메서드는 `POST` 지만 읽기다.
  - **주소 없는 계정에게 열린 변경 경로**의 정본은 시험 `WriteGateCensusTest` 의 목록이다(이 앱의 변경 핸들러를 전부 뽑아 정확히 같은지 본다 — 새 변경 엔드포인트는 목록에 적지 않는 한 막힌다).
- 둘 다 `apps/sample` 의 통합 시험이 지킨다: `EmailOnWriteJourneyIntegrationTest`(소셜 첫 로그인 → 아이디 → 약관 → 읽기 → 쓰기 `403` → 이메일 등록 → 같은 쓰기 통과 · 버리기), `AccountJourneyIntegrationTest`(아이디로 가입 · 로그인 · 이메일 변경 뒤에도 같은 아이디), `LoginIdLeakIntegrationTest`(남이 읽는 어떤 응답에도 아이디가 없다), `MigrationUpgradeIntegrationTest`(켜기 전부터 있던 계정이 게이트를 만나고 정하면 풀린다). "로그인 직후" 방식의 기존 여정(`EmailRequiredJourneyIntegrationTest`)은 `required-when=SIGN_IN` 을 얹어 그대로 둔다.

## 결제 — 응원하기 (`/api/v1/cheers`)

`payment-jdbc` + `payment-toss` 두 줄과 이 앱의 작은 조각 `cheer/` 가 전부다 — 서버가 가진 금액 목록(1,000 · 3,000 · 5,000원)에서 하나를 골라 결제하고, 내 응원 내역을 보고, 환불을 **요청**하면 운영자가 환불한다.
결제 자체(주문 만들기 · 승인 · 환불 요청 · 운영자 환불 · 웹훅)는 결제 장부 모듈의 `/api/v1/payments` · `/api/v1/admin/payments` 가 맡는다(계약: [payment-http-contract](payment-http-contract.md), 모듈: [payment](modules/payment.md) · [payment-jdbc](modules/payment-jdbc.md) · [payment-toss](modules/payment-toss.md)). 이 앱이 더한 것은 아래뿐이다.

| 메서드 · 경로 | 하는 일 | 응답 |
|---|---|---|
| `GET /api/v1/cheers/options` | 서버가 가진 금액 목록 — 브라우저는 이 안에서만 고른다 | `{values: [{itemRef: "cheer-1000", amount: {amount: 1000, currency: "KRW"}}, … 3000, … 5000]}` |
| `GET /api/v1/cheers/me?cursor&size` | 내 응원 내역, 최신순 | `{values: [{orderId, amount, refundedAmount, status: PAID|REFUNDED, createdAt, refundedAt, card: {issuer, last4}|null}], cursor: {hasNext, nextCursor}}` |

주문은 `POST /api/v1/payments/orders` 에 `{itemType: "cheer", itemRef: "cheer-3000", quantity: 1}` 로 만든다(금액은 보내지 않는다). 목록에 없는 코드 · 수량이 1 이 아닌 주문 · 다른 상품 종류는 `422 PAYMENT.ITEM_NOT_PURCHASABLE`.
`status` 는 `PAID` · `REFUNDED`(전액 환불)뿐이다 — 일부 환불은 `PAID` 에 `refundedAmount` 만 오른다. `card` 는 장부의 주문 줄에서 읽는다(카드 결제가 아니면 `null`).

**앱이 결제 장부에 끼는 자리 세 곳** (`…/app/sample/cheer/`):

1. `CheerCatalog` — `PaymentCatalog`. **금액의 유일한 출처**다. `orderName` 에는 개인정보가 없다.
2. `CheerOrderListener` — `PaymentOrderListener`. `onPaid` 가 응원 기록(`cheers` 표 한 줄)을 남기고(같은 주문이 두 번 와도 한 줄), `onRefunded` 가 환불된 금액을 올린다(전액이면 `REFUNDED` + 환불 시각; **지급한 적 없는 주문에도 불리므로 기록이 없으면 아무것도 하지 않는다**). **두 훅은 주문 줄 락 안에서 돈다** — 그동안 그 주문의 웹훅 · 승인 · 환불이 기다리므로 한 문장씩만 쓴다(알림 · 메일 · 외부 호출은 넣지 않았다. 필요하면 큐로).
3. `CheerErasure` — `AccountErasureListener`. 탈퇴한 계정의 **응원 기록을 지운다**. 장부의 주문 줄은 법정 보존이라 남고, 장부 모듈의 탈퇴 고리가 카드 두 칸(`card_issuer` · `card_last4`)을 지운다.
   - **순서**: `CheerErasure` 는 `@Order(LOWEST_PRECEDENCE)` — 장부의 탈퇴 고리(`PaymentAccountErasure.ORDER` = 0)보다 **뒤**에 돈다. 장부 고리가 먼저 주문 줄에 `account_erased_at` 을 찍어야, 그 사이 `PAID` 가 되는 주문이 방금 지운 표에 줄을 되살리지 않는다(시험: `CheerErasureOrderIntegrationTest`). 계정 모듈은 고리를 `@Order` / `Ordered` 순으로 부른다.
   - **탈퇴한 계정의 주문이 뒤늦게 `PAID` 가 되면** 이 앱은 응원 기록을 **남기지 않는다**(`onPaid` 가 `order.accountErasedAt` 을 보고 건너뛴다) — 장부에는 지급됨(`PAID`)으로 찍히고, 운영자는 `includeErased=true` 로 그 주문을 본다. **이 조각을 물건을 파는 앱에 복사할 때는 `order.accountErasedAt` 을 보고 지급할지 환불할지를 정해야 한다** — 응원처럼 「기록만 안 남기면 되는」 앱이 아니면 돈만 받고 물건을 안 주는 주문이 된다.

표는 `cheers` 하나(마이그레이션 `V20261008205449__create_cheers`, PostgreSQL 전용 — 이 앱이 그렇다): 주문 id(키) · 계정 · 금액 · 환불된 금액 · 상태 · 시각. 장부의 표를 외래 키로 가리키지 않는다(다른 모듈의 표).

**켜는 법** — 토스를 켜지 않으면 앱은 예전처럼 뜨고 `GET /api/v1/payments/config` 가 `available: false`(결제 화면은 「지금은 결제할 수 없어요」), 주문 만들기는 `503 PAYMENT.UNAVAILABLE`, 금액 목록은 그대로 나간다. 켜려면 환경변수(로컬은 `.env.local`, 배포는 선언의 `env:` · `secrets:` — [deploy.md §7](deploy.md)):

| 환경변수 | 뜻 |
|---|---|
| `SKELETON_PAYMENT_TOSS_ENABLED=true` | 토스를 켠다 |
| `SKELETON_PAYMENT_TOSS_SECRET_KEY` (별칭 `TOSS_PAYMENTS_SECRET_KEY`) | `test_sk_…` — 결제창 + API 개별 연동 키(결제위젯 키 `test_gsk_…` 는 쓰지 않는다). 비밀 |
| `SKELETON_PAYMENT_TOSS_CLIENT_KEY` (별칭 `TOSS_PAYMENTS_CLIENT_KEY`) | 같은 묶음의 `test_ck_…`. 비밀 아님 — 서버가 결제창 값으로 프론트에 준다 |
| `SKELETON_PAYMENT_TOSS_BASE_URL` | 토스 API 주소(기본 `https://api.tosspayments.com`) — 프런트 e2e 는 가짜 토스를 가리킨다 |
| `SKELETON_PAYMENT_ADMIN_ENABLED=true` | 운영자 경로. **local 프로필은 `application-local.yml` 이 이미 켠다**; 배포는 이 변수 |
| `SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN` | 웹훅 주소 끝의 비밀 조각(24자 이상). **yml 에 값을 커밋하지 않는다** — 비면 서명 없는 토스의 웹훅 경로는 늘 404, 보호 환경에서는 기동 실패. 주소: `/api/v1/payments/webhooks/toss/<값>` |

**카카오페이(토스 계약 없이)** — 같은 방식으로 꺼진 채 시작한다. 켜면 같은 나라(KR)에 결제사가 둘이라 응원하기에 「결제 방법」 고르기가 생긴다(`GET /api/v1/payments/config` 의 `choices`; 나라 지도 `KR: toss` 가 처음 골라진 쪽을 정한다). 안내: [real-provider-setup](real-provider-setup.md) §12.

| 환경변수 | 뜻 |
|---|---|
| `SKELETON_PAYMENT_KAKAOPAY_ENABLED=true` | 카카오페이를 켠다 |
| `SKELETON_PAYMENT_KAKAOPAY_SECRET_KEY` | 개발자센터 앱의 Secret key(dev). 비밀 |
| `SKELETON_PAYMENT_KAKAOPAY_CID` | 가맹점 코드 - 테스트는 `TC0ONETIME`. 비밀 아님(stage · prod 에서 테스트 코드면 기동 실패) |
| `SKELETON_PAYMENT_KAKAOPAY_RETURN_URL` | 구매자를 돌려보낼 이 앱의 화면 주소(프런트 주소 + `/payments/return`). 비밀 아님 |
| `SKELETON_PAYMENT_KAKAOPAY_ALLOWED_RETURN_ORIGINS` | 쉼표로 이은 origin - 결제를 시작한 화면의 origin 으로 돌아오게 허락(로그인 저장소가 origin 마다 따로라서). 비면 늘 `RETURN_URL`. 허락한 origin 마다 카카오 개발자센터 「플랫폼 > Web > 사이트도메인」 등록이 필요하다(반영에 몇 분). stage · prod 는 https 만 |
| `SKELETON_PAYMENT_KAKAOPAY_BASE_URL` | 카카오페이 API 주소(기본 `https://open-api.kakaopay.com`) - 프런트 e2e 는 가짜 카카오페이를 가리킨다 |

**네이버페이(토스 계약 없이)** — 같은 방식으로 꺼진 채 시작한다. 인증값은 가맹 심사 뒤에야 나오므로 그 전에는 가짜 서버(프런트 e2e · 백엔드 시험)로만 시험된다. 안내: [real-provider-setup](real-provider-setup.md) §13.

| 환경변수 | 뜻 |
|---|---|
| `SKELETON_PAYMENT_NAVERPAY_ENABLED=true` | 네이버페이를 켠다 |
| `SKELETON_PAYMENT_NAVERPAY_MODE` | `development`(기본) \| `production` - API 서버 · 결제창 도메인이 함께 따른다. stage · prod 에서 development 면 기동 실패, 로컬에서 production 이면 기동 실패 |
| `SKELETON_PAYMENT_NAVERPAY_CLIENT_ID` · `…_CHAIN_ID` | 가맹점 · 그룹 식별키(서버가 헤더로 싣는다 - 브라우저에 나가지 않는다) |
| `SKELETON_PAYMENT_NAVERPAY_CLIENT_SECRET` | 클라이언트 시크릿. 비밀 |
| `SKELETON_PAYMENT_NAVERPAY_RETURN_URL` | 구매자를 돌려보낼 이 앱의 화면 주소(프런트 주소 + `/payments/return`). 비밀 아님 |
| `SKELETON_PAYMENT_NAVERPAY_ALLOWED_RETURN_ORIGINS` | 쉼표로 이은 origin - 뜻은 카카오페이와 같다. 비면 늘 `RETURN_URL` |
| `SKELETON_PAYMENT_NAVERPAY_BASE_URL` · `…_WINDOW_BASE_URL` | 비우면 모드가 정한 네이버페이 API · 결제창 주소 - 프런트 e2e 는 가짜 네이버페이를 가리킨다 |

이 앱의 yml 이 정한 것: `skeleton.http.clients.payment-toss.response-timeout: 30s`(승인 · 환불은 토스가 느릴 수 있다 — 플랫폼 기본 5초) — 토스를 켜면 `reconcile.first-delay`(기본 60s) · `refund.settle-delay`(기본 2m)가 이보다 길어야 기동한다. 작업 큐(`skeleton.job-queue`, 폴링 1s)는 켜져 있어야 한다 — `job-queue.enabled=false` 이면 장부가 시작하지 못한다(조회 · 지급 · 웹훅 처리가 이 큐로 돈다). `job-queue.worker-enabled=false`(일은 다른 인스턴스가 한다)는 뜨지만 이 인스턴스는 작업을 돌리지 않는다.
local 프로필은 주문 만들기 한도(기본 계정당 10 / 10분)를 풀어 둔다(`application-local.yml`) — 프런트 e2e 가 한 계정으로 주문을 여럿 만든다.

**프런트 e2e** (react-skeleton `apps/sample/e2e/`)가 백엔드에 넘기는 것: 위 환경변수 다섯(웹훅 조각 제외) + `SPRING_APPLICATION_JSON` 의 `skeleton.payment.reconcile.first-delay=11s` · `skeleton.payment.checkout-ttl=40s` · `skeleton.http.clients.payment-toss.response-timeout=10s`(응답 제한 10초 < 첫 조회 11초 — 가드) · `skeleton.payment.http.order-rate-limit.account-capacity=1000` · `…ip-capacity=1000`(한 실행이 한 계정으로 주문을 여럿 만든다) · `skeleton.payment.webhook.path-token`(e2e 전용 값 — 비밀이 아니다). 앞의 다섯은 이 앱에서 실제로 먹는다 — 증거: `PaymentEnvironmentOnlyBootTest`(환경변수 모양 그대로 진짜 기동; JSON 은 점 표기 평면 키 — 찍은 프로젝트의 이름 바꾸기가 잡는 모양). 시드 `user@example.com` · `admin@example.com`(ADMIN) · `moderator@example.com`, 비밀번호 `password`.
웹훅을 손으로 보내 보기(토스의 결제 웹훅은 서명이 없고 본문을 믿지 않고 조회하므로 로컬에서 만든 것도 진짜와 같은 길을 탄다): `curl -X POST localhost:8080/api/v1/payments/webhooks/toss/$SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN -H 'Content-Type: application/json' -d '{"eventType":"PAYMENT_STATUS_CHANGED","data":{"paymentKey":"…","orderId":"ord_…"}}'`.
로그에 주의: `org.springframework.web` 을 DEBUG 로 올리거나 `spring.mvc.log-resolved-exception` 을 켜면 웹훅 주소의 비밀 조각이 평문으로 찍힌다([payment](modules/payment.md) 「웹훅」). 이 앱은 주문 id · 결제 키를 남에게 보이는 화면에 싣지 않는다(`cheers/me` 에도 결제 키가 없다).

통합 시험(`apps/sample/src/test/…/cheer/` — 진짜 PostgreSQL · 보안 체인 · 결제 장부, 결제사만 시험 소스의 `ScriptedGateway` 빈): `CheerJourneyIntegrationTest`(가입 → 주문 → 승인 → 응원 기록 → 환불 요청 → 운영자 환불 → 「환불됨」 · 일부 환불 · 금액 목록 밖 · 승인이 "모름"으로 끝난 뒤 조회 작업이 기록을 만든다) · `CheerAccessIntegrationTest`(남의 주문 404 · 본인은 환불을 실행할 수 없다 · 운영자 판정이 저장소의 지금 역할 · 웹훅 경로가 익명으로 닿되 POST 두 모양뿐 · 사용자 응답에 운영자 칸 · 심은 값 없음) · `CheerOrderListenerIntegrationTest` · `CheerOrderRateLimitIntegrationTest`(429) · `CheerOperatorDisabledIntegrationTest`(꺼져 있으면 404) · `CheerWithoutProviderIntegrationTest`(토스 없이 기동) · `PaymentEnvironmentOnlyBootTest`(환경변수만으로 기동) · `AccountJourneyIntegrationTest`(탈퇴 뒤 전체 표 스캔 — 카드 두 칸이 비고 응원 기록이 지워지고 심은 값이 없고, 운영자는 여전히 환불할 수 있다) · `MigrationUpgradeIntegrationTest` · `MySqlMigrationUpgradeTest`(기준선 데이터 위에서 새 표가 올라가고 탈퇴 고리가 카드 두 칸을 비운다).

## 이벤트 · 보상 · 쿠폰 · 결제 면제

네 줄(`reward-jdbc` · `coupon-jdbc` · `point-jdbc` · `campaign-jdbc`)과 이 앱의 작은 조각 `rewards/RewardsSeed` 가 전부다. 흐름: **이벤트가 조건을 판정해 「지급 건」을 기록**(`campaign`, 자기 트랜잭션에서 끝) → **일꾼이 건마다 지급기를 불러 준다**(`reward`, 작업 큐 위, 재시도 · 멱등) → **쿠폰 · 포인트가 각자 지급기로 받는다**(`coupon` · `point`). 이벤트는 쿠폰 · 포인트를 모른다 — 모듈 문서: [campaign](modules/campaign.md) · [reward](modules/reward.md) · [coupon](modules/coupon.md) · [point](modules/point.md).

| 사용자 경로 | 하는 일 |
|---|---|
| `GET /api/v1/campaigns` · `POST /{id}/attendance` · `GET /{id}/attendance?month` | 진행 중 이벤트 · 출석 체크(서버 시간대 `skeleton.campaign.zone` 의 날짜로 하루 한 번) · 달력 |
| `GET /api/v1/referrals/me` · `GET /api/v1/referrals/me/invitees` · `POST /api/v1/referrals/claim {code}` | 내 초대 코드 · 초대 현황(가린 이름 · 상태) · 친구의 코드 쓰기 |
| `GET /api/v1/coupons?status=AVAILABLE\|PAST` · `POST /api/v1/coupons/redeem {code}` | 쿠폰함 · 공용 코드 등록 |
| `GET /api/v1/points` · `GET /api/v1/points/history` | 포인트 잔액 · 내역 |

운영 경로는 모듈 기본이 꺼짐이고 이 앱이 `application.yml` 에서 켠다(`skeleton.{campaign,coupon,point,reward}.admin.enabled: true`). 권한 `campaign.manage` · `coupon.manage` · `point.manage` · `reward.operate` · `payment.exempt` 는 **기본 역할이 없고 전권(`ADMIN`)만** 가진다 — 역할 관리(`/api/v1/admin/roles`)에서 나눠 준다.

**결제와 맞물리는 곳**: `coupon` 이 결제 할인 수단 빈으로 서 있으므로 `POST /api/v1/payments/orders` 의 `discounts: [{type: "coupon", ref: <쿠폰 id>}]` 가 그대로 먹는다(견적은 `POST /api/v1/payments/quote`). 100% 쿠폰(무료 이용권)이면 결제사를 부르지 않고 바로 `PAID`(`settlement: FREE`). `payment.exempt` 를 가진 계정의 응원은 결제사 없이 `PAID`(`settlement: EXEMPT`, 매출 · 환불에서 빠진다). 이 앱의 자리는 두 곳 — `CheerOrderListener.onPaid` 끝의 `referrals.qualify(order.accountId, "first-payment")`: **돈이 오간 첫 결제**(`settlement == PROVIDER`)가 친구초대의 초대자 보상(`qualifyOn: SIGNAL`, `qualifyEvent: first-payment`)을 연다. 0원 · 면제 주문은 부르지 않는다. 몇 번 불러도 한 번만 먹는다. 그리고 `onRefunded`: 보상이 아직 **보류(`HELD`) · 확인 중(`REVIEW`)** 이고 환불된 주문이 **자격을 만든 그 첫 결제(`cheers.opened_referral`)의 전액 환불**일 때만 `referrals.cancelPending(order.accountId, "refunded")` 로 거둔다. 다른 주문의 환불 · 일부 환불은 건드리지 않는다(보류 7일이 회수를 대신하는 게 관행이다). 이미 지급했으면 아무것도 하지 않는다 - 지급 뒤 회수는 각 모듈의 도구.

구독에도 쿠폰을 쓴다: `POST /api/v1/payments/subscriptions` 의 `discounts` 는 시작 때 **보기만** 하고 쿠폰은 첫 결제(activate)에서 쥔다. 첫 기간이 무료면 카드는 등록받되 결제 없이 넘어가고(`provider = free`), 단계는 시작 때 복사돼 정의를 고쳐도 진행 중인 구독은 그대로다.

**씨앗**(`local` 프로필만, 이름으로 찾아 없는 것만 만든다 — `RewardsSeed`): 쿠폰 정의 「1,000원 할인」 · 「응원 30%」(최대 5,000원) · 「무료 이용권」 · 「첫 달 무료 + 3달 50%」(구독용), 공용 코드 `WELCOME`(→ 응원 30%), 출석 이벤트(매일 10포인트, 누적 3일 「1,000원 할인」), 친구초대 이벤트(초대받은 사람 「응원 30%」, 초대자 500포인트는 그 친구의 첫 응원 결제 뒤 **7일 보류**(청약철회 기간) 끝에 - `holdDays: 7`, 같은 네트워크로 보이면 운영자 승인까지 보류 `reviewSameNetwork: true`, 초대자당 10명 상한). 보류는 10분마다 도는 해제 작업(`scheduler`)이 풀고, 운영자는 초대 내역(`/api/v1/admin/referrals`, 권한 `campaign.manage`)에서 승인 · 취소한다. **이미 만들어진 로컬 DB 의 「친구 초대」 이벤트는 바뀌지 않는다**(씨앗은 이름으로 찾아 없을 때만 만든다) - 새 설정을 보려면 `docker compose down -v` 로 로컬 DB 를 버리고 다시 띄우거나 운영 화면에서 이벤트를 고친다. 같은 네트워크 확인은 `skeleton.web.client-ip.mode` 가 정해져야 믿을 수 있다: 로컬 프로필은 `direct`(`application-local.yml`), 배포는 플랫폼이 넣는 `SKELETON_CLIENT_IP_MODE` - 비어 있으면 기동 때 경고만 남는다(`ReferralDeployGuard`). 포인트 유효기간 · 초대자 보상 시점은 스켈레톤 기본이 아니라 **이 앱의 선택**이다. 시험: `RewardsFlowIntegrationTest`(진짜 PostgreSQL · 보안 체인 · 큐 일꾼, 가짜 결제사).

## 배지 · 업적

한 줄(`badge-jdbc`)과 이 앱의 작은 조각 `badges/BadgesSeed`, 그리고 `NoteService.create` 의 한 줄(`CampaignSignals.report(owner, "notes.created", 노트 개수)`)이 전부다. 흐름: 노트 저장 뒤 **앱이 자기 개수를 세어 현재 값을 보고**(같은 트랜잭션) → 업적 이벤트(`campaign` 종류 `achievement`)가 값에 닿은 단계마다 지급 건을 기록 → 일꾼이 지급기 `badge`(와 같은 단계의 `point`)로 준다 → 「받았어요」 알림(`reward`).
배지와 포인트를 같이 주는 것은 배지 모듈이 아니라 **이벤트 설정의 보상 목록에 나란히 적은 조립**이다 - `badge` 는 `reward` · `coupon` · `point` 를 모른다.

| 경로 | 하는 일 |
|---|---|
| `GET /api/v1/badges/me` · `PATCH /api/v1/badges/me/{awardId} {visible}` | 내 배지(받은 것 · 아직인 것, 숨은 배지는 「?」) · 프로필에 보일지 |
| `GET /api/v1/badges/accounts/{accountId}` | 그 계정의 프로필 배지 - 이 앱이 `skeleton.badge.http.allow-anonymous-read: true` 로 열어 **로그인 없이** 읽는다(모듈 기본은 로그인 필요; 이 경로에만 `skeleton.web.rate-limit` 을 건다; `code · name · description · imageUrl` 넷뿐, 숨긴 배지 · 없는 계정은 빈 목록). **필명 프로필(`author-profile`) 화면에는 붙이지 않는다** - 주소의 계정 id 로 필명이 한 사람으로 이어진다. 공개로 여는 앱은 `skeleton.web.rate-limit` 을 켠다 |
| `/api/v1/admin/badges` · `/badge-awards` | 정의 · 수동 수여 · 회수(사유 필수) · 내역 - 이 앱이 `skeleton.badge.admin.enabled: true` 로 켠다(`badge.manage`, ADMIN 전권만) |

**씨앗**(`local` 프로필만, 코드로 찾아 없는 것만 만든다 - `BadgesSeed`): 배지 「첫 노트」 · 「노트 10개」 · 「얼리 버드」 · 숨은 「비밀 손님」, 업적 이벤트 「노트 업적」(수치 `notes.created`: 1 → 첫 노트, 10 → 노트 10개 + 100포인트). 「얼리 버드」 · 「비밀 손님」은 운영자 수동 수여로 시연한다. 시험: `badges/BadgesFlowIntegrationTest`.

## 워크스페이스 (팀 · 조직)

두 줄(`workspace` · `workspace-jdbc`)과 이 앱의 조각 둘이 전부다 - 모듈 내부는 건드리지 않았고, 이 앱의 선택은 모두 `application.yml` 에 있다(모듈 기본은 중립: 운영자 읽기 · 초대는 꺼짐).

| 선택 | 어디 |
|---|---|
| 운영자 목록 · 상세(`GET /api/v1/admin/workspaces`, 권한 `workspace.read`)를 켠다 | `skeleton.workspace.admin.enabled: true` |
| 멤버를 메일 링크로 초대한다. 링크 = `<link-base-url>/invite?token=…`. 로컬은 프런트 개발 서버(`http://localhost:5173`)고 메일은 mailpit 이 받는다. 배포는 환경변수 `SKELETON_WORKSPACE_MAIL_LINK_BASE_URL`(비면 stage · prod 기동 실패) | `skeleton.workspace.invitation.enabled: true` · `application-local.yml` 의 `mail.link-base-url` |
| 좌석 상한(멤버 + 대기 초대)은 **소유자의 구독**이 정한다: 쓰고 있는 구독(해지 예약 포함)이 있으면 10, 아니면 3. 구독을 읽기만 한다(`PaymentSubscriptions.current`) - 결제 코드는 그대로다. 구독을 안 켠 로컬 · 시험은 모두 3 | `workspace/SubscriptionSeatPolicy` |
| 노트가 팀에 속할 수 있다 - `notes.workspace_id`(null = 내 노트) | `V20261030140000__notes_workspace.sql` |

**노트 API 의 헤더 동작**: `X-Workspace-Id` 가 **없으면 예전 그대로** 내 노트다(`workspace_id` 가 없는 것만 - 내가 쓴 팀 노트는 내 목록에 안 나온다). **있으면** 그 워크스페이스의 노트다: 요청마다 멤버십을 DB 로 확인하고(멤버가 아니거나 없는 · 닫힌 워크스페이스는 모두 404 `WORKSPACE.NOT_FOUND`, 추방은 다음 요청부터 반영), 멤버는 모두 읽고 쓰고, 지우기는 **쓴 사람 · ADMIN 이상**(그 밖은 403 `WORKSPACE.FORBIDDEN`). 팀 노트의 `ownerId` 는 쓴 사람이다. 요약(`/summary`) · 내보내기도 같은 상자를 본다(내보내기 결과 파일과 완료 알림은 **요청한 사람**의 것이고, 그 사이 추방됐으면 잡이 끝난다). 알림(`NOTE_*`)은 **그 일을 한 사람**에게 가고 팀 노트 알림에는 링크가 없다(쓴 사람이 추방돼도 제목이 새지 않는다). 첨부와 업적 개수는 내 노트만이다: 팀 노트 `PUT` 에 `attachmentKey` 를 보내면 400 `NOTES.TEAM_ATTACHMENT_UNSUPPORTED`, 팀 노트에 이미 있는 첨부는 수정해도 그대로다.

**씨앗**(`local` 프로필만, slug 로 찾아 없으면 만든다 - `workspace/WorkspaceSeed`): `user@example.com`(`acc_user`)이 소유자인 「Example Team」(slug `example-team`). 시험: `notes/WorkspaceNotesIntegrationTest`(남의 팀 노트 안 보임 · 추방 직후 404 · 헤더 없을 때 그대로 · 미리보기 공개 경로), `workspace/SubscriptionSeatPolicyTest`.
## 리뷰 · 평점 (`/api/v1/reviews`)

한 줄(`review-jdbc`)과 이 앱의 작은 조각 `reviews/`·`orders/` 가 전부다. 상품(`catalog`)에 **산 사람만** 별점 · 본문 · 사진 리뷰를 쓴다(`skeleton.review.require-purchase: true` · `body-min-length: 10` · `max-photos: 5`, 대상 종류 `[product]`).
- **대상**: `SampleReviewTargets` 가 카탈로그 상품 코드를 확인한다(`review` 는 상품을 모른다).
- **구매 신호**: 결제 장부는 `PaymentOrderListener` 빈을 하나만 받으므로 `orders/SampleOrderListener` 가 응원 기록(`CheerOrderListener`)과 리뷰 구매 신호(`reviews/ReviewPurchaseSignals`) 둘을 부른다. 구독 주문(`itemRef` = 플랜 코드)과 단건 주문(`itemRef` = 가격 코드)의 가격 코드로 카탈로그 표에서 상품 코드를 찾아 `ReviewPurchases.record(계정, product:<상품>, "order:<주문 id>")`, 전액 환불이면 `revoke`(일부 환불은 그대로). 쿠폰으로 0원이 된 주문도 상품을 받았으니 구매다.
- **보상**: `ReviewRewards`(`ReviewEventListener`)가 리뷰를 처음 공개할 때 계정의 리뷰 개수를 `CampaignSignals.report(계정, "reviews.written", 개수)` 로 보고한다(쓰기 트랜잭션 안 - 던지면 쓰기도 실패). 업적 이벤트 「리뷰 업적」(1 → 배지 「첫 리뷰」 + 100포인트)은 씨앗 `ReviewsSeed` 의 설정이다.
- **사진**: 저장 키만 싣는다. `SampleReviewPolicy.canAttach` 가 `StorageKeyOwnership` 으로 호출자 자신이 올린 키만 허용한다(`OPAQUE` 키라 계정 id 가 없다). 보기는 서버가 짧은 서명 주소로 `302` 한다.
- **읽기·운영**: 상품 요약 · 목록은 `allow-anonymous-read: true` 로 로그인 없이 읽는다(응답에 계정 id 없음 - 속도 제한은 배지 프로필 경로와 같은 `skeleton.web.rate-limit.path-patterns` 목록에 읽기 셋이 들어 있다). 운영 경로(`skeleton.review.admin.enabled: true`)는 신고 큐 · 숨김 · 되살리기 · 문제없음(`review.moderate`, `MODERATOR` 기본 포함)과 답글(`review.reply`, ADMIN 전권만).

**씨앗**(`local` 프로필만, 없는 것만 만든다 - `ReviewsSeed`): 시드 계정 `acc_user` 의 `product:basic` 구매 줄(결제 키 없이도 리뷰를 써 볼 수 있게), 배지 「첫 리뷰」, 업적 이벤트 「리뷰 업적」.

시험: `reviews/ReviewsFlowIntegrationTest`(구매 신호 → 쓰기 · 비로그인 읽기 · 환불 → 배지 내림 · 리뷰 → 배지 + 100포인트 · 사진 키 소유 · 운영 경로).

## 검색 (`/api/v1/search`)

한 줄(`search-jdbc`)과 이 앱의 작은 조각 `notes/NoteSearch.kt`(`NoteSearchSource` + `Note.toSearchDocument`), `NoteService` 의 세 줄이 전부다. `NoteService` 가 노트를 만들 때 · 고칠 때 · 지울 때 **같은 트랜잭션 안에서** `note` 문서를 색인에 넣고 빼므로(롤백되면 색인도 없다) 커밋과 함께 검색에 보인다. 문서의 주인은 작성자라 **남의 검색에는 나오지 않는다**. 상품은 `catalog` 가 상품을 만들 때 · 고칠 때 저절로 `product` 문서를 넣고(씨앗도 `CatalogAdmin` 을 지나므로 들어간다), 판매를 멈추면 뺀다 - 앱 코드는 없다.
검색은 **로그인한 사람만** 읽는다(`skeleton.search.http.allow-anonymous-read` 를 켜지 않았다 - 켜면 주인 없는 문서, 곧 상품만 비로그인에게 보인다). 운영 경로(문서 종류별 개수 · 재색인, `search.manage` 는 기본 역할 없이 ADMIN 전권만)는 이 앱이 `skeleton.search.admin.enabled: true` 로 켠다.

| 경로 | 설명 |
|---|---|
| `GET /api/v1/search?q=&types=note,product&scope=&sort=relevance\|recent&page=&size=` | 두 글자 이상 덩어리로 찾는다(`방끈` ⊂ `가방끈`). 각 줄에 제목 · 발췌와 일치 위치 |
| `GET /api/v1/admin/search/types` · `POST /api/v1/admin/search/rebuild {"type"}` | 종류별 색인 문서 수 · 재색인(작업 `search-rebuild`) - 검색을 붙이기 전에 있던 노트 · 상품은 이걸로 처음 채운다 |

**범위 한계**: note 검색 문서의 주인은 작성자라 팀(workspace) 멤버는 서로의 팀 노트를 검색에서 못 본다 — 넓히기는 다음 조각.

시험: `search/SearchFlowIntegrationTest`(주인에게만 · 고치기 · 지우기 · 상품 판매 중지/재개 · 재색인).

## 구독 — 플랜과 자동결제 (`/api/v1/payments/subscriptions`)

`payment` 의 구독(`docs/modules/payment.md` 「구독」)을 이 앱이 켠다. 플랜은 코드가 아니라 `catalog` 의 표가 낸다 - 씨앗 `catalog/CatalogSeed`(모든 프로필, 없는 코드만): basic 3,900 · standard 6,900 · pro 9,900원(월, 코드 `basic` · `standard` · `pro`)과 연 39,000 · 69,000 · 99,000원(`*-yearly`), 달러 월 $3 · $5 · $8 · 연 $30 · $50 · $80(`*-usd` · `*-yearly-usd`). 운영자가 화면에서 가격을 더하고 행사가를 붙인다(`/api/v1/admin/catalog`, 권한 `catalog.manage`). 방문 나라가 통화를 정한다(KR → 토스 → KRW, 그 밖 → Paddle → USD, `skeleton.payment.routing.provider-currencies`). `catalog/SampleCatalog` 가 `PaymentCatalog` 하나로 응원하기와 표의 단건을 `itemType` 으로 잇는다. 주기는 `skeleton.sample.subscription.period` · `yearly-period`. 코드는 `subscription/SubscriptionConfiguration.kt` 한 빈 — `SubscriptionRenewalGuard`(정상 `ACTIVE` 계정만 갱신 — 정지 · 삭제 유예 · 없는 계정은 그 기간 끝에 구독이 끝난다).

| 값 | 이름 |
|---|---|
| 켜기 | `SKELETON_PAYMENT_SUBSCRIPTION_ENABLED=true` (로컬 프로필은 토스를 켰을 때 함께 켠다) |
| 빌링키 암호화 키 | `SKELETON_CRYPTO_KEYS_MAIN` — base64 로 인코딩한 32바이트(비밀, 배포 선언의 `secrets:` 에 이름을 더한다). 켰는데 없으면 기동 실패 |
| 주기 | `SKELETON_SAMPLE_SUBSCRIPTION_PERIOD` (ISO-8601, 기본 `P1M`; 로컬 프로필 `PT3M`) · 연 가격 `SKELETON_SAMPLE_SUBSCRIPTION_YEARLY_PERIOD` (기본 `P1Y`; 로컬 `PT36M`) - 씨앗을 뿌릴 때만 먹는다(가격은 만든 뒤 못 고친다) |

로컬은 갱신 · 재시도를 몇 분 안에 볼 수 있다: 주기 3분, 거절되면 1분 뒤 다시(`skeleton.payment.subscription.renewal.retry-interval`), 세 번 거절되면 구독이 끝난다. 토스 테스트 키로 카드 등록 창은 인증 번호 `000000` 과 국내 카드만 받는다([real-provider-setup §11.7](real-provider-setup.md)).
`CheerOrderListener` 는 `itemType == cheer` 인 주문만 응원 기록으로 남긴다 — 구독 결제는 응원이 아니다.

## 돌려 보기

```bash
scripts/dev-sample.sh        # DB + 로컬 S3 → 백엔드(8080) → 옆의 ../react-skeleton/apps/sample (5173). 끝내기: Ctrl-C, 컨테이너는 scripts/dev.sh down
APP=sample scripts/dev.sh    # 백엔드(+프론트 WEB_DIR)만 같은 방식으로
./gradlew :apps:sample:test  # 통합 테스트 (Docker 필요)
```

포트가 겹치면 `SERVER_PORT` · `DB_PORT` · `S3_PORT` · `MAIL_SMTP_PORT`(1025) · `MAIL_HTTP_PORT`(8025) 환경변수로 바꾼다(compose 와 앱 yml 이 같은 이름을 읽는다). e2e 용 비대화형 시작/종료는 `scripts/sample-e2e-backend.sh start|stop` — **메일 수신기(mailpit)도 함께 올린다**: 앱은 `localhost:$MAIL_SMTP_PORT` 로 보내고 받은 메일은 `http://localhost:$MAIL_HTTP_PORT/api/v1/messages`(JSON, 한 통 `/api/v1/message/<ID>`, 비우기 `DELETE /api/v1/messages`)로 읽는다 — 프런트 e2e 는 자기 mailpit 을 띄우지 않고 이것을 쓴다
(전용 compose 프로젝트 · 깨끗한 DB, `/health` 가 UP 일 때 `READY http://localhost:<포트>` 를 찍는다).

## 이 기능 조각은 이렇게 조립됐다 (새 기능을 만들 때 이 순서로)

경로는 `apps/sample/src/main/kotlin/dev/sumin/skeleton/app/sample/notes/` (아래 `…/`) 기준이다.

1. **마이그레이션** — `./gradlew newMigration -Pname=create_notes -Pmodule=apps/sample` → `apps/sample/src/main/resources/db/migration/postgresql/V…__create_notes.sql`. UTC 타임스탬프 버전, `timestamptz`, `deleted_at` 은 audit 계약의 열.
2. **엔티티** — `…/Note.kt`: Spring Data JDBC `@Table`, `persistence-jdbc` 의 `JdbcAuditable` + `AuditTimestamps`(created/updated 를 콜백이 채운다). id 는 앱이 만든 시간순 UUIDv7(`SortableIds.newUuid()`).
3. **저장소** — `…/NoteRepository.kt`: `CrudRepository` + 한 쿼리로 되는 목록 검색(`:p IS NULL OR …`). 소유자 조건(`owner_id`)을 모든 조회에 건다 — 남의 노트는 "없는 것"과 같다.
4. **서비스** — `…/NoteService.kt`: 모든 규칙(소유자 확인 · 알림 · 잡 · 첨부 정리). 쓰기는 한 트랜잭션이라 알림 저장과 잡 등록이 노트 저장과 함께 커밋/롤백된다.
5. **DTO · 검증** — `…/NoteDtos.kt`(Bean Validation), `…/OwnAttachmentKey.kt`(구조 규칙을 커스텀 제약으로: 어기면 `errors[attachmentKey]`), `…/NoteErrors.kt`(`NOTES.NOT_FOUND`).
6. **컨트롤러** — `…/NoteController.kt`: HTTP 변환만. `@CreatedOperation` + `@IdempotentOperation`, `@AcceptedOperation`, `@NoContentOperation`, `PageQuery` → platform 표준 envelope.
7. **알림** — `…/NoteNotifier.kt`: `NotificationPublisher.publish` 한 번이 받은편지함 저장 + SSE 전달. 계약(토픽 · 타입 · payload · `link` · `collapseKey`)이 이 파일 맨 위 주석에 있다 - 수정 알림은 같은 노트를 읽기 전에 또 고치면 한 줄로 접히고, 설정 화면에서 끌 수 있다(`skeleton.notification.preferences.types`).
8. **잡** — `…/NoteExportJobHandler.kt`(`JobHandler`, 멱등) + `NoteService.startExport`(같은 트랜잭션에서 `JobQueue.enqueue`). 영구 실패는 `PermanentJobFailureException`.
9. **설정** — `apps/sample/src/main/resources/application.yml`: 모듈 기본값과 다른 값만(잡 폴링 1s, 첨부 규칙). 로컬 S3 는 `application-local.yml`.
10. **테스트** — `apps/sample/src/test/…/notes/`: `SampleIntegrationTest`(진짜 PostgreSQL · 보안 체인 · Flyway, 저장소만 메모리 `FakePresignedStorage`) 위에 `NoteCrudIntegrationTest` · `NoteListIntegrationTest` · `NoteOwnershipIntegrationTest` · `NoteNotificationIntegrationTest` · `NoteExportIntegrationTest`. 규칙마다 테스트를 먼저 썼다(빨강 → 초록).

## API 키 (`/api/v1/api-keys`)

두 줄(`api-key` · `api-key-jdbc`)과 이 앱의 작은 조각 `notes/NoteApiKeyScopes.kt`(범위 `notes.read` · `notes.write` 선언), `NoteController` 의 `@ApiKeyScope` 표시 여섯 줄이 전부다.
키로 닿는 곳은 **표시한 곳뿐**이다(기본 거부): 읽기 `GET /api/v1/notes`(목록) · `…/summary` · `…/{id}` 는 `notes.read`, 만들기 · 고치기 · 지우기는 `notes.write`. 표시 없는 경로(내보내기 · 계정 · 결제 …)와 운영 경로는 키로 열리지 않는다(`403 API_KEY.NOT_ALLOWED`) - 열고 싶으면 그 핸들러에 `@ApiKeyScope` 한 줄을 단다.
이 앱의 선택(`application.yml` 의 `skeleton.api-key`): 토큰 접두사 `notes`(`notes_…`) · 키 하나당 1분 60번 · 만료일 필수에 최대 365일 · 운영 경로 켬(`api-key.read` · `api-key.revoke` 는 기본 역할 없이 ADMIN 전권만).

| 메서드 · 경로 | 하는 일 |
|---|---|
| `POST /api/v1/api-keys {name, scopes[], expiresAt, currentPassword}` · `GET` · `DELETE …/{id}` · `GET …/scopes` | 사용자 설정 화면의 발급(다시 인증) · 내 목록 · 회수 · 고를 수 있는 범위 |
| `GET /api/v1/admin/api-keys?ownerId&status&cursor` · `POST …/{id}/revoke {reason}` | 운영 화면의 전체 목록 · 사유를 적는 회수(주인 목록엔 `revokedBy: OPERATOR`) |

시험: `apikey/ApiKeyJourneyIntegrationTest`(키로 노트 읽기 200 · 쓰기 403 · 계정 403 → 회수 뒤 401, 운영자 회수 → 주인 목록 `OPERATOR`).

## 이 샘플이 하지 않는 것

- 노트 시드 데이터 없음 — 빈 상태가 정직하게 보인다.
- 응원하기는 알림 · 메일을 보내지 않는다 — `PaymentOrderListener` 는 주문 줄 락 안에서 돌아 짧아야 한다. 결제 알림이 필요하면 `NoteNotifier` 처럼 `NotificationPublisher` 를 부르거나 작업으로 넘긴다(스펙 §4.2).
- 구독 · 정기결제 · 영수증 · 세금계산서는 없다(결제 장부 1단계는 단건 결제 + 환불).
- 첨부는 노트 하나에 파일 하나. 목록 · 버전 · 공유는 도메인이 커질 때의 일이다.
- 인증은 스켈레톤 기본(시드 계정 · 15분 액세스 토큰). 실제 계정 저장소는 `AuthAccountRepository` 빈을 앱이 둔다(README "Protected profiles").
- PostgreSQL 전용(마이그레이션 · 쿼리의 `ILIKE` · `FILTER`). 그래서 `scripts/new-project.sh --with-sample` 은 `--db mysql` 과 같이 못 쓴다.

## 새 프로젝트에 가져가기

`scripts/new-project.sh <dir> <package> <prefix> <Class>` 는 샘플을 **넣지 않는다**. 예시를 같이 보고 싶으면 `--with-sample`(샘플이 쓰는 모듈이 닫힘에 더해진다).
샘플 없이 시작한 뒤에는 이 문서의 1–10 순서로 자기 도메인을 `apps/api` 에 얹는다 — 샘플 코드를 복사해 이름만 바꿔도 된다.

## 관리자 대시보드

`dashboard` 한 줄과 `application.yml` 의 `skeleton.dashboard`(`admin.enabled: true` · `zone: Asia/Seoul` · `currency: KRW`)가 이 앱의 선택이다. 카드는 이 앱이 이미 가진 모듈이 기여한다 — 신규 가입(`account-jdbc`), 활성 이용자(`auth-session-jdbc`), 순매출 · 결제 건수(`payment-jdbc`), 그리고 가입 → 돌아옴 → 결제 퍼널. `ADMIN` 은 권한 `dashboard.read` 를 늘 가진다: `GET /api/v1/admin/dashboard`. 앱 통계 탭은 앱 코드 한 파일(`SampleDashboardConfiguration`: 게시판별 글 수 · 반응 종류별 수 — 예시일 뿐, 남의 모듈 표를 읽는 것은 정석이 아니다)이다. 키 없이 띄우면 Cloudflare · GA 카드는 「연결하면 보입니다」이고 우리 DB 숫자는 나온다(`SKELETON_DASHBOARD_CLOUDFLARE_*` · `SKELETON_DASHBOARD_GA_*` 를 넣으면 채워진다). 문서: [modules/dashboard.md](modules/dashboard.md).
