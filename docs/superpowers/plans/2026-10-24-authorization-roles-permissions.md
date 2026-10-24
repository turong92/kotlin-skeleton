# 권한 — 운영자 판정 통일 · 역할→권한 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 구현 · 리뷰는 역할(`developer` · `code-reviewer`)로 부른다.

**Goal:** 다섯 모듈의 운영자 판정을 `platform` 의 공용 검사기 하나로 모으고(계정 모듈이 있으면 매 요청 저장소로 — 회수 즉시), 모듈이 선언한 권한 문자열과 역할 → 권한 매핑(yml 또는 새 `authz` 모듈의 DB 표 + 관리 API · 화면)을 둔다. 운영 기록은 계약 자리만.

**Architecture:** 계약(`OperatorAccess` · `OperatorRef` · `PermissionDeclaration` · `RolePermissionSource` · `RoleHolders` · `OperatorAuditLog`)은 `platform` `common/authz` — board · payment · legal 이 `auth` · `account` 를 모르기 때문. 기본 구현은 토큰 역할 + yml 매핑(platform), `account` 가 저장소 판정으로 덮고, `authz-jdbc` 가 매핑을 DB 로 덮는다. 모듈은 엔드포인트마다 권한 하나를 묻는다.

**Spec:** `docs/superpowers/specs/2026-10-24-authorization-roles-permissions-audit-design.md` (결정 1~4 확정, 2026-10-24 형 승인). 이 계획과 스펙이 어긋나면 스펙이 이긴다 — 어긋남은 지휘에 알린다.

## Global Constraints

- 레포: kotlin `~/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton`(기준 main `a4d8eb73`), react `…/react-skeleton`(기준 main `afa534b`). **커밋 · 합치기 · push 는 지휘만.** 조각의 끝은 「바뀐 범위 시험 통과 + 보고」까지.
- 빌드 · 시험은 **바뀐 범위만**(아래 각 조각의 명령). 전체 스위트 · `kverify` · `scripts/test-new-project.sh --full` · react 전체(`pnpm test` · `typecheck` · `lint`) · e2e 는 **지휘가 끝에 한 번** 돌린다. 같은 워크트리에서 Gradle 을 둘 동시에 돌리지 않는다.
- 모듈 규칙: 빈은 AutoConfiguration `@Bean` + `@ConditionalOnMissingBean` 으로만(`ModuleRegistrationRulesTest`), `@Component` 금지. 새 모듈은 `docs/modules/<m>.md` · `docs/config/modules/<m>.yml` · `capabilities.json` 항목 필수(`ModuleDocumentationTest` · `CapabilitiesCatalogTest` · `ConfigPrefixDocumentationTest`).
- 마이그레이션: `./gradlew newMigration -Pname=…` 로 만들고 두 방언(`postgresql` · `mysql`) 짝, `perl scripts/migrations-lock.pl` 로 잠근다. 잠긴 파일은 고치지 않는다.
- KDoc 안에 `/**` 금지. 공개 레포 — 형 도메인 · 이메일 흔적 금지(예시는 `example.com`).
- 스켈레톤 기본값은 중립: 모든 관리 API 기본 꺼짐(`*.admin.enabled=false` → 404). 형 취향은 `apps/sample` yml 의 명시적 override.
- 기능 임의 제거 금지: `RequireRole`, JWT `roles` 클레임, `account_audit`, 결제 장부 이력, board `moderated_by`, `AUTHOR_LINK_VIEWED` · author-profile 배포 가드는 그대로.

## Review Focus (돈 · 보안 · 동시성)

- **회수 즉시**: 유효한 액세스 토큰을 쥔 채 역할 회수 · 계정 정지 → 다음 운영 호출 403. 다섯 모듈 모두(K2 · K3).
- **권한 상승**: 자기에게 없는 권한을 가진 역할을 남에게 주기(K2) · 역할에 더하기(K4) → 403.
- **전권 고정**: super-role 의 권한 편집 · 삭제, 다른 역할에 `*` → 409(K4). 마지막 전권 계정 회수 · 정지 · 삭제 → 기존 원자 판정 유지(K2).
- **옛 키**: 네 키 중 하나라도 값이 있으면 기동 실패 + 새 자리 안내(K1).
- **결제**: CS(`payment.read` + `payment.refund`)는 환불 승인만, `sync` · `close` · `retry` 는 403. 인터셉터 · 컨트롤러 두 겹이 같은 표(K3).
- **게시판 노출**: `board.moderate` 없는 호출자가 숨은 글을 보거나 남의 글을 숨기지 못한다. ADMIN(전권)은 이제 된다(K3).

---

## 조각 사이 계약 (K1 에서 확정 — R1 · R2 는 이것으로 가짜 API 를 먼저 만든다)

### C1. 권한 이름 표 (모듈이 `PermissionDeclaration` 으로 선언)

