# 네이티브 이미지 (GraalVM) — 선택 빌드

**기본은 JVM이다.** `./gradlew build` · `Dockerfile` 은 네이티브와 상관없이 지금까지와 같다. 네이티브는 `-Pnative` 를 줄 때만 켜지는 **선택 빌드**이고,
단발 서비스를 Cloud Run 에서 안 쓸 때 0대로 내리려는 경우에 콜드스타트 · 메모리를 줄이려고 쓴다 ([Cloud Run 0대 운영](cloud-run-scale-to-zero.md)에서 JVM 과 고르는 기준).

## 만드는 법

```bash
scripts/native-build.sh --app api            # 결과: apps/api/build/native/nativeCompile/app (리눅스 실행 파일)
docker build -f Dockerfile.native -t <image>:<tag> .     # 배포 이미지 (기존 Dockerfile 은 JVM 그대로)
scripts/native-test.sh                       # 앱을 네이티브로 빌드해 새 DB 와 함께 띄우고 흐름 시험을 돌린다 (이 레포: tools/native-check, 새 프로젝트: apps/api + 자기 시험 --smoke)
```

- 빌드는 **GraalVM 공식 Docker 이미지**(`ghcr.io/graalvm/native-image-community:25`) 안에서 `./gradlew :apps:<앱>:nativeCompile -Pnative -PjavaToolchain=25` 를 돌린다 — 맥 · 윈도에서도 같은 결과, 호스트에 GraalVM 이 필요 없다.
  (맥에서 GraalVM 21 은 링크 오류가 난다. 25 를 쓴다.) `-PjavaToolchain=25` 는 컨테이너의 JDK 25 로 컴파일하되 바이트코드 목표는 21 로 둔다.
- 실행 파일은 **빌드한 쪽과 같은 아키텍처의 glibc 리눅스**에서만 돈다 (맥에서 만들면 arm64). **Cloud Run 은 amd64** — 배포용은 amd64 에서(CI · `NATIVE_PLATFORM=linux/amd64`, 맥에서는 에뮬레이션이라 느리다 — 그래도 끝까지 돌아 `--full` 시험 73항목이 통과했다) 만든다. 같은 출력 폴더를 다른 플랫폼으로 다시 만들면 `scripts/native-build.sh` 가 이전 결과를 지우고 새로 만든다.
- 빌드 자원: 시험용 조립(모듈을 모두 얹은 `tools/native-check`)으로 **arm64: 약 3분 20초(캐시가 따뜻할 때), 빌드 중 최대 메모리 6GB 안팎**(native-image 자체 보고), 바이너리 약 220MB.
  **amd64**(맥 Docker Desktop 의 에뮬레이션 — Rosetta): native-image 약 6분, Gradle 전체 약 8분, **최대 메모리 12.9GB**(에뮬레이션이 부풀린 값 — 아래 진짜 x86 값을 쓴다), 바이너리 약 229MB.
  **진짜 amd64**(WSL2 x86_64 12코어, 이미지 처리 · 포인트 · 광고 조립 `tools/native-check-extra`, 2026-11-18): native-image **1분 53초**, Gradle 전체 **2분 19초**(캐시가 따뜻할 때, 컨테이너 메모리 한도 9GB), native-image 보고 **최대 메모리 8.77GB**, 바이너리 **182MB**(+ 공유 라이브러리 약 4MB).
  한도를 **5GB** 로 낮춰도 끝났다(보고 최대 5.42GB, native-image 4분 3초 · 전체 4분 32초 — GC 가 늘어 느려진다). **GitHub `ubuntu-latest`(16GB) 러너에서 모듈을 모두 얹은 `tools/native-check`**(`native` 워크플로 첫 실행, 2026-11-18): native-image **6분 23초**, Gradle 전체 **9분 9초**, native-image 보고 최대 메모리 **11.61GB** — 16GB 러너에 들어간다(한도 12.66GB 로 잡힘).
  같은 `tools/native-check` 가 WSL 박스(15GB 를 다른 작업과 공유)에서는 한도 **9GB · 10GB 모두 메모리 부족으로 끝내지 못했다**(cgroup OOM, 종료 137 — 더 키워 보지 않았다: 박스가 다른 작업과 공유라 15GB 중 여유가 그만큼 없다). 모듈을 많이 얹을수록 빌드 메모리가 는다. 컨테이너/러너에 메모리를 넉넉히 준다 (`NATIVE_MEMORY`; GitHub 공개 러너는 16GB 인데 모든 모듈 조립도 최대 11.61GB 로 들어갔다 — 위 실측).
- Gradle 캐시는 `~/.gradle-native` 에 둔다 (`NATIVE_GRADLE_HOME`) — 안 그러면 매번 의존성을 다시 받는다.
- **네이티브 플러그인은 `-Pnative` 일 때만 앱에 걸린다.** 안 주면 기본 빌드는 그대로이고(플러그인 선언만 루트에 있다), 안 쓰는 팀이 신경 쓸 것은 없다.

## 실측 — JVM 과 네이티브 (같은 앱, 같은 조건)

