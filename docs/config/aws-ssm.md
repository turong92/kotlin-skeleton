# AWS SSM Configuration

SSM is optional. Projects that do not use SSM can use profile YAML plus
environment variables.

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
SPRING_PROFILES_ACTIVE=dev \
SKELETON_CONFIG_AWS_SSM_ENABLED=true \
./gradlew :apps:api:bootRun
```

Local prod reproduction:

```bash
aws sso login --profile skeleton-prod-readonly
AWS_PROFILE=skeleton-prod-readonly \
SPRING_PROFILES_ACTIVE=prod \
SKELETON_CONFIG_AWS_SSM_ENABLED=true \
./gradlew :apps:api:bootRun
```

When SSM is enabled and credentials are missing, startup should fail with an
actionable message. That is expected behavior.

## Permissions

Runtime roles should use least privilege:

- `ssm:GetParameter`
- `ssm:GetParameters`
- `ssm:GetParametersByPath`
- `kms:Decrypt` for the KMS keys used by that environment

Long-lived AWS access keys are not the normal runtime path.
