# Local Packaging and Operations

This runbook covers the M6 local assessment environment. Authentication, production secrets, backups, tracing export and performance benchmarking are not supplied by this Compose setup.

## Build and run the packaged application

Prerequisites: JDK 21, Docker/Compose, Python 3.11+ for the verification/demo scripts, and network access on the first dependency/image download. Configure JAVA_HOME and the Colima environment as described in the README when applicable.

Create `.env` from `.env.example` once and choose a local password. Preserve an existing `.env`.

```sh
./mvnw clean verify
docker compose --profile app up --build -d --wait
./scripts/demo/seed.sh
python3 scripts/demo/client.py
```

Dockerfile consumes the executable JAR produced by Maven; it does not run Maven inside Docker. Rebuild the JAR before rebuilding the image after source changes. The runtime base is pinned by digest, runs Java 21 as UID 10001, and receives database configuration at container startup. Docker build context includes only the JAR and health-check script, excluding local environment files and source/test data.

The default Compose profile starts PostgreSQL only for host-run Java development. The `app` profile adds the packaged application. Published ports bind to localhost: APP_PORT defaults to 8080 and POSTGRES_PORT to 5433. The app connects to PostgreSQL through the Compose network at postgres:5432, not the published host port.

```sh
docker compose --profile app ps
docker compose --profile app logs -f app
```

The application's Docker healthcheck uses Bash to query readiness, avoiding an extra curl package in the runtime image. Unhealthy readiness does not automatically restart the application. Dependencies gate initial startup; database recovery is handled by the running application's pool.

## Health and metrics

```sh
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness
curl -s http://localhost:8080/actuator/metrics
curl -s http://localhost:8080/actuator/metrics/scheduler.booking.requests
curl -s http://localhost:8080/actuator/metrics/scheduler.booking.duration
curl -s http://localhost:8080/actuator/metrics/scheduler.booking.lock.wait
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.max
```

Booking metrics are lazy: make a booking attempt before querying a meter that does not exist yet. Only health and metrics Actuator endpoints are exposed; environment/configuration endpoints remain unavailable.

| Signal | Meaning |
|---|---|
| liveness | Process can serve requests; independent of database health |
| readiness | Includes database connectivity; goes DOWN/503 during outage |
| scheduler.booking.requests | BookingService invocations, with bounded outcome and reason tags |
| scheduler.booking.duration | Total service execution time by success/rejected/error outcome |
| scheduler.booking.lock.wait | Lock-acquisition SQL attempt duration for vehicle/dealership, including query/network overhead |
| hikaricp.* | Connection pool capacity/use signals |
| http.server.requests | Framework HTTP request metrics; includes requests rejected before BookingService |

Booking counters are not an exact count of every HTTP POST: DTO/UUID failures before service entry belong to HTTP logs/metrics. No metric uses customer, vehicle, appointment or request IDs as labels. In contrast, request IDs and successful appointment IDs may appear in logs for correlation.

Connection acquisition is bounded at 2 seconds, JDBC connect at 2 seconds and socket reads at 5 seconds. Booking defaults remain lock timeout 2 seconds, statement timeout 3 seconds and transaction timeout 5 seconds. These are different budgets, not a universal end-to-end HTTP deadline.

## Structured logs

Spring Boot emits Logstash JSON. Application events include:

- `booking_outcome`: success/rejected/error, bounded reason, elapsedMs, requestId when invoked over HTTP, and appointmentId only on success. This event follows transaction completion.
- `request_complete`: route template, response status, elapsed time and requestId. Query strings and payloads are not logged; MDC is cleared even when downstream processing throws.
- Sanitized API failure diagnostics: error classification without raw application SQL messages or customer payloads.

Expected conflicts are business rejections rather than infrastructure errors. Investigate sustained errors, readiness failures, pool saturation or increasing acquisition durations separately. Metric/JSON outputs are provided; no external alerting backend or trace exporter is configured.

## Restart and persistence

```sh
docker compose --profile app restart app
docker compose --profile app stop postgres
docker compose --profile app start postgres
```

Appointments should remain readable after restart/recovery. While the database is stopped, liveness remains UP, readiness becomes DOWN and API reads/writes fail with controlled 503 responses. A failed response after a potentially completed commit remains ambiguous; use appointment history before retrying POST. Do not automatically retry booking requests.

A normally configured fixed APP_PORT remains straightforward for manual use. When using APP_PORT=0 for disposable tests, Docker may allocate a different published port on restart; rediscover it with `docker compose --profile app port app 8080`.

Stop the environment while keeping the database volume:

```sh
docker compose --profile app down
```

PostgreSQL 18 stores data under the mounted `/var/lib/postgresql` volume. Deleting that volume deletes the database. No general reset utility is supplied. The app has a 10-second Spring graceful-shutdown phase and Compose allows 20 seconds before force termination. Idle graceful completion was observed; a loaded in-flight shutdown drain test is not claimed.

## Reproduce automated operational verification

After building the JAR with `./mvnw clean verify`:

```sh
python3 scripts/validation/verify_operations.py
```

This script fixes the repository's Compose file, generates a new isolated project/image name and password, and uses ephemeral localhost ports. It builds the runtime image, applies migrations, runs the explicit seed and demo, tests restart/outage/recovery, injects a deferred database failure to test rollback, inspects metrics/logs and records runtime identity. It checks the running app's identity across database recovery to ensure recovery did not rely on an application restart.

The script deletes only its own generated containers and dedicated volume in finally. It retains the verification image as build cache and never resets an existing developer project. Generated evidence is written to:

- target/m6-operations-report.json
- target/m6-operations-report.app.log

Reports and build outputs are ignored by Git. The script requires Docker access and may need first-run image downloads. It does not require Python packages beyond the standard library and reuses the demo client. Optional OpenAPI capture validation uses the separate dependencies documented in the README.

## Remaining limits

The successful local checks do not establish backup restoration, production failover, arbitrary network-partition behavior, loaded shutdown draining, tracing, deployment security or measured throughput. P1 workload measurement remains deferred. See [M6 results](m6-results.md) for exact executed checks and evidence.
