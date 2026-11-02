# Unified Service Scheduler

Backend implementation for **Keyloop Technical Assessment — Scenario A**.

**Status:** M0–M6 P0 completed. REST API, packaged Compose application, persistence, recovery and observability are verified on real PostgreSQL. P1 benchmarking is deferred; M7 has not started.

**Selected stack:** Java + Spring Boot + PostgreSQL.

## Design documents

- [System Design Document](docs/system-design.md): scope, assumptions, architecture, data model, API, transaction/locking strategy, observability and design-phase GenAI use.
- [Implementation Plan](docs/implementation-plan.md): milestones, acceptance gates, test matrix and submission preparation.

- [OpenAPI contract](docs/openapi.yaml)
- [M0 results and evidence](docs/m0-results.md)
- [M1 build and environment evidence](docs/m1-results.md)
- [M2 schema and seed evidence](docs/m2-results.md)
- [M3 business rules and availability evidence](docs/m3-results.md)
- [M4 transaction and concurrency evidence](docs/m4-results.md)
- [M5 REST and demo evidence](docs/m5-results.md)
- [M6 operations and observability evidence](docs/m6-results.md)
- [Packaging and operations runbook](docs/operations.md)

## Build, run and test

The backend, database schema, REST API and demo client are implemented. Milestone reports linked above record verification outcomes and known limits.

Prerequisites: JDK 21, Docker with a running daemon, and network access on the first build. Maven 3.9.11 is downloaded by the committed wrapper.

```sh
./mvnw test      # Unit tests
./mvnw verify    # Unit tests, packaged application and real PostgreSQL integration test
```

Integration tests start isolated PostgreSQL 18.1 containers; they fail rather than skip if Docker is unavailable. On macOS with Colima, start Colima and configure:

```sh
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Ensure JAVA_HOME points to JDK 21. For Homebrew installations:

```sh
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
```

To run the local database and application:

```sh
[ -f .env ] || cp .env.example .env
# Edit the local demo password if desired; never commit .env.
docker compose up -d --wait
set -a
. ./.env
set +a
export DB_URL="jdbc:postgresql://localhost:${POSTGRES_PORT}/${POSTGRES_DB}"
export DB_USERNAME="$POSTGRES_USER"
export DB_PASSWORD="$POSTGRES_PASSWORD"
./mvnw spring-boot:run
```

The server defaults to port 8080. Check `/actuator/health/readiness` and `/actuator/health/liveness`. Startup applies the versioned Flyway schema; a missing migration location now fails startup. The REST API is available under `/api/v1`. Stop the database using `docker compose down` to retain the named volume. Deleting the volume deletes local database data.

## Demo data

After the application has started successfully and Flyway has applied V1, run this in a second terminal from the repository root:

```sh
./scripts/demo/seed.sh
```

The script uses `psql` inside the local Compose PostgreSQL service, so a host `psql` installation is unnecessary. Seed is explicit and is never executed by application startup. The script stops on SQL errors and applies its changes transactionally. Re-running adds missing reference/calendar rows without overwriting reference data or deleting appointments.

The sample includes two dealerships, three services (30/60/90 minutes), three bays, three technicians with different qualifications, two customers and three vehicles. Both dealerships use `Asia/Ho_Chi_Minh`. Stable demo IDs are listed in `scripts/demo/seed.sql`. No appointment is seeded.

Calendar generation covers today and the next 44 local dates, with morning 08:00–12:00 and afternoon 13:00–17:00 windows; Sundays are closed. Technicians follow those windows. Re-run seed as time advances to extend the calendar; earlier rows and appointments remain. No destructive reset command is provided. To reset disposable data, use a separate isolated database rather than deleting a shared development volume.

Tests run through `./mvnw verify` and cover migration repeatability, each resource overlap constraint, relationship integrity, duration snapshots, seed repeatability, time policies, availability and catalog queries.

## Availability and catalog services

The REST controllers delegate to application services: `AvailabilityService.check` returns an advisory observation; `AvailabilityService.assess` also returns internal deterministic candidates for the future booking flow. `CatalogService` lists dealerships, services, customers and vehicles with pagination. These Spring-managed services are wired to the eight operations described by OpenAPI.

Availability evaluates the complete service interval, technician qualification/shift, bay, dealership opening window and vehicle conflicts. It writes no appointments and holds no resources. Business exceptions map to sanitized problem responses with HTTP status/code and request correlation. See the M3 report for tested rules and limitations.

## Transactional booking service

`BookingService.book(AvailabilityRequest)` locks the vehicle and dealership in that order, rechecks time and resources, inserts the complete appointment, and returns after commit. It uses an independent READ_COMMITTED/REQUIRES_NEW transaction: a surrounding caller transaction cannot subsequently undo the booking. Availability is advisory and never bypasses this recheck.

Default settings are `scheduler.booking.lock-timeout-ms=2000`, `scheduler.booking.statement-timeout-ms=3000` and `scheduler.booking.transaction-timeout-seconds=5`. Override these Spring properties when tuning the local environment. No automatic POST retry or idempotent replay is provided; an uncertain response after commit can be investigated through `GET /api/v1/appointments?customerId=...&vehicleId=...` before deciding whether to retry.

The full verification suite currently includes 26 unit and 127 integration tests, including real HTTP/PostgreSQL checks and concurrency checks observing actual database lock waits. See the milestone reports for outcomes and limits.

## REST API and demo client

Python 3.11+ is required for the demo and optional schema checks. Start the application/database and run the seed as above, then:

```sh
python3 scripts/demo/client.py --output target/demo-http-samples.json
```

Use `--base-url http://localhost:8080/api/v1` to select a server explicitly. The client reads seed IDs from the API and finds future availability; it creates one real appointment, verifies retrieval, demonstrates the second client's stale-availability conflict and recovers the appointment through history. It assumes the standard seed's single qualified Oil Change technician, preserves data and never retries POST automatically. A rerun searches for another free slot.

