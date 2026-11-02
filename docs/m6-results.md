# M6 Results — Packaging, Observability, Persistence and Recovery

**Date:** 2026-11-03  
**Status:** P0 completed. P1 performance measurement deferred. M7 has not started.

## Role contributions

- Lead used scheduler-lead to assign ownership, implement Docker/Compose packaging and health probe, resolve integration issues, run clean verification and operational checks, and update the runbook/evidence.
- Backend Engineer used scheduler-backend-engineer to finish structured logs, outcome/acquisition metrics, safe diagnostics, connection deadlines and graceful-shutdown configuration.
- QA Engineer used scheduler-qa-engineer to independently implement ObservabilityIT, RequestIdMdcTest and the isolated operational verification script.
- Reviewer used scheduler-reviewer to inspect code, packaging, isolation/cleanup and the generated evidence read-only. QA and Reviewer independently confirmed final results; neither claimed to rerun the full application tests during report inspection.

## Deliverables

- Dockerfile with digest-pinned Eclipse Temurin 21 JRE, executable JAR, non-root UID 10001 and readiness healthcheck.
- .dockerignore restricts build context to the JAR and health script; secrets and test/source data are not copied into the runtime image.
- Compose app profile adds the packaged application to PostgreSQL 18.1, with localhost published ports, initial dependency health gating, persistent PostgreSQL volume and 20-second stop grace.
- scripts/docker/healthcheck.sh queries readiness using the base image's Bash, without extra packages.
- Structured Logstash JSON, correlation, success/rejected/error booking metrics, duration and lock-acquisition timers, Hikari metrics, sanitized application diagnostics and bounded database connection behavior.
- scripts/validation/verify_operations.py reproduces isolated restart/outage/recovery and observability checks.
- [Operations runbook](operations.md) and updated README.

No schema or business-contract change was required.

## Clean build and regression verification

