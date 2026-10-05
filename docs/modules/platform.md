# platform

모든 모듈이 기대는 공용 기반이다: `ApiError` 응답 봉투와 전역 예외 처리, trace id · 요청 로깅 필터, CORS · rate limit 정책, 외부 HTTP 클라이언트(`ExternalHttpClient`), `TimeProvider`, 민감값 마스킹.
벤더 연동은 여기에 두지 않는다. 대부분의 모듈이 `implementation` 으로 이 모듈을 가져오므로 webmvc · webflux · validation · springdoc 이 런타임에 함께 온다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:platform"))` |
| 함께 오는 모듈 | 없음 |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.config.validation`, `skeleton.http`, `skeleton.observability.links`, `skeleton.openapi`, `skeleton.redaction`, `skeleton.web` — [docs/config/modules/platform.yml](../config/modules/platform.yml) |
| 기본 동작 | 켜짐. 필터 · 예외 처리 · OpenAPI 는 기본값으로 동작하고, CORS · rate limit 은 설정으로 켠다. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `GlobalExceptionHandler`, `TraceIdFilter`, `RequestLoggingFilter`, `TimeProvider`, `PublicEndpointRegistry`, `RateLimitStore`, `RateLimitKeyResolver`, `ExternalHttpClient`, `ExternalHttpErrorMapper`, `ExternalHttpTraceExtractor`, `SensitiveValueRedactor`, `ObservabilityLinkResolver` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/api-client` |
| 테스트 | `modules/platform/src/test` |

자세히: [설정 키 설명](../configuration.md) · [에러 계약](../errors.md) · [외부 HTTP](../external-http.md) · [로깅](../logging.md) · [관측 링크](../observability-links.md) · [OpenAPI](../openapi.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
