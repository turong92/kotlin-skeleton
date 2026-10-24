# 권한 — 운영자 판정 통일 · 역할→권한 · 운영 기록 자리 — 설계

> 2026-10-24. 요청(원문 요지): 「통일 + 감사 + 세부 권한」 — 운영자 판정을 한 곳(공용 검사기)으로 모으고, 계정 모듈이 있으면 모든 모듈이 매 호출 DB 로 재확인해 회수가 즉시 먹게. 역할 이름 설정도 한 묶음으로, 게시판 · 약관도 갈아끼울 수 있게. 운영자 행동을 한 표에 남겨(누가 · 언제 · 무엇을) 관리 화면에서 본다. 역할 → 권한 묶음(예: 결제 환불, 게시판 관리)을 두고 역할을 관리 화면에서 만들고 권한을 고른다(CS 담당자는 환불만 등). — 운영 기록은 결정 3 으로 「자리만」이 됐다.
> 대상: kotlin-skeleton(`platform` · `auth` · `account` · `author-profile` · `payment` · `board` · `legal` · 새 `authz` · `authz-jdbc` · `apps/sample` · `apps/workbench`) + react-skeleton(`@skeleton/auth` · `@skeleton/auth/admin` · `@skeleton/payment/admin` · `apps/sample`).
> 현황 조사: 2026-10-24 researcher(K main a4d8eb73, R main afa534b). 판정 다섯 갈래(account · author-profile 저장소 재조회, payment 토큰 · 교체 가능, board · legal 토큰만), 역할 이름 설정 네 곳, 공용 인가 없음, 운영 기록 모듈마다 다름.

## 결정 (2026-10-24, 형)

| # | 물음 | 형 원문 | 결정 |
|---|---|---|---|
| 1 | 역할 표 · 관리 API 를 둘 곳 | (추천대로) | 새 모듈 **`authz` + `authz-jdbc`**. 붙이면 역할 관리가 생기고, 빼면 yml 고정 역할로 돈다 |
| 2 | 옛 역할 키 네 개 | (추천대로) | **지우고, 값이 있으면 기동 실패** + 새 키 안내(§3.4) |
| 3 | 운영 기록 | 「이력 굳이 다 남겨야되나? 남길거면 다 남기고 아닐거면 다 아니고.」 → 비용 · 효용 설명 뒤 「안 남김, 자리만」 | **공용 운영 기록은 만들지 않는다**(표 · 마이그레이션 · 보존 삭제 · 조회 API · 기록 화면 · `authz.audit.read` 없음). `platform` 에 계약 `OperatorAuditLog`(기본 = 아무것도 안 함)만 두고 부르는 곳은 새 코드인 역할 변경뿐(§5). 기존 모듈 기록은 그대로. 앞선 「바꾸는 행동만 · 1년」을 대체한다 |
| 4 | react legal 운영 화면 | (추천대로) | 이번에는 안 한다. `legal.read` 권한은 이번에 들어간다 |

## 0. 비목표

- **공용 운영 기록**(결정 3). 넣을 자리: `OperatorAuditLog` 구현 빈 하나(표 저장 등)를 끼우고, 기존 모듈의 「누가 · 무엇을」 출처를 그 빈으로 옮기는 어댑터를 단다 — account 는 계정 이벤트(`ACCOUNT_SUSPENDED` · `UNSUSPENDED` · `RESTORED` · `ERASED_BY_ADMIN` · `REGISTRATION_BLOCK_REMOVED` · `ROLE_GRANTED` · `REVOKED`, 모두 `detail.by` = 운영자), board 는 `BoardEventListener`(`BoardActor(MODERATOR, accountId)`), payment 는 장부 이력(`PaymentActor.OPERATOR` + id). 그래서 이번에 그 모듈들에 부르는 줄을 꽂지 않는다 — 이미 있는 출처를 두 번 만들 뿐이다. 개인정보 조회 기록이 필요해지면 조회 엔드포인트(`AdminAccountController.search`/`get` · `AuthorLinkLookup` · `LegalAdminController.consents`)에서 같은 계약을 부른다.
- react legal 운영 화면(결정 4).
- 대상별 권한(「이 게시판만 관리」 · 「이 주문만」), 속성 기반 판정(ABAC), 거절 규칙(deny), 역할 상속.
- 외부 IdP 의 그룹 → 역할 동기화, 권한을 JWT 에 싣기(크고 낡는다 — 화면은 `/auth/me` 로 받는다).
- 2인 승인(4-eyes), 역할을 바꿀 때 그 계정의 세션 끊기.
- 기계 호출자: `job-queue-jdbc` 깨우기(`X-Wake-Secret` 공유 비밀)는 사람 운영자가 아니다 — 그대로. `alert` · `notification` · `storage` 는 운영 API 가 없다 — 대상 아님.
- 기존 기능 지우기: `RequireRole` · JWT `roles` 클레임 · `account_audit` · 결제 장부 이력 · board `moderated_by` · `AUTHOR_LINK_VIEWED` 와 author-profile 배포 가드는 그대로 둔다.