JVM 줄과 `apps/api` 줄은 스파이크 측정, `tools/native-check` 줄은 이 문서의 정식 힌트로 만든 이미지(5회 중앙값). **amd64 줄은 Apple 실리콘에서 에뮬레이션으로 돌린 값이라 느리고 메모리가 부풀어 있다 — 실제 x86 서버의 숫자가 아니다**(Cloud Run 같은 진짜 amd64 에서 다시 잰다). 리눅스 arm64 컨테이너, **CPU 1 · 메모리 512MB**, PostgreSQL 은 컨테이너 밖. 5회 중앙값.
맥(M3 Pro)에서 잰 값이라 Cloud Run(linux/x86_64)과 다를 수 있다 — 방향은 믿고 숫자는 배포 환경에서 다시 잰다.

| | 기동(/health 200) | Spring "Started" | RSS 기동 직후 | RSS 요청 뒤 | 첫 로그인 |
|---|---|---|---|---|---|
| JVM 그대로 (docker, CPU 1) | 약 7.7s | 약 6.7s | — | — | 320~380ms |
| JVM 다듬기 (AOT + CDS + SerialGC 등) | 약 3.3s | 약 2.7s | 224MB | 234MB | 237ms |
| **네이티브 — 스타터 `apps/api`** (실측 스파이크) | **약 0.6s** | **약 0.36s** | **206MB** | **208MB** | **120ms** |
| **네이티브 — `tools/native-check`** (결제 · 구독 · 작업 큐 · 메일 · 저장소 · 소셜 5종 · 토스 · Paddle · SSE 를 모두 얹은 조립, 새 DB 에 마이그레이션까지, arm64) | **약 0.92s** | **약 0.7s** | **212MB** | 기본 흐름 전체를 돌린 뒤 약 288MB | — |
| 같은 조립, **amd64**(맥에서 에뮬레이션 — 참고용) | 약 3.65s | 약 3.0s | 334MB | 약 455MB | — |
| **네이티브 — `tools/native-check-extra`, 진짜 amd64**(WSL, 이미지 처리 · 포인트 · 광고, PostgreSQL 18, CPU 1 · 512MB, 5회 중앙값) | **0.64s**(`docker run` 부터) | — | 260MB(VmRSS) | 흐름 시험 29항목을 돈 뒤 282MB | — |

(스타터 `apps/api` 로 맥에서 직접 재면 JVM 2.8s/419MB → 다듬기 1.1s/269MB. 다듬기 = `-Dspring.aot.enabled=true` + CDS 아카이브 + `-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xmx256m` 등. 결과 · 명령 전체는 측정 보고서에 있다.)

## 켜기/끄기는 빌드 때 굳는다 (AOT)

Spring AOT 는 `@ConditionalOnProperty` · `@ConditionalOnBean` 같은 **조건을 빌드 때(processAot) 평가해 굳힌다.** 그래서 AOT 를 쓰는 실행 — 네이티브, 그리고 JVM 의 `-Dspring.aot.enabled=true` — 에서는
**환경변수로 모듈을 켜고 끄는 일이 되지 않는다.** 직접 확인했다 (`skeleton.auth-session.http.enabled`):

| 빌드 때 | 실행 때 환경변수 | 결과 |
|---|---|---|
| 켬 | 끔 | **켜진 채 그대로** |
| 끔 | 켬 | **꺼진 채 그대로** |

규칙: 이미지를 만들 때의 환경(`SKELETON_*` · 프로필)이 곧 **구성**이다. 이미지를 모듈 조합별로 따로 만든다. **값**(DB 주소 · 비밀 · 시드 계정 · 한도)은 `@ConfigurationProperties` 라서 실행 때 환경변수로 정상 바뀐다.
빌드 환경을 CI 에서 고정해 둔다. (이 한계를 푸는 작업은 하지 않았다.) (예외로, 작업 큐의 `skeleton.job-queue.mode` · `wake.secret` 은 실행 때 읽는다 — 깨우기 컨트롤러를 조건 없이 두고 요청마다 판정하므로, 같은 이미지가 환경변수로 폴링 ↔ 요청 구동을 오간다.)

## 리플렉션 규칙 — 새 코드를 쓸 때

네이티브는 **닫힌 세계**다: 이미지에 실제로 닿는 클래스만 들어가고, 리플렉션 · 리소스 · 프록시는 빌드 때 알려 줘야 한다. 이 레포는 한꺼번에 등록하지 않고 **필요한 것만** 모듈이 낸다
(`META-INF/spring/aot.factories` 의 `RuntimeHintsRegistrar`). 지금 내는 것은 이것뿐이다:

