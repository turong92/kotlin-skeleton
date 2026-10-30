# workspace

워크스페이스(팀 · 조직) — 사용자가 워크스페이스를 만들고, 소유자 · 관리자 · 멤버 역할로 나눠 쓰고, 이름 · 주소(slug)를 바꾸고, 멤버를 내보내고, 탈퇴 · 소유권 이전을 한다. 요청마다 「지금 워크스페이스」가 정해져 앱 데이터가 그 경계 안에 묶인다.
설계와 고르지 않은 안: `docs/superpowers/specs/2026-10-30-org-design.md`. 저장은 어댑터 `workspace-jdbc`(메모리 구현 없음). **이 모듈을 안 붙인 앱의 동작은 바뀌지 않는다.** 이 조각에는 초대 · 운영자 경로가 아직 없다(다음 조각).

**역할**: 고정 셋 `OWNER > ADMIN > MEMBER` — 앱은 「최소 역할」로 묻는다. 사용자 정의 역할 · 게스트는 없다. 서비스 운영자 역할(`authz`)과는 다른 축이다 — 서비스 관리자라도 멤버가 아니면 워크스페이스 데이터를 못 본다.
**소유자는 정확히 하나** — 소유권 이전으로만 바뀐다. DB 가 소유자 표시(`owner_key`)를 유니크로 막고, 모든 변경이 워크스페이스 줄을 `for update` 로 잡은 뒤 호출자의 **현재** 멤버십을 다시 읽어 판정한다(동시 이전 · 역할 변경 · 추방이 겹쳐도 하나, 진 쪽은 새 역할로 다시 판정되어 403).

**지금 워크스페이스**: 서버는 요청 헤더 `X-Workspace-Id`(id) 로 받는다. 앱 컨트롤러가 `CurrentWorkspace` 를 주입받아 한 줄로 경계를 건다 — `current.require(WorkspaceRole.MEMBER)`(`get()` 은 헤더가 없으면 null).
헤더는 믿지 않는다: **요청마다 DB 에서 호출자의 멤버십을 읽는다**(캐시 없음 — 추방 · 역할 변경이 다음 요청부터). 없는 id · 멤버 아님 · 닫힘은 **같은 `404 WORKSPACE.NOT_FOUND`**(바이트까지 같고 id 를 되풀이하지 않는다), 헤더가 없는 `require` 는 `400 WORKSPACE.REQUIRED`, 역할 부족 `403 WORKSPACE.FORBIDDEN`, 로그인 안 됨 `401`. 모든 응답에 `Vary: X-Workspace-Id`.
화면 주소는 `/w/<slug>/…` 이고(화면이 `GET …/by-slug/{slug}` 로 풀어 id 를 얻는다) 서버 경계는 늘 id 헤더다 — 기존 백엔드 경로 · API 키 호출도 같은 방식이다.

**slug** (소문자 · 숫자 · 하이픈, 3~40자, 앞뒤 하이픈 없음): 만들 때 이름에서 자동(악센트는 뗀다, 라틴 글자 · 숫자가 3자 미만이면 `team`), 겹치면 `-2`, `-3`… 요청이 slug 를 주면 그것을 쓰고 겹치면 `409 WORKSPACE.SLUG_TAKEN`, 형식이 틀리면 `400 WORKSPACE.INVALID`.
`PATCH` 로 바꾸면 옛 slug 는 `workspace_slug_history` 에 남아 **다른 워크스페이스가 가져갈 수 없다**(가로채기 방지). 같은 워크스페이스는 자기 옛 slug 로 되돌릴 수 있다. `by-slug` 는 지금 slug 든 옛 slug 든 상세를 주고 응답의 `slug` 는 늘 지금 값 — 화면은 주소와 다르면 주소를 바꾼다.

