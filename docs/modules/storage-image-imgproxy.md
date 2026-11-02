# storage-image-imgproxy

`storage-image` 의 선택 변환 엔진(`ImageRenderer`) - imgproxy 컨테이너(libvips)를 부른다. **HEIC · AVIF 입력과 WebP 출력**이 필요한 앱만 켜고, 대신 서버(컨테이너) 하나가 더 뜬다(메모리는 앱 안 변환보다 적다 - [배포](../deploy.md) 의 imgproxy 절). 기본 엔진(`storage-image-imageio`)은 HEIC · AVIF 를 못 읽고 JPEG(투명이면 PNG)로만 쓴다.

- **원본은 비공개 그대로** imgproxy 가 원본을 가져가는 길은 `ImgproxySourceResolver`(저장소 키 -> 원본 주소) 하나다. 기본 구현 둘: `SIGNED_URL`(기본) 설정된 저장소가 서명한 **짧은 수명의 읽기 주소**(`source-ttl`) · `S3` `s3://<버킷>/<키>` 를 주고 imgproxy 가 `IMGPROXY_USE_S3` 와 자기 읽기 전용 자격으로 직접 읽는다(R2 · MinIO 는 `IMGPROXY_S3_ENDPOINT`). 어느 쪽이든 주소는 앱이 만든 것이지 사용자 입력이 아니다(SSRF 가 열리지 않는다) - imgproxy 쪽도 `IMGPROXY_ALLOWED_SOURCES` 로 저장소(호스트 또는 `s3://<버킷>/`) 하나만 허용한다([배포](../deploy.md)). 다른 저장소(예 `gs://`)는 `ImgproxySourceResolver` 빈을 두면 끼운다 - 구현은 주지 않는다.
- **서명 없는 imgproxy 는 없다** 요청 주소는 `key`/`salt` 로 HMAC-SHA256 서명한다. 켰는데 `url` · `key` · `salt` 가 비면(또는 16진수가 아니면) 기동 실패. `SIGNED_URL` 인데 저장소(`PresignedStorage`, `storage-s3`)가 없거나 `S3` 인데 버킷이 없어도 기동 실패.
- **HEIC · AVIF 입력** 켜면 이 엔진이 JPEG · PNG · GIF · WebP · AVIF · HEIC 를 맡는다 - 기본 엔진만 있을 때 `UNSUPPORTED` 로 끝나던 HEIC 가 변환된다(크기는 머리의 `ispe` 상자에서 읽는다).
- **출력 형식 협상** `output: BOTH`(기본)면 변환본마다 **기본 형식**(JPEG, 투명하면 PNG)과 **WebP** 를 둘 다 만들어 저장소에 둔다(`WEBP_ONLY` · `FALLBACK_ONLY` 로 줄인다). 상태 줄과 `UploadImages` 에서는 기본 형식이 변환본이고 WebP 는 그 `alternates` 다 - `signedVariantUrl(key, variant, ttl, accept)` 가 브라우저의 `Accept` 에 `image/webp` 가 **명시**돼 있으면 WebP 를, 아니면 기본 형식을 서명한다(앱 안 엔진은 형식이 하나라 같은 호출이 기본 형식을 준다). 응답이 `Accept` 로 달라지니 캐시되는 응답이면 `Vary: Accept`.
- **출력** 메타데이터를 벗긴다(`strip_metadata` · `strip_color_profile` · 저작권 보존 끔), EXIF 방향은 픽셀에 반영, 키우지 않는다. 시험이 실제 컨테이너로 GPS · 기기 정보가 든 HEIC 의 JPEG · WebP 변환본에 입력의 EXIF 가 없음을 본다(libvips 가 적는 입력과 무관한 뼈대 EXIF - 해상도 · 버전 - 는 남는다).
- **상한** 요청마다 `timeout`, 응답 `max-response-bytes`. 처리기는 imgproxy 를 부르기 전에 머리의 크기로 `max-pixels` 를 확인하고, imgproxy 도 `IMGPROXY_MAX_SRC_RESOLUTION` · `IMGPROXY_MAX_SRC_FILE_SIZE` · `IMGPROXY_DOWNLOAD_TIMEOUT` · `IMGPROXY_TIMEOUT` 으로 한 번 더 지킨다. 서명 오류(401/403)는 설정 오류라 던져서 큐가 소진시킨다(`FAILED`), 해석할 수 없는 이미지(422)는 `REJECTED`(운영 모드의 imgproxy 는 이유를 말하지 않는다).
- **엔진 순서** 켜면 `@Order` 최우선이라 모든 이미지 종류를 이 엔진이 맡는다(기본 엔진은 쓰이지 않는다).
- **한계** 움직이는 GIF · WebP 는 머리에서 알아본 것만 움직임으로 본다(두 번째 장이 앞 256KB 밖에서 시작하는 GIF 는 정지로 본다). 움직이는 이미지는 썸네일만 imgproxy 가 첫 장으로 만들고 `display` 는 움직임을 그대로 둔 메타데이터 없는 사본이다(기본 엔진과 같다). 상태 줄의 원본 크기는 머리에서 읽은 그대로라 EXIF 회전을 반영하지 않는다(변환본의 크기는 맞다). 변환본마다 imgproxy 가 원본을 한 번씩 받는다(`BOTH` 면 두 번). 같은 그림의 두 형식은 크기가 같다고 보고 상태 줄에는 기본 형식의 크기만 적는다. 서비스 간 인증이 필요한 imgproxy(예: 인증 필요한 Cloud Run)로는 인증 헤더를 보내지 않는다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage-image-imgproxy"))` |
| 함께 오는 모듈 | `storage-image`, `platform`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.storage.image.imgproxy` ([설정 블록](../config/modules/storage-image-imgproxy.yml)) |
| 기본 동작 | 꺼짐. `skeleton.storage.image.imgproxy.enabled=true` 로 켠다(기본 엔진 대신 쓰인다). |
| 부팅에 필요한 것 | 켜면 imgproxy 컨테이너의 주소(`url`)와 서명 키(`key` · `salt`) - 없으면 기동 실패. 저장소(`storage-s3`)도 필요(`source=S3` 면 버킷). |
| 교체 지점 | `ImgproxyRenderer`, `ImgproxySourceResolver` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/storage-image-imgproxy/src/test` (`ImgproxyRendererTest` 는 Docker 로 imgproxy 컨테이너를 띄운다(HEIC 하나) - Docker 가 없으면 건너뛴다. 나머지는 Docker 없이 흉내 서버로) |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
