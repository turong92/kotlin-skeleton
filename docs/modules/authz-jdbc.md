# authz-jdbc

`authz` 의 저장소 포트(`AuthzRoleStore`)를 JDBC(PostgreSQL · MySQL)로 구현하고, 판정이 읽는 역할 → 권한 매핑(`RolePermissionSource`)을 yml 에서 **이 표**로 바꾼다. 테이블 2 개: `authz_roles`(역할 · `protected` 는 전권 역할) · `authz_role_permissions`(역할이 가진 권한 키, 역할을 지우면 같이 지워진다).
시각은 `SqlDialect` 로 바인딩한다. 전권 역할 줄은 고치기 · 지우기 문장이 `protected = false` 조건을 달고 있어 동시 호출에도 바뀌지 않고, 판정 쪽도 표의 줄과 상관없이 전권 역할을 `*` 로 읽는다.

**첫 기동 씨앗.** 표가 비어 있을 때만 yml 매핑(`skeleton.authz.roles`, 없으면 모듈 선언의 기본 `ADMIN: [*]` · `MODERATOR: [board.moderate, board.manage]`)을 표로 옮긴다. 표에 역할이 하나라도 있으면 아무것도 하지 않는다 — 그래서 기동 사이에 yml 을 바꿔도 표는 그대로이고, 운영자가 고친 역할을 덮지 않고, 지운 역할이 되살아나지 않는다(처음 한 번만 가져오는 방식). 그 뒤 바꾸려면 관리 API 를 쓴다.
**캐시.** 역할 → 권한만 프로세스 메모리에 담는다(`skeleton.authz.cache-ttl`, 기본 30초, `0` 이면 끔). 같은 인스턴스의 변경은 커밋 직후 비우므로 다음 요청부터 반영되고, 다른 인스턴스는 최대 ttl 뒤에 반영된다. 계정이 가진 역할은 캐시하지 않는다(`account` 가 매 요청 읽는다).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:authz-jdbc"))` |
| 함께 오는 모듈 | `authz`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (캐시 수명 skeleton.authz.cache-ttl 은 platform 의 설정) |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 저장소 · 표 기반 `RolePermissionSource` · 씨앗을 등록한다 (앱이 같은 타입의 빈을 두면 그쪽이 쓰인다). 역할 관리 API 는 `authz` 의 스위치(기본 꺼짐)가 따로 켠다 |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마 (`spring.flyway.locations: classpath:db/migration/{vendor}`) |
| 교체 지점 | `AuthzRoleStore`, `RolePermissionSource` |
| 마이그레이션 | `modules/authz-jdbc/src/main/resources/db/migration/postgresql`, `modules/authz-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/auth` (HTTP 계약: [authz-http-contract.md](../authz-http-contract.md)) |
| 테스트 | `modules/authz-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`) — 저장소 계약 · 씨앗이 처음 한 번만 · FK cascade · 보호 역할 동시 고치기 거절 · 동시 만들기가 한 줄 · 캐시 무효화) |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
