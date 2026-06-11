# AWS SSM Configuration

SSM is optional. Projects that do not use SSM can skip the
`modules:config-aws-ssm` dependency and use profile YAML plus environment
variables.

Applications that use SSM add:

```kotlin
implementation(project(":modules:config-aws-ssm"))
```

When the SSM module is on the classpath, it loads automatically for:

- `local` with `AWS_PROFILE` or `skeleton.config.aws.ssm.credential-profile`
- `dev`, `staging`, and `prod`

`skeleton.config.aws.ssm.enabled=false` is only an explicit escape hatch for an
application that includes the module but intentionally does not want SSM.

## Account Management

The application never stores AWS access keys.

Each runtime supplies credentials through the AWS SDK default credential chain:

- Local developer: AWS CLI SSO profile and `AWS_PROFILE`.
- Local prod reproduction: read-only prod SSO profile.
- Dev/staging/prod servers: IAM role, ECS task role, EKS IRSA, or equivalent
  temporary credentials.
- CI: OIDC assume-role or CI-managed temporary credentials.

Expected local profile names:

```text
skeleton-dev
skeleton-staging-readonly
skeleton-prod-readonly
```

Local dev SSM run:

```bash
aws sso login --profile skeleton-dev
AWS_PROFILE=skeleton-dev \
SPRING_PROFILES_ACTIVE=local \
./gradlew :apps:api:bootRun
```

Local dev-profile reproduction:

```bash
aws sso login --profile skeleton-dev
AWS_PROFILE=skeleton-dev \
SPRING_PROFILES_ACTIVE=dev \
./gradlew :apps:api:bootRun
```

Local prod reproduction:

```bash
aws sso login --profile skeleton-prod-readonly
AWS_PROFILE=skeleton-prod-readonly \
SPRING_PROFILES_ACTIVE=prod \
./gradlew :apps:api:bootRun
```

When SSM is enabled and credentials are missing, startup should fail with an
actionable message. That is expected behavior.

Dev/staging/prod servers normally do not set `AWS_PROFILE`. Instead, they run
with an IAM role, ECS task role, EKS IRSA role, or equivalent temporary
credential provider. The AWS SDK default credential chain discovers those
credentials automatically.

## Permissions

Runtime roles should use least privilege:

- `ssm:GetParameter`
- `ssm:GetParameters`
- `ssm:GetParametersByPath`
- `kms:Decrypt` for the KMS keys used by that environment

Long-lived AWS access keys are not the normal runtime path.
