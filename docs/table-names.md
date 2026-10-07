# 모듈 테이블 이름 — `skeleton_` 접두사를 뗀 이름표

**결정(주인)**: 모듈 테이블에는 `skeleton_` 접두사를 붙이지 않는다 — 이름이 겹치는 일은 각 프로젝트가 알아서 한다. 이름은 옛 이름에서 **`skeleton_` 토큰만 뗀 것**이다(인덱스 `idx_skeleton_x` → `idx_x`, 제약 `uq_skeleton_x` → `uq_x`). 아직 어디에도 배포되지 않아 마이그레이션 파일을 제자리에서 고쳤다(이름 바꾸는 마이그레이션 없음). `RepositoryMigrationsTest`(`MigrationFileRules`)가 마이그레이션에 `skeleton_` 로 시작하는 이름이 다시 생기면 빌드를 깬다.

## 예외 — 단순히 "접두사 제거"가 아닌 이름

- **데이터베이스 객체: 없다.** 아래 표의 모든 새 이름은 옛 이름에서 `skeleton_` 만 뗀 것이다. PostgreSQL 이 자동으로 붙이는 이름(`<테이블>_pkey` · `<테이블>_<열>_fkey` · `<테이블>_<열>_seq`)도 테이블 이름을 따라 접두사가 사라진다.
- **테이블이 아니라서 바꾸지 않은 것**: 리프레시 쿠키의 기본 이름 `skeleton_refresh`(`skeleton.auth-session.cookie.name`) — DB 이름이 아니다. 찍은 프로젝트에서는 `rename-skeleton.sh` 가 `<접두사>_refresh` 로 바꾼다.
- **마이그레이션 파일 이름의 설명 부분**(`V…__skeleton_accounts.sql` → `V…__accounts.sql`)도 같은 규칙으로 바꿨다. 버전(시각) 숫자는 그대로다. Flyway 는 설명과 체크섬을 이력에 적으므로 **이미 옛 마이그레이션을 적용한 로컬 DB 는 `flywayClean` 으로 다시 만든다**(배포된 곳은 없다).
- **옛 이름이 없는 것(접두사를 단 적이 없다)**: `account-jdbc` 의 표 `account_blocks`(재가입 차단 해시) · `account_locks`(주기 정리 임대)와 열 `accounts.erased_at` — 위 이름표에 행이 없는 이유다. `accounts.status` 에 `ERASED` 값이 늘었다.
- 예약어: `jobs` · `alerts` · `boards` · `accounts` · `notification_inbox` · `auth_sessions` · … 어느 것도 PostgreSQL · MySQL 8.4 의 예약어가 아니다 — 따옴표 없이 두 방언 시험이 통과한다.

## 이름표 (옛 → 새)

