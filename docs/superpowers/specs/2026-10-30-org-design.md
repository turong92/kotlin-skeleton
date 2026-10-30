# 워크스페이스(팀 · 조직) — 설계 초안

> 2026-10-30. 요청: 사용자가 워크스페이스를 만들고, 메일로 멤버를 초대하고(만료 · 재발송 · 취소), 소유자 · 관리자 · 멤버 역할로 나눠 쓰고, 탈퇴 · 추방 · 소유권 이전을 한다. 요청마다 「지금 워크스페이스」가 정해지고 앱 데이터가 그 경계 안에 묶인다. 좌석과 구독을 잇고, 운영자 화면 · 견본 앱 · e2e 한 여정까지.
> 형 원칙: 조립식 · 캡슐화(프로젝트 특화는 앱이) · 모듈 기본은 중립(앱 yml 에서 덮어쓴다) · 업계 관행 · 공개 레포라 개인 흔적 없음(`example.com`).
> 대상: kotlin-skeleton(새 `workspace` · `workspace-jdbc`, 기존 `platform` · `account` · `apps/sample`) + react-skeleton(새 `@skeleton/workspace`, `apps/sample`). 기준: kotlin main `2e6aaae4`, react main `cebcc0f`.
> 읽은 코드: `common/account/{AccountFacts,AccountLabels}` · `common/authz/*` · `common/erasure/Erasure.kt` · `common/contact/ContactAddresses` · `notification-mail` `MailSender` · `payment` 구독(`payment_subscriptions.active_key` = 계정 id, `SubscriptionPlans`) · `docs/modules/authz.md` · `docs/multi-project.md` · `badge` 모듈 짜임 · react `api-client` 의 `getAuthHeaders` · `apps/sample/e2e/mail.ts`.

## 0. 국룰 조사 → 권장안

| 물음 | 업계 | 권장 |
|---|---|---|
| 지금 워크스페이스를 어떻게 싣나 | 헤더: OpenAI `OpenAI-Organization`, Stripe `Stripe-Account`. 경로: GitHub `/orgs/{org}/…`, Supabase. 토큰: Clerk · Auth0 Organizations(세션에 `org_id`, 바꾸면 재발급) | **결정 1**. 추천은 헤더 `X-Workspace-Id`(앱 데이터) + 워크스페이스 자기 관리는 경로 `/workspaces/{id}/…` |
| 역할 | Slack 소유자 · 관리자 · 멤버(+게스트), GitHub Owner · Member(+사용자 정의는 엔터프라이즈), Linear Admin · Member · Guest | 고정 셋 `OWNER > ADMIN > MEMBER`, 앱은 「최소 역할」로 묻는다. 사용자 정의 역할 · 게스트는 다음 |
| 소유자 수 | Slack 주 소유자 1 + 소유자 여럿, GitHub 소유자 여럿(최소 1) | **소유자 정확히 1** — 이전으로만 바뀐다(요청의 「소유권 이전」 뜻, 과금 주체가 하나로 선다) |
| 초대 | 메일 링크, GitHub 7일 만료, 재발송은 새 링크, 받은 주소로 확인된 계정만 수락(GitHub · Linear · Notion) | 메일 링크 · 7일 · 재발송 = 새 토큰(옛 링크 무효) · 취소 · **확인된 계정 이메일이 초대 주소와 같아야 수락** |
| 좌석 | GitHub: 멤버 + 대기 중 초대가 좌석. Slack: 활성 멤버만, 일할 정산 | 좌석 = 멤버 + 대기 초대. 상한은 앱이 정한다(계약 하나, 기본 무제한). 과금 연결 폭은 **결정 2** |
| 소유자 계정 삭제 | GitHub: 유일한 소유자면 계정 삭제를 막는다(먼저 이전하라) | 삭제 요청을 막는다(409) + 그래도 지워질 때의 안전망(가장 오래된 관리자 → 멤버에게 이전, 혼자면 닫음) |
| 추방 · 탈퇴 뒤 데이터 | Slack · GitHub · Linear: 쓴 글은 워크스페이스에 남는다 | 남긴다. 접근만 다음 요청부터 끊긴다 |

## 1. 범위

