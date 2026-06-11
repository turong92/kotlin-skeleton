# Config Profile And Secrets Design

## Goal

Make the skeleton runnable by choosing an environment profile while keeping real
secrets out of git.

The developer experience should be predictable:

- `local` can start from a fresh clone when external secret providers are not
  enabled.
- `local` with SSM enabled must fail if AWS credentials are missing.
- `dev`, `staging`, and `prod` can also be run locally for testing, but only
  when required values are provided by env, private files, or an external
  provider.
- Startup failures must say which property is missing and where the operator can
  provide it.

## Non-goals

- Do not store AWS access keys, SSO tokens, database passwords, OAuth secrets, or
  webhook URLs in committed config files.
- Do not make SSM mandatory for every project.
- Do not invent a custom AWS credential system.
- Do not hide missing prod configuration behind dummy values.

## Profile Model

Committed profile files:

- `application.yml`: shared safe defaults.
- `application-local.yml`: local developer defaults.
- `application-dev.yml`: shared development environment defaults.
- `application-staging.yml`: prod-like staging defaults.
- `application-prod.yml`: strict production defaults.

Expected behavior:

| Profile | Fresh clone startup | External secrets | Missing secret behavior |
| --- | --- | --- | --- |
| `local` | Must start when SSM is disabled. | Optional. | Dummy values allowed for local-only defaults. |
| `local` + SSM enabled | Requires AWS credentials. | SSM or env/private overrides. | Fail with credential/key guidance. |
| `dev` | Can be run locally for testing. | Env, private file, or SSM. | Missing required values fail or warn by policy. |
| `staging` | Can be run locally for testing. | Env, private file, or SSM. | Fail. Dummy values rejected. |
| `prod` | Can be run locally for testing. | Env, private file, or SSM. | Fail. Dummy values rejected. |

`staging` is included from the start because existing auth validation already
treats `staging` as a protected profile.

## Property Precedence

Use Spring Boot externalized configuration instead of custom file loading for
normal values. Spring Boot supports config files, environment variables, system
properties, and command line arguments, with later sources overriding earlier
ones. Its default config file search is based on `application.*` and
`application-{profile}.*` files. Reference:
[Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html).

Standard local order:

1. Committed `application.yml`.
2. Committed profile file, such as `application-local.yml`.
3. Optional SSM property source when the SSM module is enabled.
4. Ignored private config file if explicitly supplied.
5. OS environment variables.
6. Command line arguments.

Environment variables and command line arguments intentionally remain the final
override layer.

## Committed Versus Ignored Files

Commit structure and contracts:

- `application*.yml` profile skeletons.
- `.env.example`.
- `docs/configuration.md`.
- `docs/config/aws-ssm.md`.
- `docs/config/required-config.yml`.
- `docs/config/ssm-parameters.yml`.

Ignore real values:

- `.env`
- `.env.*`
- `!.env.example`
- `application-*.private.yml`
- `application-*.secret.yml`
- SSM export or dump files.

`.env.local` is a standard developer convention, but Spring Boot does not load
that filename automatically. The skeleton should document it as an environment
injection file used by shell, IDE, Docker Compose, or scripts.

Example:

```bash
set -a
source .env.local
set +a
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:api:bootRun
```

## Required Config Manifest

The repo should include a machine-readable manifest describing required config
without containing values.

Example:

```yaml
required:
  - property: skeleton.auth.jwt.secret
    env: JWT_SECRET
    ssm: /kotlin-skeleton/{profile}/skeleton.auth.jwt.secret
    profiles: [dev, staging, prod]
    allow-dummy: [local]
    secret: true
    description: JWT signing secret.

  - property: spring.datasource.password
    env: SPRING_DATASOURCE_PASSWORD
    ssm: /kotlin-skeleton/{profile}/spring.datasource.password
    profiles: [dev, staging, prod]
    secret: true
    description: Database password.
```

This manifest serves two purposes:

- Humans and agents know which values must exist.
- Startup validation can report missing values in a predictable format.

## Startup Validation

Add a config validation layer that checks:

- Required properties for the active profile are present.
- Dummy secrets are not used in `staging` or `prod`.
- SSM is enabled only when credentials can be resolved.
- SSM required paths and keys exist when fail-fast is enabled.

Failure output should be actionable:

```text
Configuration startup failed.

Missing required config:
- skeleton.auth.jwt.secret
  provide one of:
    env JWT_SECRET
    ssm /kotlin-skeleton/dev/skeleton.auth.jwt.secret

AWS SSM is enabled but credentials were not found.
Try:
  aws sso login --profile skeleton-dev
  AWS_PROFILE=skeleton-dev SPRING_PROFILES_ACTIVE=dev ./gradlew :apps:api:bootRun
```

## SSM Module

SSM support remains optional in `modules:config-aws-ssm`.

Suggested namespace:

```yaml
skeleton:
  config:
    aws:
      ssm:
        enabled: false
        region: ap-northeast-2
        credential-profile: ${AWS_PROFILE:}
        fail-fast: true
        paths:
          - /kotlin-skeleton/{profile}/common/
          - /kotlin-skeleton/{profile}/api/
```

Behavior:

- Disabled mode does nothing.
- Enabled mode loads configured paths into Spring property sources.
- Later paths override earlier paths.
- Missing credentials fail when `fail-fast=true`.
- Missing paths or keys fail when the required config manifest marks them as
  required for the active profile.
- Local can use dev paths if configured, plus optional local override paths.

## SSM Account Management

The application does not manage AWS login secrets.

Instead, each runtime provides AWS credentials through standard AWS mechanisms:

- Local developer: AWS CLI SSO profile plus `AWS_PROFILE`.
- Local prod reproduction: read-only production SSO profile.
- Dev/staging/prod servers: IAM role, ECS task role, EKS IRSA role, or equivalent
  temporary credential provider.
- CI: OIDC assume-role or CI-managed temporary credentials.

The AWS SDK for Java 2.x default credentials provider chain is the baseline. It
resolves credentials from standard locations such as system properties,
environment variables, shared AWS profile files, and runtime roles. Reference:
[AWS SDK Java credentials chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html).

AWS SSO setup belongs in developer machine configuration, not in the repository.
The repo should document expected profile names:

```text
skeleton-dev
skeleton-staging-readonly
skeleton-prod-readonly
```

Example local dev SSM run:

```bash
aws sso login --profile skeleton-dev
AWS_PROFILE=skeleton-dev SPRING_PROFILES_ACTIVE=dev ./gradlew :apps:api:bootRun
```

Example local prod reproduction:

```bash
aws sso login --profile skeleton-prod-readonly
AWS_PROFILE=skeleton-prod-readonly SPRING_PROFILES_ACTIVE=prod ./gradlew :apps:api:bootRun
```

Server-side roles should follow least privilege:

- `ssm:GetParameter`
- `ssm:GetParameters`
- `ssm:GetParametersByPath`
- `kms:Decrypt` only for the SSM KMS keys used by that environment.

Long-lived access keys should not be used for normal app runtime.

## Test Strategy

Profile/config tests should cover:

- `local` starts with only committed defaults when SSM is disabled.
- `local` with SSM enabled fails without AWS credentials.
- `dev` can start locally when required values are supplied as test properties.
- `prod` fails when dummy secrets are active.
- Missing required values list the property name, env name, and SSM path.
- `.env.example` contains all env names referenced by the required config
  manifest.

SSM module tests should use a fake SSM client rather than real AWS.

## Rollout Order

1. Add profile skeleton files and `.env.example`.
2. Add docs for local/dev/prod execution.
3. Add required config manifest and validator.
4. Add tests for profile-specific variable injection and missing-value errors.
5. Add optional `modules:config-aws-ssm` with fake-client tests.
6. Add SSM documentation and path manifest.