| 모듈 | 권한 | 기본 역할 |
|---|---|---|
| account | `account.read` · `account.manage` · `account.roles` | — |
| author-profile | `author-profile.read` | — |
| payment | `payment.read` · `payment.refund` · `payment.operate` | — |
| board | `board.moderate` · `board.manage` | `MODERATOR` |
| legal | `legal.read` | — |
| authz | `authz.roles` | — |

- `*` = 전권(super-role 전용). 키 규칙 `^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$`, ≤64자.
- 기본 매핑(platform yml 기본값): `ADMIN: ["*"]`, `MODERATOR: ["board.moderate", "board.manage"]`.

### C2. 설정 키

| 키 | 기본 | 주인 | 조각 |
|---|---|---|---|
| `skeleton.authz.roles` (맵 역할 → 권한 목록; 주면 통째로 이긴다) | 위 기본 매핑 | platform | K1 |
| `skeleton.authz.super-role` | `ADMIN` | platform | K1 |
| `skeleton.authz.cache-ttl` | `30s` (`0` = 캐시 끔) | platform | K1 |
| `skeleton.authz.admin.enabled` | `false` | authz | K4 |
| 삭제: `skeleton.account.admin.role` · `skeleton.payment.admin.role` · `skeleton.legal.admin-role` · `skeleton.board.moderator-role` | 값이 있으면 기동 실패 | platform 가드(K1), 프로퍼티 제거는 K2 · K3 | |

- 접두사 겹침(`skeleton.authz` = platform, `skeleton.authz.admin` = authz)을 `CapabilitiesCatalogTest` · `ConfigPrefixDocumentationTest` 가 거부하면 K1 · K4 developer 는 이름을 임의로 바꾸지 말고 지휘에 알린다.

### C3. `GET /api/v1/auth/me` + 로그인·갱신 응답의 `principal` (K1, 추가만)

```json
{ "value": { "accountId": "acc_user", "username": "…", "email": "…", "roles": ["USER"], "permissions": ["payment.read", "payment.refund"] }, "meta": { … } }
```
- 같은 모양(`permissions` 포함)을 로그인 · 소셜 · 매직링크 · 토큰 갱신 응답의 `value.principal` 도 돌려준다(액세스 토큰 자체에는 싣지 않는다).
- `permissions` 는 **늘 있다**(없으면 `[]`), 전권이면 `["*"]` 그대로. 순서 정렬(사전순). 나머지 필드 · `sessionId` 규칙 불변.

### C4. 역할 관리 API (K4, `authz.admin.enabled=true` 일 때)

```
GET    /api/v1/admin/permissions      → ListResponse  { "values": [ { "key": "payment.refund", "module": "payment" } ] }
GET    /api/v1/admin/roles            → ListResponse  { "values": [ { "name": "CS", "description": "…", "permissions": ["payment.read","payment.refund"], "protected": false, "holders": 1 } ] }
POST   /api/v1/admin/roles            ← { "name": "CS", "description": "…", "permissions": [ … ] }   → 201 DataResponse<Role>
PUT    /api/v1/admin/roles/{name}     ← { "description": "…", "permissions": [ … ] }                 → 200 DataResponse<Role>
DELETE /api/v1/admin/roles/{name}                                                                    → 204
```
- 권한: 모두 `authz.roles`, 단 `GET /roles` 는 `authz.roles` 또는 `account.roles`. `holders` 는 `RoleHolders` 가 없으면 `null`.
- 오류(ApiError `code`): `AUTHZ.ROLE_PROTECTED` 409 · `AUTHZ.ROLE_IN_USE` 409 · `AUTHZ.ROLE_EXISTS` 409 · `AUTHZ.ROLE_NOT_FOUND` 404 · `AUTHZ.PERMISSION_UNKNOWN` 400 · `AUTHZ.ESCALATION` 403 · `AUTHZ.SELF_LOCKOUT` 409. 이름 규칙 위반은 기존 검증 400.
- 계정 역할 부여(K2, 기존 경로 `PUT /api/v1/admin/accounts/{id}/roles/{role}`)의 새 오류: `ACCOUNT.ROLE_UNKNOWN` 400 · `ACCOUNT.ROLE_ESCALATION` 403.

### C5. 운영 기록 계약 (K1, 기본 아무것도 안 함)

`OperatorAuditLog.record(OperatorAction(actorId: String, action: String, targetType: String, targetId: String, detail: Map<String, String> = emptyMap()))`. 부르는 곳은 K4 의 역할 변경 셋(`authz.role.created` · `authz.role.updated`(detail `added` · `removed`) · `authz.role.deleted`)뿐.

---

## 워크트리 · 브랜치 · 합치는 순서

| 조각 | 레포 | 브랜치 | 워크트리(제안) | 갈래 시작점 |
|---|---|---|---|---|
| K1 | kotlin | `authz/k1-platform` | `kotlin-skeleton/.claude/worktrees/authz-k1` | `main` |
| K2 | kotlin | `authz/k2-account` | `…/worktrees/authz-k2` | K1 이 지휘 확인을 받은 커밋 |
| K3 | kotlin | `authz/k3-modules` | `…/worktrees/authz-k3` | 같음 |
| K4 | kotlin | `authz/k4-authz` | `…/worktrees/authz-k4` | 같음 |
| R1 | react | `authz/r1-guards` | `react-skeleton/.claude/worktrees/authz-r1` | `main` |
| R2 | react | `authz/r2-roles` | `…/worktrees/authz-r2` | R1 확인 커밋 |