| | 1차(끝에서 끝까지 한 벌) | 다음에 넓힐 것 |
|---|---|---|
| 워크스페이스 | 만들기(누구나 로그인하면) · 이름 바꾸기 · 내 목록 · 상세 | 삭제(데이터 고리 `WorkspaceErasureListener`) · 주소 이름(slug) · 그림 · 가입 때 개인 워크스페이스 자동(결정 3 이 B 면 1차) |
| 멤버 | 목록 · 역할 바꾸기(관리자 ↔ 멤버) · 추방 · 탈퇴 · 소유권 이전 | 게스트 · 사용자 정의 역할 · 이전 때 다시 인증 · 워크스페이스 활동 기록 · 멤버 변동 알림 |
| 초대 | 이메일(한 번에 20개까지) · 7일 만료 · 재발송 · 취소 · 공개 미리보기 · 수락 | 거절 · 기존 계정(아이디) 초대 · 도메인 자동 가입 · 공유 초대 링크 · 끝난 초대의 이메일 정리 작업 |
| 경계 | 헤더로 정한 지금 워크스페이스 + 「최소 역할」 검사(`CurrentWorkspace`) · 견본 노트에 `workspace_id` | 기존 모듈(board · storage · notification)이 따르기 — 그때 계약을 `platform` 으로 올린다 · API 키 소유자 `WORKSPACE`(api-key 의 `ApiKeyOwnerPolicy` 구현, 관리 = ADMIN 이상, 그 키의 요청은 헤더 대신 키의 워크스페이스가 문맥) |
| 좌석 · 과금 | `WorkspaceSeatPolicy`(상한, 기본 없음) · 초대 · 수락에서 검사 · 견본이 구독 플랜 → 상한 | 결정 2 의 B · C |
| 운영 | 목록 · 검색 · 상세(멤버 · 대기 초대 수) — 읽기만 | 강제 소유권 이전 · 정지 · 운영 기록 |

**비목표**: 행 수준 보안(PostgreSQL RLS) · 워크스페이스마다 스키마(그건 `multi-project` 의 몫 — 프로젝트는 서비스 단위, 워크스페이스는 한 서비스 안의 팀) · SSO/SCIM · 워크스페이스별 결제사 키 · 기존 모듈 데이터에 경계 걸기.
**반목표**: 남의 워크스페이스 id 로 데이터가 보인다 / 없는 워크스페이스와 멤버가 아닌 워크스페이스가 구별된다 / 소유자가 0 · 2명인 순간이 커밋된다 / 좌석 상한을 동시 수락으로 넘는다 / 초대 토큰이 평문으로 저장 · 로그된다 / 이 모듈을 안 붙인 앱의 동작이 바뀐다.

## 2. 모듈 경계 · 확장 지점

```
workspace(-jdbc) ──api──▶ platform{AccountFacts(+verifiedEmail), AccountLabels, AccountErasureListener,
                                    AccountDeletionCheck(새), OperatorAccess, PermissionDeclaration}
workspace ··compileOnly··▶ notification-mail   (초대 메일 — 없으면 로컬은 링크를 로그로, stage · prod 는 DeployGuard 문제. account 와 같다)
account ──▶ platform  (verifiedEmail · AccountDeletionCheck 를 구현 · 부른다 — workspace 는 모른다)
```

- **authz 와의 관계**: 다른 축이다. authz 는 서비스 운영자 역할(전역 권한 키), 워크스페이스 역할은 멤버십 한 줄의 값. 섞지 않는다 — 서비스 관리자(`ADMIN`)라도 멤버가 아니면 워크스페이스 데이터를 못 본다. 운영 경로만 `OperatorAccess`(`workspace.read`)를 쓴다.
- **multi-project 와의 관계**: 프로젝트 모드면 워크스페이스 표가 프로젝트 스키마마다 생긴다(따로 할 일 없음). 메일 링크 주소만 프로젝트마다 필수(`account.mail.link-base-url` 과 같은 규칙).

