# M5 Results — REST API, HTTP Contract and Demo Client

**Date:** 2026-11-03  
**Status:** Completed. M6 has not started.

## Roles and scope

- Lead used `scheduler-lead` to coordinate ownership, settle protocol-error contract details, implement the demo/response validator, run integrated verification and update documentation.
- Backend Engineer used `scheduler-backend-engineer` to add REST controllers, separate API DTOs, appointment retrieval/history queries, problem responses and request correlation.
- QA Engineer used `scheduler-qa-engineer` to independently implement HttpContractIT using a real HTTP server and PostgreSQL. QA also inspected final reports and revalidated captured responses.
- Reviewer used `scheduler-reviewer` to inspect implementation, HTTP tests, demo and validation tool read-only. No blocking finding remained after corrections.

M4 transaction behavior and schema are preserved. M6 operational hardening, broader outage/recovery demonstrations, full structured observability and optional performance measurement remain pending.

## Implemented API

Base path: `/api/v1`.

| Method | Path | Behavior |
|---|---|---|
| GET | /dealerships | Active dealerships, stable pagination |
| GET | /dealerships/{id}/services | Active offered services; parent validation |
| GET | /customers | Synthetic demo customer discovery |
| GET | /customers/{id}/vehicles | Vehicles for a known customer |
| GET | /availability | Advisory full-interval availability and reason |
| POST | /appointments | Committed appointment, 201 and Location |
| GET | /appointments/{id} | Retrieve a persisted appointment |
| GET | /appointments | Customer-required, optional vehicle-filtered history |

History orders by startsAt ascending and then ID, supports page/size with totalElements, validates parent existence, and returns ownership mismatch 422 as agreed. It provides recovery when a client is unsure whether a POST committed. No automatic POST retry or idempotency-key replay was added.

API DTOs are separate from persistence records. UUID inputs must have canonical formatting. Timestamp outputs are explicit UTC strings ending in Z. Unknown booking fields, including endsAt and assigned resource IDs, are rejected. Required fields, malformed UUID/JSON and timestamp syntax return 400, while valid timestamps violating time policy return 422.

Errors use application/problem+json with type, title, status, detail, instance, code and requestId; field-validation errors may include errors. Unexpected failures are sanitized, and database transient errors remain 503 rather than false capacity conflicts.

The request-ID filter reuses only valid 1–128-character ASCII tokens, otherwise generates a UUID, returns X-Request-ID on success/errors and clears its MDC entry in finally. Filter cleanup was inspected in review; this milestone does not claim a dedicated MDC thread-reuse log-capture test or completed M6 log formatting.

## Contract alignment

OpenAPI is version 0.2.0. The existing business statuses remain unchanged. Standard 405 (unsupported method), 406 (unacceptable response type) and 415 (unsupported request media type) use INVALID_REQUEST problem responses. A 405 response includes Allow. The Problem.status enum and default protocol-error responses were extended accordingly, avoiding masking these errors as 500. Unknown routes return a 404 problem.

These protocol decisions were resolved by Lead and recorded in the SDD. They do not expand booking scope or alter transactional allocation.

## Integrated Java verification

