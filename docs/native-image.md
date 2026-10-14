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
- 실행 파일은 **빌드한 쪽과 같은 아키텍처의 glibc 리눅스**에서만 돈다 (맥에서 만들면 arm64). **Cloud Run 은 amd64** — 배포용은 amd64 에서(CI · `NATIVE_PLATFORM=linux/amd64`, 맥에서는 에뮬레이션이라 매우 느리다) 만든다.
- 빌드 자원: 시험용 조립으로 **약 2분(캐시가 따뜻할 때), 빌드 중 최대 메모리 5.4~6GB**(native-image 자체 보고), 바이너리 약 150~190MB. 컨테이너에 메모리 8GB 이상을 준다 (`NATIVE_MEMORY`).
- Gradle 캐시는 `~/.gradle-native` 에 둔다 (`NATIVE_GRADLE_HOME`) — 안 그러면 매번 의존성을 다시 받는다.
- **네이티브 플러그인은 `-Pnative` 일 때만 앱에 걸린다.** 안 주면 기본 빌드는 그대로이고(플러그인 선언만 루트에 있다), 안 쓰는 팀이 신경 쓸 것은 없다.

## 실측 — JVM 과 네이티브 (같은 앱, 같은 조건)

JVM 줄과 `apps/api` 줄은 스파이크 측정, `tools/native-check` 줄은 이 문서의 정식 힌트로 만든 이미지(5회 중앙값). 리눅스 arm64 컨테이너, **CPU 1 · 메모리 512MB**, PostgreSQL 은 컨테이너 밖. 5회 중앙값.
맥(M3 Pro)에서 잰 값이라 Cloud Run(linux/x86_64)과 다를 수 있다 — 방향은 믿고 숫자는 배포 환경에서 다시 잰다.

| | 기동(/health 200) | Spring "Started" | RSS 기동 직후 | RSS 요청 뒤 | 첫 로그인 |
|---|---|---|---|---|---|
| JVM 그대로 (docker, CPU 1) | 약 7.7s | 약 6.7s | — | — | 320~380ms |
| JVM 다듬기 (AOT + CDS + SerialGC 등) | 약 3.3s | 약 2.7s | 224MB | 234MB | 237ms |
| **네이티브 — 스타터 `apps/api`** (실측 스파이크) | **약 0.6s** | **약 0.36s** | **206MB** | **208MB** | **120ms** |
| **네이티브 — `tools/native-check`** (결제 · 구독 · 작업 큐 포함, 새 DB 에 마이그레이션까지) | **약 0.85s** | **약 0.6s** | **216MB** | 핵심 흐름 전체를 돌린 뒤 250MB | — |

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
| `payment-toss` · `payment-stripe` · `auth-social-google/kakao/naver` · `crypto` | 외부 응답 · 웹훅 본문 · 토큰 본문 DTO (`BindingHints`) | 컨트롤러 밖에서 JSON 으로 바꾸는 타입 |

**새 코드를 쓸 때의 규칙** (`CLAUDE.md` 에도 한 줄):

1. **컨트롤러 밖에서 JSON 으로 바꾸는 타입은 등록 표시를 한다** — 작업 큐 일감 본문, 웹훅 본문, JSON 컬럼에 저장하는 값, 이벤트 페이로드. 컨트롤러의 `@RequestBody` · 반환 타입은 AOT 가 알아서 찾는다.
   그 밖의 타입은 쓰는 빈에 `@RegisterReflectionForBinding(Type::class)` 을 단다. (직접 확인: 표시가 없으면 네이티브에서 `KotlinReflectionInternalError: Unresolved class` 로 500 이다.)
2. **반환 타입을 구체적으로** 적는다. `ResponseEntity<Any>` · `Map<String, Any>` 에 담긴 타입은 AOT 가 못 찾는다 (직접 확인: 깨우기 주소의 응답이 이 때문에 500 이었다).
3. **이름으로 만드는 클래스**(Hibernate Validator 의 `ConstraintValidator`, `ServiceLoader`, `Class.forName`)는 `RuntimeHintsRegistrar` 로 직접 등록한다. 빈 정의에 **인스턴스 공급자(람다)를 달지 않는다** — AOT 가 못 굽는다 (`payment-jdbc` 의 작업 처리기 등록이 이래서 생성자 인자 참조로 바뀌었다).
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
  기동 · 마이그레이션 → 가입(메일은 기록) · 이메일 확인 · 로그인 · 토큰 갱신(재사용 거절) → 가짜 결제사로 단건 결제 · 구독 시작 → 작업 큐 일감 하나(깨우기 주소로 처리) → 웹훅 하나(재전송 중복 · 틀린 비밀 조각 404) → OpenAPI 문서.