| 확장 지점 | 자리 | 고른 이유 |
|---|---|---|
| `CurrentWorkspace`(`get()`: 멤버십 또는 null · `require(min: WorkspaceRole)`) + 헤더 필터 | `workspace` | 앱 컨트롤러가 한 줄로 경계를 건다. 다른 모듈이 따를 때(두 번째 사용자) `platform` 으로 올린다 — 지금은 얇게 |
| `Workspaces`(서버 API: `create` · `membershipOf` · `membersOf`) | `workspace` | 앱이 가입 후 개인 워크스페이스를 만들거나 자기 화면에 멤버를 끼울 때 |
| `WorkspaceSeatPolicy`(`limitOf(workspaceId): Int?`, 기본 null = 무제한) | `workspace` | 좌석 값은 앱의 상품이 정한다(결정 2). 모듈은 세고 막기만 |
| `WorkspaceStore` | `workspace`, 구현 `workspace-jdbc` | 다른 모듈과 같은 포트/어댑터 짝 |
| `WorkspaceInvitationMail`(제목 · 본문 ko/en) | `workspace` | 문구 · 브랜드는 앱 몫 — `AccountMailTemplates` 와 같은 교체 지점 |
| `AccountFacts.verifiedEmail(accountId)`(기본 null) | `platform` · `account` 구현 | 수락의 이메일 대조. 기존 계약에 기본 메서드로 더해 다른 구현이 안 깨진다 |
| `AccountDeletionCheck`(`blockers(accountId)`) | `platform` · `account` 가 삭제 요청 때 모은다 | 「유일한 소유자는 먼저 이전」(GitHub). 다음 사용자(결제 미정산 등)도 같은 자리 |

## 3. 데이터 (`workspace-jdbc`, 두 방언, 시각 PG `timestamptz` · MySQL `datetime(6)` UTC, 부분 인덱스 없음)

- `workspaces(id pk 'ws_', name varchar(100), status ACTIVE|CLOSED, created_by, created_at, updated_at, closed_at null)`
- `workspace_members(workspace_id, account_id, role OWNER|ADMIN|MEMBER, owner_key varchar(40) null unique, joined_at, invited_by null, pk(workspace_id, account_id), index(account_id))` — `owner_key` = OWNER 일 때만 workspace_id(결제 `active_key` 와 같은 꼴) → **소유자 둘이 커밋될 수 없다**. 「최소 하나」는 모든 변경이 `workspaces` 줄을 `for update` 로 잡고 판정하는 것으로 지킨다.
- `workspace_invitations(id pk 'wsi_', workspace_id, email varchar(254) 소문자, role ADMIN|MEMBER, token_hash char(64) unique, status PENDING|ACCEPTED|REVOKED, pending_key varchar(300) null unique, invited_by, expires_at, sent_count, last_sent_at, accepted_by null, accepted_at null, created_at, updated_at, index(workspace_id, status))` — `pending_key` = PENDING 일 때 `workspace_id + '\n' + email` → 같은 주소에 대기 초대는 하나. 만료는 상태가 아니라 `expires_at < now` 로 읽는다(작업 없이).
- 토큰: 무작위 32바이트 base64url, 저장은 sha256 만(`auth-session` 리프레시 토큰과 같다). 응답 · 로그 · `toString` 어디에도 평문 없음 — 메일 본문에만.

## 4. 흐름

