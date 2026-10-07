# storage

벤더 중립 저장소 계약(`ObjectStorage`, `PresignedStorage`, `StoragePublicUrlResolver`)과 파일 크기 · 확장자 · content-type 검증이다.
실제 저장소는 `storage-s3` 같은 어댑터가 구현한다.

브라우저가 저장소로 직접 올리고 내려받는 HTTP 길도 이 모듈이 연다 (서블릿 웹 앱 + Spring Security + `PresignedStorage` 빈 = 버킷이 설정됐을 때, 인증 필요 · 기본 켜짐):
`POST /api/v1/storage/validate` · `/presign` · `/presign-download` · `/multipart/start` · `/multipart/part` · `/multipart/complete` · `/multipart/abort`.
키는 서버가 `<key-prefix>/<계정 id>/<uuid>/<파일 이름>` 으로 정하고, 클라이언트가 보낸 키는 자기 접두사 아래일 때만 받는다(남의 키는 404 `STORAGE.OBJECT_NOT_FOUND`). 업로드는 `StorageFileValidator` 를 통과해야 하고 거절은 400 `STORAGE.FILE_REJECTED`(`data.errors` 에 사유).

**키 소유 방식 `skeleton.storage.web.key-owner`** — `ACCOUNT_ID`(기본, 위 꼴) | `OPAQUE`. `OPAQUE` 는 키가 `<key-prefix>/o/<uuid>.<mac>/<파일 이름>` 이라 키만 봐서는 계정 id 를 알 수 없고, 업로드마다 uuid 가 달라 같은 계정의 첨부끼리도 키로 이어지지 않는다(작성자 프로필을 쓰는 앱 — [board](board.md) · `author-profile`). `mac` 은 `HMAC-SHA256(key-secret, 계정 id + "\n" + uuid)` 의 앞 32자(hex)이고, 소유 확인은 다시 계산해 상수시간으로 비교한다(DB 없음).
`key-secret` 은 `OPAQUE` 일 때 32자 이상이어야 하고(비었거나 짧으면 기동 실패), 비밀이라 환경변수 `<P>_STORAGE_WEB_KEY_SECRET` 로 넣는다([deploy.md §7](../deploy.md)). 비밀을 바꾸면 이미 발급한 `OPAQUE` 키는 소유 확인에서 떨어진다. `ACCOUNT_ID` 꼴의 옛 키는 어느 방식에서나 지금처럼 소유가 확인되고, 남의 키 · 맞지 않는 `mac` 은 없는 키와 같은 404 다.

**`StorageKeyOwnership`** — `owns(accountId, key): Boolean`. 모듈이 현재 `key-owner` 방식에 맞는 빈을 내고(`@ConditionalOnMissingBean` — 앱이 같은 타입의 빈을 두면 그것이 쓰인다), 업로드 HTTP 가 꺼져 있거나 저장소 빈이 없어도 등록된다. 앱의 첨부 검사가 접두사 비교 대신 이것을 쓴다:

```kotlin
class AppBoardPolicy(private val keys: StorageKeyOwnership) : BoardPolicy by DefaultBoardPolicy() {
    override fun canAttach(caller: BoardCaller, keys: List<String>) = keys.all { this.keys.owns(caller.accountId, it) }
}
```

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
