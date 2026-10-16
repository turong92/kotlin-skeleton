# redis-core

Redis 연결 팩토리, `StringRedisTemplate` · JSON 템플릿, 키 접두사(`RedisKeyPrefixer`)를 준다.
연결은 지연이라 Redis 가 없어도 앱은 뜬다.

프로젝트 여럿([multi-project](../multi-project.md)): 프로젝트 안(요청 · `ProjectContext.runIn`)에서 `RedisKeyPrefixer` 가 만드는 키는 `<key-prefix>:project:<프로젝트 id>:…` 이다 — 요청 한도(`redis-rate-limit`) · 캐시(`redis-cache`, 전체 비우기도 그 프로젝트 것만) · 락(`redis-lock`) 이 모두 이 한 곳을 지나므로 같은 IP · 계정 id · 락 이름이 프로젝트 사이에서 섞이지 않는다. 프로젝트 밖(서버 전체 일, 예: 모든 프로젝트가 나눠 쓰는 리더 락)은 지금까지와 같은 키다. 접속 정보(호스트 · 데이터베이스 번호)는 서버 공통이다 — 프로젝트별 Redis 는 범위 밖.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:redis-core"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.redis` — [docs/config/modules/redis-core.yml](../config/modules/redis-core.yml) |
| 기본 동작 | 켜짐 (연결은 지연). |
| 부팅에 필요한 것 | 없음. 첫 명령에 Redis 서버가 필요하다. |
| 교체 지점 | `RedisConnectionFactory`, `StringRedisTemplate`, `RedisKeyPrefixer` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/redis-core/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
