# scheduler

스케줄 작업의 실행 가드 · 락 매니저 · 실패 핸들러를 갖춘 어노테이션 기반 스케줄러다.
기본 락은 no-op(단일 인스턴스용)이다 — `lockKey` 를 적어도 인스턴스가 여럿이면 작업은 인스턴스마다 다 돈다. 이 레포에는 여러 인스턴스용 락 매니저 구현이 없고(`redis-lock` 은 메서드용 분산 락이다), 필요하면 앱이 `SkeletonScheduledLockManager` 를 구현한다.
**그래서 이 레포의 주기 작업은 모두 겹쳐 돌아도 안전하게 짜여 있다**(조건부 갱신 · 멱등 삭제 — 만료 회수, 소멸, 초대 해제, 청소). 락은 중복 일을 줄일 뿐 정확성은 멱등이 지킨다: 새 `@SkeletonScheduled` 작업도 겹쳐 돌아도 해가 없게 쓴다. 인스턴스가 0대로 내려가는 배포에서는 타이머 대신 모듈의 정리 작업은 `OnRequestTask` 가 깨우고([cloud-run-scale-to-zero](../cloud-run-scale-to-zero.md)), 앱이 쓴 `@SkeletonScheduled` 작업은 `mode: external` 로 밖의 스케줄러가 부른다(아래). 전용 스케줄러 빈 이름은 `skeletonTaskScheduler`.

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

## 밖의 스케줄러로 돌리기 (`mode: external`)

인스턴스가 0대로 내려가는 배포(Cloud Run 등)에서는 앱 안 타이머가 돌지 않고, 타이머를 돌리려고 인스턴스를 띄워 두면 하루 몇 번 도는 작업 때문에 종일 요금을 낸다.
`skeleton.scheduler.mode=external` 이면 **언제는 밖의 스케줄러(Cloud Scheduler)가, 무엇은 앱이** 맡는다 — 타이머를 하나도 걸지 않고, 작업마다 주소를 연다.

- 주소: `POST /internal/scheduler/run/{taskId}` (`external.path` 로 바꾼다). taskId 는 어노테이션의 `id`, 없으면 `클래스.메서드` — **id 를 적어 두기를 권한다**(이름을 바꾸면 스케줄러가 404 를 맞는다).
- 인증: 헤더 `X-Scheduler-Secret`(상수 시간 비교). local 모드이거나 비밀값이 비었거나 없는(이 환경의 프로필 · `enabledProperty` · `local-execution` 설정으로 꺼진 — 외부 모드에도 그대로 적용된다) 작업이면 404, 틀리면 401. 보호 환경(stage · prod)에서 비밀값이 없거나 약하면(24자 미만 · 서로 다른 글자 8개 미만) 기동 실패한다. Cloud Run 을 비공개로 두고 OIDC 로 부르면 IAM 이 한 겹 더 막는다 — 이 경로의 Bearer 는 JWT 필터가 읽지 않는다.
- 응답: 작업을 **요청 안에서 끝까지** 돌린다. 성공 200 `{taskId, ran:true, elapsedMillis}`, 락을 못 잡아 건너뛰면 200 `ran:false`(재시도 안 함), 작업이 던지면 실패 핸들러에 알린 뒤 500(스케줄러가 재시도) — 앱 안 타이머가 로그만 남기고 넘어가는 것과 다르다.
- `mode` · `secret` 은 실행 때 읽는다 — 같은 이미지(네이티브 포함)를 환경변수(`<P>_SCHEDULER_MODE=external` · `<P>_SCHEDULER_EXTERNAL_SECRET`)로 바꾼다.

외부 모드에서 작업을 짤 때:

