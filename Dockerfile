# 배포 이미지 — 홈서버 배포 계약(docs/deploy.md)을 지킨다:
#   · 0.0.0.0:8080 에서 듣는다 (server.address 를 정하지 않는다)   · 로그는 stdout 으로만
#   · 컨테이너 안에서 `wget` 이 돈다 (플랫폼 헬스체크가 `wget -S -q -O /dev/null http://localhost:<port><health>` 를 실행한다 — busybox 가 들어 있는 alpine 이면 된다.
#     slim · distroless 로 바꾸면 wget 이 사라져 건강한 앱이 영원히 unhealthy 가 된다. scripts/test-deploy-contract.sh 가 이 줄을 지킨다)
#   · 아무 환경변수 없이도 이미지는 같다 — DB · Redis · 비밀은 전부 실행 때 환경변수로 들어온다
#
#   docker build -t <image>:<tag> .                  # apps/api (스타터)
#   docker build --build-arg APP=sample -t ... .     # apps/sample (new-project.sh --with-sample) · APP=workbench 는 데모라 배포 대상이 아니다

# 빌드 스테이지 — wrapper 가 gradle-wrapper.properties 의 Gradle 을 받아 쓴다
FROM eclipse-temurin:21-jdk-alpine AS builder
ARG APP=api
WORKDIR /app
COPY . .
# 워크벤치는 모든 모듈(AWS SSM · S3 · Kafka …)을 얹은 데모다 — 쓰지 않는 모듈의 설정을 요구하므로 배포 이미지로 만들지 않는다
RUN if [ "$APP" = workbench ]; then echo "apps/workbench is a demo of every module and is not deployable (docs/deploy.md) - build APP=api or APP=sample" >&2; exit 1; fi
RUN ./gradlew :apps:${APP}:bootJar --no-daemon

# 런타임 스테이지
FROM eclipse-temurin:21-jre-alpine
ARG APP=api
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=builder /app/apps/${APP}/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
