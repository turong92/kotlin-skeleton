# storage

벤더 중립 저장소 계약(`ObjectStorage`, `PresignedStorage`, `StoragePublicUrlResolver`)과 파일 크기 · 확장자 · content-type 검증이다.
실제 저장소는 `storage-s3` 같은 어댑터가 구현한다.

브라우저가 저장소로 직접 올리고 내려받는 HTTP 길도 이 모듈이 연다 (서블릿 웹 앱 + Spring Security + `PresignedStorage` 빈 = 버킷이 설정됐을 때, 인증 필요 · 기본 켜짐):
`POST /api/v1/storage/validate` · `/presign` · `/presign-download` · `/multipart/start` · `/multipart/part` · `/multipart/complete` · `/multipart/abort`.
키는 서버가 `<key-prefix>/<계정 id>/<uuid>/<파일 이름>` 으로 정하고, 클라이언트가 보낸 키는 자기 접두사 아래일 때만 받는다(남의 키는 404 `STORAGE.OBJECT_NOT_FOUND`). 업로드는 `StorageFileValidator` 를 통과해야 하고 거절은 400 `STORAGE.FILE_REJECTED`(`data.errors` 에 사유).
버킷을 정하지 않으면 저장소 빈이 없어 이 엔드포인트도 없다(404). 앱이 자기 컨트롤러를 두거나 끄려면 `skeleton.storage.web.enabled=false`.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.storage` — [docs/config/modules/storage.yml](../config/modules/storage.yml) |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `StorageFileValidator`, `StorageController` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/storage` |
| 테스트 | `modules/storage/src/test` |

자세히: [S3 저장소 상세](../storage-s3.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
