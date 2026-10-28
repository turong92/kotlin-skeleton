# authz

운영자 **역할 관리**: 역할 만들기 · 고치기 · 지우기(역할 → 권한 묶음)와, 붙은 모듈이 선언한 권한의 카탈로그(`GET /api/v1/admin/permissions`). 예: CS 담당자 역할 = `payment.read` + `payment.refund` 만 — 결제 화면은 보이고 환불은 되지만 결제사와 맞추기는 403.
판정 자체(`OperatorAccess.require(…)`)와 yml 고정 매핑(`skeleton.authz.roles`)은 `platform` 에 있다 — 이 모듈은 **매핑을 화면에서 바꾸게 하는 관리 API** 이고, 저장은 어댑터 `authz-jdbc` 가 한다(이 모듈에는 메모리 구현이 없다). 계정에 역할을 주는 일은 `account` 의 관리자 API 다.
기본은 꺼짐이다(`skeleton.authz.admin.enabled=false` → 404). 프런트용 HTTP 계약: [authz-http-contract.md](../authz-http-contract.md).

| 메서드 · 경로 | 하는 일 | 필요한 권한 |
|---|---|---|
| `GET /api/v1/admin/permissions` | 붙은 모듈이 선언한 권한 `[{key, module}]` | `authz.roles` |
| `GET /api/v1/admin/roles` | 역할 `[{name, description, permissions, includes, effectivePermissions, protected, holders}]` — `permissions` 는 직접 가진 권한, `effectivePermissions` 는 `includes` 로 물려받은 것까지 펼친 값. `holders` 는 `account` 가 있을 때만(없으면 `null`) | `authz.roles` 또는 `account.roles` |
| `POST /api/v1/admin/roles` | `{name, description, permissions, includes?}` → 201 | `authz.roles` |
| `PUT /api/v1/admin/roles/{name}` | `{description, permissions, includes?}` 로 교체 → 200 (이름은 못 바꾼다). `includes` 가 빠지면 **물려받는 역할은 그대로** 둔다 | `authz.roles` |
| `DELETE /api/v1/admin/roles/{name}` | 204 | `authz.roles` |

안전장치(`AUTHZ.*` 에러 코드, 상태 코드는 계약 문서): 전권 역할(`skeleton.authz.super-role`) 고치기 · 지우기와 다른 역할에 `*` 주기 → 409 `ROLE_PROTECTED` · 자기에게 없는 권한을 역할에 더하거나 빼기, 그런 권한을 가진 역할 지우기 → 403 `ESCALATION` · 자기가 가진 역할에서 `authz.roles` 를 잃는 변경 → 409 `SELF_LOCKOUT` · 활성 계정이 가진 역할 지우기 → 409 `ROLE_IN_USE` · 위계(`includes`): 순환 → 409 `ROLE_CYCLE` · 없는 역할 → 400 `INCLUDE_UNKNOWN` · 전권 역할 물려받기 → 409 `ROLE_PROTECTED` · 더하거나 빼는 `includes` 의 펼친 권한이 호출자 권한 밖이면 403 `ESCALATION` · 호출자가 물려받아 갖던 `authz.roles` 를 잃는 변경 → 409 `SELF_LOCKOUT` · 다른 역할이 물려받는 역할 지우기 → 409 `ROLE_INCLUDED`(그쪽에서 먼저 뺀다) · 선언에 없는 권한 400 `PERMISSION_UNKNOWN` · 같은 이름 409 `ROLE_EXISTS` · 없는 역할 404 `ROLE_NOT_FOUND`.
역할을 지우면 같은 트랜잭션에서 정지 · 대기 · 삭제 유예 계정을 포함한 모든 계정의 그 배정도 거둔다(`RoleHolders.revokeFromAll`, 계정마다 `ROLE_REVOKED`) — 같은 이름으로 다시 만든 역할을 아무도 주지 않은 계정이 물려받지 못한다. 고치기 · 지우기는 역할 줄을 `select … for update` 로 잠그고 판정해 동시 요청이 옛 목록으로 권한을 되살리지 못한다.
성공한 만들기 · 고치기 · 지우기는 `OperatorAuditLog.record` 를 한 번씩 부른다(`authz.role.created` · `updated`[detail `added` · `removed`, 위계가 바뀌면 `includesAdded` · `includesRemoved`] (`created` 는 `includes`) · `deleted`). 기본 구현은 아무것도 하지 않는다 — 저장하려면 앱이 `OperatorAuditLog` 빈 하나를 둔다. 실패한 변경은 부르지 않는다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:authz"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.authz.admin` — [docs/config/modules/authz.yml](../config/modules/authz.yml) |
| 기본 동작 | 꺼짐. `skeleton.authz.admin.enabled=true` 이고 서블릿 웹 앱 + Spring Security 가 있을 때만 컨트롤러가 선다. 켜도 권한 `authz.roles`(전권 역할은 늘 가진다)이 있어야 부른다. 권한 선언 `authz.roles` 는 모듈이 클래스패스에 있으면 켜지 않아도 낸다 |
| 부팅에 필요한 것 | 켰다면 저장 포트 `AuthzRoleStore` — `authz-jdbc` 가 내거나 앱이 구현한다. 없으면 시작이 실패한다. 끄면 아무것도 필요 없다 |
| 교체 지점 | `AuthzRoleStore`, `RoleService`, `AuthzAdminController` |
| 마이그레이션 | 없음 (스키마는 `authz-jdbc`) |
| 프론트 짝 | `@skeleton/auth` (HTTP 계약: [authz-http-contract.md](../authz-http-contract.md)) |
| 테스트 | `modules/authz/src/test`, `modules/authz/src/noOptionalTest` (`spring-security-core` 와 계정 모듈이 없을 때) |

