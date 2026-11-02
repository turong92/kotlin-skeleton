# storage-image-imageio

`storage-image` 의 기본 변환 엔진(`ImageRenderer`) - 앱 안 순수 자바(JDK ImageIO + TwelveMonkeys JPEG · WebP). 추가 서버가 없다. JPEG · PNG · GIF · WebP 를 읽고 JPEG(투명이면 PNG)로 쓴다. HEIC · AVIF 원본은 읽지 못한다(`UNSUPPORTED`). JVM 전용(네이티브 이미지에서는 뺀다).

- **머리만 읽어 크기를 안다** 디코드 전에 폭 · 높이 · EXIF 방향 · 움직임을 읽으므로 픽셀 폭탄(작은 파일, 머리에 3만 x 3만)은 크기로 걸려 디코드되지 않는다.
- **메모리를 묶는다** 디코드는 부분 표본 추출(`setSourceSubsampling`)로 가장 큰 변환본의 두 배 근처까지만 올리고(변환본과 같은 크기로 읽으면 점 표본의 무아레가 생긴다), 원본 바이트는 메모리가 아니라 임시 파일에 캐시한다. 한 장당 대략 `(가장 큰 변환본의 가로 x 세로 x 4 x 2~4 x 4)` 바이트(기본 1600 x 1600 이면 수십~수백 MB) - 동시 변환은 `max-concurrent`(기본 1)가 묶는다. WebP 디코더는 표본 추출을 지키지 않을 수 있어 WebP 는 `max-pixels` 에 비례해 쓴다.
- **내보내는 사진은 깨끗하다** EXIF 방향은 픽셀에 반영하고, 색 공간은 sRGB 로 그려 넣고, 메타데이터 없이 다시 인코딩한다 - EXIF(GPS · 기기) · XMP · IPTC · ICC 가 변환본에 남지 않는다. 큰 변환본에서 작은 변환본을 반씩 줄여 이어서 만든다.
- **움직이는 GIF · WebP** 는 첫 장으로 썸네일만 만든다(`display` 는 원본). 두 번째 장이 앞 256KB 밖에서 시작하는 GIF 는 정지 이미지로 본다.
- **ImageIO 플러그인** TwelveMonkeys 는 `ImageIO.scanForPlugins()` 로 찾는다(스프링 부트 중첩 jar 에서도 보이게 시작 때 한 번 부른다). 배포 이미지는 JRE 의 headless AWT 가 필요하다(alpine JRE 에서 쓸 수 있다는 것은 배포 확인 몫).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage-image-imageio"))` |
| 함께 오는 모듈 | `storage-image`, `platform`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 꺼짐. `storage-image` 가 켜져 있을 때(`skeleton.storage.image.enabled=true`) 엔진으로 등록된다. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `ImageIoRenderer` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/storage-image-imageio/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
