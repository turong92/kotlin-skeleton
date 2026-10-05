# config-aws-ssm

시작할 때 SSM 파라미터 경로를 Spring `Environment` 프로퍼티로 불러온다 (`EnvironmentPostProcessor`).
빈을 등록하지 않는다. `fail-fast` 가 불러오기 실패 시 시작을 막을지 정한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:config-aws-ssm"))` |
| 함께 오는 모듈 | 없음 |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.config.aws.ssm` — [docs/config/modules/config-aws-ssm.yml](../config/modules/config-aws-ssm.yml) |
| 기본 동작 | 조건부. `paths` 가 정해지거나 `credential-profile` 이 있을 때만 불러온다. |
| 부팅에 필요한 것 | 없음. 불러올 때 AWS 자격증명이 필요하다. |
| 교체 지점 | 없음 |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/config-aws-ssm/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