- 순서: **K1 ∥ R1** → **K2 ∥ K3 ∥ K4 ∥ R2(가짜 API)** → kotlin 을 **K2 → K3 → K4** 순으로 합침 → R2 e2e(진짜 백엔드) → 지휘 끝 검사.
- 셋이 같이 만질 수 있는 파일과 주인:

| 파일 | 주인 | 다른 조각 |
|---|---|---|
| `apps/sample/src/main/resources/application.yml` · `application-local.yml` | K3(옛 키 정리 · `CheerPaymentConfiguration` 관련 주석) | K4 는 `skeleton.authz.admin.enabled` 블록만 **파일 끝 `skeleton:` 아래 새 줄**로 — 합칠 때 지휘가 맞춘다. K2 는 손대지 않는다 |
| `apps/sample/build.gradle.kts` · `apps/workbench/build.gradle.kts` | K4 | — |
| `capabilities.json` · `llms.txt` · `docs/capabilities.md` | K4(새 항목) | K1 · K2 · K3 가 `config.prefixes` 를 바꿔야 하면 보고만, 합친 뒤 지휘가 `perl scripts/build-capabilities.pl` |
| `CHANGELOG.md` `[Unreleased]` | **K1 이 이번 일 전체 블록을 쓴다**(Breaking 표 · 행동 변화 · 새 모듈 · react 짝 언급) | K2~K4 는 손대지 않는다. 끝난 모양이 블록과 다르면 지휘가 합칠 때 고친다 |
| `migrations.lock` | K4 | — (K1~K3 는 마이그레이션 없음) |
| `docs/minimal-composition.md` · `docs/new-project-recipe.md` | K4 | — |
| react `packages/api-client/src/errorCodes.ts` | R2 | R1 은 손대지 않는다 |
| react `apps/sample/src/routes/routes.tsx` · `layouts/RootLayout.tsx` | R1 | R2 는 `/admin/roles` 라우트 한 줄 · 메뉴 한 줄만 더한다(R1 위에서 시작하므로 충돌 없음) |

---

### Task K1: platform 계약 + 옛 키 가드 + `/auth/me` permissions (kotlin, 중)

**목표:** C1~C3 · C5 를 코드로. 다른 모듈은 아직 그대로 돈다(토큰 기본 구현).

**Files:**
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/authz/` — `OperatorAccess.kt`(인터페이스 + `require` 의 403 `PLATFORM.FORBIDDEN`), `OperatorRef.kt`, `PermissionDeclaration.kt`, `RolePermissionSource.kt`(+ yml 구현 · 캐시), `RoleHolders.kt`, `OperatorAuditLog.kt`(+ `NoOpOperatorAuditLog`), `TokenOperatorAccess.kt`, `AuthzProperties.kt`(`skeleton.authz`), `LegacyRoleKeysGuard.kt`(옛 키 네 개 — 기동 실패), `AuthzAutoConfiguration.kt` + `.imports` 등록
- Modify: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/AuthController.kt`(`me` 응답에 `permissions`), 필요하면 `auth/principal/CurrentPrincipal.kt` 또는 응답 DTO
- Docs: `docs/modules/platform.md`(authz 절) · `docs/config/modules/platform.yml`(C2 의 platform 키) · `docs/modules/auth.md` · `docs/account-http-contract.md`(`/auth/me` 필드) · `CHANGELOG.md`(위 표의 블록)

**바뀌는 계약:** C1 매핑 기본값, C2 platform 키, C3, C5. 요청 메모: 한 요청 안에서 같은 `accountId` 의 `permissionsOf` 는 한 번만 계산(요청 속성 — 요청 밖 호출은 메모 없이).

**먼저 쓸 실패 시험** (`modules/platform/src/test/kotlin/dev/sumin/skeleton/common/authz/`):
- [ ] `LegacyRoleKeysGuardTest`: 네 키 각각 값이 있으면 컨텍스트 기동 실패, 메시지에 옛 키 이름과 새 자리(스펙 §3.4 의 줄)가 있다. 키가 없으면 기동.
- [ ] `TokenOperatorAccessTest`: 토큰 `ROLE_ADMIN` → `*` 로 모든 권한 `has`=true; `ROLE_MODERATOR` → `board.moderate` 만; 매핑에 없는 역할 → 빈 집합; `skeleton.authz.roles` 를 주면 기본 매핑이 통째로 바뀐다(ADMIN 을 안 적으면 ADMIN 은 권한 없음).
- [ ] `RolePermissionSourceTest`: 다른 역할에 `*` 를 적은 yml → 기동 실패(전권은 super-role 전용); 선언에 없는 권한 키 → 기동 실패; 키 규칙 위반 → 기동 실패.
- [ ] `OperatorAccessRequireTest`: `require` 거절은 403 `PLATFORM.FORBIDDEN`, 익명은 401.
- [ ] `NoOpOperatorAuditLogTest`: 기본 빈이 등록되고 아무것도 쓰지 않는다(로그 출력 없음 — 캡처로 확인), 앱 빈이 있으면 물러난다.
- [ ] auth `MeEndpointTest`(기존 시험 파일이 있으면 거기에): `permissions` 키가 늘 있고 정렬, 토큰 ADMIN → `["*"]`.