## 1. 범위

| | 1차(끝에서 끝까지 한 벌) | 다음에 넓힐 것 |
|---|---|---|
| 판정 | 모든 운영 경로가 `OperatorAccess.require(호출자, 권한)` 하나로 판정. 계정 모듈이 있으면 매 요청 저장소의 계정 상태 · 역할로(부여 · 회수 즉시), 없으면 토큰의 역할로 | 대상별 권한 |
| 권한 | 모듈이 자기 권한을 선언(§3.1), 역할 → 권한 매핑. `authz` 없으면 yml 고정 매핑, 있으면 DB 표 + 관리 API | 역할 상속 · 기간제 역할 |
| 운영 기록 | 계약 자리만(§5) | 구현 빈 · 어댑터(§0) |
| 화면 | 역할 · 권한 관리, `RequirePermission` · 메뉴 숨김, 하드코딩 역할 이름 제거 | legal 운영 화면(결정 4) |
| e2e | 관리자가 CS 역할(결제 보기 + 환불)을 만들어 시드 사용자에게 주면 그 사용자는 결제 메뉴만 보이고 환불은 되고 「결제사와 맞추기」는 서버가 403. 회수하면 **다시 로그인하지 않아도** 다음 호출이 403 | |

## 2. 확장 지점 (developer 는 이것만 만든다)

| 지점 | 자리 | 고른 이유 |
|---|---|---|
| `OperatorAccess`(`permissionsOf(OperatorRef): Set<String>` · `has` · `require`) | `platform` `common/authz` | board · payment · legal 은 `auth` · `account` 를 모른다(호출자를 `Authentication.name` 으로만 읽는다). 공용 계약은 이미 `SignUpConsentGate` · `AccountErasureListener` 처럼 `platform` 에 둔다. **모든 모듈의 교체 지점이 이 빈 하나** — 게시판 · 약관도 이것으로 갈아끼운다 |
| `OperatorRef(accountId, tokenRoles, ip)` | `platform` | `platform` 은 spring-security 를 모른다. 각 모듈의 `*Callers` 가 이미 하는 `Authentication` 풀기를 그대로 두고 결과만 넘긴다 |
| `PermissionDeclaration`(모듈별 빈: 권한 키 · 모듈 · 기본 역할) | `platform` | 권한 카탈로그는 「붙은 모듈이 가진 권한」이어야 한다 — 모듈을 빼면 그 권한도 화면에서 사라진다 |
| `RolePermissionSource`(역할 → 권한) | `platform` 기본(yml) · `authz-jdbc` 교체(DB) | 역할 관리 화면이 없는 앱은 yml 로 충분하다. 모듈 하나를 더하면 DB 가 진실이 된다 |
| `RoleHolders`(역할별 활성 보유 계정 수) | 계약 `platform`, 구현 `account` | 「가진 계정이 있으면 역할 삭제 불가」를 `authz` 가 `account` 를 모르고도 판정 |
| `OperatorAuditLog.record(OperatorAction)`(기본 = 아무것도 안 함) | `platform` | 결정 3 의 「자리」. 나중에 구현 빈 하나만 끼우면 되게 모양만 정해 둔다 |
| `PaymentOperatorPolicy.permits(authentication, permission)`(기본 메서드 = `isOperator`) | `payment` | 기존 교체 지점 호환 — 람다 하나로 갈아끼운 앱은 그대로 「전부 아니면 없음」으로 돈다 |
| `BoardCaller.manager`(기본 false) | `board` | 게시판 만들기 · 설정을 `board.manage` 로 나눈다. 기본값이 있어 기존 생성 코드가 깨지지 않는다 |

