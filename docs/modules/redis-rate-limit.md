# redis-rate-limit

`platform` 의 rate limit 저장소(`RateLimitStore`)를 Redis 고정 윈도 카운터로 바꾼다.
`skeleton.web.rate-limit.enabled=true` 일 때만 쓰이고, 오류는 `FAIL_OPEN` 이다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:redis-rate-limit"))` |
| 함께 오는 모듈 | `platform`, `redis-core` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.redis-rate-limit` — [docs/config/modules/redis-rate-limit.yml](../config/modules/redis-rate-limit.yml) |
| 기본 동작 | 켜짐 (인메모리 저장소를 대체; rate limit 을 켜야 쓰인다). |
| 부팅에 필요한 것 | 없음. rate limit 을 켜면 Redis 서버가 필요하다. |
| 교체 지점 | `RateLimitStore`, `RateLimitKeyResolver`, `RedisFixedWindowCounter` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/redis-rate-limit/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