**바뀐 범위 시험:**
```bash
./gradlew :modules:platform:test --tests 'dev.sumin.skeleton.common.authz.*' :modules:auth:test
./gradlew :modules:platform:test --tests '*ModuleRegistrationRulesTest' --tests '*ConfigPrefixDocumentationTest' --tests '*ModuleDocumentationTest'
```

**끝 조건:** 위 통과. 기존 모듈은 아직 자기 판정을 쓴다(K2 · K3 에서 바꾼다) — 그 시험은 돌리지 않는다. 보고에 C1~C5 의 최종 모양(타입 시그니처 포함)을 적어 R1 · R2 · K2~K4 에 넘긴다.

---

### Task K2: account + author-profile (kotlin, 중) — 선행 K1

**목표:** `account` 가 있으면 매 요청 저장소의 상태 · 역할로 판정. 관리자 API 를 엔드포인트별 권한으로. 부여 검증 · 상승 막기. super-role.

**Files:**
- Create: `modules/account/src/main/kotlin/dev/sumin/skeleton/account/authz/AccountOperatorAccess.kt`(저장소 판정 — `ACTIVE` 아니면 빈 집합, 토큰 역할 무시), `AccountRoleHolders.kt`, `AccountPermissions.kt`(`PermissionDeclaration` 빈)
- Modify: `account/web/AccountCallers.kt`(`requireAdmin` → `requirePermission(auth, key)`), `account/web/AdminAccountController.kt`(표: 검색 · 상세 · 차단 목록 = `account.read`, suspend · unsuspend · restore · erase · 차단 해제 = `account.manage`, roles PUT/DELETE = `account.roles`), `account/AdminService.kt`(부여: 정의된 역할만 · 상승 막기; 마지막 전권 판정의 역할 이름 = `skeleton.authz.super-role`), `account/AdminBootstrap.kt`(super-role 부여), `account/AccountProperties.kt`(`admin.role` 제거), `account/AccountErrors.kt`(`ROLE_UNKNOWN` · `ROLE_ESCALATION`), account 의 AutoConfiguration(`before` platform authz)
- Modify: `modules/author-profile/src/main/kotlin/dev/sumin/skeleton/authorprofile/web/AuthorProfileAdminWebAutoConfiguration.kt` · `AuthorProfileAdminController.kt`(`author-profile.read`), `AuthorProfilePermissions` 선언
- Docs: `docs/modules/account.md` · `docs/config/modules/account.yml`(`admin.role` 삭제) · `docs/accounts.md`(관리자 · 부트스트랩 · 한계 절 — 「새로고침 즉시」 문단을 「매 요청」으로) · `docs/account-http-contract.md`(새 오류 코드) · `docs/modules/author-profile.md`

**먼저 쓸 실패 시험** (`modules/account/src/test/kotlin/dev/sumin/skeleton/account/authz/`, 기존 `AccountHarness` 사용):
- [ ] **회수 즉시**: ADMIN 토큰 발급 → 저장소에서 ADMIN 회수 → 같은 토큰으로 `GET /api/v1/admin/accounts` 403. 정지된 운영자도 403.
- [ ] **부여 즉시**: 토큰 발급 뒤 `account.read` 를 가진 역할을 저장소에 부여 → 같은 토큰으로 200(토큰 역할을 보지 않는다).
- [ ] **상승 막기**: `account.roles` 만 가진 역할의 운영자가 ADMIN 부여 → 403 `ACCOUNT.ROLE_ESCALATION`, 자기 권한 안의 역할(`MODERATOR` — 단 그가 `board.*` 를 가졌을 때만)은 200.
- [ ] 정의 안 된 역할 부여 → 400 `ACCOUNT.ROLE_UNKNOWN`; `default-roles`(USER)는 허용.
- [ ] **마지막 전권**: `super-role=OWNER` 로 바꾼 앱에서 마지막 OWNER 회수 · 정지 · 삭제 → 기존과 같은 거절(기존 `DeletionAndAdminTest` 의 단정이 이름만 바뀌어 통과).
- [ ] 엔드포인트 표: `account.read` 만 가진 사람은 검색 200 · 정지 403.
- [ ] 부트스트랩이 super-role 을 준다.
- [ ] 한 요청에 저장소 조회 한 번(같은 요청에서 `permissionsOf` 두 번 → 저장소 호출 1회 — 세는 대역).
- [ ] author-profile: `author-profile.read` 없는 ADMIN 아닌 계정 403, 회수 즉시 403.

