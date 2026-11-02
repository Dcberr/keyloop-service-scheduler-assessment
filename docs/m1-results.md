# M1 Results and Environment Verification

**Date:** 2026-11-02
**Status:** Completed.
**Scope:** Project foundation only; M2 schema and booking logic are not implemented.

## Contributions

- Lead: Compose PostgreSQL 18.1 with localhost port 5433 and persistent volume, environment example, ignore rules, local run instructions and integrated verification.
- Backend Engineer using scheduler-backend-engineer: Java 21 / Spring Boot 4.1.2 project, standard Maven 3.9.11 wrapper, MVC/validation/JDBC/Flyway/PostgreSQL/Actuator dependencies, UTC replaceable Clock and external database configuration.
- QA Engineer using scheduler-qa-engineer: two Clock unit tests and one real PostgreSQL Testcontainers integration test, without skipping for unavailable Docker.
- Reviewer using scheduler-reviewer: independent read-only configuration review. Found JDBC port mismatch (5432 vs Compose 5433); Backend corrected it. README bridges POSTGRES_* settings to DB_* application settings.

## Environment

Host: macOS arm64. JDK 21.0.8 explicitly selected because system Maven otherwise uses Java 25. Docker server 28.4.0 runs in Colima; Colima was started with approval. Dependencies and wrapper downloads required approved network access. Test database containers are isolated and managed by Testcontainers.

## Verification command

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
DOCKER_HOST=unix:///Users/congnguyenngocchien/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./mvnw -Dmaven.repo.local=/tmp/keyloop-maven-repository verify
```

The temporary Maven repository is an environment-specific verification choice, not a requirement for users. Normal local commands are documented in README. The integration test starts an actual HTTP server and asserts database connectivity, PostgreSQL 18, enabled Flyway with no business migrations and UTC Clock. Unit tests prove the default Clock and test override.

Compose configuration was validated with a synthetic POSTGRES_PASSWORD. The persistent Compose database was not started; real database/application startup is verified through Testcontainers. Health probe configuration is present, but database outage/recovery and persistence demonstrations remain later milestones.

## Boundaries

No business tables, seed data, appointment endpoints, resource allocation or concurrency correctness are claimed. M2 has not started. All M1 results below must be read as infrastructure checks, not proof of the assessment's business behavior.

## Final results

- Integrated Maven `verify`: BUILD SUCCESS on 2026-11-02.
- ClockConfigurationTest: 2 tests, 0 failures, 0 errors, 0 skipped.
- PostgresConnectionIT: 1 test, 0 failures, 0 errors, 0 skipped.
- Packaged executable JAR produced under target/.
- Test reports: target/surefire-reports and target/failsafe-reports (generated, ignored by Git).
- Expected Flyway warning: no business migrations yet, intentionally deferred to M2. Mockito dynamic-agent warnings did not fail the build.
- First image pull caused startup delay and temporary Ryuk connection warnings; resource reaper subsequently connected and the test completed successfully.

M1 acceptance gates are met. No M2 work was started. Colima remains running for subsequent local development; test containers are managed by Testcontainers.