## 3. 권한 모델

### 3.1 권한 문자열 — `<모듈>.<동사>`, 모듈이 선언

| 모듈 | 권한 | 여는 것 |
|---|---|---|
| account | `account.read` | 계정 검색 · 상세 · 재가입 차단 목록 |
| | `account.manage` | 정지 · 해제 · 복구 · 지우기 · 차단 해제 |
| | `account.roles` | 계정에 역할 주기 · 회수 |
| author-profile | `author-profile.read` | 작성자 연결 조회(관리자) |
| payment | `payment.read` | 주문 · 환불 · 웹훅 목록과 상세 |
| | `payment.refund` | 환불 요청 승인 · 거절, 바로 환불 |
| | `payment.operate` | 결제사와 맞추기 · 멈춘 결제 닫기 · 웹훅 재시도 |
| board | `board.moderate` | 남의 글 · 댓글 숨김 · 복원 · 삭제 · 고정 · 승인, 숨은 글 보기 |
| | `board.manage` | 게시판 만들기 · 설정 고치기 |
| legal | `legal.read` | 문서 목록 · 동의 기록 조회(관리자) |
| authz | `authz.roles` | 역할 만들기 · 고치기 · 지우기, 권한 카탈로그 |

- 키 규칙 `^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$`, 64자 이하. `*` 는 전권(§3.3) 전용.
- 기존 `account` 관리자 API · `author-profile` · `legal` · `payment` 의 「ADMIN 이면 다」는 위 표로 나뉜다. 한 엔드포인트 = 한 권한(§4.3).

### 3.2 역할 → 권한 매핑

- **`authz` 없음**: `skeleton.authz.roles`(yml 맵)가 진실. 모듈 기본값은 선언의 `defaultRoles` 로 만든 `ADMIN: ["*"]`, `MODERATOR: ["board.moderate", "board.manage"]` — 앱 yml 이 맵을 주면 그 맵이 통째로 이긴다.
- **`authz-jdbc` 있음**: 표가 진실. 첫 기동에 위 매핑을 씨앗으로 넣고(insert-ignore — 이미 있는 역할은 건드리지 않는다), 이후 yml 을 바꿔도 표에 반영하지 않는다(Keycloak realm 가져오기와 같은 「처음 한 번」). 문서에 적는다.
- 표(두 방언, `authz-jdbc` V 파일 하나):
  - `authz_roles(name varchar(40) pk, description varchar(200), protected boolean, created_at, updated_at)` — 이름 규칙은 계정 역할과 같다(`^[A-Z][A-Z0-9_]{1,31}$`).
  - `authz_role_permissions(role varchar(40) fk → authz_roles on delete cascade, permission varchar(64), pk(role, permission))`.
- 매핑에 없는 역할(예: 기본 `USER`)은 권한이 없다 — 오류 아님.

### 3.3 기본 역할 · 전권

- 전권 역할은 하나, 이름은 `skeleton.authz.super-role`(기본 `ADMIN`), 권한 `*`. **고정** — 권한 편집 · 이름 변경 · 삭제가 안 되고(`protected`), 다른 역할에 `*` 를 줄 수 없다. 그래서 「마지막 전권」 판정은 지금의 「마지막 ADMIN」 원자 판정(`AdminService` · 저장소)을 역할 이름만 `super-role` 로 바꿔 그대로 쓴다.
- `MODERATOR` 는 씨앗일 뿐 — 고치고 지울 수 있다(가진 계정이 있으면 삭제 409).
- 행동 변화: ADMIN 이 이제 게시판도 관리한다(전권). 지금은 MODERATOR 만 된다 — CHANGELOG 에 적는다.
- 첫 관리자 부트스트랩(`skeleton.account.bootstrap.admin-email`)은 `super-role` 을 준다.