**바뀐 범위 시험:**
```bash
./gradlew :modules:account:test --tests 'dev.sumin.skeleton.account.authz.*' --tests '*DeletionAndAdminTest' --tests '*AdminBootstrap*'
./gradlew :modules:author-profile:test --tests '*Admin*' :modules:author-profile:noOptionalTest
```

**끝 조건:** 위 통과, `requireAdmin` 호출이 레포에 남지 않음(`grep -rn requireAdmin modules apps` 가 sample 의 `CheerPaymentConfiguration` 말고 0 — 그 파일은 K3 가 지운다). account-jdbc 저장소 계약 시험은 손대지 않았으면 돌리지 않는다.

---

### Task K3: payment · board · legal + sample 정리 (kotlin, 중) — 선행 K1, K2 와 나란히

**목표:** 세 모듈이 `OperatorAccess` 로 판정. 결제는 엔드포인트별 권한 + 옛 교체 지점 호환. 게시판은 `moderator` · `manager`.

**Files:**
- payment: `payment/PaymentAppContracts.kt`(`PaymentOperatorPolicy.permits(authentication, permission)` 기본 메서드 = `isOperator`), `payment/web/PaymentCallers.kt`(`RolePaymentOperatorPolicy` → `OperatorAccess` 기반 기본 정책), `payment/web/PaymentOperatorInterceptor.kt` · `PaymentAdminController.kt`(핸들러 → 권한 표: GET = `payment.read`; `refund` · `approve` · `reject` = `payment.refund`; `sync` · `close` · `retry` = `payment.operate` — 두 겹 같은 표, 표에 없는 핸들러는 기동 실패 또는 거절), `payment/PaymentProperties.kt`(`admin.role` 제거), `payment/web/PaymentHttpAutoConfiguration.kt`, `PaymentPermissions` 선언
- board: `board/BoardModels.kt`(`BoardCaller.manager = false`), `board/web/BoardCallers.kt`(`OperatorAccess` 주입, 로그인한 호출만 묻는다), `board/BoardPolicy.kt`(`canCreateBoard` · 설정 고치기 = `manager`), `board/BoardProperties.kt`(`moderatorRole` 제거), board AutoConfiguration, `BoardPermissions`(기본 역할 `MODERATOR`)
- legal: `legal/web/LegalCallers.kt` · `LegalAdminController.kt`(`legal.read`), `legal/LegalProperties.kt`(`adminRole` 제거), `legal/LegalWebAutoConfiguration.kt`, `LegalPermissions`
- sample: `apps/sample/src/main/kotlin/dev/sumin/skeleton/app/sample/cheer/CheerPaymentConfiguration.kt` **삭제**, `apps/sample/src/main/resources/application.yml`(결제 운영 주석을 새 판정으로)
- Docs: `docs/modules/payment.md` · `docs/payment-http-contract.md`(운영자 판정 · 권한 표 — 168행 「토큰 ≤15분」 문단 교체) · `docs/config/modules/payment.yml` · `docs/modules/board.md` · `docs/board-http-contract.md` · `docs/config/modules/board.yml` · `docs/modules/legal.md` · `docs/legal-http-contract.md` · `docs/config/modules/legal.yml` · `docs/sample.md`(99행)

**먼저 쓸 실패 시험:**
- [ ] payment(`modules/payment/src/test/.../web/`): **CS 역할**(`payment.read`+`payment.refund`) — 환불 승인 200, 바로 환불 200, `sync` 403, `close` 403, `webhook retry` 403, 목록 200. 인터셉터를 끈 조립(컨트롤러만 등록)에서도 같은 결과(두 겹).
- [ ] payment: 앱이 옛 람다 `PaymentOperatorPolicy { true }` 를 주면 모든 운영 경로 통과 · `{ false }` 면 전부 403(호환).
- [ ] payment: 회수 즉시 — `OperatorAccess` 대역이 권한을 빼면 같은 인증으로 다음 호출 403.
- [ ] payment: 권한 표에 없는 새 핸들러가 생기면 실패하는 시험(컨트롤러의 모든 핸들러가 표에 있다 — 리플렉션).
- [ ] board(`modules/board/src/test/.../`): `board.moderate` 없는 로그인 호출자는 숨은 글 404 · 남의 글 숨김 403; 있으면 200. `board.manage` 없으면 게시판 만들기 403. ADMIN(`*`)은 둘 다 된다. 글 주인의 댓글 관리 · 비회원 규칙은 기존 시험 그대로 통과.
- [ ] board: 익명 읽기에서 `OperatorAccess` 를 부르지 않는다(세는 대역 0회).
- [ ] legal: `legal.read` 없는 ADMIN 아닌 계정 403, 있으면 200; 회수 즉시.
- [ ] 세 모듈 `noOptionalTest`: spring-security 또는 선택 모듈이 없는 조립에서 기동(기존 묶음이 통과).

