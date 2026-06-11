# Config Profile Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first configuration foundation so `local`, `dev`, `staging`, and `prod` profiles have committed skeleton files, ignored private overrides, documented usage, and startup validation that reports missing values.

**Architecture:** Keep normal profile loading on Spring Boot standard config files and environment variables. Add a small platform-level required-config validator that reads configured requirements and fails startup with actionable messages. Keep real AWS SSM loading out of this phase; document SSM account management and leave the optional `config-aws-ssm` module for the next plan.

**Tech Stack:** Spring Boot configuration properties, Kotlin, `ApplicationRunner`, Gradle/JUnit tests.

---

## File Structure

- Modify `apps/api/src/main/resources/application.yml`: shared safe defaults and required-config manifest entries.
- Create `apps/api/src/main/resources/application-local.yml`: local defaults that can run without SSM.
- Create `apps/api/src/main/resources/application-dev.yml`: dev defaults with required secrets supplied by env/private/SSM.
- Create `apps/api/src/main/resources/application-staging.yml`: prod-like staging defaults.
- Create `apps/api/src/main/resources/application-prod.yml`: strict prod defaults.
- Modify `.gitignore`: ignore local env/private/secret files while keeping `.env.example`.
- Create `.env.example`: committed env key contract.
- Create `docs/configuration.md`: profile execution guide.
- Create `docs/config/aws-ssm.md`: AWS SSO/profile/role account management guide.
- Create `docs/config/required-config.yml`: human and agent manifest for required values.
- Create `docs/config/ssm-parameters.yml`: SSM path manifest without values.
- Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/config/ConfigValidationProperties.kt`: bind required-config validation settings.
- Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/config/RequiredConfigValidator.kt`: validate active profile values and build missing-value messages.
- Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/config/ConfigValidationAutoConfiguration.kt`: run validation on startup.
- Modify `modules/platform/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: register config validation auto-config.
- Create `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/config/RequiredConfigValidatorTest.kt`: unit tests for local/dev/prod behavior.
- Create `apps/api/src/test/kotlin/dev/sumin/skeleton/api/ProfileConfigurationIntegrationTest.kt`: app-level proof for profile-specific variables.

---

## Task 1: Profile Files And Documentation

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Create: `apps/api/src/main/resources/application-local.yml`
- Create: `apps/api/src/main/resources/application-dev.yml`
- Create: `apps/api/src/main/resources/application-staging.yml`
- Create: `apps/api/src/main/resources/application-prod.yml`
- Modify: `.gitignore`
- Create: `.env.example`
- Create: `docs/configuration.md`
- Create: `docs/config/aws-ssm.md`
- Create: `docs/config/required-config.yml`
- Create: `docs/config/ssm-parameters.yml`

- [ ] **Step 1: Add profile files and docs**

Add the exact files listed above. Keep real secrets out. Use `${ENV:}` placeholders for sensitive values.

- [ ] **Step 2: Verify resources process**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :apps:api:processResources
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add .gitignore .env.example apps/api/src/main/resources docs/configuration.md docs/config
git commit -m "docs: add profile configuration skeleton"
```

---

## Task 2: Required Config Validator

**Files:**
- Create: `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/config/RequiredConfigValidatorTest.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/config/ConfigValidationProperties.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/config/RequiredConfigValidator.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/config/ConfigValidationAutoConfiguration.kt`
- Modify: `modules/platform/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: Write failing validator tests**

Test behaviors:

```kotlin
@Test
fun `local ignores dev prod requirements`() {
    RequiredConfigValidator.validate(
        activeProfiles = setOf("local"),
        properties = ConfigValidationProperties(
            requirements = listOf(
                ConfigValidationProperties.Requirement(
                    property = "skeleton.auth.jwt.secret",
                    env = "JWT_SECRET",
                    ssm = "/kotlin-skeleton/{profile}/skeleton.auth.jwt.secret",
                    profiles = setOf("dev", "staging", "prod"),
                    secret = true,
                ),
            ),
        ),
        propertyResolver = MapPropertyResolver(emptyMap()),
    )
}

@Test
fun `dev reports missing required value`() {
    val error = assertFailsWith<ConfigValidationException> {
        RequiredConfigValidator.validate(
            activeProfiles = setOf("dev"),
            properties = ConfigValidationProperties(
                requirements = listOf(
                    ConfigValidationProperties.Requirement(
                        property = "skeleton.auth.jwt.secret",
                        env = "JWT_SECRET",
                        ssm = "/kotlin-skeleton/{profile}/skeleton.auth.jwt.secret",
                        profiles = setOf("dev", "staging", "prod"),
                        secret = true,
                    ),
                ),
            ),
            propertyResolver = MapPropertyResolver(emptyMap()),
        )
    }

    assertTrue(error.message.orEmpty().contains("skeleton.auth.jwt.secret"))
    assertTrue(error.message.orEmpty().contains("JWT_SECRET"))
    assertTrue(error.message.orEmpty().contains("/kotlin-skeleton/dev/skeleton.auth.jwt.secret"))
}
```

