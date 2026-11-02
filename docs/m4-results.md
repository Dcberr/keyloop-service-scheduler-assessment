# M4 Results — Transactional Booking and Concurrency

**Handoff date:** 2026-11-03  
**Final runtime verification:** 2026-11-02  
**Status:** Completed. M5 has not started.  
**Scope:** Application-service booking and PostgreSQL transaction correctness. HTTP controllers, response serialization and demo requests remain M5 work.

## Roles and ownership

- Lead applied `scheduler-lead`, coordinated API/ownership, reviewed integration details, changed the existing PostgreSQL driver to compile scope for typed diagnostics, executed full verification and documented the results.
- Backend Engineer applied `scheduler-backend-engineer`, implementing the appointment package and factoring shared reference validation in AvailabilityService.
- QA Engineer applied `scheduler-qa-engineer`, independently implementing BookingTransactionIT with real database contention and fault injection.
- Reviewer applied `scheduler-reviewer`, inspecting transaction ownership, lock ordering, fresh availability checks, error mapping and the tests read-only. No blocking finding remained after corrections.

## Implemented behavior

`BookingService.book(AvailabilityRequest)` returns a persisted Appointment only after its own transaction commits. AppointmentRepository performs parameterized SQL; BookingFailureMapper interprets failures outside the completed/rolled-back transaction.

The transaction uses a Spring TransactionTemplate, one transaction-bound DataSource connection, READ_COMMITTED isolation and REQUIRES_NEW propagation. Its sequence is:

1. Validate input shape and timestamp syntax.
2. Begin the transaction and apply transaction-local PostgreSQL lock/statement timeouts.
3. Lock the vehicle row with FOR UPDATE.
4. Reuse reference validation, preserving missing-reference 404 before ownership-mismatch 422.
5. Lock the dealership row with FOR UPDATE.
6. Run a new availability assessment after the locks, rechecking time with the injected Clock and all resource rules.
7. Insert one complete confirmed appointment with both assignments and the duration snapshot.
8. Commit, then publish success metrics/log and return the appointment.

REQUIRES_NEW deliberately makes booking an independently committed operation even when a caller already has a transaction. A subsequent rollback of that caller's transaction does not undo the booking. This is tested and prevents reporting success before a surrounding transaction decides to commit. Callers must not assume booking can be rolled back as part of a larger workflow. The future HTTP controller should invoke the service directly without creating an unnecessary surrounding transaction.

The dealership lock serializes allocation at one dealership; different dealerships with different vehicles can progress independently. PostgreSQL exclusion constraints remain the final overlap safeguard. There are no in-memory allocation locks, external network calls inside the booking transaction or automatic booking retries.

## Timeouts, errors and observability

| Property | Default |
|---|---:|
| scheduler.booking.lock-timeout-ms | 2000 |
| scheduler.booking.statement-timeout-ms | 3000 |
| scheduler.booking.transaction-timeout-seconds | 5 |

Values must be positive. Lock/statement settings are transaction-local and do not persist on pooled connections. The transaction deadline is configured in Spring. Real lock and statement timeout cancellation are tested; a separate transaction-deadline expiration scenario is not claimed.

Known SQLState 23P01 errors use PostgreSQL's structured constraint field:

- appointments_no_bay_overlap / appointments_no_technician_overlap → 409 BOOKING_CONFLICT.
- appointments_no_vehicle_overlap → 409 VEHICLE_UNAVAILABLE.
- Unknown or missing constraint diagnostics → controlled 500 INTERNAL_ERROR, even if the message or DETAIL mentions a known name.

Lock timeout, cancellation, deadlock and connection-related failures are mapped to 503 TEMPORARILY_UNAVAILABLE. Resource shortages retain their M3 conflict reason. Unexpected integrity errors remain 500. These values are service-layer errors; HTTP mapping is pending.

Success counter `scheduler.booking.requests` with outcome=success/reason=none, success duration timer and appointment_confirmed log occur after TransactionTemplate returns from commit. Deferred commit failure tests prove no success counter increment and no persisted appointment. Log placement was verified by review; automated log capture is not claimed. Full failure metrics and request correlation remain later observability/API work.

## Executed verification

