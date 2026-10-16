# storage-s3

`PresignedStorage` 를 S3 호환 저장소(AWS S3, Cloudflare R2, MinIO)로 구현한다. presign PUT/GET · 멀티파트 · 복사 · 일괄 삭제를 지원한다.
공개 URL 은 `RAW`(기본)와 `OPAQUE`(암호화 토큰) 중에서 고른다. `OPAQUE` 만 `crypto` 가 필요하고, 그 의존은 컴파일 전용이라 평범한 S3/R2 앱은 `crypto` 를 받지 않는다.

프로젝트 여럿([multi-project](../multi-project.md)): 프로젝트마다 `storage-s3.*`(버킷 · 지역 · 엔드포인트 · 자격 증명 · presign · 공개 URL)를 따로 두고 S3 클라이언트 · 서명기 · 저장소를 프로젝트마다 만든다 — 부를 때 지금 프로젝트의 것으로 넘어간다(문맥 없이 부르면 예외). 버킷을 정하지 않은 프로젝트는 저장소가 없다(쓰면 그 프로젝트 이름의 예외).
같은 버킷을 나눠 쓰려면 `key-prefix` 를 프로젝트마다 다르게 준다 — 앱은 접두사 없는 키로 말하고 버킷에는 `<key-prefix>/<키>` 로 들어간다. 같은 버킷 · 같은 접두사를 둘이 쓰면 기동 실패다. 프로젝트 모드의 공개 URL 은 `RAW`(프로젝트의 `base-url` + 실제 키, 접두사 포함)만 — `OPAQUE` 는 프로젝트별 암호화 키가 필요한데 `crypto` 가 아직 프로젝트를 모른다([multi-project](../multi-project.md)).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage-s3"))` |
| 함께 오는 모듈 | `platform`, `storage` |
| 컴파일 전용 | `crypto` |
| 설정 접두사 | `skeleton.storage-s3` — [docs/config/modules/storage-s3.yml](../config/modules/storage-s3.yml) |
| 기본 동작 | 켜짐. 버킷이 없으면 클라이언트만 있고 `PresignedStorage` 빈은 없다. |
| 부팅에 필요한 것 | 없음. 버킷(R2 · MinIO 는 endpoint · 키도)은 저장소를 쓸 때 필요하다. `public-url.strategy=OPAQUE` 는 앱이 `:modules:crypto` 와 `skeleton.crypto.keys` 를 더하지 않으면 시작에 실패하고 메시지가 그 모듈을 짚는다. |
| 교체 지점 | `S3Client`, `S3Presigner`, `StoragePublicUrlResolver`, `PresignedStorage` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/storage` |
| 테스트 | `modules/storage-s3/src/test`, `modules/storage-s3/src/noCryptoTest` (`crypto` 가 클래스패스에 없을 때(RAW 동작 · OPAQUE 실패 메시지)) |

자세히: [S3 저장소 상세](../storage-s3.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
