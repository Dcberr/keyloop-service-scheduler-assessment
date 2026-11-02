# Implementation Plan — Unified Service Scheduler

**Date:** 2026-11-01  
**Status:** M0–M6 P0 completed; M6 P1 benchmarking is deferred and M7 has not started. See [M0 results](m0-results.md), [M1 results](m1-results.md), [M2 results](m2-results.md), [M3 results](m3-results.md), [M4 results](m4-results.md), [M5 results](m5-results.md) and [M6 results](m6-results.md).  
**Stack selected by the user:** Java + Spring Boot + PostgreSQL.  
**Reference design:** [System Design Document](system-design.md).

## 1. Objectives and implementation principles

Complete the Topic A backend: accept an appointment request, establish that a bay and a qualified technician are available for the full duration, persist the appointment, and prove that concurrent requests cannot cause double-booking.

Implement in independently verifiable increments. Each milestone must meet its completion criteria before dependent work proceeds. Do not start a frontend, admin CRUD or external integrations within the current scope.

The design document and this plan are in English for the reviewer. The README and API contract will also be in English during implementation.

### Priorities

| Priority | Scope |
|---|---|
| P0 — required | Schema and constraints, sample data, booking, availability, appointment retrieval, business/concurrency tests, basic logs/health/metrics, run instructions, AI narrative and demo |
| P1 — after P0 | Performance measurement using the SDD workload, expanded recovery tests, query/index tuning |
| P2 — optional if time permits | Idempotency key, trace export, Swagger UI, alternative-slot search |
| Out of scope | Complete frontend, authentication/authorization, cancellation/rescheduling, payment, notifications, working-calendar administration |

Do not cut concurrency tests, constraints or AI documentation to make time for P2.

## 2. Decisions underpinning implementation

- Java 21; Spring Boot 4.1.2 was the baseline checked against official documentation when the SDD was prepared. Verify dependency resolution during project setup and record adjustments.
- PostgreSQL 18; select and pin the image patch/digest in M1, using the same image for Compose and Testcontainers.
- Maven Wrapper 3.9.x, with the patch pinned during setup; common dependencies follow Spring Boot dependency management.
- Spring MVC, Jakarta Validation, Spring JDBC, Flyway, Actuator/Micrometer; no ORM is needed for the first version.
- One application, one database. The system assigns resources; only confirmed appointments are stored.
- Every booking locks the vehicle before the dealership; check availability in the next query within a Read Committed transaction.
- The database has separate overlap constraints for bays, technicians and vehicles.
- Reference data is not modified through the API; future mutations require re-evaluating locking and historical assumptions.
- Availability is advisory at the time checked; POST always rechecks.
- No idempotency in P0: use appointment history to investigate a lost response after commit; do not automatically retry POST.

No further frontend or cloud-infrastructure decision is needed to start P0. These detailed choices are design proposals and may be adjusted during implementation with a recorded rationale.

## 3. Dependency order

```mermaid
flowchart LR
    M0["M0: Design review"] --> M1["M1: Project foundation"]
    M1 --> M2["M2: Schema and seed"]
    M2 --> M3["M3: Rules and availability"]
    M3 --> M4["M4: Booking transaction"]
    M4 --> M5["M5: HTTP contract and E2E"]
    M5 --> M6["M6: Operations and overall verification"]
    M6 --> M7["M7: Submission and video"]
```

OpenAPI can be drafted in M0 and tests added with each milestone. Do not defer all testing to M6.

## 4. Detailed milestone tasks

### M0 — Review design and acceptance criteria

**Inputs:** Current SDD, assessment, user's backend and stack choices.

- [x] Map SDD requirements R1–R8 to the planned outputs.
- [x] Review A1–A16, particularly fixed durations, every bay supporting every service, read-only reference data and booking horizon.
- [x] Settle endpoints, input fields, errors and reason priority.
- [x] During implementation, write `docs/openapi.yaml` against the SDD contract, including success and failure responses and examples.
- [x] Record new or changed decisions in the decision record; do not silently change business behavior in code.

**Complete when:** Expected outcomes can be described clearly for a valid booking, insufficient resources, incorrect qualification, outside working hours and two competing bookings. OpenAPI and SDD agree on HTTP statuses and field names.

### M1 — Set up application and test environment

**Depends on:** M0.

