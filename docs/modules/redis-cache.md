# redis-cache

이름 있는 Redis 캐시(TTL · 접두사)와 안정적인 키 생성기, 캐시 오류 처리 정책(`FAIL_OPEN` 기본)을 준다.
Redis 가 죽어도 캐시 오류는 무시하고 원본을 읽는다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:redis-cache"))` |
| 함께 오는 모듈 | `platform`, `redis-core` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.redis-cache` — [docs/config/modules/redis-cache.yml](../config/modules/redis-cache.yml) |
| 기본 동작 | 켜짐 (`FAIL_OPEN`). |
| 부팅에 필요한 것 | 없음. 적중하려면 Redis 서버가 필요하다. |
| 교체 지점 | `NamedRedisCacheRegistry`, `CacheErrorHandler`, `KeyGenerator`, `CacheManager` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/redis-cache/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
