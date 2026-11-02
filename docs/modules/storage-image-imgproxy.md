# storage-image-imgproxy

`storage-image` 의 선택 변환 엔진(`ImageRenderer`) - imgproxy 컨테이너(libvips)를 부른다. **HEIC · AVIF 입력과 WebP 출력**이 필요한 앱만 켜고, 대신 서버(컨테이너) 하나가 더 뜬다(메모리는 앱 안 변환보다 적다 - [배포](../deploy.md) 의 imgproxy 절). 기본 엔진(`storage-image-imageio`)은 HEIC · AVIF 를 못 읽고 JPEG(투명이면 PNG)로만 쓴다.

- **원본은 비공개 그대로** imgproxy 에는 설정된 저장소가 서명한 **짧은 수명의 원본 주소**(`source-ttl`)만 준다. 임의 주소를 받는 길이 없다(SSRF). imgproxy 쪽도 `IMGPROXY_ALLOWED_SOURCES` 로 저장소 주소만 허용해 이중으로 막는다.
- **서명 없는 imgproxy 는 없다** 요청 주소는 `key`/`salt` 로 HMAC-SHA256 서명한다. 켰는데 `url` · `key` · `salt` 가 비면(또는 16진수가 아니면) 기동 실패. 저장소(`PresignedStorage`, `storage-s3`)가 없어도 기동 실패.
- **출력** 메타데이터를 벗긴 WebP(`strip_metadata` · `strip_color_profile` · 저작권 보존 끔), EXIF 방향은 픽셀에 반영, 키우지 않는다. 시험이 실제 컨테이너로 GPS 든 JPEG 의 변환본에 입력의 EXIF 가 없음을 본다(libvips 가 적는 입력과 무관한 뼈대 EXIF 마디 - 해상도 · 버전 - 는 남는다).
- **상한** 요청마다 `timeout`, 응답 `max-response-bytes`. 처리기는 imgproxy 를 부르기 전에 머리의 크기로 `max-pixels` 를 확인하고, imgproxy 도 `IMGPROXY_MAX_SRC_RESOLUTION` · `IMGPROXY_MAX_SRC_FILE_SIZE` 로 한 번 더 지킨다. 서명 오류(401/403)는 설정 오류라 던져서 큐가 소진시킨다(`FAILED`), 해석할 수 없는 이미지(422)는 `REJECTED`(imgproxy 는 이유를 말하지 않는다).
- **엔진 순서** 켜면 `@Order` 최우선이라 JPEG · PNG · GIF · WebP · AVIF · HEIC 모두 이 엔진이 맡는다(기본 엔진은 쓰이지 않는다).
- **한계** 움직이는 GIF · WebP 는 첫 장만 변환한다(imgproxy 기본, 표시본도 첫 장). 상태 줄의 원본 크기는 머리에서 읽은 그대로라 EXIF 회전을 반영하지 않는다(변환본의 크기는 맞다). 변환본마다 imgproxy 가 원본을 한 번씩 받는다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage-image-imgproxy"))` |
| 함께 오는 모듈 | `storage-image`, `platform`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.storage.image.imgproxy` ([설정 블록](../config/modules/storage-image-imgproxy.yml)) |
| 기본 동작 | 꺼짐. `skeleton.storage.image.imgproxy.enabled=true` 로 켠다(기본 엔진 대신 쓰인다). |
| 부팅에 필요한 것 | 켜면 imgproxy 컨테이너의 주소(`url`)와 서명 키(`key` · `salt`) - 없으면 기동 실패. 저장소(`storage-s3`)도 필요. |
| 교체 지점 | `ImgproxyRenderer` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/storage-image-imgproxy/src/test` (`ImgproxyRendererTest` 는 Docker 로 imgproxy 컨테이너를 띄운다 - Docker 가 없으면 건너뛴다) |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