| Method | Path under /api/v1 |
|---|---|
| GET | /dealerships |
| GET | /dealerships/{id}/services |
| GET | /customers |
| GET | /customers/{id}/vehicles |
| GET | /availability?customerId=...&vehicleId=...&dealershipId=...&serviceTypeId=...&startsAt=... |
| POST | /appointments |
| GET | /appointments/{id} |
| GET | /appointments?customerId=...&vehicleId=... |

The OpenAPI document specifies fields, pagination and all errors. POST returns 201 and Location only after commit. Errors use application/problem+json and X-Request-ID; valid supplied request IDs are reused. UUIDs require canonical formatting, unknown write fields are rejected and timestamps are returned in UTC.

After `./mvnw verify`, optionally validate the captured real HTTP responses against OpenAPI:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/validation/requirements.txt
.venv/bin/python scripts/validation/validate_http_contract.py target/m5-http-contract-samples.json
.venv/bin/python scripts/validation/validate_http_contract.py target/demo-http-samples.json
```

The last command assumes the demo capture was generated. Maven tests do not require these Python packages. The response checker validates OpenAPI, body/header schemas and formats, status/correlation consistency, and successful coverage of all eight operations. It supports this contract's acyclic local schema references; routing errors are checked against the shared Problem schema separately.

## Packaged Compose application and operational verification

After creating `.env` once, build from source and run both containers:

```sh
./mvnw clean verify
docker compose --profile app up --build -d --wait
./scripts/demo/seed.sh
python3 scripts/demo/client.py
```

The Dockerfile consumes the freshly built JAR, runs as UID 10001 on a digest-pinned Java 21 runtime, and excludes environment files from its build context. The `app` profile adds the packaged application; without that profile Compose continues to support the host-run Java workflow above. Ports are published on localhost. `docker compose --profile app down` stops containers while retaining database data.

To reproduce the isolated M6 operational checks after the JAR build:

```sh
python3 scripts/validation/verify_operations.py
```

The script creates a unique disposable Compose project, verifies restart persistence, database outage/recovery without app restart, rollback, JSON logs and bounded metrics, then removes only that project's containers/volume. Generated reports are under `target/m6-operations-report.*`. It retains the built verification image as local cache.

Application logs are JSON with request correlation. Booking metrics distinguish success, business rejection and errors; lock-acquisition duration and Hikari pool signals are available through Actuator. Liveness stays independent of PostgreSQL; readiness includes it. See the operations runbook for exact signals, budgets and troubleshooting. P1 load measurement and external tracing are not implemented.

## AI Collaboration Narrative

During design, the assistant analyzed the supplied assessment, distinguished required behavior from proposed implementation decisions, consulted official Spring/PostgreSQL documentation, and drafted the linked documents. The engineer selected the backend layer and Java + Spring Boot + PostgreSQL.

M0 used Lead, Architect, QA Engineer and Reviewer roles. Independent review caught a history-endpoint error example that schema validation alone did not catch; it was corrected and the contract revalidated. During M1, Backend created the scaffold, QA added independent tests, and Reviewer found a JDBC port mismatch with Compose. Backend corrected it; Lead integrated and ran Maven verify successfully (2 unit tests and 1 real PostgreSQL integration test, no skips). This section will be updated with actual review decisions, corrections and executed test results, with links to evidence, as the project progresses.

M2 used Data Engineer, QA Engineer and Reviewer roles. Lead caught a session-timezone dependency in draft seed IDs; Data corrected it and QA verified cross-timezone reruns. QA also fixed a test-compilation ambiguity and a Colima startup-readiness race. Final integrated verification passed 2 unit and 39 integration tests with no skips; the actual seed runner succeeded twice on a separate disposable Compose database. See the M2 evidence report for details.

M3 used Backend, QA and Reviewer roles to implement and independently verify read-only business rules. Review caught a long fractional-timestamp edge case, which was corrected and covered by regression tests. Integrated verification passed all 100 tests (24 unit and 76 PostgreSQL integration tests), with no skips.

M4 used Backend, QA and Reviewer roles for transactional booking. Review corrections preserved missing-reference error precedence and replaced human-message constraint parsing with structured PostgreSQL diagnostics, including a missing-diagnostic null guard. Lead integrated and verified all 121 tests; actual database lock waits, deferred commit failure and timeout tests support the transaction claims.

M5 used Backend, QA and Reviewer roles for REST/API validation. Lead settled standard protocol-error semantics, added a dynamic demo and a reusable response checker, then verified all 148 tests and actual HTTP captures. Review strengthened canonical UUID validation and the checker's protocol-error assertions. A packaged-JAR demo with fresh PostgreSQL and seed produced the expected creation, conflict and history recovery. See the M5 report for evidence.

M6 used Backend, QA and Reviewer roles for packaging and observability. Lead ran a clean build and the isolated operational scenario; all 153 tests and nine runtime checks passed. QA corrected an ephemeral host-port assumption after preserving the initial failed restart check. Independent report review confirmed persistence, controlled outage responses, recovery without app restart, rollback and sanitized correlated logs. No benchmark claim is made.
