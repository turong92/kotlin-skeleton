# scheduler

스케줄 작업의 실행 가드 · 락 매니저 · 실패 핸들러를 갖춘 어노테이션 기반 스케줄러다.
기본 락은 no-op(단일 인스턴스용)이고 여러 인스턴스에서는 `redis-lock` 같은 락 매니저로 바꾼다. 전용 스케줄러 빈 이름은 `skeletonTaskScheduler`.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:scheduler"))` |
| 함께 오는 모듈 | 없음 |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.scheduler` — [docs/config/modules/scheduler.yml](../config/modules/scheduler.yml) |
| 기본 동작 | 켜짐 (no-op 락). |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `SkeletonScheduledLockManager`, `SkeletonSchedulerExecutionGuard`, `SkeletonScheduledFailureHandler`, `SkeletonScheduleResolver`, `skeletonTaskScheduler` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/scheduler/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