The initial offline clean command could not resolve the not-yet-cached Maven clean plugin. It stopped before compilation/tests. The approved online command downloaded that plugin and completed a clean build. Cached dependencies were reused; this is not a claim of a cache-free machine installation.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
DOCKER_HOST=unix:///Users/congnguyenngocchien/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./mvnw -B -ntp -Dmaven.repo.local=/tmp/keyloop-maven-repository clean verify
```

**Result: BUILD SUCCESS.**

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| ClockConfigurationTest | 2 | 0 | 0 | 0 |
| BookingTimePolicyTest | 22 | 0 | 0 | 0 |
| RequestIdMdcTest | 2 | 0 | 0 | 0 |
| PostgresConnectionIT | 1 | 0 | 0 | 0 |
| SchemaConstraintsIT | 38 | 0 | 0 | 0 |
| AvailabilityRulesIT | 37 | 0 | 0 | 0 |
| BookingTransactionIT | 21 | 0 | 0 | 0 |
| HttpContractIT | 27 | 0 | 0 | 0 |
| ObservabilityIT | 3 | 0 | 0 | 0 |
| Total | 153 | 0 | 0 | 0 |

Totals: **26 unit tests and 127 integration tests**. New tests verify distinct outcomes, rejection at service validation, deferred-commit failure without false success, bounded metric tags, acquisition/pool meters and MDC cleanup on normal/exceptional paths.

Evidence: target/surefire-reports, target/failsafe-reports and /tmp/keyloop-m6-verify.log. The existing HTTP contract checker also passed all 74 operation responses plus two routing/method errors, preserving successful coverage of all eight operations.

## Packaged runtime evidence

Actual operational execution used:

```sh
DOCKER_HOST=unix:///Users/congnguyenngocchien/.colima/default/docker.sock \
python3 scripts/validation/verify_operations.py
```

Final status: **passed — all nine checks**.

| Runtime property | Observed value |
|---|---|
| Build JDK | Homebrew OpenJDK 21.0.8 |
| Container JRE | Eclipse Temurin 21.0.13+9 LTS |
| Runtime UID | 10001 |
| Docker server | 28.4.0 |
| PostgreSQL | 18.1 |
| Runtime base digest | sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269 |
| Built app image | sha256:2cd4fc4bc3257bf808fba5243ff56be54f06ae767d71a94f3ed5a3db8f3f5e32 |
| Isolated project | scheduler-m6-7bf23a077f5e |

These identifiers describe this verification run; the source remains reproducible through the committed Dockerfile and normal build commands. Images may remain as local cache; dedicated test containers and the test volume were removed.

### Nine actual checks

1. Fresh packaged application starts with successfully migrated real PostgreSQL.
2. Container runs as UID 10001; actual Java, Docker and image identities are recorded.
3. Dynamic HTTP demo commits exactly one appointment.
4. The same appointment and assignment survive application restart on the same database volume.
5. With PostgreSQL stopped, liveness remains UP/200, readiness is DOWN/503, and API reads and booking return controlled 503 errors.
6. After database start, the existing application recovers without restart; container ID, start timestamp and restart count remain unchanged. The original appointment survives and the failed outage POST created nothing.
7. An injected deferred commit failure returns 500 and leaves the original row count unchanged. After removing the test trigger, an explicit new booking succeeds; the success counter increases once, not for the failed attempt.
8. Outcome, duration, acquisition and pool metrics are exposed; custom metric labels are bounded and contain no entity IDs.
9. Parsed JSON events correlate HTTP requests, omit injected private SQL/customer/VIN/password markers, and contain no false success event.

The final report contains 24 operational HTTP samples plus 10 demo responses, 96 parsed JSON log records and five booking outcomes: two successes, one rejection and two errors. Failed outcomes contain no appointmentId. One graceful-shutdown completion was observed during the app restart. This demonstrates an idle graceful shutdown and restart, not a loaded in-flight drain test.

## Observability semantics

- Booking counters measure BookingService invocations. HTTP requests rejected by DTO/UUID validation before service entry are covered by HTTP request metrics/logs instead.
- Booking outcome logs/metrics follow transaction completion. Telemetry-backend exceptions cannot turn a committed booking into a reported booking failure.
- The acquisition timer measures the lock-acquisition SQL attempt, including network/query overhead; it does not isolate PostgreSQL's internal lock wait.
- Custom tags use bounded outcomes/reasons and vehicle/dealership resource values; IDs belong in correlated logs, not metric labels.
- Application failure events no longer log raw SQL exceptions or payloads. Framework health diagnostics may still report connection failures, while operational sensitive-marker checks passed.
- Request completion uses route templates and clears MDC in finally. Dedicated unit tests now verify both normal and exceptional cleanup.

Connection acquisition/connect/socket limits are 2/2/5 seconds, separate from the existing booking lock/statement/transaction budgets of 2/3/5 seconds. This is not a universal hard HTTP deadline. Liveness and readiness are explicitly separate. Docker uses readiness as health without automatically restarting an unhealthy application.

## Actual failures and corrections

- Offline clean-plugin resolution failed before tests; downloading the missing plugin enabled the successful clean build.
- Reviewer recommended fixing COMPOSE_FILE in the operations script so an inherited environment could not redirect isolation/cleanup to a different Compose definition. QA applied this before runtime verification.
- The first operational attempt timed out after application restart. The application log showed a successful restart and repeated internal healthy probes, while the client script retained its initial ephemeral host port. The failed report/log are retained as target/m6-operations-first-attempt.json and .app.log.
- QA changed the script to rediscover the published port before readiness checks. The final run recorded an actual change from host port 32774 to 32775, and all checks passed. The initial failure was a test-runner endpoint assumption, not evidence of application-data loss.

The failed attempt is recorded honestly; no outcome is converted to a pass merely by suppressing an assertion. Readiness checks remain intact in the corrected script.

## Reproduction and limitations

See [operations.md](operations.md) for packaged/host-run commands, health/metrics, restart and verification instructions. Generated reports are ignored by Git and are recreated by tests/scripts. The final operational log is /tmp/keyloop-m6-operations.log; the reusable report/log paths are target/m6-operations-report.json and target/m6-operations-report.app.log.

P0 acceptance is met for M6, including T27/T28/T30 operational evidence and prior lock/rollback regressions. **P1/T32 benchmark was not run**; no p95, throughput or production-readiness result is claimed. Backups/restore, loaded shutdown draining, production failover, tracing export, authentication and deployment security remain outside the verified local scope. Container restart/stop-start persistence is demonstrated; a backup restoration exercise is not.

M7 submission documentation/video has not started. No repository push, publication or submission was performed.

Platform references consulted: [Spring Boot logging](https://docs.spring.io/spring-boot/reference/features/logging.html), [Actuator health/endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html), [graceful shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html) and [official Eclipse Temurin images](https://hub.docker.com/_/eclipse-temurin). Actual results are supported by executed checks above.