- **만들기** `POST /workspaces {name}` → 워크스페이스 + 만든 사람 OWNER 한 트랜잭션. 초대를 켠 앱에 `AccountFacts` 빈이 없으면 기동 실패(수락의 이메일 대조를 못 한다 — 메시지가 빈 이름을 적는다).
- **지금 워크스페이스**(결정 1 이 A 일 때): 필터가 인증된 요청의 `X-Workspace-Id` 를 읽어 멤버십을 DB 에서 한 번 읽는다(캐시 없음 → 추방 · 역할 변경이 다음 요청부터). 없는 id · 멤버 아님 · `CLOSED` 는 모두 같은 `404 WORKSPACE.NOT_FOUND`. 헤더가 없으면 문맥 없음 — `require` 가 `400 WORKSPACE.REQUIRED`. 역할 부족 `403 WORKSPACE.FORBIDDEN`. 응답에 `Vary: X-Workspace-Id`.
- **초대** ADMIN 이상 → 주소마다: 대기 초대가 있으면 `alreadyInvited`(재발송은 따로), 없으면 좌석 검사(멤버 + 대기 ≥ 상한 → 그 주소부터 `seatLimited`) 뒤 줄 + 토큰 → 커밋 뒤 메일. ADMIN 은 MEMBER 로만, OWNER 는 ADMIN 으로도 초대. 대기 상한 `invitation.max-pending`(기본 100) 넘으면 `409 WORKSPACE.TOO_MANY_PENDING`.
- **재발송** 새 토큰(옛 링크 즉시 무효) · 만료 다시 7일 · 같은 초대 60초 안 재발송 `429 WORKSPACE.RESEND_TOO_SOON`. **취소** → `REVOKED`(`pending_key` 비움).
- **미리보기**(로그인 불필요) `POST /workspace-invitations/preview {token}` → `{workspaceName, inviterLabel?, emailMasked, role, expiresAt}`. 없는 · 만료 · 쓴 · 취소 토큰은 모두 `400 WORKSPACE.INVITATION_INVALID`(`detail.reason`: `EXPIRED`|`USED`|`INVALID` — 토큰을 가진 사람에게만 보이므로 알려 준다). 토큰은 주소가 아니라 본문으로 — 접근 로그에 안 남게.
- **수락**(로그인) `POST /workspace-invitations/accept {token}` → 워크스페이스 줄 잠금 → 토큰 유효 · `verifiedEmail(호출자) == 초대 주소`(아니면 `403 WORKSPACE.INVITATION_EMAIL_MISMATCH`, 확인된 이메일이 없으면 `403 WORKSPACE.EMAIL_UNVERIFIED`) · 이미 멤버면 초대만 `ACCEPTED` 로 닫고 `409 WORKSPACE.ALREADY_MEMBER` · 좌석 다시 검사(멤버 ≥ 상한 → `409 WORKSPACE.SEAT_LIMIT`, 초대는 그대로) → 멤버 줄. 계정이 없으면 화면이 가입 · 로그인으로 보냈다가 돌아와 수락한다(토큰은 탭 저장소에, 주소창에서 지운다).
- **역할 바꾸기** OWNER 만, ADMIN ↔ MEMBER. **추방** OWNER: 소유자 말고 누구나, ADMIN: MEMBER 만. **탈퇴** 소유자는 `409 WORKSPACE.OWNER_CANNOT_LEAVE`(먼저 이전).
- **소유권 이전** OWNER → 지금 멤버 하나: 한 트랜잭션에서 옛 소유자 ADMIN, 새 소유자 OWNER(`owner_key` 옮김). 대상이 멤버가 아니면 `404 WORKSPACE.MEMBER_NOT_FOUND`.
- **계정 삭제**: 삭제 요청 때 `account` 가 `AccountDeletionCheck` 를 모아 다른 멤버가 있는 워크스페이스의 소유자면 `409 ACCOUNT.DELETION_BLOCKED`(`data.blockers: [{module: "workspace", code: "OWNER", refs: [ws id…]}]`). 혼자인 워크스페이스는 막지 않는다. 그래도 지워지면(운영자 삭제 · 유예 중 생긴 멤버) `AccountErasureListener` 가 가장 오래된 ADMIN → MEMBER 에게 이전, 아무도 없으면 `CLOSED`, 멤버 줄은 지우고 `invited_by` · `created_by` 는 묘비 값으로.

## 5. API (사용자 경로는 붙이면 켜짐 `skeleton.workspace.http.enabled`, 운영은 `skeleton.workspace.admin.enabled` 기본 꺼짐 → 404)

| 경로 | 하는 일 | 권한 |
|---|---|---|
| `GET /api/v1/workspaces` · `POST` `{name}` | 내 목록 `[{id, name, role}]` · 만들기 201 | 로그인 |
| `GET /api/v1/workspaces/{id}` · `PATCH {name}` | `{id, name, role, memberCount, pendingInvitations, seatLimit?, createdAt}` · 이름 | 멤버 · ADMIN |
| `GET /{id}/members` | `[{accountId, label?, role, joinedAt}]`(`label` = `AccountLabels`) | 멤버 |
| `PATCH /{id}/members/{accountId}` `{role}` · `DELETE …` | 역할 · 추방 | OWNER · ADMIN(멤버만) |
| `POST /{id}/leave` · `POST /{id}/transfer {accountId}` | 탈퇴 · 소유권 이전 | 멤버 · OWNER |
| `GET/POST /{id}/invitations` · `POST …/{invId}/resend` · `DELETE …/{invId}` | 대기 목록 `[{id, email, role, expiresAt, expired, sentCount, invitedBy}]` · 초대 `{emails(≤20), role}` → `{invited, alreadyInvited, seatLimited}` · 재발송 · 취소 | ADMIN |
| `POST /api/v1/workspace-invitations/preview` · `/accept` `{token}` | §4 | 공개(미리보기만) · 로그인 |
| `GET /api/v1/admin/workspaces?q&cursor` · `GET …/{id}` | 목록(이름 · 소유자 label · 멤버 수 · 상태 · 만든 때) · 상세(멤버 · 대기 초대 수) | `workspace.read` |

