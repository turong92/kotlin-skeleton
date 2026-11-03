# video-cloudrun

**자리만 잡아 둔 모듈이다 — 코드가 아직 없다.** 변환 일꾼이 0 으로 줄어드는 실행 환경(Google Cloud Run 작업)에서 돌 때, 서버가 일이 생기면 그 작업을 깨우는 `VideoWaker` 구현이 같은 묶음의 다음 조각에서 여기에 들어온다(서비스 계정 키로 JWT 를 서명해 Admin API 를 직접 부른다 — 구글 라이브러리 없음, 동시 실행 상한 · 최소 간격 · 연속 실패 경보). 홈서버 컨테이너나 아무 기계에서 같은 일꾼 이미지를 도는 길은 이 모듈이 필요 없다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:video-cloudrun"))` |
| 함께 오는 모듈 | `video`, `platform`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 꺼짐. 지금은 아무것도 등록하지 않는다. |
| 부팅에 필요한 것 | 없음 (구현이 들어오면 Cloud Run 프로젝트 · 지역 · 작업 이름 · 서비스 계정 키). |
| 교체 지점 | 없음 |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/video-cloudrun/build.gradle.kts` (테스트는 구현과 함께 온다) |

[모듈 색인](README.md) · [video](video.md)
