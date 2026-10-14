# 한 프로세스에 프로젝트 여럿

부품(빈)은 한 벌, 프로젝트마다 다른 것은 **설정과 데이터**뿐이다. 앱 설정의 `skeleton.projects` 가 비어 있으면(기본) 아무 일도 일어나지 않고 앱은 서비스 하나다.

```yaml
skeleton:
  projects:
    ovation:
      hosts: [ovation.fans, api.ovation.fans]   # 요청의 Host 로 프로젝트를 정한다 (ForwardedHeaderFilter 가 푼 뒤)
      schema: ovation                          # DB 스키마 (MySQL 은 데이터베이스) — 프로젝트마다 하나
      datasource: { username: ovation, password: ${OVATION_DB_PASSWORD}, pool-max: 3 }   # 선택. 비우면 공통 spring.datasource.*
      config:                                  # skeleton.* 아래 키 — 공통 값 위에 덮어 같은 properties 클래스로 바인딩한다
        auth.jwt.secret: ${OVATION_JWT_SECRET}
        account.mail.brand: Ovation
```

- 모르는 Host → `421 COMMON.UNKNOWN_HOST`. `/health` · `/info` 는 프로젝트 없이 지나간다(헬스체크는 `localhost` 로 온다).
- 같은 Host 를 두 프로젝트가 쓰거나 스키마가 겹치면 기동 실패.
- 요청 밖(뒤 작업)은 `ProjectContext.runIn(project) { … }` 로만 프로젝트를 정한다. 문맥 없이 `current()` 는 `NoProjectContextException`.
- 프로젝트마다 한 번 만드는 부품: `ProjectRegistry.scopedFrom(prefix, Props::class.java) { project, props -> … }`.

## DB (프로젝트마다 스키마)

- 서버 하나, 프로젝트마다 연결 풀 하나(`persistence-jdbc` 의 `ProjectRoutingDataSource`). 풀은 처음 쓸 때 열고(안 쓰면 연결 0), 최대 `datasource.pool-max`(기본 3), 최소 유지 0. 같은 서버 URL(`spring.datasource.*`)에 프로젝트의 스키마를 고른다(PostgreSQL `search_path`, MySQL 은 데이터베이스). 프로젝트별 DB 계정(`datasource.username/password`)을 주면 자기 스키마 밖은 DB 가 막는다.
- **프로젝트 문맥 없이 연결을 꺼내면 예외**(`NoProjectContextException`) — 기본 스키마로 가지 않는다. 트랜잭션이 열린 뒤 프로젝트가 바뀌면 문장을 만들 때 예외.
- 기동 때 `migration-flyway` 가 스키마마다 같은 스크립트를 돌린다(이력도 스키마마다, 하나라도 실패하면 기동 실패). 스키마가 없으면 Flyway 가 만든다 — DB 계정에 권한이 없으면 운영자가 미리 만든다. `public` 은 건드리지 않는다.
- `SqlDialectVerifier` 는 기동 때 연결을 열지 않고 URL 로 방언을 본다. `/health` 는 프로젝트별 풀을 하나씩 본다(Boot 의 라우팅 DataSource 지원).
- `status: closed` 인 프로젝트: 도메인은 `503 COMMON.PROJECT_CLOSED`, 풀 · 마이그레이션 · "프로젝트 둘 이상" 개수에서 빠진다(스키마는 안 적어도 된다).
