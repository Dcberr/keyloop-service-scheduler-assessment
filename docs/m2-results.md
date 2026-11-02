# M2 Results — Schema, Constraints and Demo Data

**Date:** 2026-11-02  
**Status:** Completed. M3 has not started.

## Scope and role ownership

- Lead used `scheduler-lead` to coordinate, integrate, verify, update operational documentation and require a valid migration location.
- Data Engineer used `scheduler-data-engineer` to implement V1 and the explicit demo seed/runner.
- QA Engineer used `scheduler-qa-engineer` to independently write schema tests and update the application PostgreSQL smoke test.
- Reviewer used `scheduler-reviewer` to inspect schema, seed, tests and configuration read-only, including a final pass after corrections. No blocking finding remained.

## Deliverables

- `src/main/resources/db/migration/V1__create_scheduler_schema.sql`: all 11 SDD tables, UUID keys, mandatory fields, uniqueness/check constraints, five composite appointment foreign keys and three independent GiST exclusion constraints using btree_gist.
- `scripts/demo/seed.sql`: explicit transactional, rerunnable demo data, outside Flyway migrations and application startup.
- `scripts/demo/seed.sh`: executes seed with psql inside the selected Compose service and stops on SQL errors.
- `SchemaConstraintsIT`: 38 real PostgreSQL test cases covering database integrity and seed lifecycle.
- `PostgresConnectionIT`: verifies application startup, PostgreSQL access, V1 application/validation and no migration on a repeated run.
- Flyway now fails if its migration location is missing. README documents local seed execution and data boundaries.

Named overlap constraints available for later error mapping:

```text
appointments_no_bay_overlap
appointments_no_technician_overlap
appointments_no_vehicle_overlap
```

Other relationship and check names are defined in V1; later services should map only recognized constraints, not every database exception to a booking conflict.

## Integrated verification

Environment: macOS arm64, JDK 21.0.8, Maven Wrapper 3.9.11, Spring Boot 4.1.2, Docker 28.4.0 through Colima, PostgreSQL 18.1. Existing cached dependencies and images were used. Verification ran offline against isolated disposable test databases, not a developer/shared database.

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
| PostgresConnectionIT | 1 | 0 | 0 | 0 |
| SchemaConstraintsIT | 38 | 0 | 0 | 0 |
| Total | 41 | 0 | 0 | 0 |

Generated evidence is in `target/surefire-reports` and `target/failsafe-reports`; these build outputs are intentionally ignored by Git. The final verification log for this session is `/tmp/keyloop-m2-verify.log`. The temporary Maven repository is specific to this environment; normal `./mvnw verify` remains the documented user command.

### Proven database behavior

- Fresh Flyway migration succeeds; validation succeeds and repeat migration applies zero changes.
- Schema migration alone leaves business tables empty; demo seed is not automatic.
- Each resource overlap constraint rejects identical, partial-at-either-end and enclosing intervals with SQLState 23P01 and the expected constraint name, while unrelated resources remain valid.
- Adjacent half-open intervals are allowed independently for bay, technician and vehicle.
- Ownership, dealership membership, service offerings and qualifications are enforced with foreign keys; referenced historical relationships cannot be removed or changed incompatibly.
- Invalid durations, mandatory nulls, unsupported status, non-finite timestamps and invalid intervals are rejected. Where checks overlap, fixtures identify which constraint is expected rather than assuming a different check order.
- An existing appointment retains its duration/end snapshot when the default service duration changes.
- Seed reruns under UTC and America/New_York preserve row counts, window/shift IDs and an existing appointment.
- Calendar tests verify the 45-date generation horizon, two daily windows on open dates, lunch breaks, Sunday closures and shift/opening correspondence.

This supplies M2 evidence for T18, T19 and T29 and database-level interval/history checks. HTTP policy, allocation and concurrent booking behavior remain later milestones; passing schema tests does not mark those application scenarios complete.

## Demo runner verification

A separate isolated Compose project, `scheduler-m2-seed-check`, was started with PostgreSQL 18.1 and a dynamically assigned localhost port. V1 SQL was applied directly using psql for this shell-path check; Flyway lifecycle correctness was separately verified by the automated tests above. The actual `seed.sh` runner was executed twice.

Observed result on 2026-11-02:

| Entity | Count |
|---|---:|
| Dealerships | 2 |
| Service types | 3 |
| Opening windows | 156 |
| Technician shifts | 234 |
| Appointments | 0 |

Reference data includes two customers, three vehicles, three bays and three technicians with different qualifications. Service durations are 30, 60 and 90 minutes. Both dealerships use Asia/Ho_Chi_Minh. Today plus the next 44 local dates contains 39 open dates for this run; each has 08:00–12:00 and 13:00–17:00 windows. Counts depend on the seed date. Closed Sundays deliberately have no rows.

The isolated Compose containers and their dedicated test volume were removed after the check. No shared development volume was removed. Seed itself does not delete data; no destructive reset utility was introduced.

## Actual review findings and corrections

1. Lead found that a draft seed hashed a timezone-dependent timestamp representation. Data Engineer changed IDs to use absolute epoch values; QA verified stable IDs under different session time zones.
2. Initial integrated build failed at test compilation because SQLException is also Iterable and AssertJ overload selection was ambiguous. QA made the null assertion's type explicit; production code and constraints were unchanged.
3. The next run reached a database startup race: container logs were ready before Colima's mapped port accepted connections. QA added bounded readiness for both logs and listening ports, without sleeps or skips. Final integrated verification passed.
4. Reviewer suggested historical-reference checks and confirmed the final schema/test corrections had no blocking regression.

These are factual collaboration events, not hypothetical examples. Earlier failures are retained here rather than represented as successful runs.

## Handoff to M3

M2 acceptance gates are satisfied. The next implementation milestone can use the agreed schema and stable demo IDs for business rules and availability. Keep active-state checks, complete opening/shift containment, ownership/time policies and booking-horizon validation at the service layer as designed. No M3 repository queries, availability API or booking transaction was implemented in this milestone.
