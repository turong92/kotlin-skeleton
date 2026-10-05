# crypto

AES-GCM 텍스트 암호화(`TextEncryptor`), URL 에 안전한 불투명 토큰(`OpaqueUrlTokenCodec`), 선택적 JPA · JDBC 영속 변환기를 준다.
키 회전을 위해 키 id 를 붙여 암호화한다. `storage-s3` 의 OPAQUE 공개 URL 이 이 모듈을 쓴다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:crypto"))` |
| 함께 오는 모듈 | 없음 |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.crypto` — [docs/config/modules/crypto.yml](../config/modules/crypto.yml) |
| 기본 동작 | 조건부. `skeleton.crypto.keys` 에 키가 있어야 빈이 생긴다. |
| 부팅에 필요한 것 | 없음. 키는 쓸 때 필요하다. |
| 교체 지점 | `TextEncryptor`, `OpaqueUrlTokenCodec` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/crypto/src/test` |

자세히: [암호화 상세](../crypto.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
