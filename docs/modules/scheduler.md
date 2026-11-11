# scheduler

스케줄 작업의 실행 가드 · 락 매니저 · 실패 핸들러를 갖춘 어노테이션 기반 스케줄러다.
기본 락은 no-op(단일 인스턴스용)이다 — `lockKey` 를 적어도 인스턴스가 여럿이면 작업은 인스턴스마다 다 돈다. 이 레포에는 여러 인스턴스용 락 매니저 구현이 없고(`redis-lock` 은 메서드용 분산 락이다), 필요하면 앱이 `SkeletonScheduledLockManager` 를 구현한다.
**그래서 이 레포의 주기 작업은 모두 겹쳐 돌아도 안전하게 짜여 있다**(조건부 갱신 · 멱등 삭제 — 만료 회수, 소멸, 초대 해제, 청소). 락은 중복 일을 줄일 뿐 정확성은 멱등이 지킨다: 새 `@SkeletonScheduled` 작업도 겹쳐 돌아도 해가 없게 쓴다. 인스턴스가 0대로 내려가는 배포에서는 타이머 대신 `OnRequestTask` 가 깨운다([cloud-run-scale-to-zero](../cloud-run-scale-to-zero.md)). 전용 스케줄러 빈 이름은 `skeletonTaskScheduler`.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:scheduler"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.scheduler` — [docs/config/modules/scheduler.yml](../config/modules/scheduler.yml) |
| 기본 동작 | 켜짐 (no-op 락). |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `SkeletonScheduledLockManager`, `SkeletonSchedulerExecutionGuard`, `SkeletonScheduledFailureHandler`, `SkeletonScheduleResolver`, `skeletonTaskScheduler` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/scheduler/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
