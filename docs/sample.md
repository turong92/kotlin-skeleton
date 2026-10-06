# 샘플 앱 "Notes" — 새 기능을 어떻게 얹는지 보여 주는 정본

`apps/sample` 은 **제품 모양의 작은 앱**이다: 로그인한 사람이 노트(제목 · 본문 · 상태 · 고정 · 첨부)를 관리한다.
스타터(`apps/api`)에 모듈 몇 줄과 도메인 하나(`notes`)만 얹었고, 모듈 내부는 건드리지 않았다. 새 부업 프로젝트에서 기능 하나를 만들 때 **그대로 따라 하는 예시**다.
짝 프론트는 react-skeleton 의 `apps/sample` 이다(화면 · 컴포넌트 매핑은 그쪽 README).

| 이 화면/기능이 필요하면 | 이 샘플에서 쓴 모듈 |
|---|---|
| 로그인 · JWT · 가입 · 세션 · 탈퇴 | `auth` + `account-jdbc`(진짜 계정 — 가입 · 이메일 확인 · 재설정 · 삭제) + `auth-session-jdbc`(리프레시 토큰) + `auth-magic-link` + `notification-mail`(메일은 로컬에서 compose `mail` 프로필의 mailpit `http://localhost:8025`). 로컬 · 시험 시드 계정 3 개 — `application-local.yml` / `src/test/resources/test-seeds.yml` 의 `skeleton.account.seed.accounts`: `user@example.com`(`acc_user`), `admin@example.com`(`acc_admin`, ADMIN), **`moderator@example.com`(`acc_moderator`, MODERATOR)** — 비밀번호는 모두 `password`. 흐름 전체는 `AccountJourneyIntegrationTest` · [계정 수명주기](accounts.md) |
| 표준 응답 · 에러 · 검증 · 페이지 | `platform` |
| 두 번 눌러도 한 번 만들기 | `idempotency` (`@IdempotentOperation`) |
| 알림 받은편지함 + 실시간 | `notification` + `notification-jdbc` + `notification-sse` |
| 첨부 업로드 (브라우저가 S3 로 직접) | `storage` + `storage-s3` |
| 오래 걸리는 일(내보내기) | `job-queue-jdbc` |
| 게시판(글 · 대댓글 · 공감 같은 반응 종류) | `board` + `board-jdbc` (아래 "게시판") |
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
| `/api/v1/notifications` · `…/{eventId}/read` · `…/read-all` · `/sse` | 받은편지함 · 실시간 (`notification` · `notification-sse` 모듈이 연다) | |
| `/api/v1/storage/presign` · `presign-download` · `multipart/*` | 첨부 업로드 · 다운로드 (`storage` 모듈이 연다) | |

`Note` = `{id, title, body, status: DRAFT|ACTIVE|ARCHIVED, pinned, attachmentKey, attachmentName, createdAt, updatedAt}` (시각은 ISO-8601 `…Z`).
검증 실패는 400 `COMMON.VALIDATION_FAILED` + `errors: [{field, code, message}]` — `field` 는 JSON 속성 이름이다. 알 수 없는 `status` 값이나 `?status=NOPE` 는 400.
알림: 토픽 `notes`, 타입 `NOTE_CREATED | NOTE_UPDATED | NOTE_DELETED | NOTE_EXPORTED`, `payload.noteId`(딥링크) · `payload.noteTitle`. 받는 사람은 노트 주인뿐이다(SSE 도 그 사람에게만 흐른다).
첨부 규칙(앱의 선택, `application.yml`): png · jpg · gif · webp · pdf · txt, 5 MB 이하. 첨부 키는 `uploads/<내 계정 id>/…` 아래여야 한다(아니면 `errors[attachmentKey]`).

## 게시판 (`/api/v1/boards`)

노트와 별개로 `board` + `board-jdbc` 두 줄을 더한 데모다 — 이 앱 코드는 한 줄도 더하지 않았고 **설정(`application.yml`)과 시드 계정(`application-local.yml`)만** 이 앱의 선택이다:

- `skeleton.board.reaction.types: [LIKE, DISLIKE, EMPATHY]` — 모듈 기본은 `[LIKE, DISLIKE]`. 공감(`EMPATHY`)은 이 한 줄이 전부다 (코드 · 스키마 변경 없음). `GET /api/v1/boards/config` 가 프론트에 종류를 알려 준다.
- `skeleton.board.seed-boards` — 기동할 때 `general`(General) 게시판을 만든다.
- 운영자: `moderator@example.com` / `password` (`MODERATOR` 역할 — `skeleton.board.moderator-role` 의 기본). 글 숨기기 · 고정 · 남의 글/댓글 삭제 · 게시판 만들기. 일반 사용자는 `user@example.com` · `admin@example.com`.
- 댓글 알림: 내 글에 댓글이 달리면 토픽 `board`, 타입 `comment.created`(답글은 `comment.replied`)가 받은편지함 · SSE 로 온다 — `notification` 이 클래스패스에 있어서 켜진 것이다.
- 글 · 댓글 만들기는 `Idempotency-Key` 필수 (`idempotency` 가 있어서).
- 경로 · 모양 · 에러 코드 · 결정 근거: [board](modules/board.md). 통합 테스트: `apps/sample/src/test/…/board/BoardIntegrationTest.kt`.

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
2. **엔티티** — `…/Note.kt`: Spring Data JDBC `@Table`, `persistence-jdbc` 의 `JdbcAuditable` + `AuditTimestamps`(created/updated 를 콜백이 채운다). id 는 DB 가 정한다.
3. **저장소** — `…/NoteRepository.kt`: `CrudRepository` + 한 쿼리로 되는 목록 검색(`:p IS NULL OR …`). 소유자 조건(`owner_id`)을 모든 조회에 건다 — 남의 노트는 "없는 것"과 같다.
4. **서비스** — `…/NoteService.kt`: 모든 규칙(소유자 확인 · 알림 · 잡 · 첨부 정리). 쓰기는 한 트랜잭션이라 알림 저장과 잡 등록이 노트 저장과 함께 커밋/롤백된다.
5. **DTO · 검증** — `…/NoteDtos.kt`(Bean Validation), `…/OwnAttachmentKey.kt`(구조 규칙을 커스텀 제약으로: 어기면 `errors[attachmentKey]`), `…/NoteErrors.kt`(`NOTES.NOT_FOUND`).
6. **컨트롤러** — `…/NoteController.kt`: HTTP 변환만. `@CreatedOperation` + `@IdempotentOperation`, `@AcceptedOperation`, `@NoContentOperation`, `PageQuery` → platform 표준 envelope.
7. **알림** — `…/NoteNotifier.kt`: `NotificationPublisher.publish` 한 번이 받은편지함 저장 + SSE 전달. 계약(토픽 · 타입 · payload)이 이 파일 맨 위 주석에 있다.
8. **잡** — `…/NoteExportJobHandler.kt`(`JobHandler`, 멱등) + `NoteService.startExport`(같은 트랜잭션에서 `JobQueue.enqueue`). 영구 실패는 `PermanentJobFailureException`.
9. **설정** — `apps/sample/src/main/resources/application.yml`: 모듈 기본값과 다른 값만(잡 폴링 1s, 첨부 규칙). 로컬 S3 는 `application-local.yml`.
10. **테스트** — `apps/sample/src/test/…/notes/`: `SampleIntegrationTest`(진짜 PostgreSQL · 보안 체인 · Flyway, 저장소만 메모리 `FakePresignedStorage`) 위에 `NoteCrudIntegrationTest` · `NoteListIntegrationTest` · `NoteOwnershipIntegrationTest` · `NoteNotificationIntegrationTest` · `NoteExportIntegrationTest`. 규칙마다 테스트를 먼저 썼다(빨강 → 초록).

## 이 샘플이 하지 않는 것

- 노트 시드 데이터 없음 — 빈 상태가 정직하게 보인다.
- 첨부는 노트 하나에 파일 하나. 목록 · 버전 · 공유는 도메인이 커질 때의 일이다.
- 인증은 스켈레톤 기본(시드 계정 · 15분 액세스 토큰). 실제 계정 저장소는 `AuthAccountRepository` 빈을 앱이 둔다(README "Protected profiles").
- PostgreSQL 전용(마이그레이션 · 쿼리의 `ILIKE` · `FILTER`). 그래서 `scripts/new-project.sh --with-sample` 은 `--db mysql` 과 같이 못 쓴다.

## 새 프로젝트에 가져가기

`scripts/new-project.sh <dir> <package> <prefix> <Class>` 는 샘플을 **넣지 않는다**. 예시를 같이 보고 싶으면 `--with-sample`(샘플이 쓰는 모듈이 닫힘에 더해진다).
샘플 없이 시작한 뒤에는 이 문서의 1–10 순서로 자기 도메인을 `apps/api` 에 얹는다 — 샘플 코드를 복사해 이름만 바꿔도 된다.