### 3.4 옛 설정 키 이행 (깨지는 변경)

| 옛 키 | 새 자리 |
|---|---|
| `skeleton.account.admin.role` | `skeleton.authz.super-role` |
| `skeleton.payment.admin.role` | `skeleton.authz.roles.<역할>: [payment.read, payment.refund, payment.operate]` |
| `skeleton.legal.admin-role` | `skeleton.authz.roles.<역할>: [legal.read]` |
| `skeleton.board.moderator-role` | `skeleton.authz.roles.<역할>: [board.moderate, board.manage]` |

- 네 키를 지운다. 값이 하나라도 있으면 **기동 실패**, 메시지에 위 줄을 그대로 보인다(결정 2). `*.admin.enabled` 스위치들은 그대로.
- CHANGELOG `[Unreleased]` 에 「Breaking」 줄 + 위 표. sample 의 `CheerPaymentConfiguration`(requireAdmin 으로 갈아끼운 정책)은 기본이 같은 일을 하므로 지운다.

## 4. 판정

### 4.1 공용 검사기

| 조립 | `OperatorAccess` 구현 | 회수 반영 |
|---|---|---|
| `account` 있음 | `AccountOperatorAccess`(account) — 저장소의 계정을 읽어 `ACTIVE` 가 아니면 빈 집합, 아니면 **저장소의 역할**을 매핑에 통과. 토큰의 역할은 보지 않는다 | 부여 · 회수 · 정지 모두 다음 요청부터 |
| `account` 없음 | `TokenOperatorAccess`(platform 기본) — 토큰 `ROLE_<x>` → 매핑 | 토큰 만료까지(≤15분, 지금과 같다) |

- 등록: platform 기본은 `@ConditionalOnMissingBean`, account 쪽이 먼저(`before`). 앱이 빈 하나로 둘 다 갈아끼울 수 있다.
- 비용: 계정 행 PK 조회 한 번 + 역할 행 — **한 요청에 한 번**(요청 속성에 메모), 요청 사이 캐시 없음. 매핑(역할 → 권한)만 프로세스 메모리 캐시: 같은 인스턴스의 역할 변경은 즉시 무효화, 다른 인스턴스 대비 30초 만료(`skeleton.authz.cache-ttl`, `0` 이면 끔). 세션 기반 프레임워크의 국룰(Django: 요청마다 사용자 · 권한 로드, Laravel spatie/permission: 역할 → 권한만 캐시 + 변경 시 무효화)과 같다. 운영 경로만 묻고 board 는 로그인한 호출에서만 묻는다.
- 역할 부여(account): 정의된 역할(매핑에 있거나 `default-roles`)만 — 아니면 `400 ACCOUNT.ROLE_UNKNOWN`. **권한 상승 막기**: 주려는 역할의 권한이 호출자 권한의 부분집합이 아니면 `403 ACCOUNT.ROLE_ESCALATION`(Kubernetes RBAC 의 escalation 규칙). 전권은 다 가진다.

### 4.2 `/auth/me`

`CurrentPrincipal` 응답에 `permissions: string[]`(검사기 결과, `*` 그대로) 를 더한다 — 추가만, 기존 필드 불변. 화면은 이것으로 숨기고, 진짜 검사는 서버.

### 4.3 모듈별 바꾸는 것