- [x] Create Maven project and wrapper; pin Java, Spring Boot and any required versions outside the BOM.
- [x] Add MVC, validation, JDBC, PostgreSQL driver, Flyway with its PostgreSQL module, Actuator and appropriate test dependencies.
- [x] Create feature packages per SDD; configure a Clock that can be replaced in tests.
- [x] Create PostgreSQL Compose configuration with volume, healthcheck and localhost binding.
- [x] Create `.env.example`, local/test configuration and `.gitignore`; do not commit real credentials.
- [x] Set up Testcontainers with the same PostgreSQL version as local development; integrations must run with Docker and clearly fail when Docker is unavailable.
- [x] Configure Maven Surefire for unit tests and Failsafe for integration tests; `verify` must run both.

**Expected outputs:** `pom.xml`, wrapper, application configuration, Compose and test infrastructure.

**Complete when:** The application connects to the database; build and a minimal integration test run from a clean environment. Do not silently switch to H2 or skip integration tests when PostgreSQL fails.

### M2 — Schema, constraints and sample data

**Depends on:** M1.

- [x] Write migrations for tables in SDD §5, primary/foreign keys, unique and check constraints.
- [x] Add composite foreign keys for ownership, dealership membership, service offerings and technician qualifications.
- [x] Enable `btree_gist`; create three clearly named exclusion constraints for error mapping.
- [x] Add indexes for lookup, overlap queries and history; avoid duplicating constraint indexes.
- [x] Write integration tests that directly prove each constraint before relying on service validation.
- [x] Create separate local/demo seed data: two dealerships, multiple services, technicians with different qualifications, bays, customers and vehicles.
- [x] Generate at least 45 days of opening windows and shifts in dealership time zones; include breaks to demonstrate full-duration containment.
- [x] Make seed rerunnable without deleting appointments; data reset is a separately named operation.

**Complete when:** All migrations run on a fresh database, repeated seed does not duplicate data, and invalid inserts are rejected. Appointment duration snapshots do not change when default service duration changes later.

### M3 — Business rules and availability

**Depends on:** M2.

- [x] Test `[start,end)` time, duration, offset, whole-minute precision and horizon using a fixed Clock.
- [x] Validate customer/vehicle, dealership service offerings and active status.
- [x] Check the entire interval is within an opening window and a shift; do not merge separate windows across a break.
- [x] Write SQL to find free bays and qualified, free technicians; also check vehicle appointment conflicts.
- [x] Use one SQL statement for the final availability candidate/conflict assessment to avoid mixing snapshots.
- [x] Define reasons and priority consistent with the SDD; results do not hold capacity or promise a resource assignment.
- [x] Add catalog queries and pagination for the demo client.

**Complete when:** Interval, qualification, membership and working-window cases in the test matrix pass. Do not load all appointments into Java to find availability.

### M4 — Booking transaction and double-booking prevention

**Depends on:** M3.

- [x] Place the transaction boundary in a Spring-managed application service, using the same DataSource and connection throughout.
- [x] Lock the vehicle, then the dealership; revalidate time after waiting for locks.
- [x] Re-read availability in a statement after acquiring locks, using the same policy as GET.
- [x] Select bay/technician deterministically by ID and insert exactly one appointment.
- [x] Return success and record success metrics/logs only after commit completes.
- [x] Map named overlap constraints to conflicts; do not turn every database error into a 409.
- [x] Configure lock/query/transaction timeouts; roll back on failure and do not continue querying in an aborted transaction.
- [x] Write concurrent integration tests with separate connections/transactions, committed fixtures and latch/barrier synchronization.
- [x] Test one resource pair, multiple pairs, the same vehicle at two dealerships, and independence of two dealerships with different vehicles.

**Complete when:** T20–T25 pass; no appointments overlap, failed bookings leave no data, and a request with remaining capacity can use the remaining pair.

**Review point:** Do not place a barrier after an exclusive lock that prevents the second worker from reaching it. Do not use `Thread.sleep` as proof two requests actually contend. A test timeout is a failure.

### M5 — HTTP API, contract and demo client

**Depends on:** M4; draft contract from M0.

- [x] Complete controllers for catalog, availability, create, get-by-ID and history.
- [x] Keep DTOs separate from persistence records; reject unsupported write fields.
- [x] Standardize `application/problem+json`, error codes, request IDs and validation errors.
- [x] Check `201` and `Location`, UTC formatting, pagination, empty results and nonexistent parent IDs.
- [x] Align OpenAPI with actual behavior, including 400/404/409/422/503/500 errors.
- [x] Write HTTP integration tests on a real test server for availability → create → get.
- [x] Create cURL examples or a demo script that reads seed IDs and selects a valid future date; do not hard-code a date that can expire.
- [x] Demonstrate two clients seeing availability, one booking first and the other receiving a conflict.
- [x] Add history lookup for an uncertain POST commit outcome.