| 메서드 · 경로 (`/api/v1/workspaces`) | 하는 일 | 권한 |
|---|---|---|
| `GET` · `POST {name, slug?}` | 내 목록 `[{id, name, slug, role}]` · 만들기 201 (만든 사람이 소유자, 한 트랜잭션) | 로그인 |
| `GET /by-slug/{slug}` | 상세 (지금 · 옛 slug) | 멤버 |
| `GET /{id}` | `{id, name, slug, role, memberCount, pendingInvitations, seatLimit, createdAt}` (`seatLimit` 은 상한이 없으면 `null`) | 멤버 |
| `PATCH /{id} {name?, slug?}` | 이름 · slug 바꾸기 (둘 다 없으면 400) | ADMIN 이상 |
| `GET /{id}/members` | `[{accountId, label, role, joinedAt}]` — `label` 은 `AccountLabels` 가 있고 이름이 있는 계정만, 아니면 `null` | 멤버 |
| `PATCH /{id}/members/{accountId} {role}` | ADMIN ↔ MEMBER (`OWNER` 로는 못 바꾼다 400, 소유자의 역할은 못 바꾼다 409 `OWNER_PROTECTED`) → 204 | OWNER |
| `DELETE /{id}/members/{accountId}` | 추방 → 204 (소유자는 못 내보낸다) | OWNER: 누구나 · ADMIN: MEMBER 만 |
| `POST /{id}/leave` | 탈퇴 → 204 (소유자는 `409 OWNER_CANNOT_LEAVE` — 먼저 이전) | 멤버 |
| `POST /{id}/transfer {accountId}` | 소유권 이전 → 204 (옛 소유자는 ADMIN) | OWNER |

멤버가 아닌 경로 id 는 어느 경로든 `404 WORKSPACE.NOT_FOUND`, 멤버 줄이 없는 대상은 `404 WORKSPACE.MEMBER_NOT_FOUND`. 추방 · 탈퇴 뒤에도 쓴 데이터는 남는다(접근만 끊긴다).

**좌석**: `WorkspaceSeatPolicy.limitOf(workspaceId): Int?` — 상한은 앱의 상품이 정한다(예: 구독 플랜). 빈이 없으면 무제한. 좌석 = 멤버 + 살아 있는(대기 · 안 만료) 초대. 이 조각은 상세에 값을 싣기만 하고, 막는 검사는 초대 · 수락(다음 조각)에서 한다.
**계정 삭제**: 다른 멤버가 있는 워크스페이스의 소유자는 삭제 요청이 `409 ACCOUNT.DELETION_BLOCKED`(`data.blockers: [{module: "workspace", code: "OWNER", refs: [워크스페이스 id…]}]`)로 막힌다 — 혼자인 워크스페이스는 막지 않는다. 그래도 지워지면(운영자 삭제 · 유예 중 생긴 멤버) `AccountErasureListener` 가 가장 오래된 ADMIN → 없으면 가장 오래된 MEMBER 에게 이전하고, 아무도 없으면 `CLOSED`(slug 는 그대로 잡혀 있다), 멤버 줄은 지우고 `created_by` · `invited_by` 는 묘비 값으로 바꾼다(멱등).
앱 서버 코드는 `Workspaces`(`create` · `membershipOf` · `membersOf`)를 쓴다 — 가입 뒤 개인 워크스페이스를 만들거나 자기 화면에 멤버를 끼울 때. 브라우저에 열려 있지 않다.
이 모듈은 `account` 를 모른다 — 계정 이름은 `platform` 의 `AccountLabels`, 삭제 연동은 `AccountDeletionCheck` · `AccountErasureListener` 계약으로만 만난다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:workspace"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | Spring Security (경로를 등록할 때만 필요하다) |
| 설정 접두사 | `skeleton.workspace` — [docs/config/modules/workspace.yml](../config/modules/workspace.yml) |
| 기본 동작 | 켜짐. 사용자 경로 켜짐(`skeleton.workspace.http.enabled=false` 로 끈다 — 경계 `CurrentWorkspace` 는 그대로), 좌석 상한 없음. |
| 부팅에 필요한 것 | 저장소 포트 `WorkspaceStore` — `workspace-jdbc` 가 낸다. 없으면 서비스 빈이 켜지지 않는다. |
| 교체 지점 | `WorkspaceStore`, `WorkspaceService`, `Workspaces`, `CurrentWorkspace`, `WorkspaceSeatPolicy`, `WorkspaceController`, `workspaceDeletionCheck`, `workspaceAccountErasureListener` |
| 마이그레이션 | 없음 (스키마는 `workspace-jdbc`) |
| 프론트 짝 | 없음 |
| 테스트 | `modules/workspace/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