| 모듈 | 지금 | 바뀐 뒤 |
|---|---|---|
| account | `requireAdmin` — 토큰 ADMIN + 저장소 재조회 | `requirePermission(auth, 권한)` 엔드포인트마다(§3.1). 마지막 전권 · 자기 정지 금지 유지 |
| author-profile | account `requireAdmin` 재사용 | `author-profile.read` |
| payment | 인터셉터 → `PaymentOperatorPolicy.isOperator`(기본 토큰) | 인터셉터가 핸들러 → 권한 표(GET = `read`, 환불 셋 = `refund`, sync · close · retry = `operate`)로 `policy.permits` 를 부른다. 기본 정책 = `OperatorAccess`. 앱이 준 옛 람다는 `permits` 기본 메서드로 전부-아니면-없음. 「두 겹」(컨트롤러 `requireOperator`)도 같은 표 |
| board | `BoardCallers` 가 토큰 `ROLE_MODERATOR` | `moderator = has(board.moderate)`, `manager = has(board.manage)`. `BoardPolicy.canCreateBoard` · 설정 고치기는 `manager`. 글 주인의 댓글 관리 · 글쓴이 본인 규칙은 그대로 |
| legal | `LegalCallers` 토큰 `ROLE_ADMIN` | `requirePermission(legal.read)` |
| authz(새) | — | 자기 API 를 `authz.roles` 로 |

거절은 지금과 같이 401(익명) · 403(`PLATFORM.FORBIDDEN`). 응답 모양 불변.

## 5. 운영 기록 자리 (결정 3)

- 계약(`platform` `common/authz`): `OperatorAuditLog.record(OperatorAction(actorId, action, targetType, targetId, detail: Map<String, String>))`. 기본 빈은 **아무것도 하지 않는다**(`@ConditionalOnMissingBean` — 앱 · 다른 모듈이 빈 하나로 대신한다). 로그도 쓰지 않는다(지금 없는 출력을 새로 만들지 않는다).
- 모양 규칙만 KDoc 에 적는다: `action` 은 `<모듈>.<대상>.<과거형>`, 대상은 id 로만(이메일 · 이름 · 본문 · 사유 원문 · 토큰 금지), 성공한 행동만, 쓰기 트랜잭션 안에서 부른다(나중의 DB 구현이 함께 커밋 · 롤백되게).
- **부르는 곳은 `authz` 역할 변경 셋뿐**: `authz.role.created` · `updated`(detail = 더한 · 뺀 권한) · `deleted`. 역할 변경은 다른 출처가 없는 새 코드라 여기서 한 줄씩 꽂는 것이 가장 싸다.
- account · payment · board 에는 꽂지 않는다 — 「누가 · 무엇을」이 이미 계정 이벤트 · 장부 이력 · `BoardEventListener` 로 나온다(§0 넣을 자리). 기존 기록(`account_audit` · 장부 이력 · `moderated_by` · `AUTHOR_LINK_VIEWED`)은 그대로.

## 6. 역할 관리 API (`authz`, `skeleton.authz.admin.enabled` 기본 꺼짐 → 404)

| 경로 | 권한 | 비고 |
|---|---|---|
| `GET /api/v1/admin/permissions` | `authz.roles` | 카탈로그: `[{key, module}]`(붙은 모듈의 선언만) |
| `GET /api/v1/admin/roles` | `authz.roles` · `account.roles` 중 하나 | `[{name, description, permissions, protected, holders}]` — 계정 화면의 「줄 수 있는 역할」도 이것 |
| `POST /api/v1/admin/roles` | `authz.roles` | `{name, description, permissions}` |
| `PUT /api/v1/admin/roles/{name}` | `authz.roles` | `{description, permissions}` — 이름은 못 바꾼다(계정 역할 행이 이름을 가리킨다) |
| `DELETE /api/v1/admin/roles/{name}` | `authz.roles` | |