| 모듈 | 내는 것 | 이유 |
|---|---|---|
| `platform` (`KotlinReflectionHints`) | `kotlin.ParameterName`(리플렉션 + JDK 프록시), `kotlin.collections.EmptyMap/List/Set` | 컨텍스트가 서기 전에 코틀린 생성자를 읽는 곳, `emptyMap()` 이 응답에 실릴 때의 Jackson |
| `migration-flyway` (`FlywayNativeHints`) | `db/migration` 아래 파일, Flyway 의 `version.txt` · 플러그인 목록, `Slf4jLogCreator` | 마이그레이션 · Flyway 기동 |
| `legal` (`LegalNativeHints`) | `legal-templates/` · `legal/` 문서 | 약관 카탈로그 |
| `time` (`TimeNativeHints`) | `country-zones.tsv` | 나라별 시간대 표 |
| `auth` (`AuthNativeHints`) | `RequiredLoginIdentifierValidator` | Bean Validation 검증기 |
| `payment-toss` · `payment-kakaopay` · `payment-naverpay` · `payment-stripe` · `auth-social-google/kakao/naver` · `crypto` | 외부 응답 · 웹훅 본문 · 토큰 본문 DTO (`BindingHints`) | 컨트롤러 밖에서 JSON 으로 바꾸는 타입 |
| `auth-social` (`AuthSocialNativeHints`) | `OAuthSocialLoginRequest` | 소셜 로그인 경로가 컨트롤러가 아니라 **함수형 라우트**(`RouterFunction`)라 AOT 가 요청 본문 타입을 못 찾는다 |
| `notification-sse` (`NotificationSseNativeHints`) | `NotificationSseConnectedEvent` · `NotificationEvent` | `SseEmitter.send(객체)` 로 흘리는 이벤트 본문 |
| `persistence-jooq` (`JooqRecordAotProcessor`) | 앱 패키지의 jOOQ 레코드 클래스(`XxxRecord`)의 생성자 | jOOQ 가 `getDeclaredConstructor()` 로 레코드를 만든다 — 없으면 `Could not construct new record` 로 쓰기 · 읽기가 500 |
| `persistence-jooq` (`JooqNativeHints`) | `SQLDataType` 상수들의 자바 타입의 **배열형**(`String[]` · `Long[]` …) | jOOQ 의 `SQLDataType` 정적 초기화가 자료형마다 `Array.newInstance(타입, 0).getClass()` 로 배열형을 만든다. 네이티브에서 배열 클래스를 미리 알리지 않으면 null 이 나와 `ConcurrentHashMap.putIfAbsent` NPE 로 `DSLContext` 가 서지 못한다 |
| `storage-image-imageio` (`META-INF/native-image/storage-image-imageio/reachability-metadata.json`) | `java.awt` · `sun.java2d` · `sun.awt` 의 JNI 접근 목록, JPEG 리더/라이터(`jniAccessible`), TwelveMonkeys SPI 클래스, `META-INF/services/javax.imageio.spi.*` · ICC 프로필 리소스, `sun.awt.resources.awt` | `ImageIO` 를 처음 쓰면 AWT 네이티브 라이브러리가 JNI 로 `java.awt.GraphicsEnvironment` 등을 찾는다 — 등록이 없으면 기동 중 `NoClassDefFoundError`(그 다음은 `NoSuchMethodError: System.load`). JVM 추적 에이전트(`-agentlib:native-image-agent`)로 WebP · CMYK JPEG · GIF 를 돌려 모은 것에서 이 모듈이 쓰는 패키지만 추렸다. **`RuntimeHintsRegistrar` 가 JNI 를 표현하지 못해** GraalVM 의 metadata 파일을 모듈 jar 에 둔다 |
| `platform` (`ConfigurationPropertiesBindingAotProcessor`) | 모든 `@ConfigurationProperties` 클래스와 그 중첩 타입 · 맵 값 타입 | 코틀린 기본 인자 data class 의 중첩 설정(`StorageWebProperties` …)을 kotlin-reflect 가 못 찾는다 |
| `platform` (`ApiResponseEnvelopeAotProcessor`) | `@ApiResponseEnvelope(value = X::class)` 의 `X` | 컨트롤러가 `Any` 를 돌려줘도(저장소 presign) 응답 타입이 이 어노테이션에 있다 |

**새 코드를 쓸 때의 규칙** (`apps/AGENTS.md` 에도 한 줄):

1. **컨트롤러 밖에서 JSON 으로 바꾸는 타입은 등록 표시를 한다** — 작업 큐 일감 본문, 웹훅 본문, JSON 컬럼에 저장하는 값, 이벤트 페이로드. 컨트롤러의 `@RequestBody` · 반환 타입은 AOT 가 알아서 찾는다.
   그 밖의 타입은 쓰는 빈에 `@RegisterReflectionForBinding(Type::class)` 을 단다. (직접 확인: 표시가 없으면 네이티브에서 `KotlinReflectionInternalError: Unresolved class` 로 500 이다.)