- 권한 선언 `PermissionDeclaration("workspace", [workspace.read])`, 기본 역할 없음(전권만). 계정 이름은 `account.read` 도 있을 때만(쿠폰 · 배지 선례).
- 초대 메일 링크 `<skeleton.workspace.mail.link-base-url>/invite?token=…`. 프로젝트 모드에서 프로젝트마다 필수(`ProjectConfigKeys.SUPPORTED` 에 더한다).

## 6. 화면 (react 새 패키지 `@skeleton/workspace`)

- `createWorkspaceApi` · 훅 · `WorkspaceProvider`/`useWorkspace`(고른 id 를 저장소에 보관, 목록에 없으면 비움) · `createWorkspaceHeaders(store)`(앱이 `getAuthHeaders` 와 합친다 — 패키지끼리 import 하지 않는다) · `WorkspaceSwitcher`(머리줄 고르기 + 「새 워크스페이스」) · `CreateWorkspaceForm` · `WorkspaceSettings`(이름 · 멤버 표: 역할 고르기 · 추방 · 이전 대화상자 · 탈퇴) · `WorkspaceInvitations`(여러 주소 입력 · 대기 표 · 재발송 · 취소, 좌석 `n / 상한`) · `AcceptInvitation`(`/invite` — 미리보기 → 로그인 안 했으면 「로그인 · 가입 후 수락」, 토큰은 `scrubUrl` 로 지우고 탭 저장소에) · `@skeleton/workspace/admin` 의 `AdminWorkspaces`.
- 단추는 역할로 숨기고(서버가 어차피 막는다) 운영 화면은 `can(key)`. 문구 ko/en `labels` prop, 부품마다 스토리 + `play`, 화면은 Patterns 에서 복사, 눌리는 것은 눌리게, SSR 안전.

## 7. 견본 앱 (kotlin `apps/sample` + react `apps/sample`)

조립 `workspace` · `workspace-jdbc`, yml `admin.enabled: true` · 링크 주소 · `invitation`. 노트에 `workspace_id`(null 가능) 새 V 파일 — 헤더가 있으면 그 워크스페이스 노트(멤버는 읽기 · 쓰기, 지우기는 쓴 사람 · ADMIN 이상), 없으면 지금처럼 내 노트(결정 3 의 A). 좌석: 견본 `WorkspaceSeatPolicy` 가 소유자의 구독(`PaymentSubscriptions.current`) 플랜 → 상한(무료 3 · 유료 10, 결정 2 의 A). 씨앗(로컬만): user 가 소유자인 「Example Team」.
**e2e 한 여정**(react sample + kotlin sample + mailpit): user 가 워크스페이스 만들기 → 새 주소로 초대 → 메일 링크 → 새 계정 가입 · 확인 → 수락 → 둘이 같은 워크스페이스 노트를 본다 → user 가 추방 → 그 사람의 다음 요청은 목록에서 사라지고 노트가 안 보인다. (`e2e/mail.ts` 의 링크 종류에 `invite` 를 더한다.)

## 8. 검증 (깨지면 사고 나는 것만)

**kotlin**: 경계 — 멤버 아닌 · 없는 · 닫힌 id 응답 바이트가 같다, 추방 직후 다음 요청 404, 헤더 없는 `require` 400. 소유자 — 이전 · 동시 이전 둘 · 역할 변경이 겹쳐도 커밋된 소유자는 늘 1. 좌석 — 상한 1 남은 워크스페이스에 동시 수락 둘 → 하나만. 초대 — 재발송 뒤 옛 토큰 무효, 만료 · 취소 · 쓴 토큰 거절, 다른 · 미확인 이메일 거절, DB · 로그 · 응답에 평문 토큰 없음. 삭제 — 다른 멤버가 있는 소유자 삭제 요청 409, 안전망 이전 · 닫힘 멱등. 조립 — `notification-mail` 없이(`noOptionalTest`), `account` 없이(수락 기동 실패 메시지), 이 모듈 없는 `apps/api` 그대로. 두 방언 `dbTest` · `MigrationLockTest` · `CapabilitiesCatalogTest` · `ModuleDocumentationTest`.
**react**: 고르기 저장 · 목록에서 사라지면 비움, 헤더 합치기, 역할별 단추, 초대 폼(주소 형식 · 20개) · 수락 화면 세 상태 스토리 `play`, api 경로 기록 시험, `capabilities` 검사.