안전장치(오류 코드 `AUTHZ.*`, 프런트 `errorCodes.ts` 에도):
- 전권 역할 편집 · 삭제, 다른 역할에 `*` → `409 AUTHZ.ROLE_PROTECTED`.
- 가진 활성 계정이 있는 역할 삭제 → `409 AUTHZ.ROLE_IN_USE`(`RoleHolders`; `account` 가 없으면 검사 없이 지운다).
- 선언에 없는 권한 → `400 AUTHZ.PERMISSION_UNKNOWN`. 같은 이름 → `409 AUTHZ.ROLE_EXISTS`. 없음 → `404 AUTHZ.ROLE_NOT_FOUND`.
- 권한 상승: 자기에게 없는 권한을 역할에 더하기 → `403 AUTHZ.ESCALATION`.
- 자기 잠금: 변경 뒤 호출자가 `authz.roles` 를 잃으면 → `409 AUTHZ.SELF_LOCKOUT`.
- 성공한 변경은 `OperatorAuditLog.record` 를 부른다(§5).

## 7. 화면 (react-skeleton)

- `@skeleton/auth`: `AuthPrincipal.permissions`(없으면 `[]`), `hasPermission(principal, key)`(`*` 처리), `usePermission(key | keys)`, `RequirePermission { permission | anyOf, redirectTo, forbidden }` — `RequireRole` 과 같은 401/403 의미. `RequireRole` 은 남긴다(문서는 `RequirePermission` 을 권한다).
- `@skeleton/auth/admin`: `AdminRoles`(목록 · 만들기 · 고치기 · 지우기, 권한을 모듈별 체크 묶음으로, 전권 행은 잠금 표시, 오류 코드별 안내), `createAdminRolesApi`, 스토리 + `play`. `AdminAccounts` 의 행 동작은 권한으로 숨긴다(`can` prop), `assignableRoles` 는 남기되 sample 은 `GET /admin/roles` 로 채운다.
- `@skeleton/payment/admin`: 단추를 `can(key)` prop 으로 숨긴다(환불 = `payment.refund`, 맞추기 · 닫기 · 재시도 = `payment.operate`). 패키지는 `@skeleton/auth` 를 import 하지 않는다 — 앱이 넘긴다.
- 권한 이름 문구: ko/en 라벨(`labels` prop), 모르는 키는 키 그대로.
- sample: 라우트 가드를 `RequirePermission` 으로(`/admin/accounts` = `account.read`, `/admin/payments` = `payment.read`, 새 `/admin/roles` = `authz.roles`), `AdminLinks` 는 항목마다 권한, `'ADMIN'` · `'MODERATOR'` 하드코딩 제거.
- starter · starter-ssr: 운영 화면이 없으므로 그대로.

## 8. 검증 목록 (깨지면 사고 나는 것만)

### 8.1 kotlin 시험
- 회수 즉시: 유효한 액세스 토큰을 쥔 채 역할 회수 · 계정 정지 → 다음 운영 호출 403(account · payment · board · legal · author-profile 각각 한 건).
- CS 역할(`payment.read` + `payment.refund`): 환불 승인 200, sync 403, 계정 검색 403. 옛 람다 `PaymentOperatorPolicy` 앱은 전부 통과.
- 상승 막기: `account.roles` 만 가진 사람이 ADMIN 부여 → 403. `authz.roles` 만 가진 사람이 역할에 `payment.refund` 더하기 → 403.
- 전권 고정 · 사용 중 삭제 · 자기 잠금 · 마지막 전권 회수(기존 시험 이름만 바꿔 유지).
- 토큰 경로(`account` 없음, workbench 조립): 토큰 ADMIN → 전권, 매핑 yml override 가 이긴다.
- 옛 키 하나라도 있으면 기동 실패 + 메시지에 새 키.
- `authz-jdbc`: 두 방언 `dbTest`(`postgresTest` · `mysqlTest`), 씨앗은 두 번 기동해도 한 번. 역할 변경 셋이 기록용 대역 빈에 한 번씩 닿고, 실패한 변경은 닿지 않는다.
- 카탈로그: `authz` 를 뺀 조립(`noOptionalTest` 식)에서 기동 · yml 매핑으로 판정.
- 문서 가드: `CapabilitiesCatalogTest` · `ModuleDocumentationTest` · `MigrationLockTest` 통과.