## 앱이 바꾸는 곳

- 역할 → 권한 매핑 자체(`skeleton.authz.roles` · `super-role` · `cache-ttl`)는 `platform` 의 설정이다. 이 모듈의 접두사는 스위치 하나다. 역할 표 화면은 프런트 `@skeleton/auth` 의 admin 하위 경로.
- 켜기: `skeleton.authz.admin.enabled=true` + 저장소(`authz-jdbc`).
- `holders`(역할을 가진 계정 수)와 「쓰는 계정이 있는 역할 삭제 막기」는 `account` 가 `RoleHolders` 빈을 내면 켜진다. `account` 가 없으면 `holders` 는 `null` 이고 삭제 검사는 없다.
- 역할 만들기 · 고치기 · 지우기는 이 프로세스 안에서 한 줄로 선다(`RoleService` 의 잠금) — 행 잠금이 없는 저장소(메모리)에서도 같은 역할의 동시 편집 · 마지막 `authz.roles` 보호가 지켜진다. 잠금은 메서드가 끝나면 풀리므로 앱이 `RoleService` 를 자기 `@Transactional` 안에서 부르면 안 된다(바깥 트랜잭션에 합류해 커밋 전에 풀린다). 여러 인스턴스 사이는 저장소의 행 잠금(`authz-jdbc` 의 `for update`)이 **같은 역할 한 줄**의 동시 고치기 · 지우기만 직렬화한다 — 「마지막 `authz.roles` 보호」가 보는 호출자의 **다른 역할** 권한은 캐시(`cache-ttl`)에서 잠금 없이 읽으므로, 서로 다른 인스턴스에서 두 역할을 동시에 지우면 둘 다 통과할 수 있다(전권 역할 `ADMIN` 은 고정이라 완전히 잠기지는 않는다).
- 호출자의 「자기 잠금」은 요청이 싣고 온 역할(토큰의 역할)로 판정한다.

## 역할 위계 (`includes`)

역할이 다른 역할을 물려받는다 - Spring Security `RoleHierarchy` 의 뜻(`STAFF > CS`: 위가 아래의 권한을 모두 얻는다, 전이적, 순환 금지, 펼침 깊이 8). 판정은 한 곳이다: `RolePermissionSource.permissionsOf(role)` 가 **물려받은 것까지 펼친 값**을 돌려주므로 `OperatorAccess` · 계정 모듈의 상승 막기 · `/auth/me` 의 `permissions` 가 고치지 않고 위계를 따른다(`roles` 는 배정된 역할 그대로). 전권 역할은 `includes` 를 갖지도 받지도 않는다. yml 로는 `skeleton.authz.role-includes`(platform), 표로는 `authz_role_includes`(`authz-jdbc`).
이 모듈의 판정(상승 · 자기 잠금 · 응답의 `effectivePermissions`)은 **저장소의 위계**로 펼친다 - 캐시를 거치지 않는다. 위계를 바꾸는 편집(만들기 · 고치기 · 지우기)은 먼저 `AuthzRoleStore.lockHierarchy()` 를 부른다(`authz-jdbc`: 전권 역할 줄 `for update`, 첫 문장으로) - 그 뒤에 읽은 위계가 앞선 편집의 커밋을 본다.

## Decisions and rejected alternatives

**모듈 둘 + 판정은 platform.** 판정·선언·yml 매핑을 `platform` 에 두면 board · payment · legal 이 이 모듈 없이도 같은 검사기를 쓴다. 이 모듈은 「관리 API」만이다. 버린 것: *판정까지 이 모듈*(다섯 모듈이 `authz` 에 의존해야 하고 끄면 판정이 사라진다).

**전권은 고정, 상승은 막는다.** 전권 역할은 표에서도 못 바꾸고(`protected` 줄은 저장소 문장이 건드리지 않는다), 자기에게 없는 권한은 역할에 못 더한다(Kubernetes RBAC 의 escalation 규칙). 그래서 역할 관리 권한(`authz.roles`)만 가진 운영자가 결제 권한을 스스로 만들 수 없다.

**운영 기록은 계약 자리만.** 누가 · 언제 · 무엇을 바꿨는지를 저장하는 공용 표는 만들지 않는다 — 역할 변경 셋이 `OperatorAuditLog.record` 를 부를 뿐이고, 기본은 아무것도 하지 않는다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