**Complete when:** A reviewer can perform the main operations without reading source code; actual responses match the API contract and acceptance criteria.

### M6 — Operations, overall verification and optional performance

**Depends on:** M5.

- [x] Complete structured logs, request IDs and booking outcome metrics; do not log VIN, customer names or payloads.
- [x] Separate liveness/readiness health; readiness reflects database failure while liveness does not fail solely because the database is disconnected.
- [x] Exercise database unavailability, lock timeout and rollback; verify controlled error responses.
- [x] Package the application with the database in Compose, including graceful shutdown and volume persistence.
- [x] Run migrations/build/tests from a clean environment and record actual results.
- [x] Restart the app/database while retaining the volume; retrieve an existing appointment.
- [x] Check logs record success only after successful commit and metrics do not use IDs as labels.
- [ ] P1 (deferred, not required for P0 completion): measure the SDD §8 workload; record machine, data, read/write mix, p95 and errors; optimize only with evidence.

**Complete when:** P0 tests pass; persistence and failure-path demos work; no benchmark result is claimed without measurement.

### M7 — Submission materials and video

**Depends on:** M6.

- [ ] Write an English README: prerequisites, build/run/test, migrations/seed, API examples, demo, troubleshooting and limitations.
- [ ] Ensure the README has a dedicated **AI Collaboration Narrative** as required by the assessment.
- [ ] Update the SDD from proposed design to the final implementation; clearly identify deferred items.
- [ ] Create `docs/verification.md` with commands run, environment, results and unresolved issues.
- [ ] Create `docs/ai-collaboration.md` or equivalent evidence log from actual events; summarize and link it in the README.
- [ ] Confirm another person can follow the README from a clean checkout; schema/seed must not depend on files private to the author's machine.
- [ ] Prepare and record a 5–10 minute video using the outline below; the submitter introduces themselves and explains their decisions.
- [ ] Check that the repository, System Design and video constitute all three artifacts before submission. Publish/push/submit only when within the user's instructions.

**Complete when:** All three deliverables are complete and consistent; every code or test claim has evidence. Do not mark the video complete when only a script exists.

## 5. Core test matrix

All rows below are **planned**, not yet executed. All are P0 except the row marked P1.

| ID | Scenario | Outcome to prove |
|---|---|---|
| T01 | Sufficient resources, vehicle belongs to customer | 201; appointment links the correct customer/vehicle/dealer/service/bay/technician |
| T02 | No bay | GET availability false; POST 409; no new row |
| T03 | No free technician | False/409; no new row |
| T04 | Technician is free but lacks qualification | Not selected |
| T05 | Bay/technician free at another dealership | Not selected; composite FK also rejects an invalid insert |
| T06 | Identical, partially overlapping at either end, or enclosing interval | All rejected |
| T07 | Previous appointment ends exactly when the next starts | Allowed if other conditions are valid |
| T08 | Resource free at start but busy before end | Rejected |
| T09 | Service extends past closing time/break/shift change | Rejected; do not join separate windows |
| T10 | Customer does not own vehicle | 422; no data written |
| T11 | Malformed ID / valid ID that does not exist | 400 / 404 respectively |
| T12 | Service not offered or reference inactive | 422 for service/dealership; inactive resource not selected |
| T13 | Past start, equal to now or beyond horizon | 422; test the exact now + 30 days boundary |
| T14 | Missing offset, malformed timestamp / invalid precision | 400 for format; 422 for precision outside the rule |
| T15 | Two timestamps with different offsets represent the same instant | Same interval and conflict result |
| T16 | Client supplies endsAt/technicianId/serviceBayId or unknown field | 400; no effect on allocation/duration |
| T17 | Duration is calculated and stored as a snapshot | Correct end time; changing default duration in a fixture does not alter an old appointment |
| T18 | Each bay/technician/vehicle exclusion constraint | Direct SQL insert violating each constraint is independently rejected |
| T19 | Foreign key/check constraint | Reject invalid ownership, dealership, offering, qualification, interval and duration |
| T20 | Two POSTs for different vehicles compete for one resource pair | Exactly one 201, one 409 and one new appointment |
| T21 | Two POSTs for different vehicles at the same time with two pairs available | Both succeed with non-overlapping allocations |
| T22 | Same vehicle simultaneously at two dealerships | Exactly one succeeds; no overlapping vehicle appointments |
| T23 | Two dealerships, two different vehicles | Booking at dealership B finishes while transaction A holds dealership A's lock |
| T24 | Availability reads true, then another client takes resources | POST rechecks and returns conflict |
| T25 | Error before commit or lock wait exceeds timeout | Clean rollback; controlled error; no success log/metric |
| T26 | Pagination, empty result, history lookup | Stable ordering, size limit, correct filter, no leaked SQL errors |
| T27 | GET after POST and after restart retaining volume | Data and allocation remain intact |
| T28 | Database down; connection restored | Readiness fails then recovers; API creates no fake booking |
| T29 | Seed run repeatedly | No duplicate reference/window data or deleted appointments |
| T30 | Request ID, errors and metrics | Complete correlation, no PII, no ID-based label cardinality |
| T31 | POST waits for a lock until startsAt has passed | Revalidate after lock and reject the past appointment |
| T32 | P1: multi-dealership workload and single-dealership hotspot | Report measurements; do not bypass integrity to increase throughput |