### 8.2 e2e (react sample + kotlin sample, 시드 계정)
1. admin 로그인 → 역할 화면에서 `CS`(결제 보기 + 환불) 만들기 → 계정 화면에서 시드 `acc_user`(`user@example.com`) 에 `CS` 부여.
2. user 로그인 → 메뉴에 결제만, 계정 · 역할 메뉴 없음. 열린 환불 요청 승인 성공. 「결제사와 맞추기」 단추 없음, 직접 호출 403.
3. admin 이 `CS` 회수 → user 화면에서 다음 동작이 403(차단 안내), 다시 로그인 없이.
4. `CS` 를 가진 계정이 있을 때 삭제 → 안내 문구(409). 전권 행은 편집 칸이 잠겨 있다.

## 9. 작업 목록 — 6조각

| # | 레포 | 조각 | 선행 | 크기 |
|---|---|---|---|---|
| K1 | kotlin | `platform` 계약(§2: `OperatorAccess` · `OperatorRef` · `PermissionDeclaration` · `RolePermissionSource` yml 기본 · `RoleHolders` · `OperatorAuditLog` 빈 기본 · `skeleton.authz.roles` / `super-role` / `cache-ttl`) + 옛 키 기동 실패 + `auth` `/auth/me` `permissions` + 계약 문서 | — | 중 |
| K2 | kotlin | `account`(검사기 · 선언 · 엔드포인트별 권한 · 부여 검증 · 상승 막기 · `super-role` · `RoleHolders`) + `author-profile`(권한만) + 문서 | K1 | 중 |
| K3 | kotlin | `payment`(인터셉터 권한 표 · `permits` 호환) · `board`(`manager` · 판정) · `legal`(권한만) + sample `CheerPaymentConfiguration` 삭제 · yml + 문서 | K1 (K2 와 나란히) | 중 |
| K4 | kotlin | 새 `authz` · `authz-jdbc`: 역할 표 두 방언 · 씨앗 · DB 매핑 · 역할 API · 카탈로그 · 안전장치 · 역할 변경 `record` + `capabilities.json` · 모듈 문서 · `new-project.sh` 조각 · sample/workbench 조립 | K1 (K2 · K3 와 나란히) | 중 |
| R1 | react | `@skeleton/auth` `permissions` · `hasPermission` · `usePermission` · `RequirePermission` + sample 라우트 · 메뉴 + `@skeleton/payment/admin` `can` | 계약(§4.2)만 — K1 과 나란히 | 중 |
| R2 | react | `@skeleton/auth/admin` `AdminRoles` · API · 스토리 + sample 역할 화면 · 역할 목록 API 로 채우기 + e2e(§8.2) | R1 · (e2e 만) K2 · K3 · K4 | 중 |

- 줄기: **K1 ∥ R1** → **K2 ∥ K3 ∥ K4 ∥ R2(가짜 API 로 화면까지)** → R2 e2e. 나란히 도는 kotlin 셋이 같이 만지는 곳은 `apps/sample` yml · `capabilities.json` · CHANGELOG 뿐 — 합칠 때 K2 → K3 → K4 순서로 지휘 세션이 맞춘다.
- developer 6회, 묶음(kotlin 끝 · react 끝)마다 리뷰 1 + 확인 1. 전체 스위트는 kotlin 셋을 합친 뒤 지휘 세션이 한 번, react 도 끝에 한 번.

## 정한 것

형이 고른 네 가지는 맨 위 「결정」 표(1 새 `authz` · `authz-jdbc` 모듈 / 2 옛 키는 지우고 기동 실패 / 3 운영 기록은 안 남김 · 계약 자리만 / 4 legal 화면은 다음에).

설계에서 정한 것 — 바꾸려면 말해 달라: 권한 판정은 계정 모듈이 있으면 토큰의 역할을 보지 않고 매 요청 저장소로(부여도 즉시) / 매핑만 30초 캐시 / 전권 역할 하나는 고정 / ADMIN 이 게시판도 관리하게 됨 / 역할 이름은 못 바꿈 / 운영 기록 계약을 부르는 곳은 역할 변경뿐(account · payment · board 는 기존 출처를 어댑터로) / 화면 라벨은 프런트 사전.
