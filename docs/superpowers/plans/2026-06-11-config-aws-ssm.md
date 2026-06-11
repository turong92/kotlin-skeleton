# Config AWS SSM Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional `modules:config-aws-ssm` support that loads AWS Systems Manager Parameter Store paths into Spring before normal bean binding and config validation.

**Architecture:** Register an `EnvironmentPostProcessor` through `META-INF/spring.factories` so SSM values are available before the application context refreshes. Keep AWS SDK usage behind a small `SsmParameterClient` interface so unit tests use a fake client. Add SSM below OS env/system properties but above committed `application*.yml`, so env still wins while SSM can override skeleton defaults.

**Tech Stack:** Spring Boot `EnvironmentPostProcessor`, AWS SDK for Java v2 SSM client, Kotlin, Gradle, JUnit.

---

## File Structure

- Modify `settings.gradle.kts`: include `:modules:config-aws-ssm`.
- Create `modules/config-aws-ssm/build.gradle.kts`: AWS SDK BOM and SSM dependencies.
- Create `modules/config-aws-ssm/src/main/resources/META-INF/spring.factories`: register the post processor.
- Create `modules/config-aws-ssm/src/main/kotlin/dev/sumin/skeleton/config/aws/ssm/AwsSsmProperties.kt`: bind `skeleton.config.aws.ssm`.
- Create `modules/config-aws-ssm/src/main/kotlin/dev/sumin/skeleton/config/aws/ssm/SsmParameterClient.kt`: small parameter client abstraction.
- Create `modules/config-aws-ssm/src/main/kotlin/dev/sumin/skeleton/config/aws/ssm/AwsSdkSsmParameterClient.kt`: AWS SDK adapter.
- Create `modules/config-aws-ssm/src/main/kotlin/dev/sumin/skeleton/config/aws/ssm/AwsSsmEnvironmentPostProcessor.kt`: load and insert property source.
- Create `modules/config-aws-ssm/src/test/kotlin/dev/sumin/skeleton/config/aws/ssm/AwsSsmEnvironmentPostProcessorTest.kt`: fake-client tests.
- Create `apps/api/src/test/kotlin/dev/sumin/skeleton/api/AwsSsmProfileConfigurationIntegrationTest.kt`: application-level proof with fake post processor call plus validator.

---

## Tasks

1. Register module skeleton and verify Gradle recognizes it.
2. Write failing post-processor tests for disabled mode, path merge order, env override precedence, and fail-fast behavior.
3. Implement SSM properties, fakeable client abstraction, AWS SDK adapter, and post processor.
4. Add app-level proof that SSM values can satisfy required config before validation runs.
5. Run focused verification and commit.

## Verification Commands

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:config-aws-ssm:test
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :apps:api:test --tests '*AwsSsmProfileConfigurationIntegrationTest'
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:config-aws-ssm:test :apps:api:test --tests '*AwsSsm*'
```

## Self-Review

- Spec coverage: Loads SSM paths, supports profile path placeholders, local override order, disabled no-op, fail-fast versus warn-only, and AWS credential guidance through failure messages.
- Intentional gap: This does not create AWS accounts, SSO profiles, IAM roles, or real SSM parameters.
- Placeholder scan: No placeholder implementation tokens are intended.