2. **반환 타입을 구체적으로** 적는다. `ResponseEntity<Any>` · `Any` · `Map<String, Any>` 에 담긴 타입은 AOT 가 못 찾는다 (직접 확인: 깨우기 주소의 응답이 이 때문에 500 이었다). 단 `@ApiResponseEnvelope(value = X::class)` 를 단 메서드는 `X` 가 자동 등록된다. **함수형 라우트**(`RouterFunction`) · `SseEmitter.send(객체)` 의 본문 타입은 반환 타입에 안 보이므로 직접 등록한다.
3. **이름으로 만드는 클래스**(Hibernate Validator 의 `ConstraintValidator`, `ServiceLoader`, `Class.forName`)는 `RuntimeHintsRegistrar` 로 직접 등록한다. 빈 정의에 **인스턴스 공급자(람다)를 달지 않는다** — AOT 가 못 굽는다 (`payment-jdbc` 의 작업 처리기 등록은 생성자 인자 참조로, `auth-social-oidc` 의 제공자 빈은 팩터리 메서드 + 코드(문자열)로 바뀌었다). 설정 개수만큼 빈을 만드는 레지스트라도 같다 — 목록은 빌드 때 굳는다.
4. **classpath 리소스**(`getResource` · 약관 · SQL · 템플릿 · 이미지)는 패턴을 등록한다.
5. **새 의존성은 릴리스 전에 네이티브 시험으로 확인한다** (아래). 라이브러리가 리플렉션을 쓰는지는 문서를 봐서는 모른다 — 돌려 봐야 안다.
6. 라이브러리별 설정은 GraalVM 플러그인의 reachability metadata 저장소가 대고(자동), 자기 jar 에 더 새 설정이 든 것(`org.flywaydb:*`)은 루트 `build.gradle.kts` 의 `excludedModules` 로 jar 쪽을 쓴다.

앱이 자기 검증기 · 리소스를 더하면 앱에 `RuntimeHintsRegistrar` 하나를 두고 `@ImportRuntimeHints` 로 건다:

```kotlin
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(AppHints::class)
class AppHintsConfiguration

class AppHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, cl: ClassLoader?) {
        hints.resources().registerPattern("templates/*")
        hints.reflection().registerType(MyValidator::class.java, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_DECLARED_METHODS)
    }
}
```

앱 코틀린 클래스를 `kotlin-reflect` 가 읽는 경우(`Unresolved class: class <우리 클래스>`)는 위 `INVOKE_DECLARED_METHODS` 까지 등록해야 한다.

## 시험 — `scripts/native-test.sh`

`scripts/native-test.sh [--app <앱>] [--smoke <스크립트>]` 가 앱을 네이티브로 빌드하고(`scripts/native-build.sh`), 새 데이터베이스와 함께 컨테이너(CPU 1 · 512MB)에서 띄워 흐름 시험을 돌린다.
자기 compose 프로젝트의 Postgres 를 따로 올리고 끝나면 지운다 (개발 DB 는 건드리지 않는다).

- **이 레포(스켈레톤)**: `tools/native-check` — 스타터 + 결제 장부 + 구독 + 작업 큐(요청 구동)에 **가짜 결제사**(`ScriptedGateway`)를 얹은 시험용 조립(배포 대상 아님). `scripts/native-smoke.sh` 가 실제 HTTP 로 이 흐름을 돈다:
  기동 · 마이그레이션 → 가입(메일은 기록) · 이메일 확인 · 로그인 · 토큰 갱신(재사용 거절) → 가짜 결제사로 단건 결제 · 구독 시작 → 작업 큐 일감 하나(깨우기 주소로 처리) → 웹훅 하나(재전송 중복 · 틀린 비밀 조각 404) → jOOQ 레코드 쓰기 · 읽기 · 고치기(audit 리스너 · `fetchInto`) → OpenAPI 문서.
- **새 프로젝트**: `tools/` 와 `native-smoke.sh` 는 복사되지 않는다. `scripts/native-test.sh` 는 남아 **`apps/api`(내 앱)** 를 대상으로 돈다 — 기본은 최소 확인(/health · OpenAPI 문서 200).
  네이티브로 배포하기로 하면 **내 핵심 흐름(가입 · 결제 …)을 `scripts/<내 시험>.sh` 로 쓰고 `scripts/native-test.sh --smoke scripts/<내 시험>.sh`** 로 붙인다 (`--url <주소>` 를 받고 0 = 통과; 스켈레톤 레포의 `scripts/native-smoke.sh` 가 틀: ok · bad · expect · call 함수).
  앱에 필요한 설정(비밀 · 주소)은 `NATIVE_TEST_ENV="KEY=VALUE …"` 로 넘긴다. 워크플로(`.github/workflows/native.yml`)는 손으로 도는 것만 남아 있다 — 파일 안 주석대로 릴리스 태그 · 의존성 파일 PR 트리거를 켠다.

**언제 도는가** (스켈레톤의 `.github/workflows/native.yml`): 릴리스 태그(`v*`)를 밀 때, 의존성 파일(빌드 파일 · `gradle/` · lock) 또는 네이티브 힌트(`aot.factories`)가 바뀐 PR, 손으로. **평소 CI 는 그대로**다 (매번 몇 분이 더 든다).
시험이 깨지면 위 규칙의 어느 것을 빠뜨렸는지부터 본다 — 컨테이너 로그의 `Unresolved class` · `MissingReflectionRegistrationError` · `... missing from the class path` 가 거의 전부다.

