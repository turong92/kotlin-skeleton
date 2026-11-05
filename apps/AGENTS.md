# apps/ — 실행 앱 규칙

- Spring Boot applications, each in a sub-package of the root so `@SpringBootApplication` scans only app code (`dev.sumin.skeleton.app.api`, `dev.sumin.skeleton.app.workbench`); `apps/video-worker` is not one of them — a small standalone Kotlin worker with no server code dependency (`apps/video-worker/README.md`):
  - `apps/api` is the **starter**: the minimal composition with real accounts (`platform`, `auth`, `account-jdbc`, `auth-session-jdbc`, `legal-jdbc`, `persistence-jdbc`, `db-postgresql`, `migration-flyway`, `time`) with one `HelloController`. New projects start here; its tests are the proof that a small composition boots without Redis/Kafka/S3/mail.
  - `apps/workbench` is the **demo**: every module plus the sample controllers under `/api/v1/skeleton/**` and `/api/v1/examples/**` (the react-skeleton workbench UI calls them — do not change paths or shapes) and the integration tests proving the modules coexist.
<!-- sample:start -->
  - `apps/sample` is the **worked example** ("Notes": a signed-in user manages notes with attachments): the starter plus `idempotency`, `notification-jdbc`/`-sse`, `storage-s3`, `job-queue-jdbc`, `board`/`board-jdbc` (the demo accounts include a `MODERATOR`), `author-profile`/`author-profile-jdbc` (writes carry a profile id; storage keys are `OPAQUE`, so the secret `SKELETON_STORAGE_WEB_KEY_SECRET` is required outside local/test) and one small domain. It is a product-shaped slice to copy from — see "새 기능의 정본 예시" below. `scripts/new-project.sh` leaves it out unless `--with-sample`.
<!-- sample:end -->

**경계 책임:**
- `apps/api` 는 실행 앱 조립과 HTTP 변환만. 비즈니스 로직 금지. 서비스 호출. (`apps/workbench` 는 데모 — 제품 코드를 넣지 않는다.)
- 앱 고유 `domain/`, `infra/`, `config/` 패키지는 필요할 때 `apps/api` 의 `app.api` 하위 패키지로 둔다.

## 새 기능 추가 시

  1. `apps/api` 에 컨트롤러 + DTO (`dev.sumin.skeleton.app.api` 하위 — 앱 클래스 패키지 밖에 두면 스캔되지 않는다)
  2. 앱 고유 비즈니스 로직은 `apps/api` 안의 `domain/` 패키지에 둔다 (필요 시)
  3. 앱 고유 DB/외부 연동은 `apps/api` 안의 `infra/` 패키지에 둔다 (필요 시)
  4. 요청 DTO에 Bean Validation constraint 를 붙이고 integration test 로 `ApiError.errors[]` 를 확인한다
  5. 생성/비동기/204/page 응답은 platform 표준 helper와 annotation 을 먼저 사용한다
  6. 공통 web/error/observability 코드는 `modules/platform` 에 둔다
  7. 인증 계약과 로그인 흐름은 `modules/auth` 에 둔다
  8. 외부 API 연동은 `ExternalHttpClient` 기반으로 만들고, provider/vendor 별 mapper/customizer 만 추가한다
  9. 기능 모듈이 endpoint/route 를 자동 등록하면 같은 모듈의 `openapi/` 에 명세 기여도 같이 둔다
  10. DB 스키마 바뀌면 `./gradlew newMigration -Pname=<snake_case>` (→ `db/migration/postgresql/V<UTC 14자리>__<name>.sql`)
  11. 네이티브 이미지(선택 빌드, `docs/native-image.md`): 컨트롤러 밖에서 JSON 으로 바꾸는 타입(작업 일감 · 웹훅 본문 · JSON 컬럼 값)은 `@RegisterReflectionForBinding` 으로 등록 표시, 반환 타입은 구체적으로(`ResponseEntity<Any>` 금지), 이름으로 만드는 클래스 · classpath 리소스는 `RuntimeHintsRegistrar`, 빈 정의에 인스턴스 공급자(람다) 금지 — 새 의존성은 릴리스 전 `scripts/native-test.sh`
  12. entity audit 이 필요하면 선택한 persistence 모듈의 `AuditTimestamps` 를 사용하고, core/platform 에 JPA/JDBC annotation 을 직접 추가하지 않는다.

<!-- sample:start -->
## 새 기능의 정본 예시 — `apps/sample` (에이전트 필독)

새 기능(엔드포인트 + 저장 + 알림 + 비동기 작업)을 만들 때는 **처음부터 짜지 말고 `apps/sample` 의 `notes` 조각을 따라 한다.** 순서와 파일별 설명은 `docs/sample.md` 의 "이 기능 조각은 이렇게 조립됐다" (1 마이그레이션 → 2 엔티티 → 3 저장소 → 4 서비스 → 5 DTO·검증 → 6 컨트롤러 → 7 알림 → 8 잡 → 9 설정 → 10 테스트).

```
apps/sample/src/main/kotlin/dev/sumin/skeleton/app/sample/   # notes 조각의 주요 파일만 (같은 앱에 다른 기능 패키지도 있다: booking · orders · reviews …)
├── SampleApplication.kt            # 루트의 하위 패키지 — 모듈은 AutoConfiguration 으로만 붙는다
└── notes/
    ├── Note.kt                     # Spring Data JDBC 엔티티 (JdbcAuditable + AuditTimestamps)
    ├── NoteRepository.kt           # 소유자 조건 + 한 쿼리 검색
    ├── NoteService.kt              # 규칙 · 트랜잭션 (알림 · 잡 · 첨부 정리)
    ├── NoteController.kt           # HTTP 변환만 — /api/v1/notes
    ├── NoteDtos.kt · OwnAttachmentKey.kt · NoteErrors.kt   # 검증 · 구조 규칙 제약 · NOTES.* 에러 코드
    ├── NoteNotifier.kt             # NotificationPublisher → 받은편지함 + SSE (계약은 파일 맨 위)
    └── NoteExportJobHandler.kt     # job-queue-jdbc 핸들러 (멱등)
```

- 이 앱이 하는 선택(잡 폴링 · 첨부 규칙)은 앱 `application.yml` 에, 모듈은 메커니즘만 — 모듈 내부를 고쳐서 맞추지 않는다.
- 응답 · 에러 · 페이지 · 멱등 · 검증 에러 모양과 테스트 모양(진짜 PostgreSQL + 보안 체인, 저장소만 메모리)은 샘플 테스트(`apps/sample/src/test/…/notes/`)를 본다.
- 프론트 쪽 짝은 react-skeleton 의 `apps/sample`(Storybook Patterns 로 조립한 화면 · TanStack Query 훅) — 같은 `/api/v1/notes` 계약이다. 백엔드 조각을 바꾸면 그쪽 계약 문서도 함께 본다.
- 로컬 전체 실행 `scripts/dev-sample.sh`, e2e 용 `scripts/sample-e2e-backend.sh start|stop`.
<!-- sample:end -->