- **새 프로젝트**: `tools/` 와 `native-smoke.sh` 는 복사되지 않는다. `scripts/native-test.sh` 는 남아 **`apps/api`(내 앱)** 를 대상으로 돈다 — 기본은 최소 확인(/health · OpenAPI 문서 200).
  네이티브로 배포하기로 하면 **내 핵심 흐름(가입 · 결제 …)을 `scripts/<내 시험>.sh` 로 쓰고 `scripts/native-test.sh --smoke scripts/<내 시험>.sh`** 로 붙인다 (`--url <주소>` 를 받고 0 = 통과; 스켈레톤 레포의 `scripts/native-smoke.sh` 가 틀: ok · bad · expect · call 함수).
  앱에 필요한 설정(비밀 · 주소)은 `NATIVE_TEST_ENV="KEY=VALUE …"` 로 넘긴다. 워크플로(`.github/workflows/native.yml`)는 손으로 도는 것만 남아 있다 — 파일 안 주석대로 릴리스 태그 · 의존성 파일 PR 트리거를 켠다.

**언제 도는가** (스켈레톤의 `.github/workflows/native.yml`): 릴리스 태그(`v*`)를 밀 때, 의존성 파일(빌드 파일 · `gradle/` · lock) 또는 네이티브 힌트(`aot.factories`)가 바뀐 PR, 손으로. **평소 CI 는 그대로**다 (매번 몇 분이 더 든다).
시험이 깨지면 위 규칙의 어느 것을 빠뜨렸는지부터 본다 — 컨테이너 로그의 `Unresolved class` · `MissingReflectionRegistrationError` · `... missing from the class path` 가 거의 전부다.

## 네이티브에서 확인한 것과 못 한 것

확인(시험용 조립): 기동 · Flyway(새 DB 에 마이그레이션 적용) · 가입 / 로그인 / 갱신 · 단건 결제 · 구독 · 작업 큐 · 웹훅 · Jackson · 검증 · OpenAPI.
**못 본 것**: 위 표의 `payment-toss` · `payment-stripe` · 소셜(google/kakao/naver) · `crypto` 힌트는 이름을 확인하고 넣었지만 **네이티브로 돌려 보지 못했다**(`crypto` 는 시험용 조립이 얹지만 토큰 경로를 타지 않는다). `payment-paddle` · `auth-social-oidc/x` · `notification-sse` 이벤트는 확인하지 않았다(Map/String 응답이면 필요 없을 수 있다). 그 밖에 소셜 로그인 · 메일 발송(`notification-mail`) · S3(`storage-s3`) · 이메일 변경 · 비밀번호 재설정 · 샘플 앱 전체 · 부하 · amd64. 이런 모듈을 쓰는 앱은 처음 네이티브로 빌드할 때 같은 종류의 규칙 위반이 몇 개 더 나올 수 있다.
Flyway 는 기동 때 `Unable to scan location: /db/migration/postgresql (unsupported protocol: resource)` 경고를 여러 번 찍지만 마이그레이션은 적용된다.

## 무거운 라이브러리를 쓰는 앱을 위한 안내

JS 압축기(Closure Compiler) · 이미지 처리(`ImageIO` · AWT) · jOOQ 같은 라이브러리는 리플렉션 · 리소스 · 네이티브 라이브러리를 많이 쓴다. 네이티브가 맞는지 정하는 순서:

1. 이 레포의 모듈(`persistence-jooq` · `storage-s3` …)을 얹은 앱은 **모듈 단위로** 네이티브 시험을 먼저 돌린다 (스켈레톤 레포에서는 `tools/native-check` 에 모듈 한 줄을 얹어, 새 프로젝트에서는 앱에 얹어 `scripts/native-test.sh`). 안 되면 아래 둘 중 하나.
2. **JVM 으로 간다** — [Cloud Run 0대 운영](cloud-run-scale-to-zero.md)의 "JVM 다듬기"(AOT + CDS)가 콜드스타트를 절반 이하로 줄인다. 무거운 라이브러리가 핵심이면 이쪽이 안전하다.
3. 그 라이브러리를 **별도 서비스**로 떼어 낸다 (예: 이미지 처리만 JVM 서비스).
4. 정말 네이티브가 필요하면 라이브러리별로 `RuntimeHintsRegistrar` 를 쓰고(위 규칙), GraalVM 추적 에이전트(`-agentlib:native-image-agent`)로 JVM 에서 시험을 돌려 목록을 뽑아 **필요한 것만** 추려 모듈에 둔다 (결과를 통째로 붙이지 않는다).