## 9. 내가 정한 것 (관행대로 — 바꾸려면 말해 줘)

1. 역할 고정 셋 + 「최소 역할」 비교, 사용자 정의 역할 없음. 2. 소유자 정확히 1, 이전으로만. 3. 초대 7일(GitHub) · 재발송은 새 토큰 · 60초 간격 · 대기 100개 · 한 번에 20개 — 안전 장치라 모듈 기본값으로 둔다(`skeleton.workspace.invitation.*` 로 덮는다). 4. 수락은 확인된 계정 이메일이 초대 주소와 같을 때만. 5. 좌석 = 멤버 + 대기 초대, 상한 기본 없음. 6. 추방 · 탈퇴 뒤 데이터는 남긴다. 7. 유일한 소유자의 계정 삭제는 막고, 안전망은 자동 이전. 8. 운영 화면은 읽기만. 9. 기존 모듈은 1차에 경계를 걸지 않고, 계약은 `workspace` 안에 얇게. 10. 워크스페이스 만들기 개수 제한 없음(중립). 11. 메일 언어는 초대하는 사람의 요청 언어.

## 10. 작업 계획

| 키 | 조각 | 선행 | 크기 | 예상 |
|---|---|---|---|---|
| K1 | `platform`: `AccountFacts.verifiedEmail` · `AccountDeletionCheck` / `account`: 구현 · 삭제 요청에서 모아 `409 ACCOUNT.DELETION_BLOCKED` · 계약 문서 한 줄 | — | 소 | 1.5h |
| K2 | `workspace` · `workspace-jdbc` 뼈대: 표 셋(V 파일 두 방언 · 잠금) · 만들기 · 목록 · 상세 · 멤버 · 역할 · 추방 · 탈퇴 · 이전 · `CurrentWorkspace` 필터 · 좌석 계약 · 삭제 고리 · 모듈 문서 · 설정 문서 · 카탈로그 · workbench 한 줄 | K1(삭제 고리만 — 나머지 나란히) | 대 | 5h |
| K3 | 초대: 만들기 · 재발송 · 취소 · 미리보기 · 수락 · 메일 틀 ko/en · 로그 링크 · DeployGuard · 프로젝트 키 | K1 · K2 | 중 | 3.5h |
| K4 | 운영 API(목록 · 상세) · 권한 선언 · OpenAPI 기여 | K2 | 소 | 1.5h |
| K5 | 견본: 노트 `workspace_id` · 좌석 정책 · 씨앗 · 통합 시험 · `docs/sample.md` · CHANGELOG | K3 · K4 | 중 | 2.5h |
| R1 | `@skeleton/workspace`: api · Provider · 헤더 · Switcher · 만들기 · 설정(멤버) + 스토리 · 카탈로그 | §5 표(가짜 API) | 대 | 4h |
| R2 | 초대 부품 · `AcceptInvitation` · `/admin` `AdminWorkspaces` | R1 | 중 | 3h |
| R3 | react 견본 조립(머리줄 · 라우트 · 헤더 합치기 · 노트 목록) · e2e 한 여정 | R2 · K5 합친 뒤 | 중 | 3h |

- 묶음: ① K1 ∥ K2 ∥ R1 → ② K3 ∥ K4 ∥ R2 → ③ K5 → R3 → 끝 검사(지휘: kotlin `kverify` 한 번 · react 전체 + `e2e:sample` 한 번, 리뷰 kotlin 1 · react 1, QA 1 — 화면당 캡처 2장까지). 동시 최대 3.
- developer 8회 + 리뷰 2 + QA 1. 벽시계 대략 12h.
- 같이 만지는 파일: `settings.gradle.kts` · `capabilities.json` · `docs/modules/README.md` 는 K2 만, `ProjectConfigKeys` 는 K3 만, 견본 yml · 마이그레이션은 K5 만.
- 계약이 바뀌면 kotlin 조각이 보고에 적고 지휘가 react 조각에 넘긴다. 스펙과 계획이 어긋나면 스펙이 이긴다.