- **요청 기한 안에 끝낸다.** 오래 걸리는 일은 한 번에 한 묶음만 하고 커서(DB · Redis)를 남겨 다음 회차가 이어받게 쪼갠다. 무한히 도는 작업은 만들지 않는다.
- **응답 뒤 백그라운드 일(`@Async` · `CompletableFuture`)은 잃는다** — 요청이 끝나면 CPU 가 없다. 블로킹으로 부르거나 작업 큐에 넣는다.
- 재시도가 있으니 **겹쳐 불려도 안전하게**(멱등). 하루 한 번만 돌아야 하는 일은 "오늘 키"를 먼저 잡고, 실패하면 키를 풀어 재시도가 다시 돌게 한다.
- 순수 `@Scheduled` 는 이 체계 밖이라 깨어 있는 인스턴스에서 아무 때나 돈다 — `@SkeletonScheduled` 만 쓴다.
- 한 환경에서 앱 안 타이머와 외부 호출을 같이 켜지 않는다(이중 실행). 모드는 프로세스 하나에 하나다.
- 이 레포 모듈의 주기 정리(계정 삭제 · 세션 · 예약 만료 …)는 `OnRequestTask` 로 job-queue 깨우기 주소가 돌린다([cloud-run-scale-to-zero](../cloud-run-scale-to-zero.md)) — 이 모드는 앱이 직접 쓴 `@SkeletonScheduled` 작업용이다.

### 작업 목록을 코드에서 만든다

스케줄러에 만들 목록은 사람이 따로 적지 않고 어노테이션에서 뽑는다. `@SkeletonScheduled` 의 `budgetSeconds` · `retries` · `minBackoffSeconds` 가 호출자 설정이다(-1 = 기본: 주기 1시간 이하는 60초 · 0회 · 300초, 그보다 길면 900초 · 2회 · 120초).

```kotlin
// 앱 테스트 하나 — 목록을 파일로 써 두고 CI 가 인프라 레포로 올린다(또는 커밋된 파일과 다르면 실패)
val tasks = context.getBean(SkeletonScheduledTaskRegistrar::class.java).discoveredTasks()
val schedules = SkeletonExternalSchedules.from(tasks)   // taskId · cron(unix 5자리) · zone · budgetSeconds · retries · minBackoffSeconds · profiles
objectMapper.writerWithDefaultPrettyPrinter().writeValue(File("build/schedules.json"), schedules)
```

- 스프링 cron 은 초가 0 이어야 하고(`L` `W` `#` 불가, 일과 요일을 둘 다 적으면 unix cron 은 OR 로 읽어 불가, `5/15` 는 `5-59/15` 로 옮긴다), taskId 에 `/` 가 없어야 하고, `fixedDelay` · `fixedRate` 는 한 시간 · 하루를 고르게 나누는 분 · 시간이어야 하며, `localTime` 은 초 없이 — 아니면 **실패한다**(몰래 다른 주기로 돌지 않게). 간격 작업은 UTC 정각 기준(`*/5 * * * *`)이 되고 `initialDelay` 는 버린다.
- `profiles` 가 적힌 작업은 그 환경에만 만든다(다른 환경에서 부르면 404).

Cloud Scheduler 한 작업은 이렇게 만든다(목록의 줄마다 — terraform 이면 `for_each`):

```bash
gcloud scheduler jobs create http <taskId> \
  --location=asia-northeast3 --schedule="<cron>" --time-zone="<zone>" \
  --uri="https://<서비스>.run.app/internal/scheduler/run/<taskId>" --http-method=POST \
  --headers="X-Scheduler-Secret=<비밀값>" \
  --attempt-deadline=<budgetSeconds×2>s --max-retry-attempts=<retries> --min-backoff=<minBackoffSeconds>s
```

- `--attempt-deadline` 은 예산의 2배, Cloud Run 요청 제한 시간은 그보다 길게 — 기한이 실행보다 짧으면 재시도가 겹친다.
- 비밀값은 Secret Manager 에서 Scheduler 헤더 · 서비스 환경변수 양쪽에 같은 값으로. 저장소에 커밋하지 않는다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
