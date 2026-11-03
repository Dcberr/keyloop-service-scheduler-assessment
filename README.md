# Unified Service Scheduler

Backend implementation for **Keyloop Technical Assessment — Scenario A**.

**Selected stack:** Java + Spring Boot + PostgreSQL.

## Design documents

- [System Design Document](docs/system-design.md): final architecture, assumptions, data model, API, locking strategy, observability and design-phase GenAI use.
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

## Quick start: packaged application

Run the following from the repository root (the directory containing `pom.xml`). Use a POSIX shell, JDK 21, Docker with Compose v2 and a running daemon, and Python 3.11+ with system IANA time-zone data. First-time setup needs network access for Maven and container images. A host Maven or PostgreSQL installation is unnecessary. On Windows, use WSL2 for the shell scripts; Windows execution has not been verified.

```sh
java -version
docker version
docker compose version
python3 --version
[ -f .env ] || cp .env.example .env
./mvnw clean verify
docker compose --profile app up --build -d --wait
./scripts/demo/seed.sh
python3 scripts/demo/client.py --output target/demo-http-samples.json
```

The default API URL is `http://localhost:8080/api/v1`. A successful demo prints two available observations, one `201` booking, retrieval, a `409 NO_AVAILABLE_TECHNICIAN` conflict, and history recovery. It creates one real appointment and retains it. It finds a new future slot on rerun; it does not replay a POST automatically. To stop while keeping data:

```sh
docker compose --profile app down
```

This path runs both services in Docker. The host-run alternative below is for development; stop a host application before starting the packaged app on the same port. The example password is for disposable local data; `.env` is ignored by Git. Change its password before first database initialization if needed; editing the variable later does not rotate an existing PostgreSQL user's password.

## Live browser demo and Swagger UI

After startup and seed, open:

- **Guided demo:** <http://localhost:8080/demo/index.html>
- **Swagger UI:** <http://localhost:8080/swagger-ui/index.html>
- **OpenAPI contract:** <http://localhost:8080/openapi.yaml>

Use your configured port if it differs from 8080. UI requests follow the current origin automatically. The pages and Swagger UI 5.34.1 assets are bundled in the executable JAR; no runtime CDN, separate frontend server or Node installation is required to run the application. Maven packages the canonical `docs/openapi.yaml` without a manually maintained duplicate.

For the presentation, click **Load demo data → Find a shared slot → Check both vehicles → Book vehicle A → Book vehicle B → Retrieve A → Load customer history**. The standard seed selects Oil Change and two vehicles from the same customer. Keep the requested time unchanged between A and B. Point to the actual 201/409 responses, assignment IDs, request ID and Location. Availability text is a last observation, not a reservation. This is a sequential stale-availability demonstration; concurrent service/database tests provide separate evidence.

The optional search samples 24 future times four hours apart within 96 hours, using at most 48 sequential availability GETs. Cancel is available. A failed search does not mean the full booking horizon is exhausted; enter an explicit-offset whole-minute timestamp to check another time.

Swagger groups the eight operations into Catalog, Availability and Appointments. Expand an operation, click **Try it out**, fill its inputs and click **Execute**. For POST, prepare a fresh slot in the guided demo, expand **Request JSON**, and copy the actual request into Swagger. Static example IDs/dates are illustrative. Executing POST creates a real appointment; the two pages do not synchronize their observation state.

The guided page blocks duplicate clicks while a request is pending and blocks replay of a successful or uncertain request in that page session. If the response is lost, inspect history before any deliberate retry. This is not server idempotency; refreshing the page clears its in-memory safeguards. All catalog discovery and history support pagination. No browser state is persisted to local storage.

The Python client remains a command-line fallback.

## Build, run and test

The backend, database schema, REST API and demo client are implemented. Milestone reports linked above record verification outcomes and known limits.

Versions: JDK 21, Spring Boot 4.1.2, PostgreSQL 18.1 and Maven Wrapper 3.9.11. Maven is downloaded by the checked-in wrapper. Docker Compose must support profiles and `up --wait`.

