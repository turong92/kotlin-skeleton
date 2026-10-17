# storage

벤더 중립 저장소 계약(`ObjectStorage`, `PresignedStorage`, `StoragePublicUrlResolver`)과 파일 크기 · 확장자 · content-type 검증이다.
실제 저장소는 `storage-s3` 같은 어댑터가 구현한다.

브라우저가 저장소로 직접 올리고 내려받는 HTTP 길도 이 모듈이 연다 (서블릿 웹 앱 + Spring Security + `PresignedStorage` 빈 = 버킷이 설정됐을 때, 인증 필요 · 기본 켜짐):
`POST /api/v1/storage/validate` · `/presign` · `/presign-download` · `/multipart/start` · `/multipart/part` · `/multipart/complete` · `/multipart/abort`.
키는 서버가 `<key-prefix>/<계정 id>/<uuid>/<파일 이름>` 으로 정하고, 클라이언트가 보낸 키는 자기 접두사 아래일 때만 받는다(남의 키는 404 `STORAGE.OBJECT_NOT_FOUND`). 업로드는 `StorageFileValidator` 를 통과해야 하고 거절은 400 `STORAGE.FILE_REJECTED`(`data.errors` 에 사유).

**키 소유 방식 `skeleton.storage.web.key-owner`** — `ACCOUNT_ID`(기본, 위 꼴) | `OPAQUE`. `OPAQUE` 는 키가 `<key-prefix>/o/<uuid>.<mac>/file[.<ext>]` 이라 키만 봐서는 계정 id 를 알 수 없고, 업로드마다 uuid 가 달라 같은 계정의 첨부끼리도 키로 이어지지 않는다(작성자 프로필을 쓰는 앱 — `board` · `author-profile`). `mac` 은 `HMAC-SHA256(key-secret, 계정 id + "\n" + uuid)` 의 앞 32자(hex)이고, 소유 확인은 다시 계산해 상수시간으로 비교한다(DB 없음).
`key-secret` 은 `OPAQUE` 일 때 32자 이상이어야 하고(비었거나 짧으면 기동 실패), 비밀이라 환경변수 `<P>_STORAGE_WEB_KEY_SECRET` 로 넣는다([deploy.md §7](../deploy.md)). 비밀을 바꾸면 이미 발급한 `OPAQUE` 키는 소유 확인에서 떨어진다. `ACCOUNT_ID` 꼴의 옛 키는 어느 방식에서나 지금처럼 소유가 확인되고, 남의 키 · 맞지 않는 `mac` 은 없는 키와 같은 404 다. 유효한 키가 아니거나(`..` · 제어문자) 접두사 뒤가 비어 있어도 소유가 아니다.
**`o` 규칙**: `OPAQUE` 에서는 `<key-prefix>/o/` 로 시작하는 키를 `mac` 으로만 판정한다 — 그래서 계정 id 가 `o` 인 계정의 옛 `ACCOUNT_ID` 꼴 키(`uploads/o/…`)는 `OPAQUE` 에서 소유로 인정되지 않는다(`o` 를 계정 id 로 쓰지 않는다). 기본 방식에서는 이 예외가 없다.
**파일 이름**: `OPAQUE` 키에서는 클라이언트가 보낸 파일 이름이 사라지고 **알려진 확장자**(내장 목록: jpg jpeg png gif webp heic heif avif bmp svg pdf txt md csv json xml zip gz mp3 wav m4a mp4 mov webm docx xlsx pptx hwp, 원본 이름 끝에서 소문자로 비교)만 남는다(`file[.<ext>]`). 목록에 없으면 `file` — `kim.minsu` · `example.com` · `report.2024` 처럼 이름의 일부가 확장자 자리에 올 수 있어서다. 파일 이름이 두 프로필의 첨부를 잇는 값이 되기 때문이다. 원래 이름이 필요하면 앱이 별도 열에 저장한다. 소유 확인은 `<uuid>.<mac>/` 뒤 꼬리가 `file` 또는 `file.<소문자 영숫자 1~8자>` 일 때만 인정한다. 기본 방식은 지금처럼 정리한 원래 이름을 쓴다.

**`StorageKeyOwnership`** — `owns(accountId, key): Boolean`. 모듈이 현재 `key-owner` 방식에 맞는 빈을 내고(`@ConditionalOnMissingBean` — 앱이 같은 타입의 빈을 두면 그것이 쓰인다. 단 업로드 HTTP 의 발급 · 확인은 이 빈을 보지 않고 `StorageController` 가 직접 정한다), 업로드 HTTP 가 꺼져 있거나 저장소 빈이 없어도 등록된다. 앱의 첨부 검사가 접두사 비교 대신 이것을 쓴다:

```kotlin
class AppBoardPolicy(private val keys: StorageKeyOwnership) : BoardPolicy by DefaultBoardPolicy() {
    override fun canAttach(caller: BoardCaller, keys: List<String>) = keys.all { this.keys.owns(caller.accountId, it) }
}
```

**프로젝트 여럿([multi-project](../multi-project.md))**: `storage.web.key-prefix` · `key-owner` · `key-secret` · `guest.*`(`guest.secret` 은 프로젝트끼리 같으면 기동 실패) 를 프로젝트마다 둘 수 있고(`skeleton.projects.<id>.config.storage.web.*`) 발급 · 소유 확인이 지금 프로젝트의 규칙으로 돈다. `OPAQUE` 비밀은 프로젝트마다 달라야 한다 — 같으면 기동 실패(A 의 비밀로 B 의 키를 만들 수 있다). 비밀은 키의 MAC 에만 쓰이고 프로젝트 id 는 섞지 않는다(한 프로젝트를 떼어 혼자 띄워도 발급한 키가 그대로 맞는다). 프로젝트마다 저장소(`ProjectRoutedStorage`)와 같은 버킷을 나눠 쓰는 접두사 장식(`KeyPrefixedPresignedStorage`)은 어댑터 문서 참고. 목록이 비면 지금과 같다.

**비회원 업로드 `skeleton.storage.web.guest.*`** — **기본 꺼짐**(앱 yml 이 명시해야 켜진다). 켜면 `POST /api/v1/storage/guest/presign` **하나만** 로그인 없이 열린다(`PublicEndpointContributor`) - 다운로드(`presign-download`) · 멀티파트 · `validate` 는 지금처럼 로그인이 필요하고, 꺼진 채에서는 이 경로도 401(`STORAGE.UNAUTHENTICATED`)이다. 본문 `{fileName, contentType, sizeBytes, captchaToken?}` 의 파일 **하나**, 응답은 `presign` 과 같다.
- **키** `<key-prefix>/g/<uuid>.<mac>/file.<ext>`, `mac = HMAC-SHA256(guest.secret, "guest\n" + uuid)` 의 앞 32자(회원 키의 `key-secret` 과 별개 비밀, 프로젝트마다 달라야 한다). `<ext>` 는 파일 이름이 아니라 허용한 Content-Type 에서 정한다 (jpg · png · webp · gif). `StorageKeyScheme.isGuestKey(key)`(꼴 + MAC 상수시간 비교)가 소유 확인이고, platform 의 `GuestUploadKeys` 로 board 에 이어진다. 회원의 `owns` 는 `<key-prefix>/g/` 를 누구의 키로도 인정하지 않는다(계정 id 가 `g` 인 계정도).
- **제한(설정, 기본)** 이미지 형식 `allowed-content-types`(jpeg · png · webp · gif 안에서만 고를 수 있다 - **SVG 는 설정으로도 못 연다**, 스크립트가 든 이미지로 저장형 XSS 가 되기 때문) · `max-size-bytes` 10 MB · `rate-limit` IP 별 20 / 1시간(`RateLimitStore` - Redis 가 있으면 공유, `rate-limit.enabled` 와 무관하게 `guest.enabled` 면 늘 걸린다; 거절된 요청도 센다 → `429 STORAGE.RATE_LIMITED`) · 파일 이름 확장자는 Content-Type 과 맞아야 한다(`EXTENSION_CONTENT_TYPE_MISMATCH`, `400 STORAGE.FILE_REJECTED`). presign 이 Content-Type · 크기를 서명에 넣으므로 저장소가 선언과 다른 업로드를 거절한다. **매직 바이트는 서버가 볼 수 없다** - 브라우저가 저장소로 직접 올리는 길이라서, 위 서명 + 이미지 형식만 허용 + SVG 금지가 한계선이다.
- **캡차** `guest.captcha=REQUIRED` 면 발급에 `captchaToken` 이 필요하다(`400 STORAGE.CAPTCHA_FAILED`) - `captcha-turnstile` 이 있으면 그 검증기, 없으면 `StorageGuestCaptcha` 빈이 필요하고 둘 다 없으면 기동 실패. **공개 서비스에서는 board 의 `guest.captcha` 와 함께 켠다** (익명 스토리지 쓰기 = 스팸 · 비용 위험).
- **고아 회수** `guest.cleanup.enabled=true`(기본 꺼짐)면 `cleanup.ttl`(24시간) 안에 글 · 댓글(고친 판 포함)에 붙지 않은 비회원 키를 한 시간마다 지운다(`scheduler` 모듈의 `skeletonTaskScheduler` 에 예약 - 모듈이 없으면 경고만 하고 돌지 않는다, 락 없이 인스턴스마다 돌아도 안전하다(지우기는 멱등), 한 번에 `batch-size` 개, 프로젝트 모드에서는 프로젝트마다). 지우는 조건은 비회원 꼴 + MAC 이 맞고(남이 둔 파일은 안 건드린다) · 만든 지 ttl 이 지났고 · platform 의 `UploadedKeyUsage`(board-jdbc 가 구현)가 사용 중이라고 하지 않은 것 - **구현이 없으면 아무것도 지우지 않는다.** 올린 뒤 ttl 이 지나서야 붙이는 키는 이미 지워졌을 수 있다(그 글의 사진이 깨진다).
- **`UploadedKeyUsage` 는 여러 개일 수 있다** - 합집합이다(`UploadedKeyUsages.union`, 하나라도 쓰고 있다고 하면 쓰는 것). board-jdbc 가 글 · 댓글 · 판의 키를 알리고, **앱은 자기 키만 알리면 된다**(같은 타입의 빈을 하나 더 두면 board 의 보호가 빠지지 않는다). 회수는 쪽마다 붙었는지 물어 고아가 `batch-size` 모일 때까지 넘긴다.
- **남는 위험** 익명 업로드는 IP 한도 안에서 저장소 용량을 쓴다(10 MB × 20 / 시간 / IP) - 캡차 · 회수 · 버킷 수명 규칙을 함께 둔다. IP 한도는 같은 망의 다른 사용자에게 번진다.

