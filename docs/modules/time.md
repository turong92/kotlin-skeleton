# time

요청마다 뷰어의 시간대 · 로케일을 정하고(`TimeContext`), 미래 현지 시각 `ZonedMoment` · 사람이 읽는 포맷(`TimeFormatter`) · 국가 → 시간대 조회를 준다.
시각의 세 종류(`Instant` · `LocalDate` · `ZonedMoment`)를 구분하는 규칙은 문서에 있다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:time"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.time` — [docs/config/modules/time.yml](../config/modules/time.yml) |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `TimeContext`, `TimeFormatter` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/time` |
| 테스트 | `modules/time/src/test` |

자세히: [시간 처리 상세](../time.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
