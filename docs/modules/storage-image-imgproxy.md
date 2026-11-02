# storage-image-imgproxy

`storage-image` 의 선택 변환 엔진(`ImageRenderer`) - imgproxy 컨테이너(libvips)를 부른다. HEIC · AVIF 입력과 WebP 출력이 필요한 앱만 켜고, 대신 서버(컨테이너) 하나가 더 뜬다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage-image-imgproxy"))` |
| 함께 오는 모듈 | `storage-image`, `platform`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 꺼짐. `skeleton.storage.image.imgproxy.enabled=true` 로 켠다(기본 엔진 대신 쓰인다). |
| 부팅에 필요한 것 | imgproxy 컨테이너와 그 주소 · 서명 키. |
| 교체 지점 | 없음 |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/storage-image-imgproxy/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
