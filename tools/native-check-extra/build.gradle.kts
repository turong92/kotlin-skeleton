// 네이티브 이미지 시험용 두 번째 조립 (docs/native-image.md) — 배포 대상이 아니다. native-check 에 없는 모듈을 모듈 단위로 확인한다:
// 이미지 처리(storage-image + ImageIO/TwelveMonkeys WebP·CMYK JPEG + 앱 안 ffmpeg) · point · 웹 보상형 광고 · AdMob 보상 콜백(SSV).
// 가입 · 로그인에 필요한 스타터 줄기만 얹었다(native-check 보다 작아 빌드 메모리가 덜 든다). scripts/native-smoke-extra.sh 가 확인한다. 새 프로젝트에는 복사되지 않는다.
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":modules:platform"))
    implementation(project(":modules:auth"))
    implementation(project(":modules:account-jdbc"))
    implementation(project(":modules:auth-session-jdbc"))
    implementation(project(":modules:legal-jdbc"))
    implementation(project(":modules:persistence-jdbc"))
    implementation(project(":modules:db-postgresql"))
    implementation(project(":modules:migration-flyway"))
    implementation(project(":modules:time"))
    implementation(project(":modules:job-queue-jdbc"))
    implementation(project(":modules:notification-mail"))
    implementation(project(":modules:storage-s3"))
    implementation(project(":modules:storage-image"))
    implementation(project(":modules:storage-image-imageio"))
    implementation(project(":modules:storage-image-jdbc"))
    implementation(project(":modules:storage-image-ffmpeg"))
    implementation(project(":modules:point"))
    implementation(project(":modules:point-jdbc"))
    implementation(project(":modules:ads-web-rewarded"))
    implementation(project(":modules:ads-web-rewarded-jdbc"))
    implementation(project(":modules:ads-admob"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    compileOnly("org.crac:crac")   // CRaC 시험(-Pcrac) 때만 실행 클래스패스에 오른다 — CracPool.kt
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
}