**바뀐 범위 시험:**
```bash
./gradlew :modules:payment:test --tests '*Admin*' --tests '*Operator*' :modules:payment:noOptionalTest
./gradlew :modules:board:test :modules:board:noOptionalTest
./gradlew :modules:legal:test --tests '*Admin*' --tests '*Callers*' :modules:legal:noOptionalTest
./gradlew :apps:sample:test --tests '*Payment*Admin*' --tests '*Board*'
```

**끝 조건:** 위 통과, 옛 키 세 개(`payment.admin.role` · `legal.admin-role` · `board.moderator-role`)가 코드 · 문서 · yml 어디에도 없다(`grep`). 게시판 `ownerModeratesComments` · 비회원 경로 불변.

---

### Task K4: 새 `authz` · `authz-jdbc` (kotlin, 중) — 선행 K1, K2 · K3 와 나란히

**목표:** DB 매핑(씨앗 한 번) · 역할 관리 API(C4) · 권한 카탈로그 · 안전장치 · 역할 변경 `record`. 조립 · 문서 · 카탈로그.

**Files:**
- Create `modules/authz/`: `build.gradle.kts`(`api(platform)`, spring-security `compileOnly` — board 와 같은 방식), `src/main/kotlin/dev/sumin/skeleton/authz/` — `AuthzRoleStore`(포트), `RoleService`(안전장치 · `record`), `web/AuthzAdminController.kt`(C4), `AuthzErrors.kt`(`AUTHZ.*`), `AuthzPermissions`(`authz.roles`), `AuthzAdminProperties`(`skeleton.authz.admin`), AutoConfiguration + `.imports`; `src/noOptionalTest`(account 없는 조립 — `holders` null, 삭제 검사 없음)
- Create `modules/authz-jdbc/`: `JdbcAuthzRoleStore`, `JdbcRolePermissionSource`(platform 의 yml 구현을 덮는다, 캐시 무효화), 씨앗(insert-ignore — `SqlDialect`), `src/main/resources/db/migration/{postgresql,mysql}/V<ts>__authz_roles.sql`(`authz_roles` · `authz_role_permissions`, 스펙 §3.2), `src/dbTest`
- Modify: `settings.gradle.kts`, `apps/sample/build.gradle.kts` · `apps/workbench/build.gradle.kts`(두 모듈 추가), sample yml(`skeleton.authz.admin.enabled: true` — 형 취향 override), `migrations.lock`
- 조립 · 카탈로그: `capabilities.json`(항목 두 개 — `newProjectFlag`, `config.prefixes`, 짝 프런트 `@skeleton/auth`), `perl scripts/build-capabilities.pl`, `scripts/new-project.sh`(`authz` 를 고르면 `authz-jdbc` 를 권하는 NOTES — board 와 같은 방식), `scripts/test-new-project.sh`(아래)
- Docs: `docs/modules/authz.md` · `docs/modules/authz-jdbc.md` · `docs/modules/README.md` 색인 · `docs/config/modules/authz.yml` · `docs/minimal-composition.md`(기동 필요 표) · `docs/new-project-recipe.md`(관리 화면이 있는 작업 예에 `authz,authz-jdbc`) · 계약 문서 `docs/authz-http-contract.md`(C4 — 새 파일, `capabilities.json` 의 문서 경로에)

**new-project 조합 시험** (`scripts/test-new-project.sh`):
- `--quick` 에 단정 둘: `--modules authz,authz-jdbc` 의 닫힘에 `platform` 만 더해지고 `account` 는 따라오지 않는다; `authz` 를 빼면 `authz*` 디렉토리 · 문서 행 · `capabilities.json` 항목이 남지 않는다.
- `--full` 에 조합 하나: `10-authz` = `--modules authz,authz-jdbc`(스타터의 account-jdbc 와 함께 — `RoleHolders` 가 붙는 조립, `authz-jdbc` dbTest 두 방언). 「뺀 조합」은 기존 `1-defaults`(authz 없음)가 맡는다. `--full` 은 지휘가 돌린다.

**먼저 쓸 실패 시험:**
- [ ] **전권 고정**: super-role `PUT` · `DELETE` → 409 `AUTHZ.ROLE_PROTECTED`; 다른 역할 `POST`/`PUT` 에 `*` → 409.
- [ ] **상승 막기**: `authz.roles` 만 가진 운영자가 역할에 `payment.refund` 더하기 → 403 `AUTHZ.ESCALATION`; 자기에게 있는 권한만 더하면 200.
- [ ] **자기 잠금**: 자기 역할에서 `authz.roles` 를 빼는 `PUT` → 409 `AUTHZ.SELF_LOCKOUT`.
- [ ] 사용 중 삭제: `RoleHolders` 대역이 1 → 409 `AUTHZ.ROLE_IN_USE`; 0 → 204.
- [ ] 선언에 없는 권한 → 400; 같은 이름 → 409; 없는 이름 → 404; 꺼진 스위치 → 404.
- [ ] **매핑 즉시**: 역할에서 권한을 빼면 같은 인스턴스의 다음 `OperatorAccess.has` 가 false(캐시 무효화); `cache-ttl` 이 지나면 다른 인스턴스(두 번째 소스 객체)도 false.
- [ ] 씨앗: 두 번 기동해도 행이 한 번, 기동 사이 yml 을 바꿔도 표 불변, 운영자가 고친 `MODERATOR` 를 덮지 않는다.
- [ ] `record`: 성공한 생성 · 수정 · 삭제가 기록 대역에 한 번씩(수정 detail = `added` · `removed`), 실패(409 등)는 0회.
- [ ] dbTest(`authz-jdbc/src/dbTest`): 두 방언에서 저장소 계약 · 씨앗 insert-ignore · FK cascade.
- [ ] `authz` 를 뺀 조립(K1 의 yml 매핑)과 `account` 를 뺀 조립(`noOptionalTest`)에서 기동.