For T18, use fixtures valid under every other constraint to identify exactly which constraint is violated. For T20–T23, use a real database and separate connections; mocked repositories do not prove locking.

## 6. Planned repository structure

The implementation paths below have not been created; currently only design documentation exists.

```text
service-scheduler/
  README.md
  pom.xml
  mvnw / mvnw.cmd / .mvn/
  compose.yaml
  Dockerfile
  .env.example
  docs/
    system-design.md
    implementation-plan.md
    openapi.yaml
    ai-collaboration.md
    verification.md
    demo-script.md
  src/main/java/com/keyloop/scheduler/
    appointment/
    availability/
    catalog/
    schedule/
    shared/
    config/
  src/main/resources/
    application.yaml
    db/migration/
  src/test/java/com/keyloop/scheduler/
  scripts/
    demo/
```

Specific file names may be adjusted for the framework. There is no need to create all empty directories before they have an actual responsibility.

## 7. AI collaboration and evidence

Apply the [role-based multi-agent workflow](agent-workflow.md): the Lead coordinates Architect, Data Engineer, Backend Engineer, QA Engineer and Reviewer by milestone. Assign file ownership clearly and parallelize only independent work.

For each milestone, provide AI with the scope, relevant assumptions, contract and test IDs to satisfy. Request explanations for difficult choices, especially transaction/SQL decisions; review and then verify with tests. Do not substitute “AI checked it” for an execution result.

Example evidence-log row:

| Milestone | AI request | Proposal / change | Issue found | Verification executed | Actual result |
|---|---|---|---|---|---|
| Example structure, no result yet | Task summary | File/commit link when available | Actual finding or “none found” | Actual command or test ID | Passed/failed with notes |

At planning time, evidence consists only of PDF analysis, the user's backend/stack selection, consultation of official documentation and design drafting. No bug fix or executed application test is yet available for the narrative.

## 8. Proposed video outline — 8 minutes

| Time | Content |
|---|---|
| 00:00–00:30 | Introduce yourself and the Topic A/backend choice |
| 00:30–02:00 | Two-resource problem, architecture diagram and key assumptions |
| 02:00–03:30 | AI collaboration, one decision and actual verification evidence |
| 03:30–05:30 | Demo availability, successful booking, appointment retrieval and conflict |
| 05:30–06:45 | Run/explain concurrent booking tests and database constraints |
| 06:45–07:30 | Persistence, logs/health and per-dealership throughput limitation |
| 07:30–08:00 | Lessons, challenges and future improvements |

Prepare data in advance but use real requests and results. If showing only previous test results, state when they were run and how to rerun them. Do not read all source code in the video.

## 9. Overall completion criteria

- [ ] The three business requirements R1–R3 each have implementation and supporting tests.
- [ ] No double-booking of bay, technician or vehicle in concurrency tests.
- [ ] Build/run/test are reproducible; database is persistent; seed does not depend on hard-coded dates.
- [ ] API contract, README and SDD describe final behavior accurately.
- [ ] Business errors, infrastructure errors and unexpected errors are distinguished.
- [ ] Observability works at the committed level; deferred items are clearly documented.
- [ ] AI narrative reflects the actual process and contains no hypothetical evidence.
- [ ] A 5–10 minute video has been created and checked; repository and documentation are ready for submission.

P1/P2 are not required to satisfy P0, but all remaining limitations must be documented honestly in the submission.