### 샘플 앱(`apps/sample`)을 네이티브로 — 프런트 e2e 를 그 위에서

`scripts/native-build.sh --app sample` 이 샘플 앱을 만든다(맥 arm64 에서 약 5분, 실행 파일 약 224MB). 샘플은 모듈을 가장 많이 쓰는 앱이라 처음 만들 때 찾은 것이 둘 있다:
- **저장소(`CrudRepository`)가 있는 앱은 빌드 때 DB 에 연결하려 든다.** Spring Data 의 저장소 AOT 생성이 방언을 알아내려고 DB 에 붙는데 빌드에는 DB 가 없다. 앱 `application.yml` 에 `spring.aot.repositories.enabled: false` 를 둔다(저장소 프록시는 실행 때 만들어진다).
  또 `DatabaseStartupWaitPostProcessor`(DB 기다리기)는 AOT 처리 중(`spring.aot.processing`)에는 건너뛴다 — 빌드가 60초를 기다리다 죽던 일.
- 앱이 직접 쓰는 `ConstraintValidator`(샘플의 `OwnAttachmentKeyValidator`)는 앱의 `RuntimeHintsRegistrar`(`SampleNativeHints`, `@ImportRuntimeHints`)로 등록한다 — 규칙 3 그대로.

모듈 켜기/끄기가 빌드 때 굳으므로 **빌드 구성 = 그때의 환경**이다: `scripts/native-build.sh` 는 `NATIVE_BUILD_ENV="NAME1 NAME2 …"`(빌드 컨테이너에 그대로 넘길 환경변수 이름)을 받고, 값이 바뀌면(숫자는 무시) AOT 결과를 지우고 다시 한다.
`Dockerfile.native` 는 같은 구성을 빌드 인자 `BUILD_ENV_B64`(`KEY='값'` 셸 줄을 base64 한 줄로)로 받아 `set -a; . <파일>; set +a` 로 읽은 뒤 빌드한다 — Cloud Run 배포 워크플로가 선언의 `env:` 와 플랫폼 env 로 만들어 넘기고, 인자가 없으면 기존 빌드와 같다.

**프런트(react-skeleton) e2e 를 네이티브 백엔드로 돌리기**: `E2E_NATIVE=1 pnpm e2e` (kotlin 레포 위치는 `SAMPLE_API_DIR`). 백엔드 스크립트(`scripts/sample-e2e-backend.sh`)가 지금 환경(`SKELETON_*` · `SPRING_APPLICATION_JSON` · `PADDLE_*`)으로 샘플을 네이티브로 빌드해
리눅스 컨테이너로 띄운다. 앱이 쓰는 `localhost:<포트>`(DB · 로컬 S3 · 메일 · 가짜 소셜/토스/Paddle)는 곁 컨테이너(`alpine/socat`)가 호스트로 이어 줘서 JVM 때와 같은 설정이 그대로 맞는다.
이미 만든 실행 파일을 쓰려면 `E2E_NATIVE_SKIP_BUILD=1`. 기본 e2e(JVM)는 그대로다.

## 네이티브에서 확인한 것과 못 한 것

