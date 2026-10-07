# author-profile-jdbc

`author-profile` 의 저장소 포트 `AuthorProfileRepository` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 테이블 1 개: `author_profiles`(작성자 프로필 한 줄 = 공개 작성자 id `prf_…` + 비공개 주인 계정 id + 이름 · 비교용 키 · 꼬리표 + 상태). **메모리 구현은 없다** — 재시작에 신원이 사라지면 안 된다 (`board` 와 같은 이유). 어댑터가 없으면 `author-profile` 은 시작에 실패한다.
설계 · 위협 모델: `docs/superpowers/specs/2026-10-07-author-profiles-design.md` §6. 규칙의 정본은 포트의 KDoc 이다.

**규칙** (유니크는 DB 가 가른다 — 먼저 읽고 넣는 방식으로 판정하지 않는다)

| 무엇 | 어떻게 |
|---|---|
| `(name_key, tag)` 유니크 | 둘 중 하나라도 NULL 이면 겹치지 않는다 (NULL 은 서로 다르다 — 두 방언 공통, 시험으로 확인). 지운 프로필은 둘 다 NULL 이라 꼬리표가 풀린다. MySQL `name_key` 는 `utf8mb4_bin` — 악센트 · 대소문자만 다른 키는 다른 키다 (같음의 기준은 `DisplayNameRules` 가 정한다) |
| `primary_of` 유니크 | 계정의 첫 프로필이면 계정 id, 아니면 NULL → 계정당 하나. 부분 인덱스가 필요 없어 두 방언이 같다. 지워도 자리가 남아 첫 프로필을 두 번 만들 수 없다 |
| `insert` (첫 프로필) | 충돌하면 아무것도 하지 않는 insert. PostgreSQL `on conflict do nothing`(대상을 적지 않는다 — `primary_of` 든 `(name_key, tag)` 든 같은 길), MySQL `SqlDialect.insertIgnore`(`on duplicate key update id = id`). **넣었는지는 갱신 행 수가 아니라 id 로 다시 읽어 판정**한다 (MySQL 은 이미 있는 행에도 1 을 돌려준다). 어느 쪽이 겹쳤는지는 알리지 않는다 |
| `insertWithinCap` (만든 프로필) | 한 트랜잭션: 계정의 **첫 프로필 줄을 `for update` 로 잠그고**(지워졌어도) → 살아 있는 줄 수를 세고 → 상한이면 `LIMIT_REACHED`(이름 겹침과 겹쳐도 이쪽이 먼저) → 아니면 위의 insert-ignore, 안 들어갔으면 `NAME_TAKEN`. 같은 계정의 동시 요청이 이 잠금에서 줄을 서 상한을 넘지 못한다. 잠그는 줄이 계정당 하나라 교착이 없다. 첫 프로필 줄이 없으면 `IllegalStateException`(부르는 쪽이 먼저 보장한다) |
| `rename` | 이름 · 키 · 꼬리표를 한 문장으로. 주인 계정 + `ACTIVE` 조건 — 남의 · 없는 · 지워진 줄은 `NOT_FOUND`. `(name_key, tag)` 충돌은 `TAKEN`(바뀐 것 없음). 바깥 트랜잭션 없이 부른다 (PostgreSQL 은 실패한 문장이 트랜잭션을 망가뜨린다) |
| `markDeleted` | `status=DELETED`, 이름 · 키 · 꼬리표 NULL, `deleted_at`. `primary_of` 는 그대로. 글 · 댓글 쪽 행은 건드리지 않는다 |
| `markAdopted` | `adopted_at` 이 비어 있을 때만 채운다 |
| `countCreatedSince` | `primary_of is null` 인 줄, **지워진 것 포함**, `created_at >= since`. 만들기 한도의 근거 — 표에서 세므로 재시작 · 인스턴스 여럿에도 같다 |
| `deleteByAccount` | 탈퇴 고리 — 지워진 줄까지 전부. 멱등 |
| `findAll` | `where id in (…)` 쿼리 한 번 (500 개씩 덩어리) |

**인덱스**: `(account_id, status)` — 살아 있는 프로필 목록 · 개수 · 상한 세기. 만들기 한도 세기(`account_id` + `primary_of is null` + `created_at`)는 이 인덱스로 계정의 줄만 훑는다 (계정당 줄 수는 상한 · 만들기 한도가 막아 한 자리라서 `(account_id, created_at)` 를 따로 두지 않는다). `(name_key, tag)` 유니크의 앞 열이 `tagsOf` · `primariesByKey` 를, `primary_of` 유니크가 `findPrimary` 와 잠금을 받는다.

**스키마 관리**: Flyway 가 모듈 마이그레이션 `V20261007170317__author_profiles.sql` 을 적용한다 (두 방언). PostgreSQL 은 `create table if not exists` · `create index if not exists` 로 멱등하다. MySQL 판은 `create table if not exists` 한 문장에 인덱스를 `/* [jooq ignore start] */` 마커로 접어 넣었다 — `alter table` · 별도 `create index` 가 없어서 `schema.sql` 모드(MySQL)에 그대로 복사해도 두 번째 기동에서 깨지지 않는다 ([job-queue-jdbc](job-queue-jdbc.md) 의 같은 주의, [스키마 관리](../schema-management.md)). `name_key` 의 `collate utf8mb4_bin` 은 jOOQ 마커로 가렸다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:author-profile-jdbc"))` |
| 함께 오는 모듈 | `author-profile`, `account`, `auth`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 저장소 포트를 등록한다 (앱이 같은 타입의 빈을 두면 그쪽이 쓰인다). |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마 (`spring.flyway.locations: classpath:db/migration/{vendor}`). |
| 교체 지점 | `AuthorProfileRepository` (`@ConditionalOnMissingBean`) |
| 마이그레이션 | `modules/author-profile-jdbc/src/main/resources/db/migration/postgresql`, `modules/author-profile-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/author-profile-jdbc/src/test`, `modules/author-profile-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`) — `(name_key, tag)` · `primary_of` 유니크를 16 스레드 경합으로 · 상한보다 많은 스레드가 상한을 못 넘김 · 탈퇴 고리 멱등 · 고리 뒤 `ensureFirstProfile` 이 지워진 계정에 첫 프로필을 못 만듦) |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
