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

## 로그인 쪽 설정을 프로젝트별로

`config` 에 적은 `skeleton.*` 키는 공통 값 위에 덮여 **같은 properties 클래스**로 바인딩된다 (`ProjectRegistry.bind` / `scopedFrom`). 지금 프로젝트별로 갈리는 것:

| 무엇 | 키 (config 아래) | 어떻게 |
|---|---|---|
| JWT 키 · 발급자 · TTL | `auth.jwt.*` | 프로젝트마다 `JwtTokenService` 부품. 토큰에 프로젝트 이름(`prj`)이 들어가고 받을 때 대조 — A 토큰은 B 에서 거절(키를 같이 써도). 잘못된 비밀은 기동에서 막는다 |
| 리프레시 쿠키 | `auth-session.cookie.*` | `RefreshTokenDelivery` 가 지금 프로젝트의 이름 · 경로 · 보안 속성을 쓴다 |
| CORS 허용 출처 | `web.cors.*` | 요청의 프로젝트가 고른다 (`skeleton.web.cors.enabled=true` 일 때) |
| 계정 메일 링크 주소 · 브랜드 · 제목 접두사 | `account.mail.*` | 내장 메일 틀은 프로젝트마다 한 번 만든다 (앱이 자기 `AccountMailTemplates` 빈을 두면 그것을 모두가 쓴다) |
| 시드 계정 | `account.seed.accounts[n].*` | 기동 때 프로젝트마다 그 스키마에 만든다 |

뒤 주기 작업 중 계정 삭제 유예 정리 · 세션 정리는 프로젝트를 차례로 돈다. 그 밖의 값(가입 정책 · 비밀번호 정책 · 한도 …)은 아직 공통이다.

알려진 한계: stage · prod 의 계정 가드(`link-base-url` 비면 실패)와 JWT 가드의 일부는 공통 값을 본다(JWT 비밀은 프로젝트마다 검사한다).
