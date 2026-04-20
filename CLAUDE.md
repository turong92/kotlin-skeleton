# kotlin-skeleton — Claude Code 컨텍스트

Kotlin + Spring Boot 백엔드 토이 프로젝트의 공개 출발점.

## 패키지 구조 (AI 참조용)

```
dev.sumin.skeleton/
├── KotlinSkeletonApplication.kt   # 엔트리 포인트 (수정 거의 없음)
├── api/                           # @RestController + 요청/응답 DTO
│   └── HelloController.kt
├── domain/                        # 비즈니스 로직, 서비스, 도메인 엔티티
├── infra/                         # DB 리포지토리, 외부 API 클라이언트
├── common/                        # 인프라성 공통 컴포넌트 (수정 드물음)
│   ├── ApiError.kt                # 표준 에러 응답 포맷
│   ├── ApplicationException.kt    # 도메인 예외 베이스 클래스
│   ├── GlobalExceptionHandler.kt  # 모든 예외 → ApiError 변환
│   └── TraceIdFilter.kt           # X-Request-Id → MDC traceId 심기
└── config/                        # @Configuration 빈 (보안, CORS 등)
```

**경계 책임:**
- `api/` 는 HTTP 변환만. 비즈니스 로직 금지. 서비스 호출.
- `domain/` 은 프레임워크 독립. Spring 애노테이션 최소화 (`@Service` 정도만)
- `infra/` 만 DB 연결, 외부 HTTP 호출. 도메인 레이어가 이걸 인터페이스로 참조
- 새 파일 200줄 넘어가면 분할 신호

## 핵심 컨벤션

- **REST 네임스페이스**: `/api/v1/*` — 컨트롤러에서 `@RequestMapping("/api/v1/...")`
- **응답 포맷**:
  - 성공: Kotlin data class → JSON (Jackson 자동)
  - 에러: [ApiError] (RFC 7807 변형 + traceId + timestamp)
- **도메인 예외**: `class XxxNotFoundException : ApplicationException(...)` 식으로 선언, throw만 하면 표준 응답
- **스키마 변경**: `src/main/resources/db/migration/V{n}__{desc}.sql` — Flyway 마이그레이션만
- **로그**: SLF4J 사용. 모든 로그 라인엔 traceId 자동 포함 (MDC)

## traceId 흐름 (디버깅용 핵심)

1. 프론트엔드가 요청마다 `X-Request-Id: <UUID>` 헤더 부착
2. [TraceIdFilter]가 이 값을 MDC `traceId` 키에 심음 (없으면 생성)
3. 모든 로그 라인에 `[app,<traceId>]` 프리픽스 출력
4. 응답 헤더 `X-Trace-Id` 로 같은 값 반환
5. 에러 응답 body `traceId` 필드에도 포함
6. **디버깅**: 프론트 콘솔/토스트에 찍힌 traceId 로 서버 로그 `grep` → 해당 요청 전체 흐름 한 번에 파악

## 프론트엔드와의 통신

프론트(React + Vite, [react-skeleton](https://github.com/turong92/react-skeleton))가 같은 도메인에서 `/api/v1/*` 호출. 프로덕션에선 Caddy가 정적 번들 + 백엔드 프록시를 한 origin으로 합침 → **CORS 불필요**.

## 작업 원칙

- **Kotlin idiomatic**: data class, scope function (`let`/`apply`/`also`), null 안전성 활용
- **테스트**: Testcontainers로 실 MySQL 띄워 Flyway 마이그레이션 포함 검증
- **새 기능 추가 시**:
  1. `api/` 에 컨트롤러 + DTO
  2. `domain/` 에 서비스
  3. `infra/` 에 리포지토리 (필요 시)
  4. DB 스키마 바뀌면 `db/migration/V{n}__.sql`
  5. `common/` 은 건드리지 말 것 (공통 인프라)

## 변경 이력

`CHANGELOG.md` 에 기록. 새 기능은 `[Unreleased]` 섹션에 먼저 적고, 릴리스 시 버전 섹션으로 승격.

## 스켈레톤 → 실제 프로젝트 전환 시

이 레포에서 `Use this template` 으로 출발한 뒤엔 업스트림 자동 동기화 안 함. 스켈레톤 업데이트는 CHANGELOG 확인해서 필요한 부분만 수동 반영.
