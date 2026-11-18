# job-queue-wake-cloudtasks

인스턴스가 0대로 내려가는 배포(Cloud Run)에서 **일감이 생긴 때 · 이르러질 때** 깨우기 주소(`skeleton.job-queue.wake.path`)를 부르는 `JobWake`(platform 의 포트) 구현이다. 일감을 넣으면(커밋 뒤) · drain 이 끝나 남은 일이 있으면 그 시각에 **Cloud Tasks 태스크**를 하나 만들고, 태스크가 깨우기 주소에 `POST`(`X-Wake-Secret` 을 싣는다)한다. 재시도 · 백오프는 큐의 몫이고 이 모듈은 「언제 깨울까」만 정한다([작업 큐 깨우기](../job-queue-jdbc.md), [Cloud Run 0대 운영](../cloud-run-scale-to-zero.md)).

- **켜짐은 실행 때 판정한다.** `project` · `region` · `queue` · `target-url` 넷이 다 차야 한다. 빈은 늘 서 있고(네이티브 AOT 에서 빌드 때 굳지 않게), 비어 있으면 `request` 는 아무것도 하지 않고 기동 로그에 한 줄(비어 있는 속성 이름)만 남는다. 이 선언은 플랫폼이 환경변수(`<P>_JOB_QUEUE_WAKE_CLOUDTASKS_*`)로 넣는다.
- **인증**은 실행 계정의 메타데이터 서버 토큰이다(`/computeMetadata/v1/instance/service-accounts/default/token`, `Metadata-Flavor: Google`, 키 파일 없음). `expires_in` 보다 60초 일찍까지 캐시한다.
- **예약 시각** `scheduleTime` 은 요청 시각을 지금 ~ 지금+29일(Tasks 한도 30일)로 맞춘 UTC RFC3339 이다. 이미 지난 시각은 지금, 먼 시각은 지금+29일이고, 그날 깨어난 drain 이 끝에서 다시 무장한다. `dispatchDeadline` 은 `wake.time-budget` 의 두 배(15초 ~ 1800초).
- **합치기** 이 인스턴스가 이미 건 예약 시각이 `지금 < 예약 ≤ 요청 시각` 이면 새 태스크를 만들지 않는다(그 시각의 깨우기가 이어서 처리한다). 인스턴스가 여럿이면 태스크도 여럿일 수 있다 — drain 은 프로세스마다 한 번에 하나고 일감 집기는 `SKIP LOCKED` 라 안전하다.
- **실패**(네트워크 · 4xx · 5xx · 토큰)는 상태 코드 · 예외 종류만 WARN 한 줄로 남기고 삼킨다. 일감은 DB 에 남고 다음 깨우기가 처리한다. 비밀값 · 토큰은 로그에 싣지 않는다. 실패한 요청은 「예약됨」으로 세지 않는다.
- **깨우기 비밀은 태스크 정의에 남는다.** 태스크가 `X-Wake-Secret` 을 헤더로 싣고 Cloud Tasks 가 그 정의를 보관하므로, 큐를 읽을 수 있는 계정(`roles/cloudtasks.viewer` 등)은 비밀을 볼 수 있다. 그 역할을 따로 주지 않는다 — 깨우기 비밀을 바꾸면 이미 만들어진 태스크는 옛 비밀로 불려 401 이 되고 다음 깨우기가 다시 처리한다.
- **배포 가드** 보호 환경(stage · prod)에서 켜져 있는데 `api-base-url` · `metadata-url` 이 기본이 아니거나, `target-url` 이 https 가 아니거나, `skeleton.job-queue.wake.secret` 이 비어 있으면 기동 실패(속성 이름만 말한다).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:job-queue-wake-cloudtasks"))` |
| 함께 오는 모듈 | `job-queue-jdbc`, `persistence-jdbc`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.job-queue-wake-cloudtasks` — [docs/config/modules/job-queue-wake-cloudtasks.yml](../config/modules/job-queue-wake-cloudtasks.yml) |
| 기본 동작 | 조건부: 빈은 서지만 설정(`project` · `region` · `queue` · `target-url`)이 비어 있으면 아무것도 하지 않는다. |
| 부팅에 필요한 것 | `job-queue-jdbc`(`JobQueueProperties`). 켜려면 `project` · `region` · `queue` · `target-url` 과 `skeleton.job-queue.wake.secret`. |
| 교체 지점 | `JobWake`(platform 의 포트 — 다른 빈이 있으면 이 모듈의 것은 서지 않는다) |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/job-queue-wake-cloudtasks/src/test` (JDK `HttpServer` 로 메타데이터 서버와 Tasks API 를 흉내 낸다) |

자세히: [작업 큐 깨우기](../job-queue-jdbc.md) · [Cloud Run 0대 운영](../cloud-run-scale-to-zero.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