## 정해야 할 것

1. **지금 워크스페이스를 요청에 싣는 법** — 앱 컨트롤러 · 프런트 라우트 모양이 갈린다.
   - **A. 헤더 `X-Workspace-Id`(앱 데이터) + 워크스페이스 관리는 경로, 프런트는 고른 것을 저장 (추천)** — OpenAI · Stripe 관례. 기존 경로 · 패키지를 안 바꾸고 `getAuthHeaders` 옆에 한 줄로 붙는다.
   - B. 경로 `/api/v1/workspaces/{id}/…` + 프런트 주소 `/w/{slug}/…` — GitHub · Linear 모양. 링크 공유가 되지만 앱 경로가 모두 바뀌고 slug 가 1차에 들어온다.
   - C. 토큰에 박기(Clerk · Auth0) — 전환마다 재발급, `auth` 를 고친다.
2. **좌석 · 구독을 1차에 어디까지 잇나** — `payment` 를 고치는지가 갈린다(지금 구독은 계정당 하나, `active_key` = 계정 id).
   - **A. 상한 계약만, 견본이 소유자의 구독 플랜 → 좌석 상한 (추천)** — 돈 경로를 안 건드린다. 이전하면 새 소유자의 플랜을 따른다는 어색함은 문서에 적는다.
   - B. 구독 주체를 워크스페이스로(`payment` 에 주체 칸) + 플랜 등급으로 상한 — Slack · Notion 처럼 팀이 결제한다. `payment` 중 크기 한 조각 추가.
   - C. 좌석 수량 과금(수량 · 일할 정산, Stripe quantity) — B 에 더해 결제사 셋의 수량 지원 조사까지, 대 두 조각.
   - D. 과금 연결 없음(상한도 1차에 안 만든다).
3. **견본 앱의 데이터 모양** — 노트 · e2e 여정이 갈린다.
   - **A. 내 노트 + 워크스페이스 노트 공존(헤더 없으면 내 노트) (추천)** — 기존 노트 여정 · e2e 가 그대로이고 경계 시연은 새 여정 하나.
   - B. 가입 때 개인 워크스페이스를 자동으로 만들고 모든 노트가 워크스페이스 안(Vercel · Linear) — 모듈에 가입 고리 · 개인 표시가 1차로 들어오고 기존 노트 데이터 이관 V 파일이 필요하다.
   - C. 노트는 그대로 두고 견본에 작은 새 도메인(팀 할 일)을 더한다.

## 결정됨 (2026-10-30, 형)

이 절이 위 본문과 다르면 이 절이 이긴다.

1. **지금 워크스페이스 — 절충안.** 서버는 헤더 `X-Workspace-Id` 로 받고(기존 백엔드 경로 그대로, API 키·외부 호출도 같은 방식), 화면 주소는 `/w/<slug>/…` 이다(탭마다 다른 팀, 링크 공유 정확). 그래서 slug(중복 금지 · 변경 가능 · 옛 slug 는 새 slug 로 넘김)가 1차에 들어온다. 반드시 지킬 것:
   - 헤더는 믿지 않는다 — 요청마다 DB 에서 멤버십 · 역할을 확인하고, 멤버가 아니면 404(존재도 알리지 않음).
   - 로그인 안 됨 → 401 → 로그인 뒤 원래 `/w/<slug>/…` 로 돌아온다.
   - 역할 변경 · 추방은 다음 요청부터 바로 반영(토큰에 싣지 않는다).
   - 새로고침 · 탭: 팀은 주소에서 읽는다. 탭끼리 섞이지 않는다. 「마지막에 쓴 팀」은 첫 화면으로 보낼 때만 쓴다.
   - 이 다섯을 e2e 로 확인한다(두 탭에 다른 팀 · 추방 직후 404 · 옛 slug 넘김 포함).
2. **좌석 · 구독 — 상한 계약만**(추천안 A). 결제 코드는 건드리지 않는다.
3. **견본 앱 데이터 — 내 노트와 워크스페이스 노트 공존**(추천안 A, 형이 따로 고르지 않아 지휘 세션이 정함).
