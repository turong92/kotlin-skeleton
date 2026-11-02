# storage-image-imageio

`storage-image` 의 기본 변환 엔진(`ImageRenderer`) - 앱 안 순수 자바(JDK ImageIO + TwelveMonkeys). 추가 서버가 없다. 출력은 JPEG(투명이면 PNG), HEIC · AVIF 원본은 읽지 못한다. JVM 전용(네이티브 이미지에서는 뺀다).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage-image-imageio"))` |
| 함께 오는 모듈 | `storage-image`, `platform`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 꺼짐. `storage-image` 가 켜져 있을 때 엔진으로 등록된다. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | 없음 |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/storage-image-imageio/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