**기본 시험**(`scripts/native-test.sh`): 기동 · Flyway(새 DB 에 마이그레이션) · 가입(메일은 실제 SMTP → mailpit) / 로그인 / 갱신 · 단건 결제 · 구독 · 작업 큐(요청 구동) · 웹훅 · Jackson · 검증 · OpenAPI — 30항목.
**`--full` 시험**(`scripts/native-test.sh --full`, **릴리스 전에**): 가짜 상대를 붙여 실제 흐름으로 —
메일 발송(비밀번호 재설정 메일 수신) · 파일 저장(로컬 S3 에 업로드 주소 발급 → PUT → 내려받기) · 소셜 로그인 5종(google · kakao · naver · LINE(OIDC, HS256 ID 토큰 + PKCE + nonce) · X(PKCE) — 토큰 · 사용자정보 JSON 읽기) ·
Paddle(서명한 웹훅 → 주문 PAID, 틀린 서명 거절) · 토스(승인 · 웹훅 → 조회 · 빌링키 발급 + 첫 과금) · SSE 알림. 가짜 서버는 `scripts/native-fakes.py`(앱과 같은 네트워크 이름공간의 컨테이너 — OIDC 모듈이 http 엔드포인트를 localhost 에만 허용한다),
S3 는 compose 의 SeaweedFS(`s3` 프로필 — MinIO 를 쓰지 않는다: 이미 로컬 개발이 쓰는 S3 대역이다), 메일은 compose 의 mailpit. `--full` 은 `python:3-slim` 이미지가 필요하다.
이 시험이 **찾아 고친 것**(전부 위 표에 있다): `auth-social-oidc` 의 람다 공급자 빈, `OAuthSocialLoginRequest`(함수형 라우트), 저장소 응답(`Any` 반환), SSE 이벤트 본문, 코틀린 설정 중첩 타입.
**두 번째 조립 `tools/native-check-extra`**(`scripts/native-test.sh --app native-check-extra --ffmpeg`, 진짜 amd64 에서 29항목 통과): 이미지 처리(`storage-image` + `storage-image-imageio` + `storage-image-jdbc`: WebP · CMYK JPEG 를 ImageIO/TwelveMonkeys 로 읽어 표시본 · 썸네일 JPEG 로 변환 · 내려받기, 움직이는 GIF 는 앱 안 ffmpeg 가 MP4 표시본으로) ·
`point` · 웹 보상형 광고(`ads-web-rewarded` + jdbc: 세션 → 너무 빠른 요청 거절 → 보상 → `point` 잔액 · 두 번째 보상 거절) · `ads-admob`(ECDSA 서명 콜백 수락 · 같은 `transaction_id` 한 번만 적립 · 위조 거절 — 구글 대역은 이 앱의 `AdmobProbe`) · PostgreSQL 18.
찾아 고친 것: `storage-image-imageio` 의 AWT JNI 힌트(위 표). **`Dockerfile.native` 는 실행 파일 곁의 공유 라이브러리(`libawt*.so` · `libjavajpeg.so` …)까지 복사해야 한다** — 이미지 처리 앱의 네이티브 빌드는 이것들을 실행 파일 옆에 만든다(없으면 기동 실패). 이 레포의 시험은 폴더째 마운트하고 `Dockerfile.native` 는 폴더째 복사하도록 고쳤다.
**ffmpeg**: `Dockerfile.native` 의 빌드 인자 `FFMPEG=1`(기본 꺼짐)이 실행 이미지(Debian bookworm)에 ffmpeg 를 넣는다 — `scripts/native-test.sh --ffmpeg` 가 같은 `runtime-base` 단계로 시험한다. 모듈이 쓰는 `-fps_mode` 는 ffmpeg 5.1 이상이 필요하다(bookworm 은 5.1, Ubuntu 22.04 의 4.4 는 `Unrecognized option 'fps_mode'` 로 변환을 건너뛴다).
ffmpeg 가 없는 이미지에서는 사본이 쓰인다(시험이 그것을 확인한다). MySQL 8.4(`db-mysql`)는 이번에 확인하지 않았다(이 시험은 PostgreSQL 18 로 했다).
**못 본 것**: `payment-stripe`(시험용 조립에 없다) · `crypto` 의 불투명 URL 토큰 경로(`OpaqueUrlTokenPayload`, 저장소 공개 URL 을 `OPAQUE` 로 쓸 때) · 부하. (샘플 앱 전체 — 이메일 변경 · 로그인 아이디 · 매직 링크 · 게시판 · 작성자 프로필 · 노트 · 첨부 · 응원하기 — 는 프런트 e2e 로 확인했다, 위.) 이런 기능을 쓰는 앱은 처음 네이티브로 빌드할 때 같은 종류의 규칙 위반이 몇 개 더 나올 수 있다.
Flyway 는 기동 때 `Unable to scan location: /db/migration/postgresql (unsupported protocol: resource)` 경고를 여러 번 찍지만 마이그레이션은 적용된다.

## jOOQ (`persistence-jooq`)

네이티브에서 jOOQ 가 깨지는 곳은 둘이었고 모듈이 둘 다 막는다 (위 표):
1. `SQLDataType` 정적 초기화가 배열 클래스를 리플렉션으로 만든다 → `JooqNativeHints` 가 배열형을 등록한다 (`DSLContext` 가 서지 못하던 원인).
2. jOOQ 가 레코드를 `recordType.getDeclaredConstructor()` 로 만든다 → `JooqRecordAotProcessor` 가 앱 패키지(`AutoConfigurationPackages`)의 `org.jooq.Record` 구현 클래스(코드 생성 `XxxRecord` 포함)의 생성자를 빌드 때 찾아 등록한다.
   앱이 따로 표시하지 않아도 되지만, **레코드 클래스가 앱의 자동 구성 패키지(`@SpringBootApplication` 이 있는 패키지) 밖에 있으면** 앱이 `@RegisterReflectionForBinding` 이 아니라 자기 `RuntimeHintsRegistrar` 로 `INVOKE_DECLARED_CONSTRUCTORS` 를 등록해야 한다.
- 시험 경로: `tools/native-check` 의 `JooqProbeController` 가 jOOQ 로 레코드를 쓰고(audit 리스너가 `created_at` · `updated_at` 을 채운다) · 읽고 · 고치고 · `fetchInto(값 클래스)` 로 읽는다. `scripts/native-smoke.sh` 의 "jOOQ" 단계가 이것을 확인한다.
- **확인 상태**: 두 힌트는 단위 테스트(`JooqNativeHintsTest` · `JooqRecordAotProcessorTest`)로 확인했다. 네이티브 실빌드의 첫 시험에서 레코드 생성자 문제(2번)가 드러났고, 고친 뒤 `scripts/native-test.sh` 의 jOOQ 단계(쓰기 · audit 리스너 · 읽기 · `fetchInto` · 고치기)가 모두 통과했다(이 조립의 빌드는 Docker VM 메모리 6GB 가까이 필요하다 — 빌드 자원 절). 시험 DB 는 PostgreSQL 이라 MySQL 방언, 배열 컬럼, 집계 · 윈도 함수는 보지 않는다.
- **앱이 할 일 하나**: `fetchInto(클래스)` · `into(클래스)` 로 옮기는 값 클래스는 컨트롤러 반환 타입이 아니라 AOT 가 못 찾는다 — 쓰는 빈에 `@RegisterReflectionForBinding(값클래스::class)` 를 단다 (`JooqProbeController` 의 `ProbeNote` 가 그 예).

