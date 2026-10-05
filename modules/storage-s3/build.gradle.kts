dependencies {
    // crypto 는 OPAQUE 공개 URL 에만 쓴다 — 컴파일에만 걸고 런타임 전이는 하지 않는다 (필요한 앱이 :modules:crypto 한 줄을 더한다)
    compileOnly(project(":modules:crypto"))
    api(project(":modules:storage"))   // 계약 타입이 이 모듈의 공개 API 에 나온다 — 의존성 한 줄로 충분하게
    implementation(platform("software.amazon.awssdk:bom:2.46.8"))

    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("software.amazon.awssdk:auth")
    implementation("software.amazon.awssdk:regions")
    implementation("software.amazon.awssdk:s3")

    testImplementation(project(":modules:crypto"))   // OPAQUE 테스트 · FilteredClassLoader 로 부재를 흉내 낸다
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// crypto 가 클래스패스에 정말 없을 때(앱이 :modules:crypto 를 안 더했을 때)를 증명하는 별도 묶음 — 일반 test 는 OPAQUE 시험 때문에 crypto 를 얹는다
testing {
    suites {
        register("noCryptoTest", JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())   // 소비자 시점: compileOnly 인 crypto 는 따라오지 않는다
                implementation("org.assertj:assertj-core")
                implementation("org.springframework.boot:spring-boot-test")
                implementation("org.springframework.boot:spring-boot-autoconfigure")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}
tasks.named("check") { dependsOn("noCryptoTest") }
