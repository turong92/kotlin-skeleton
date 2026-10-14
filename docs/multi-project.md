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
