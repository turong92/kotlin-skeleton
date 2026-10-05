# redis-lock

Redisson 기반 `@DistributedLock` 어노테이션과 락 실행기를 준다.
Redisson 클라이언트는 지연 연결이고 시작 점검은 선택이다 (`startup-check.enabled=true`, 운영 권장).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:redis-lock"))` |
| 함께 오는 모듈 | `redis-core` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.redis-lock` — [docs/config/modules/redis-lock.yml](../config/modules/redis-lock.yml) |
| 기본 동작 | 켜짐 (지연 연결, 시작 점검은 꺼짐). |
| 부팅에 필요한 것 | 없음. 첫 락에 Redis 서버가 필요하고, 없으면 `@DistributedLock` 이 즉시 실패한다. |
| 교체 지점 | `RedissonClient`, `DistributedLockBackend`, `DistributedLockKeyResolver` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/redis-lock/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