- [ ] **Step 2: Run failing tests**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:platform:test --tests '*RequiredConfigValidatorTest'
```

Expected: FAIL because validator classes do not exist.

- [ ] **Step 3: Implement validator**

Create `ConfigValidationProperties`, `ConfigValidationException`, `PropertyResolver`, and `RequiredConfigValidator`. Treat a requirement as active when its `profiles` is empty or intersects active profiles. Treat blank values as missing. Reject dummy markers for secret requirements unless the active profile is in `allowDummyProfiles`.

- [ ] **Step 4: Register auto-configuration**

Create `ConfigValidationAutoConfiguration` with `@EnableConfigurationProperties(ConfigValidationProperties::class)` and an `ApplicationRunner` that passes Spring `Environment` values into the validator.

- [ ] **Step 5: Run validator tests**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:platform:test --tests '*RequiredConfigValidatorTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/platform/src/main/kotlin/dev/sumin/skeleton/common/config modules/platform/src/test/kotlin/dev/sumin/skeleton/common/config modules/platform/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
git commit -m "feat: validate required configuration"
```

---

## Task 3: Profile Variable Tests

**Files:**
- Create: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/ProfileConfigurationIntegrationTest.kt`

- [ ] **Step 1: Write failing app-level profile tests**

Test with `ApplicationContextRunner` and platform auto-configuration:

```kotlin
@Test
fun `dev starts when required variables are supplied`() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=dev",
            "skeleton.config.validation.requirements[0].property=skeleton.auth.jwt.secret",
            "skeleton.config.validation.requirements[0].env=JWT_SECRET",
            "skeleton.config.validation.requirements[0].ssm=/kotlin-skeleton/{profile}/skeleton.auth.jwt.secret",
            "skeleton.config.validation.requirements[0].profiles[0]=dev",
            "skeleton.config.validation.requirements[0].secret=true",
            "skeleton.auth.jwt.secret=dev-test-secret-32-bytes-change",
        )
        .run { context -> assertThat(context).hasNotFailed() }
}
```

- [ ] **Step 2: Run failing or red test**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :apps:api:test --tests '*ProfileConfigurationIntegrationTest'
```

Expected: FAIL until validator auto-configuration is available to the app test classpath.

- [ ] **Step 3: Implement complete tests**

Cover:

- `local` passes with SSM disabled and no required secrets.
- `dev` passes when required variables are supplied.
- `prod` fails on dummy secret.
- Missing required values show property/env/SSM path.

- [ ] **Step 4: Run profile tests**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :apps:api:test --tests '*ProfileConfigurationIntegrationTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/test/kotlin/dev/sumin/skeleton/api/ProfileConfigurationIntegrationTest.kt
git commit -m "test: cover profile configuration validation"
```

---

## Task 4: Final Verification

**Files:**
- Modify if needed: `docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md`

- [ ] **Step 1: Run focused verification**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:platform:test --tests '*RequiredConfigValidatorTest' :apps:api:test --tests '*ProfileConfigurationIntegrationTest'
```

Expected: PASS.

- [ ] **Step 2: Run resource verification**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :apps:api:processResources
```

Expected: PASS.

- [ ] **Step 3: Commit any roadmap updates**

If the roadmap changed:

```bash
git add docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md
git commit -m "docs: mark config profile foundation progress"
```

---

## Self-Review

- Spec coverage: Covers profile skeletons, ignored secret files, env-first flow, required config manifest, actionable startup validation, and SSM account management documentation.
- Intentional gap: Real AWS SSM property-source loading remains for the next `modules:config-aws-ssm` implementation plan.
- Placeholder scan: No placeholder tokens are intended in this plan.
- Type consistency: `ConfigValidationProperties`, `RequiredConfigValidator`, `ConfigValidationException`, and `ProfileConfigurationIntegrationTest` are used consistently.
