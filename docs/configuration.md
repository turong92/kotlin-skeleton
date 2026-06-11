# Configuration

## Profiles

The skeleton standard profiles are:

- `local`: developer machine defaults. Starts from a fresh clone when SSM is disabled.
- `dev`: shared development settings. Can run locally when required values are supplied.
- `staging`: prod-like validation. Missing secrets fail startup.
- `prod`: strict production validation. Missing or dummy secrets fail startup.

Run local without SSM:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:api:bootRun
```

Run with `.env.local`:

```bash
cp .env.example .env.local
set -a
source .env.local
set +a
./gradlew :apps:api:bootRun
```

`.env.local` is ignored by git. Spring Boot does not load `.env.local` by file
name automatically; the shell, IDE, Docker Compose, or a script must inject it
as environment variables.

Run dev locally with explicit values:

```bash
SPRING_PROFILES_ACTIVE=dev \
JWT_SECRET=dev-test-secret-change-me-32-bytes \
SPRING_DATASOURCE_PASSWORD=dev-db-password \
./gradlew :apps:api:bootRun
```

Run prod locally for reproduction:

```bash
SPRING_PROFILES_ACTIVE=prod \
JWT_SECRET=prod-secret-from-secure-source \
SPRING_DATASOURCE_URL=jdbc:mysql://prod-db:3306/app?connectionTimeZone=UTC\&forceConnectionTimeZoneToSession=true \
SPRING_DATASOURCE_USERNAME=app \
SPRING_DATASOURCE_PASSWORD=prod-password-from-secure-source \
./gradlew :apps:api:bootRun
```

Real production values should come from the runtime environment or an external
secret provider, not from committed files.

## Required Values

The required config contract is stored in:

- `docs/config/required-config.yml`
- `docs/config/ssm-parameters.yml`

If startup fails, the error should say:

- which Spring property is missing,
- which environment variable can provide it,
- which SSM path can provide it when SSM is enabled.

## Private Files

Private config file names are ignored:

- `application-local.private.yml`
- `application-*.private.yml`
- `application-*.secret.yml`

Use private files only when your IDE or deployment flow explicitly adds them
through `spring.config.additional-location`.