```sh
./mvnw test      # Unit tests
./mvnw verify    # Unit tests, packaged application and real PostgreSQL integration tests
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

The server defaults to port 8080. Check `/actuator/health/readiness` and `/actuator/health/liveness`. Startup applies the versioned Flyway schema; a missing migration location fails startup. The REST API is available under `/api/v1`. Stop the host Java process with Ctrl-C and use `docker compose down` to retain the named volume. Deleting the volume deletes local database data.

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

The REST controllers delegate to application services: `AvailabilityService.check` returns an advisory observation; `AvailabilityService.assess` also returns internal deterministic candidates for booking. `CatalogService` lists dealerships, services, customers and vehicles with pagination. These Spring-managed services are wired to the eight operations described by OpenAPI.

Availability evaluates the complete service interval, technician qualification/shift, bay, dealership opening window and vehicle conflicts. It writes no appointments and holds no resources. Business exceptions map to sanitized problem responses with HTTP status/code and request correlation. See the M3 report for tested rules and limitations.

## Transactional booking service

`BookingService.book(AvailabilityRequest)` locks the vehicle and dealership in that order, rechecks time and resources, inserts the complete appointment, and returns after commit. It uses an independent READ_COMMITTED/REQUIRES_NEW transaction: a surrounding caller transaction cannot subsequently undo the booking. Availability is advisory and never bypasses this recheck.

Default settings are `scheduler.booking.lock-timeout-ms=2000`, `scheduler.booking.statement-timeout-ms=3000` and `scheduler.booking.transaction-timeout-seconds=5`. Override these Spring properties when tuning the local environment. No automatic POST retry or idempotent replay is provided; an uncertain response after commit can be investigated through `GET /api/v1/appointments?customerId=...&vehicleId=...` before deciding whether to retry.

The full verification suite now includes 26 unit and 130 integration tests (156 total), including real HTTP/PostgreSQL checks, documentation-resource checks and concurrency checks observing actual database lock waits. Historical M7 results retain their 153-test total; the browser extension added three integration tests. See the milestone and browser reports for outcomes and limits.

## REST API and demo client

Python 3.11+ is required for the command-line demo and optional schema checks; the browser UI does not require Python. Start the application/database and run the seed as above, then:

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

Read-only examples for the default port:

```sh
curl -i http://localhost:8080/actuator/health/readiness
curl -i -H 'X-Request-ID: reviewer-catalog-1' \
  'http://localhost:8080/api/v1/dealerships?page=0&size=20'
curl -i http://localhost:8080/actuator/metrics/scheduler.booking.requests
```

For a reproducible POST example, use the demo client above: it discovers real customer/vehicle/dealership/service IDs and selects an available date. Static JSON examples in OpenAPI illustrate the shape and are not promises that those dates or records are bookable. Requests require `customerId`, `vehicleId`, `dealershipId`, `serviceTypeId` and an offset-bearing `startsAt`; the server assigns resources and computes `endsAt`.

Malformed input returns 400, unknown references 404, ownership/time/offering violations 422, unavailable capacity 409, transient database failures 503 and unexpected failures 500. Availability can return 200 with `available: false`; it never reserves resources. Catalogs/history use page 0 and size 20 by default, with a maximum size of 100.

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

I used AI to draft the design and implementation, review transaction behavior, and develop tests. Two concrete corrections came out of review: booking conflict mapping now uses PostgreSQL constraint diagnostics rather than parsing error text, and the recovery verifier rediscovers the published port after an application restart. Real PostgreSQL integration tests and the packaged recovery checks provide the evidence for those changes.


## Troubleshooting

| Symptom | Check / action |
|---|---|
| Build reports an incompatible Java release | Point `JAVA_HOME` to JDK 21 and confirm `./mvnw -version`. The system Maven installation is not used. |
| Docker/Testcontainers cannot connect | Start the Docker daemon; verify `docker version`. For Colima, export the two variables above. Integrations intentionally fail without PostgreSQL. |
| First build or image pull fails offline | Allow dependency/image downloads, then retry. A cached build is not proof of an offline first installation. |
| Port 8080 or 5433 is occupied | Stop the conflicting local service, or set `APP_PORT` / `POSTGRES_PORT` in `.env`. Pass the matching `--base-url` to the demo. Host Java uses `SERVER_PORT`, not `APP_PORT`. |
| Demo sees empty catalogs | Wait for app readiness, then run `./scripts/demo/seed.sh` from this repository. Startup never inserts demo data. |
| Demo finds no future slot | Re-run seed to extend the calendar. The demo assumes the standard synthetic data; Sundays and lunch breaks are closed. |
| Database credentials changed but login still fails | Existing volumes retain the initialized PostgreSQL credentials. Restore the matching local configuration or deliberately administer that database; do not delete a shared volume. |
| POST times out and outcome is unknown | Query appointment history for the customer and vehicle; do not automatically retry a potentially committed POST. |
| Readiness is 503 | Inspect `docker compose --profile app ps` and app/database logs. Liveness can remain 200 during database failure. See the operations runbook. |

## Scope and remaining work

Fixed service durations, one bay plus one qualified technician, whole-minute start times, a 30-day elapsed booking horizon and read-only reference data are explicit assumptions. Bay/technician/vehicle overlaps are protected in PostgreSQL; per-dealership locking intentionally favors a simple correctness argument over peak write throughput.

Authentication/authorization, cancellation/rescheduling, administration, idempotent POST replay, external tracing, load benchmarks, backup restoration and production failover are deferred. A customer ID is not authorization. The local API and Actuator endpoints must not be exposed publicly as a production service. A full customer frontend remains out of scope; Swagger UI, the guided browser page and the Python client provide demo harnesses for the selected backend layer.
