# redis-rate-limit

`platform` 의 rate limit 저장소(`RateLimitStore`)를 Redis 고정 윈도 카운터로 바꾼다. 이 저장소는 경로 필터 `skeleton.web.rate-limit` 만이 아니라 **모듈의 한도**(가입 · 로그인 · 인증 코드 · 비밀번호 찾기 · 로그인 링크 · 문의 …)도 센다 — `skeleton.web.rate-limit` 을 안 켜도 이 모듈을 붙이면 그 한도들이 인스턴스 사이에 공유된다. 인스턴스가 여럿이거나 0대로 내려가는 Cloud Run 에서는 메모리 저장소(인스턴스마다 따로 · 재시작에 비워짐) 대신 이것을 쓴다 — 키 표와 고르는 길: [Cloud Run 「민감 경로 속도 제한」](../cloud-run-scale-to-zero.md).
오류는 `FAIL_OPEN`(Redis 가 죽으면 통과)이 기본이고 `skeleton.redis-rate-limit.failure-policy=FAIL_CLOSED` 로 거부로 바꾼다(그러면 Redis 장애가 로그인 장애가 된다).

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
