# account-jdbc

`account` 의 저장을 DB 에 둔다 — `accounts` · `account_roles` · `account_identities`(수단 한 줄 = `(method, subject)` 유일) · `account_tokens`(한 번 쓰는 토큰, 해시만) · `account_challenges`(6자리 코드 챌린지 · 가입 시도) · `account_audit`(켜면) · `account_blocks`(운영자가 지운 계정의 재가입 차단 — **해시만**) · `account_locks`(주기 정리를 인스턴스 하나만 하게 하는 짧은 임대 — 이름 · 만료 · 소유 표).
계정 행의 `erased_at`(지운 시각) · `erase_claimed_at`(지우기 선점)은 `ANONYMIZE` 지우기 · 선점이 쓴다.
유일성은 일반 insert 의 **유니크 위반**으로 판정하고(`insertIgnore` 의 행 수는 MySQL 에서 믿을 수 없다), 토큰 소비는 조건부 UPDATE 한 문장, 마지막 수단 보호는 계정 행 락이다 — 둘 다 16 스레드 경쟁으로 두 DB 에서 시험한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:account-jdbc"))` |
| 함께 오는 모듈 | `account`, `auth`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (감사 기록을 켜는 키는 `account` 의 접두사 아래 `audit` 항목) |
| 기본 동작 | 켜짐. 감사 기록은 꺼져 있다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcAccountRepository`, `JdbcOneTimeTokenStore`, `JdbcChallengeStore`, `JdbcAccountBlockStore`, `JdbcAccountMaintenanceLease` |
| 마이그레이션 | `modules/account-jdbc/src/main/resources/db/migration/postgresql`, `modules/account-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/account-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [account](account.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