| 모듈 | 종류 | 옛 이름 | 새 이름 | 방언 |
|---|---|---|---|---|
| account-jdbc | table | `skeleton_account_audit` | `account_audit` | mysql · postgresql |
| account-jdbc | table | `skeleton_account_challenges` | `account_challenges` | mysql · postgresql |
| account-jdbc | table | `skeleton_account_identities` | `account_identities` | mysql · postgresql |
| account-jdbc | table | `skeleton_account_roles` | `account_roles` | mysql · postgresql |
| account-jdbc | table | `skeleton_account_tokens` | `account_tokens` | mysql · postgresql |
| account-jdbc | table | `skeleton_accounts` | `accounts` | mysql · postgresql |
| account-jdbc | index | `idx_skeleton_account_audit_account` | `idx_account_audit_account` | mysql · postgresql |
| account-jdbc | index | `idx_skeleton_account_audit_at` | `idx_account_audit_at` | mysql · postgresql |
| account-jdbc | index | `idx_skeleton_account_challenges_account` | `idx_account_challenges_account` | mysql · postgresql |
| account-jdbc | index | `idx_skeleton_account_challenges_expires` | `idx_account_challenges_expires` | mysql · postgresql |
| account-jdbc | index | `idx_skeleton_account_challenges_owner` | `idx_account_challenges_owner` | mysql · postgresql |
| account-jdbc | index | `idx_skeleton_account_identities_account` | `idx_account_identities_account` | mysql · postgresql |
| account-jdbc | index | `idx_skeleton_account_roles_role` | `idx_account_roles_role` | mysql · postgresql |
| account-jdbc | index | `idx_skeleton_account_tokens_expires` | `idx_account_tokens_expires` | mysql · postgresql |
| account-jdbc | index | `idx_skeleton_account_tokens_owner` | `idx_account_tokens_owner` | mysql · postgresql |
| account-jdbc | index | `idx_skeleton_accounts_due` | `idx_accounts_due` | mysql · postgresql |
| account-jdbc | index | `uq_skeleton_account_identities_subject` | `uq_account_identities_subject` | mysql |
| account-jdbc | constraint | `uq_skeleton_account_identities_subject` | `uq_account_identities_subject` | postgresql |
| alert-jdbc | table | `skeleton_alerts` | `alerts` | mysql · postgresql |
| alert-jdbc | index | `idx_skeleton_alerts_occurred` | `idx_alerts_occurred` | mysql · postgresql |
| alert-jdbc | constraint | `uq_skeleton_alerts_kind_key` | `uq_alerts_kind_key` | mysql · postgresql |
| auth-session-jdbc | table | `skeleton_auth_refresh_tokens` | `auth_refresh_tokens` | mysql · postgresql |
| auth-session-jdbc | table | `skeleton_auth_sessions` | `auth_sessions` | mysql · postgresql |
| auth-session-jdbc | index | `idx_skeleton_auth_refresh_tokens_session` | `idx_auth_refresh_tokens_session` | mysql · postgresql |
| auth-session-jdbc | index | `idx_skeleton_auth_sessions_account` | `idx_auth_sessions_account` | mysql · postgresql |
| board-jdbc | table | `skeleton_board_comments` | `board_comments` | mysql · postgresql |
| board-jdbc | table | `skeleton_board_post_attachments` | `board_post_attachments` | mysql · postgresql |
| board-jdbc | table | `skeleton_board_posts` | `board_posts` | mysql · postgresql |
| board-jdbc | table | `skeleton_board_reactions` | `board_reactions` | mysql · postgresql |
| board-jdbc | table | `skeleton_boards` | `boards` | mysql · postgresql |
| board-jdbc | index | `idx_skeleton_board_comments_roots` | `idx_board_comments_roots` | mysql · postgresql |
| board-jdbc | index | `idx_skeleton_board_comments_roots_by_reactions` | `idx_board_comments_roots_by_reactions` | mysql · postgresql |
| board-jdbc | index | `idx_skeleton_board_comments_thread` | `idx_board_comments_thread` | mysql · postgresql |
| board-jdbc | index | `idx_skeleton_board_posts_author` | `idx_board_posts_author` | mysql · postgresql |
| board-jdbc | index | `idx_skeleton_board_posts_by_comments` | `idx_board_posts_by_comments` | mysql · postgresql |
| board-jdbc | index | `idx_skeleton_board_posts_by_reactions` | `idx_board_posts_by_reactions` | mysql · postgresql |
| board-jdbc | index | `idx_skeleton_board_posts_latest` | `idx_board_posts_latest` | mysql · postgresql |
| board-jdbc | index | `idx_skeleton_board_reactions_by_type` | `idx_board_reactions_by_type` | mysql · postgresql |
| job-queue-jdbc | table | `skeleton_jobs` | `jobs` | mysql · postgresql |
| job-queue-jdbc | index | `idx_skeleton_jobs_claim` | `idx_jobs_claim` | mysql · postgresql |
| job-queue-jdbc | index | `idx_skeleton_jobs_running` | `idx_jobs_running` | mysql · postgresql |
| notification-jdbc | table | `skeleton_notification_inbox` | `notification_inbox` | mysql · postgresql |
| notification-jdbc | index | `idx_skeleton_notification_inbox_recipient_read_created` | `idx_notification_inbox_recipient_read_created` | mysql · postgresql |
| notification-jdbc | index | `idx_skeleton_notification_inbox_recipient_topic_created` | `idx_notification_inbox_recipient_topic_created` | mysql · postgresql |

## 마이그레이션 파일 이름

| 모듈 | 옛 | 새 |
|---|---|---|
| account-jdbc | `V20261005223426__skeleton_accounts.sql` | `V20261005223426__accounts.sql` |
| account-jdbc | `V20261006143346__skeleton_account_challenges.sql` | `V20261006143346__account_challenges.sql` |
| alert-jdbc | `V20261005181125__skeleton_alerts.sql` | `V20261005181125__alerts.sql` |
| auth-session-jdbc | `V20261005220927__skeleton_auth_sessions.sql` | `V20261005220927__auth_sessions.sql` |
| board-jdbc | `V20261005142218__skeleton_board.sql` | `V20261005142218__board.sql` |
| job-queue-jdbc | `V20260910010000__skeleton_jobs.sql` | `V20260910010000__jobs.sql` |
| notification-jdbc | `V20260617010000__skeleton_notification_inbox.sql` | `V20260617010000__notification_inbox.sql` |

## 이미 옛 이름으로 찍은 프로젝트

스켈레톤을 `skeleton_` 접두사 시절에 찍어 DB 를 만든 프로젝트는 마이그레이션을 고칠 수 없다(적용된 파일은 고치지 않는다). **이름 바꾸는 마이그레이션을 새로 더한다** — 예(PostgreSQL):

```sql
alter table skeleton_accounts rename to accounts;
alter index idx_skeleton_accounts_due rename to idx_accounts_due;
alter table skeleton_accounts rename constraint skeleton_accounts_pkey to accounts_pkey;
```

MySQL 은 `rename table skeleton_accounts to accounts;` 와 인덱스는 `alter table … rename index idx_skeleton_x to idx_x;`. 위 표의 줄마다 한 문장씩이다. 그 뒤 앱의 SQL 이 새 이름을 쓰도록 스켈레톤의 해당 모듈 소스를 다시 가져온다(`CHANGELOG.md`).