Environment: JDK 21.0.8, Maven Wrapper 3.9.11, Spring Boot 4.1.2, PostgreSQL 18.1 and Docker 28.4.0 through Colima. Isolated test databases were used; no shared development database was mutated.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
DOCKER_HOST=unix:///Users/congnguyenngocchien/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./mvnw -B -ntp -o -Dmaven.repo.local=/tmp/keyloop-maven-repository verify
```

**Final result: BUILD SUCCESS.**

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| ClockConfigurationTest | 2 | 0 | 0 | 0 |
| BookingTimePolicyTest | 22 | 0 | 0 | 0 |
| PostgresConnectionIT | 1 | 0 | 0 | 0 |
| SchemaConstraintsIT | 38 | 0 | 0 | 0 |
| AvailabilityRulesIT | 37 | 0 | 0 | 0 |
| BookingTransactionIT | 21 | 0 | 0 | 0 |
| Total | 121 | 0 | 0 | 0 |

This is 24 unit tests and 97 integration tests. The first integrated pass covered 20 booking cases; a second final pass included the newly added missing-constraint-diagnostic regression and passed all 21 booking cases. No failed runtime run is implied by pre-execution review corrections.

Generated reports: target/surefire-reports and target/failsafe-reports. Final session log: /tmp/keyloop-m4-verify.log. These are generated/local evidence, not committed source artifacts. Normal user execution remains `./mvnw verify`; offline mode and the temporary Maven repository are environment-specific.

## Proven transaction and concurrency cases

| Scenario | Actual result |
|---|---|
| Successful booking | Correct IDs, assignments, duration and confirmed status; visible on an independent connection after return; success counter increments |
| Two requests, one pair | Exactly one success and one 409 NO_AVAILABLE_BAY; one row, no overlap |
| Two requests, two pairs | Both succeed with distinct bays/technicians; two rows, no overlap |
| Same vehicle at two dealerships | Exactly one succeeds; other is 409 VEHICLE_UNAVAILABLE; no vehicle overlap |
| Dealership A locked | Dealership B completes while A's worker remains blocked; no global serialization |
| Previously available resources consumed | Booking rechecks and returns a conflict, without an extra row |
| Lock timeout | Controlled 503, no appointment or success counter increment |
| Slow INSERT trigger | Actual PostgreSQL statement timeout cancels pg_sleep work, returning 503 with clean rollback |
| Time advances while waiting for lock | Post-lock revalidation returns 422 INVALID_BOOKING_TIME, without an appointment |
| Deferred failure at commit | Controlled 500; inserted row rolls back and success counter stays unchanged |
| Known exclusion diagnostics | Only the exact recognized constraint names map to their conflict codes |
| Unknown/spoofed/missing constraint diagnostic | Controlled 500, not a false capacity conflict or null-pointer failure |
| Synthetic cancellation/deadlock SQLStates | Controlled 503 and clean rollback; this tests classification, not an independently induced real deadlock |
| Existing caller transaction rolls back | Booking remains independently committed, matching REQUIRES_NEW policy |
| Missing references combined with ownership mismatch | Missing customer/service remains 404 rather than incorrectly reporting ownership 422 |

Concurrency tests use separate worker transactions and committed fixtures. An independent connection holds a vehicle/dealership row lock; tests inspect pg_stat_activity and pg_blocking_pids to establish actual lock waits before releasing it. Barriers coordinate workers before exclusive locks, with bounded waits and future timeouts. Arbitrary sleeps are not used as evidence of contention. The SQL pg_sleep call deliberately simulates slow database work solely for the statement-timeout test.

These results cover service-level T20–T25 and T31, plus successful persistence and regression checks. They do not establish HTTP status/Location behavior, production throughput or all possible schedules/interleavings.

## Actual collaboration corrections

1. Lead identified a possible missing-reference/ownership error-precedence mismatch. Backend factored reusable reference validation and QA added combined invalid-reference cases.
2. Lead and Reviewer identified the fragility of reading constraint names from human-readable error messages. Backend switched to structured PostgreSQL diagnostics; Lead changed the already-present driver dependency's scope without adding a new dependency. QA tested exact names, spoofed message content and absent diagnostics.
3. QA identified Map.of.get(null) as a risk for errors with no constraint field. Backend added an explicit null guard and QA added the regression included in the final run.
4. Reviewer confirmed transaction ownership, lock order, post-lock snapshot/time checks and success-after-commit placement. Lead ran the integrated code, rather than treating individual agent reports as runtime proof.

## Handoff

M4 acceptance gates are satisfied. M5 can wire the existing services to the agreed REST contract, add appointment retrieval/history, problem responses, correlation and reproducible demo requests. Keep booking success after commit, preserve the structured error classification and do not trust an earlier advisory availability result. M5 has not started.

Platform references consulted: [Spring programmatic transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html), [PostgreSQL row locking](https://www.postgresql.org/docs/18/explicit-locking.html) and [PostgreSQL client timeouts](https://www.postgresql.org/docs/18/runtime-config-client.html). Runtime proof comes from the tests described above.