Environment: JDK 21.0.8, Maven Wrapper 3.9.11, Spring Boot 4.1.2, PostgreSQL 18.1 and Docker 28.4.0 through Colima.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
DOCKER_HOST=unix:///Users/congnguyenngocchien/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./mvnw -B -ntp -o -Dmaven.repo.local=/tmp/keyloop-maven-repository verify
```

**Result: BUILD SUCCESS.**

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| ClockConfigurationTest | 2 | 0 | 0 | 0 |
| BookingTimePolicyTest | 22 | 0 | 0 | 0 |
| PostgresConnectionIT | 1 | 0 | 0 | 0 |
| SchemaConstraintsIT | 38 | 0 | 0 | 0 |
| AvailabilityRulesIT | 37 | 0 | 0 | 0 |
| BookingTransactionIT | 21 | 0 | 0 | 0 |
| HttpContractIT | 27 | 0 | 0 | 0 |
| Total | 148 | 0 | 0 | 0 |

This is 24 unit tests and 124 integration tests, including all earlier milestone regressions. HttpContractIT uses Java HttpClient against a random-port server and real isolated PostgreSQL, not MockMvc or a mocked database. It covers all eight operations, UTC responses, history/filtering, pagination/parents, strict write input, reference/time validation, stale availability conflicts, correlation and protocol errors.

HTTP failure tests induce an actual database row-lock timeout for 503 and a deferred commit failure for sanitized 500 with no persisted row. These are targeted M5 error-contract checks; comprehensive operational outages/recovery remain M6 work.

Evidence: target/surefire-reports, target/failsafe-reports, target/m5-http-contract-samples.json and this session's /tmp/keyloop-m5-verify.log. Generated artifacts are ignored by Git. QA independently inspected the final reports; no extra runtime rerun is claimed during that inspection.

## Actual response-schema verification

The reusable tool `scripts/validation/validate_http_contract.py` validates OpenAPI itself, dereferences local acyclic schema references, and checks actual response bodies, media types, documented headers, formats, HTTP/body status consistency and request correlation. It requires successful samples for all eight operations and rejects undocumented successful statuses.

Executed:

```sh
PYTHONPATH=/tmp/keyloop-openapi-validation python3 scripts/validation/validate_http_contract.py target/m5-http-contract-samples.json
```

Result: **74 operation responses and 2 routing/method-error responses validated**; all eight operations had successful responses. Routing/unsupported-method samples have no defined operation and were checked against the shared Problem schema, rather than counted as operation coverage.

Additional negative checks corrupted copies of captured responses: mismatched request correlation, undocumented success status and invalid UUID format were all rejected. An initial temporary negative-check harness assumed uppercase method labels; that harness was corrected to normalize case before the successful negative checks. This was not an application or validator defect.

The optional Python dependency is pinned in scripts/validation/requirements.txt. Standard Maven verification does not install or execute Python dependencies; the README gives the explicit extra contract-check command.

## Packaged application and actual demo

An isolated Compose project, scheduler-m5-demo-check, started PostgreSQL 18.1 on an ephemeral localhost port. The actual executable JAR started on a separate localhost port and applied Flyway normally. The explicit seed runner populated the fresh database, then the actual client ran:

```sh
python3 scripts/demo/client.py --base-url http://127.0.0.1:<temporary-port>/api/v1 --output /tmp/keyloop-m5-demo-samples.json
```

The client discovered seed IDs through the API, selected a future open slot in the dealership timezone, and demonstrated:

1. Customer, vehicle, dealership and service discovery.
2. Two different vehicles both observing availability.
3. First POST returning 201 and Location; GET returning the same persisted appointment.
4. Second POST returning 409 NO_AVAILABLE_TECHNICIAN after the first consumed the seed's single qualified oil-change technician.
5. Customer/vehicle history recovering the committed appointment.

All **10 demo responses** were separately schema-validated, covering successful responses for all eight operations. Demo output selected 2026-11-04T08:00:00+07:00 dynamically; this is an observed result, not a hard-coded script date.

The script assumes the documented standard seed, creates one real appointment per successful run, searches another available slot on reruns, does not delete data, and never automatically retries an uncertain POST. The temporary verification application, containers and dedicated test volume were cleaned up after execution; no shared development volume was deleted.

Demo evidence for this session: /tmp/keyloop-m5-demo-app.log and /tmp/keyloop-m5-demo-samples.json. The script and README are the reusable deliverables; temporary captures are not required for a fresh checkout.

## Actual collaboration corrections

- Reviewer identified canonical UUID parsing as a boundary concern; Backend added a strict format check for path/query/body IDs and QA covered noncanonical forms.
- Backend proposed preserving standard 405/406/415 behavior; Lead resolved the OpenAPI alignment and QA verified it over HTTP.
- Reviewer found that the initial response checker skipped body/status/correlation consistency in its unmatched-operation branch. Lead moved those checks to the common path and added explicit rejection of undocumented successes. The final checker passed real captures and rejected corrupted copies.
- Lead tested the integrated application and packaged demo, rather than treating agent implementation reports as verification evidence. QA independently checked counts and response-schema results.

## Handoff to M6

All M5 checklist items are satisfied. REST API, error/correlation contract, appointment history and dynamic demo client are usable according to the README. M6 has not started. Deployment packaging, operational health/outage/recovery verification, complete structured logs/metrics and optional workload measurements still require their planned work; M5 does not claim production readiness.

Platform references consulted: [Spring MVC problem responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html), [Spring Boot JSON support](https://docs.spring.io/spring-boot/reference/features/json.html) and [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html). Actual application claims above are supported by executed tests and captured responses.