**게시판 사진의 공개 영역 `skeleton.storage.web.public.*`** — **기본 꺼짐**. 켜면 board 가 **공개된 글 · 댓글(PUBLISHED)의 첨부만** 공개 영역에 복사해 공개 주소(CDN)로 내보내고, 승인 대기 · 거절 · 가림 · 삭제 · 고친 판의 사진은 비공개(업로드 영역)에만 둔다. 승인하면 복사하고, 가리거나 지우거나 판으로 빠지면 공개 영역에서 지운다(원본은 비공개에 남는다).
- **설정** `enabled`, `secret`(공개 키 HMAC 비밀, 32자 이상, 환경변수 `<P>_STORAGE_WEB_PUBLIC_SECRET`), `prefix`(`public` - 업로드 접두사 `key-prefix` 와 겹치면 기동 실패), `base-url`(공개 영역 앞의 CDN 또는 공개 읽기 버킷 주소 - 켜면 필수, 주소는 `<base-url>/<공개 키>`), `cache-control`(공개 객체에 붙는 `Cache-Control`, 기본 `public, max-age=3600`), `access-ttl`(관리하는 사람의 서명 읽기 주소 수명, 기본 5분).
- **공개 키** `<prefix>/b/<hmac32>.<ext>` (`b/` 는 board 가 쓰는 하위 접두사 - board 의 전체 대조는 그 안만 본다, 같은 공개 접두사를 다른 용도가 써도 지우지 않는다), `hmac32 = HMAC-SHA256(public.secret, "public\n" + 비공개 키)` 의 앞 32자. 비공개 키(계정 id · 비회원 표시 · 원래 이름)가 드러나지 않고 비밀 없이는 추측할 수 없다. 키에서 다시 계산되므로 따로 대응표 없이 지울 수 있다. 같은 사진을 두 곳에 붙이면 공개 객체 하나를 나눠 쓴다(마지막으로 내려가는 곳이 지운다).
- **구성 요소** platform 포트 `PublicUploads`(공개 주소 계산 · 복사 · 삭제 - storage 의 `StoragePublicUploads` 가 구현) · `PrivateUploadAccess`(비공개 사진의 서명 주소 - `StoragePrivateUploadAccess`) · `PublicUploadPurger`(공개 객체를 지운 뒤 CDN 캐시를 비우는 자리). board 가 이 포트만 안다.
- **읽기 공개는 인프라가 정한다** - 이 모듈은 공개 영역의 읽기를 열지 않는다. 버킷 정책이나 CDN 원본 경로로 `<prefix>/` 아래만 익명 읽기로 연다(업로드 영역은 닫힌 채). 로컬 SeaweedFS 는 `docker-compose.yml` 의 s3 신원 설정에 익명 읽기 `Read:app/public/*` 한 줄([storage-s3.md](../storage-s3.md)).
- **형식** 복사 전에 원본 HEAD 로 Content-Type 을 읽어 그대로 쓰고(확장자로 짐작하지 않는다), image/jpeg · png · webp · gif · avif · heic · application/pdf 밖이면 공개하지 않는다(SVG · 텍스트 · 알 수 없음). 공개 객체에 `X-Content-Type-Options: nosniff` 를 붙이는 것은 CDN · 버킷 설정의 몫이다(업로더가 올린 형식을 브라우저가 다시 짐작하지 못하게).
- **공개 주소의 모양** `base-url` 은 CDN 절대 주소(`https://cdn.example.com/app`) 또는 **같은 출처의 상대 경로**(`/files`)다. 상대 경로면 응답 주소가 그대로 `/files/public/b/…` 가 되어 앱을 연 주소(터널·폰 포함)와 같은 출처에서 읽는다 - 로컬 개발은 프런트 dev 서버(vite)가 `/files` 를 로컬 S3 의 공개 접두사로 넘긴다. 운영은 CDN 절대 주소를 쓴다.
- **CDN 캐시 비우기(Cloudflare)** `skeleton.storage.web.public.purge.cloudflare.{enabled, zone-id, api-token}` (기본 꺼짐, `api-token` 은 비밀 - 환경변수 `<P>_STORAGE_WEB_PUBLIC_PURGE_CLOUDFLARE_API_TOKEN`, 로그 · `toString` 에 안 나간다). 공개 객체를 지울 때마다(가림 · 삭제 · 판으로 빠짐 · 전체 대조 정리) **객체를 먼저 지우고** `POST /zones/{zone-id}/purge_cache` `{"files":[<절대 주소>…]}` 를 부른다(한 번에 30개까지, `Authorization: Bearer`, platform outbound HTTP 클라이언트 `cloudflare-purge` - `skeleton.http.clients.cloudflare-purge.*`). purge 가 실패하면 예외로 알려 board 의 조정자가 항목을 큐에 남겨 다시 시도한다(지우기는 이미 끝났다 - 공개 쪽 보장은 「객체 삭제가 먼저」 그대로). 줄 없이 남은 객체를 전체 대조가 지울 때의 purge 실패는 경고 로그만 남는다(그 객체는 더 이상 나열되지 않는다). **`base-url` 이 상대 경로면 `purge.cloudflare.public-origin`(예: `https://app.example.com`)이 필요하다** - 없으면 기동 실패다(CDN 이 캐시한 것은 절대 주소라 purge 가 그 이름을 알아야 한다). 프로젝트 모드: purge 를 켠 프로젝트는 자기 `zone-id` · `api-token` 이 필요하다(`storage.web.public.*` 는 프로젝트 `config` 로 내려간다).
- **CDN 캐시** 가려도 CDN 에 남는 시간은 공개 객체의 `Cache-Control` max-age 이내다(기본 1시간). 즉시 지우려면 앱이 `PublicUploadPurger` 를 구현해(CDN 의 purge API 호출) 빈으로 둔다 - 이 레포에는 구현이 없다. 프로젝트 모드: `public.secret`(프로젝트끼리 같으면 기동 실패) · `base-url` · `prefix` 를 프로젝트마다 두고 버킷은 프로젝트의 저장소를 따른다.

버킷을 정하지 않으면 저장소 빈이 없어 이 엔드포인트도 없다(404). 앱이 자기 컨트롤러를 두거나 끄려면 `skeleton.storage.web.enabled=false`.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | `captcha-turnstile` |
| 설정 접두사 | `skeleton.storage` — [docs/config/modules/storage.yml](../config/modules/storage.yml) |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `StorageFileValidator`, `StorageController`, `StorageKeyOwnership` (앱의 첨부 검사용 — 업로드 HTTP 의 발급 · 확인은 이 빈을 보지 않는다. 그쪽을 바꾸려면 `StorageController` 를 교체) |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/storage` |
| 테스트 | `modules/storage/src/test` |

자세히: [S3 저장소 상세](../storage-s3.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
