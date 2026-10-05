# async

`@Async` 와 작업 그룹이 호출 스레드의 trace id · MDC 를 이어받도록 하는 실행기와 데코레이터다.
스레드 풀 크기 · 큐 · 종료 대기는 설정으로 정한다. 실행기 빈 이름은 `skeletonAsyncTaskExecutor`.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:async"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.async` — [docs/config/modules/async.yml](../config/modules/async.yml) |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `AsyncContextTaskDecorator`, `AsyncConfigurer`, `skeletonAsyncTaskExecutor` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/async/src/test` |

자세히: [비동기 실행 상세](../async.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