## CRaC — Zulu Warp 체크포인트 (선택 · 실험)

네이티브 대신 **JVM 을 그대로 두고 기동 직후 상태를 파일로 얼려** 복원하는 방법이다 (Azul Zulu 25 CRaC, Warp 엔진 `-XX:CRaCEngine=warp` — **추가 권한 없이** 체크포인트 · 복원, x86_64 · arm64).
`Dockerfile.crac` + `scripts/crac-build.sh --app <앱> [--ffmpeg]` 가 이미지를 만든다(기본 JVM · 네이티브 빌드는 그대로). 의존성은 `-Pcrac` 일 때만 `org.crac:crac` 이 앱 실행 클래스패스에 오른다(루트 `build.gradle.kts`).
방식: Spring Boot 의 `-Dspring.context.checkpoint=onRefresh` 로 앱을 띄워 체크포인트(`/crac/core.img`, 약 270MB)를 만들고, 복원은 `java -XX:CRaCEngine=warp -XX:CRaCRestoreFrom=/opt/crac`.

같은 조립(`tools/native-check-extra`)을 같은 박스(WSL amd64)에서 CPU 1 · 512MB 로 5회 중앙값:

| | JVM 그대로 | JVM 다듬기¹ | CRaC (Warp) | 네이티브 |
|---|---|---|---|---|
| 기동 (/health 200) | 12.0s | 7.6s | **0.79s** | **0.64s** |
| RSS 기동 직후 → 흐름 뒤 | 334 → 409MB | 303 → 332MB | 266 → 338MB | 260 → 282MB |
| 이미지(`FFMPEG=1`) | JDK + jar | 같음 | **1.96GB** (체크포인트 272MB 포함) | 약 0.95GB (런타임 764MB + 실행 파일 182MB) |
| 흐름 시험 29항목 | ffmpeg 항목만 빠짐² | 같음 | **통과**(ffmpeg 포함) | **통과** |
| 이미지 만들 때 필요한 것 | — | — | **살아 있는 DB**(체크포인트 때 앱이 뜬다) | 메모리 5~9GB |
| 추가 권한 | — | — | 없음 — `--cap-drop ALL`(privileged 아님) 컨테이너에서 복원 | — |

¹ 플래그만(`-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xmx256m -XX:CICompilerCount=1`). ² 시험 컨테이너에 ffmpeg 가 없었다.

**손대지 않고 되는 것**: ImageIO · TwelveMonkeys(WebP · CMYK JPEG) · 앱 안 ffmpeg 프로세스 호출 · jOOQ 가 아닌 JDBC · 메일 · S3 — 체크포인트를 위한 힌트가 필요 없다(JVM 이라 리플렉션 규칙도 없다). 이 점이 네이티브와 다르다.
**직접 확인한 제약**:
1. **열린 소켓이 있으면 체크포인트가 실패한다** (`CheckpointOpenSocketException`). 기본 설정의 Hikari 풀이 `connection-adder` 스레드가 만든 연결 10개를 열어 두므로 그대로는 `Failed to take CRaC checkpoint on refresh`.
   앱이 `org.crac.Resource` 를 빈으로 두고 체크포인트 직전에 풀을 멈추고(`allow-pool-suspension: true`) 연결을 다 닫은 뒤, 복원 때 `resumePool()` 하도록 했다(`tools/native-check-extra` 의 `CracPool.kt`가 틀 — **CRaC 는 `Resource` 를 약한 참조로 쥐므로 빈으로 살아 있어야 한다**). 이렇게 하면 복원 뒤 DB 를 다시 열고 흐름 시험이 통과한다. Redis · Kafka · 다른 소켓을 여는 모듈은 같은 일이 필요하다(이번에는 보지 않았다).
2. **체크포인트가 환경을 굳힌다.** `onRefresh` 는 빈이 다 만들어진 뒤에 찍히므로 앱은 체크포인트 때 **DB 에 붙고 Flyway 마이그레이션까지 돈다**. 복원 때 환경변수(`SPRING_DATASOURCE_PASSWORD` · `SKELETON_AUTH_JWT_SECRET`)를 바꿔 줘도 이미 읽은 설정은 바뀌지 않았다(틀린 DB 비밀번호를 줘도 옛 값으로 DB 가 쓰였다).
   **비밀(JWT · DB 비밀번호 · S3 키)이 `core.img` 에 문자열로 들어간다**(grep 으로 확인) → 이미지는 환경마다 따로, 접근 통제가 된 레지스트리에만 둔다. 같은 이미지를 환경변수로 바꿔 쓰는 배포 계약(홈서버 `docs/deploy.md`)과 맞지 않는다.
