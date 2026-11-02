# storage-image-jdbc

`storage-image` 의 이미지 상태 저장소(`ImageStatusStore`)를 JDBC(PostgreSQL · MySQL)로 구현하고, 변환 작업 `storage.image.variants` 를 `job-queue-jdbc` 에 얹는다. 표는 하나 — 이미지 원본 하나당 한 줄(`storage_images`).

규칙: 줄의 열쇠는 원본 키의 sha256(`key_hash`, 긴 키도 유니크 색인에 들어간다) · 이미지가 아닌 파일은 줄이 없다 · 계정 id 는 줄에도 변환본 키에도 없다 · 상태는 `PENDING → READY | REJECTED | UNSUPPORTED | FAILED` 로 한 번만 간다(`PENDING` 일 때만 바뀐다) ·
새 줄은 `SqlDialect.insertIgnore` 로 넣고 **작업을 넣을 권리는 `queued=false → true` 갱신이 1행일 때만** 갖는다(MySQL 의 insert-ignore 는 이미 있는 줄에도 1 을 돌려줘서 갱신 행 수로 「내가 만들었나」를 알 수 없다) — 같은 키에 완료와 지연 등록이 동시에 와도 줄 하나 · 작업 하나, 줄과 작업은 한 트랜잭션이라 함께 커밋되거나 함께 사라진다 ·
`variants` 는 `{"thumb":{"w":400,"h":300,"ext":"jpg"},…}` 닫힌 모양이고 `ext` 가 `-` 면 원본을 가리킨다(움직이는 이미지의 `display`) ·
작업이 DEAD 가 되면(재시도 소진) 아직 `PENDING` 인 줄을 `FAILED` 로 적는다(원본은 그대로). 큐가 꺼져 있으면(`skeleton.job-queue.enabled=false`) `storage-image` 를 켤 때 기동이 실패한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage-image-jdbc"))` |
| 함께 오는 모듈 | `storage-image`, `platform`, `persistence-jdbc`, `job-queue-jdbc`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 꺼짐. `storage-image` 가 켜져 있을 때(`skeleton.storage.image.enabled=true`) 저장소 · 작업 처리기를 등록한다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, `job-queue-jdbc`(켜진 채), 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `ImageStatusStore`, `ImageVariantTasks` |
| 마이그레이션 | `modules/storage-image-jdbc/src/main/resources/db/migration/postgresql`, `modules/storage-image-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/storage` |
| 테스트 | `modules/storage-image-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
