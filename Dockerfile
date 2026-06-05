# 빌드 스테이지
FROM gradle:8.11-jdk21-alpine AS builder
WORKDIR /app
COPY . .
RUN ./gradlew :apps:api:bootJar --no-daemon

# 런타임 스테이지
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=builder /app/apps/api/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