**바뀐 범위 시험:**
```bash
./gradlew :modules:authz:test :modules:authz:noOptionalTest :modules:authz-jdbc:test :modules:authz-jdbc:postgresTest :modules:authz-jdbc:mysqlTest
./gradlew :modules:platform:test --tests '*CapabilitiesCatalogTest' --tests '*ModuleDocumentationTest' --tests '*ConfigPrefixDocumentationTest' --tests '*MigrationLockTest'
bash scripts/test-new-project.sh --quick
```

**끝 조건:** 위 통과, `perl scripts/build-capabilities.pl --check` 깨끗, `migrations.lock` 에 새 두 파일.

---

### Task R1: 권한 가드 · 메뉴 · 결제 단추 (react, 중) — 계약 C1 · C3 만, K1 과 나란히

**목표:** 화면이 `permissions` 로 숨긴다. 하드코딩 역할 이름 제거(R2 가 맡는 `assignableRoles` 제외).

**Files:**
- `packages/auth/src/types.ts`(`AuthPrincipal.permissions: string[]`), `packages/auth/src/session.ts`(me 응답에 없으면 `[]`), `packages/auth/src/principal.ts`(토큰 해독본은 `permissions: []`), Create `packages/auth/src/permissions.ts`(`hasPermission` — `*` 처리), `usePermission.ts`, `RequirePermission.tsx`(+ `.stories.tsx` · `play`), `packages/auth/src/index.ts` 내보내기, `packages/auth/README.md`
- `packages/payment/src/admin/` — 부품에 `can?: (key: string) => boolean`(기본 모두 true — 기존 앱 불변): `AdminOrderPanel.tsx`(맞추기 · 닫기 = `payment.operate`, 환불 = `payment.refund`), `RefundRequestTable.tsx`(승인 · 거절 = `payment.refund`), `WebhookEventTable.tsx`(재시도 = `payment.operate`), `adminAccess.ts` 주석(ADMIN → 권한), 스토리 하나에 `can` 이 막는 경우
- `packages/auth/src/admin/AdminAccounts.tsx` · `rowActions.ts`(`can` prop — 정지 등 = `account.manage`, 역할 = `account.roles`)
- sample: `apps/sample/src/routes/routes.tsx`(`RequirePermission` — `/admin/accounts` = `account.read`, `/admin/payments` = `payment.read`), `apps/sample/src/layouts/RootLayout.tsx`(`AdminLinks` 항목별), `apps/sample/src/routes/AdminPaymentsPage.tsx`(`can` 넘김), `AdminAccountsPage.tsx`(`can`), i18n 문구
- `docs/capabilities.md`(생성물 — `capabilities.json` 고친 뒤 `node scripts/build-capabilities.mjs`), `CHANGELOG.md` `[Unreleased]`(react 쪽 블록 — R1 이 쓰고 R2 는 줄만 더한다)

**먼저 쓸 실패 시험:**
- [ ] `permissions.test.ts`: `*` 는 모든 키 통과, 빈 목록은 모두 거절, `anyOf`.
- [ ] `RequirePermission` 렌더 시험: 익명 → `/login`(state.from), 권한 없음 → forbidden(세션 유지), 있음 → children. `RequireRole` 기존 시험 그대로 통과.
- [ ] `session` 시험: me 응답에 `permissions` 가 없어도(옛 서버) `[]` 로 채워 터지지 않는다.
- [ ] payment admin: `can = k => k !== 'payment.operate'` 면 맞추기 · 닫기 · 재시도 단추가 없고 환불은 있다; `can` 없으면 전부 있다.
- [ ] sample: `permissions: ['payment.read','payment.refund']` 인 사용자의 메뉴에 결제만, `/admin/accounts` 직접 진입 → 차단 안내.
- [ ] 하드코딩: `apps/sample/src` 에 `'ADMIN'` · `'MODERATOR'` 문자열이 `AdminAccountsPage.tsx`(R2 가 지움) 말고 없다(grep 단정 시험 — 기존 `tests/` 규칙 시험 방식).

