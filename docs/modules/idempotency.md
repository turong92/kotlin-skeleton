# idempotency

`Idempotency-Key` 헤더가 있는 명령 요청의 중복 실행을 막고 첫 응답을 재생한다.
기본 저장소는 인메모리(인스턴스별)다. 여러 인스턴스라면 앱이 `IdempotencyStore` 를 대체한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:idempotency"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.idempotency` — [docs/config/modules/idempotency.yml](../config/modules/idempotency.yml) |
| 기본 동작 | 켜짐 (인메모리 저장소). 응답은 상태와 상관없이 키에 묶어 재생한다 — `@IdempotentOperation(cacheClientErrors = false)` 인 명령(비밀번호 · 코드를 싣는 계정 명령)은 4xx 를 묶지 않고 예약을 푼다(`IdempotencyStore.release`). |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `IdempotencyStore`, `IdempotencyScopeResolver` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/idempotency/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