3. **복원 때 마이그레이션이 다시 돌지 않는다.** 빈 DB 에 복원하면 첫 요청이 `BadSqlGrammarException`. 체크포인트 DB 와 같은 스키마 버전이 이미 적용된 DB 에 붙어야 한다(배포 순서: 마이그레이션 먼저 → 이미지 복원).
4. 복원한 JVM 은 jar 경로 · 내용이 체크포인트 때와 같아야 한다(`/app/app.jar` 고정), CPU 세대가 다르면 `-XX:CPUFeatures` 로 맞춘다. 체크포인트 프로세스는 SIGKILL 로 끝난다(종료 코드 137 이 정상).
5. **비root 로 돈다**: `Dockerfile.crac` 의 기본은 `useradd` 한 사용자 `app`(uid 10001)이고 체크포인트도 그 사용자로 만든다. WSL2 에서 `--cap-drop ALL` + uid 10001 로 복원해 흐름 29항목이 통과했다(root 가 아니어도 된다).
6. **설정 안 한 값을 기동 때 난수로 만든 키**(예: 대기실 서명 키)도 체크포인트에 굳어 **모든 인스턴스가 같은 값**을 쓴다 — 인스턴스마다 달라야 하는 키는 CRaC 와 맞지 않는다.
7. 체크포인트 파일(`.crac/core.img`)에는 비밀이 들어 있다 — `.gitignore` 에 있고 `scripts/crac-build.sh` 가 이미지를 굳힌 뒤 비운다. 체크포인트 때의 환경은 `CRAC_ENV_FILE`(`--env-file` 형식)로 준다(명령줄에 비밀을 적지 않는다). 베이스 이미지(`azul/zulu-openjdk` CRaC, `mwader/static-ffmpeg`)는 다이제스트로 고정했다(2026-11-18).
8. 이 JDK 이미지는 Ubuntu 22.04 라 apt ffmpeg 가 4.4 다 — `Dockerfile.crac` 는 `FFMPEG=1` 일 때 정적 ffmpeg 7.1 을 넣는다.

**CRaC 는 비교용이다 — 비밀이 이미지에 굳으므로 배포 추천안이 아니다.** 그래서 `Dockerfile.crac` · `scripts/crac-build.sh` 는 새 프로젝트에 복사되지 않는다(스켈레톤 레포에만 남긴다).
**고르는 기준**: 콜드스타트가 0.8s 급이어야 하고 **무거운 라이브러리를 손대지 않고 쓰고 싶으면** CRaC, 이미지 크기 · 메모리 · 비밀을 이미지에 넣지 않는 계약이 중요하면 네이티브. 둘 다 환경(모듈 조합)마다 이미지를 따로 만든다. Cloud Run 에서의 실제 복원(gVisor 샌드박스에서 Warp 가 도는지)은 **시험하지 않았다**.

## 무거운 라이브러리를 쓰는 앱을 위한 안내

JS 압축기(Closure Compiler) · 이미지 처리(`ImageIO` · AWT) · jOOQ 같은 라이브러리는 리플렉션 · 리소스 · 네이티브 라이브러리를 많이 쓴다. 네이티브가 맞는지 정하는 순서:

0. **ImageIO · TwelveMonkeys(JPEG · WebP 읽기)는 이제 네이티브에서도 확인했다** (`tools/native-check-extra`, 위): `storage-image-imageio` 가 힌트를 낸다. 이미지 처리 앱의 `Dockerfile.native` 는 공유 라이브러리를 함께 복사한다. 그래도 PDF(`pdfbox`)·`imgproxy` 는 확인하지 않았다.
1. 이 레포의 모듈(`storage-s3` …)을 얹은 앱은 **모듈 단위로** 네이티브 시험을 먼저 돌린다 (스켈레톤 레포에서는 `tools/native-check` 에 모듈 한 줄을 얹어, 새 프로젝트에서는 앱에 얹어 `scripts/native-test.sh`). 안 되면 아래 둘 중 하나.
2. **JVM 으로 간다** — [Cloud Run 0대 운영](cloud-run-scale-to-zero.md)의 "JVM 다듬기"(AOT + CDS)가 콜드스타트를 절반 이하로 줄인다. 무거운 라이브러리가 핵심이면 이쪽이 안전하다.
3. 그 라이브러리를 **별도 서비스**로 떼어 낸다 (예: 이미지 처리만 JVM 서비스).
4. 정말 네이티브가 필요하면 라이브러리별로 `RuntimeHintsRegistrar` 를 쓰고(위 규칙), GraalVM 추적 에이전트(`-agentlib:native-image-agent`)로 JVM 에서 시험을 돌려 목록을 뽑아 **필요한 것만** 추려 모듈에 둔다 (결과를 통째로 붙이지 않는다).