**바뀐 범위 시험:**
```bash
pnpm --filter @skeleton/auth test -- permissions RequirePermission session
pnpm --filter @skeleton/payment test -- admin
pnpm --filter sample test -- routes RootLayout
pnpm --filter @skeleton/auth --filter @skeleton/payment --filter sample typecheck
```

**끝 조건:** 위 통과, 스토리 추가분이 `docs/ui-catalog.md` 표에 있다(`tests/stories.test.ts`).

---

### Task R2: 역할 관리 화면 + e2e (react, 중) — 선행 R1, e2e 만 K2 · K3 · K4 합친 뒤

**목표:** `@skeleton/auth/admin` 에 `AdminRoles`, sample `/admin/roles`, 계정 화면의 줄 수 있는 역할을 API 로. 스펙 §8.2 e2e.

**Files:**
- Create `packages/auth/src/admin/rolesApi.ts`(`createAdminRolesApi(client)` — C4 다섯 호출), `AdminRoles.tsx`(+ `.module.css`, 목록 · 만들기 · 고치기 · 지우기 `Dialog`, 권한을 `module` 별 체크 묶음, `protected` 행 잠금, 오류 코드별 문구), `AdminRoles.stories.tsx`(가짜 API — `src/stories/`), `rolesErrors.ts`; `packages/auth/src/admin/index.ts` 내보내기
- `packages/api-client/src/errorCodes.ts` + `errorCodes.test.ts`(`AUTHZ.*` 일곱 · `ACCOUNT.ROLE_UNKNOWN` · `ACCOUNT.ROLE_ESCALATION`)
- sample: Create `apps/sample/src/routes/AdminRolesPage.tsx`, `routes.tsx`(`/admin/roles` = `authz.roles`), `RootLayout.tsx`(메뉴 한 줄), `AdminAccountsPage.tsx`(`assignableRoles` 를 `GET /admin/roles` 이름으로 — 하드코딩 제거), i18n ko/en(권한 이름 라벨 — 모르는 키는 키 그대로)
- e2e: Create `apps/sample/e2e/authzRoles.e2e.ts`(기존 `paymentSteps.ts` · `paymentAdmin.e2e.ts` 의 열린 환불 요청 만들기 재사용)
- `docs/ui-catalog.md` 행, `CHANGELOG.md` 줄

**먼저 쓸 실패 시험:**
- [ ] `rolesApi.test.ts`: 경로 · 메서드 · 본문이 C4 그대로.
- [ ] `AdminRoles` 렌더: `protected` 행은 편집 · 삭제 단추가 잠김; 409 `ROLE_IN_USE` → 「이 역할을 가진 계정이 있어 지울 수 없다」류 안내; 403 `ESCALATION` · 409 `SELF_LOCKOUT` 각 문구; 모르는 권한 키는 키 그대로 표시.
- [ ] 스토리 `play`: 역할 만들기 → 체크 두 개 → 저장 → 목록에 보인다.
- [ ] `AdminAccountsPage`: 줄 수 있는 역할이 API 응답에서 온다(가짜 API 에 `CS` 를 넣으면 보인다).
- [ ] e2e `authzRoles.e2e.ts`(스펙 §8.2 넷): admin 이 `CS` 만들고 `acc_user` 에 부여 → user 는 결제 메뉴만, 환불 승인 성공, 맞추기 단추 없음 + 직접 호출 403 → admin 이 회수 → user 의 다음 동작 403(다시 로그인 없이) → 가진 계정이 있을 때 삭제 안내 · 전권 행 잠금.

**바뀐 범위 시험:**
```bash
pnpm --filter @skeleton/auth test -- admin
pnpm --filter @skeleton/api-client test -- errorCodes
pnpm --filter sample test -- AdminRoles AdminAccounts
pnpm --filter @skeleton/auth --filter sample typecheck
# e2e 한 파일 — K2 · K3 · K4 합친 kotlin 백엔드(scripts/sample-e2e-backend.sh)로
pnpm --filter sample e2e -- authzRoles
```

**끝 조건:** 위 통과(e2e 는 kotlin 셋을 합친 뒤 한 번), `apps/sample/src` 에 역할 이름 하드코딩 0.

---

## 끝 검사 (지휘)

- kotlin: 세 갈래를 K2 → K3 → K4 로 합친 뒤 `perl scripts/build-capabilities.pl` → `kverify`(전체 build · `test-new-project.sh --full` 의 새 `10-authz` 포함 · 배포 계약) 한 번.
- react: R1 → R2 합친 뒤 `pnpm test` · `pnpm typecheck` · `pnpm lint` · `pnpm e2e:sample` 한 번.
- 리뷰: kotlin 묶음 끝 1회 · react 묶음 끝 1회(`code-reviewer`, Review Focus 위주, 시험 재실행 안 시킴), 확인(`qa`) 1회 — 스펙 §8.2 를 진짜 화면으로.
- CHANGELOG 두 레포의 `[Unreleased]` 블록이 끝난 모양과 맞는지 지휘가 합칠 때 본다.
