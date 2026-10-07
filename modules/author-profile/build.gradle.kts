dependencies {
    api(project(":modules:platform"))   // 타입(AuthorDirectory · AuthorIdentities · AuthorAdoptionListener · ApplicationException)이 이 모듈의 공개 API 에 나온다

    // account 위에 얹힌 선택 모듈이다 (auth-magic-link 와 같은 방식) — 닉네임 규칙 · 꼬리표 · 한도 · 계정 저장소 타입이 공개 생성자에 나온다
    api(project(":modules:account"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")   // HttpStatus (오류 코드) · 뒤따를 HTTP 조각
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
