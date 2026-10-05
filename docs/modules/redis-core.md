# redis-core

Redis 연결 팩토리, `StringRedisTemplate` · JSON 템플릿, 키 접두사(`RedisKeyPrefixer`)를 준다.
연결은 지연이라 Redis 가 없어도 앱은 뜬다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:redis-core"))` |
| 함께 오는 모듈 | 없음 |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.redis` — [docs/config/modules/redis-core.yml](../config/modules/redis-core.yml) |
| 기본 동작 | 켜짐 (연결은 지연). |
| 부팅에 필요한 것 | 없음. 첫 명령에 Redis 서버가 필요하다. |
| 교체 지점 | `RedisConnectionFactory`, `StringRedisTemplate`, `RedisKeyPrefixer` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/redis-core/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
