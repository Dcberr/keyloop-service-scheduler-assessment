# M3 Results — Business Rules and Availability

**Date:** 2026-11-02  
**Status:** Completed. M4 has not started.  
**Scope:** Read-only application/domain services and PostgreSQL queries. HTTP controllers remain M5 work; appointment writes and transactional allocation remain M4 work.

## Roles and ownership

- Lead applied `scheduler-lead`, established scope, coordinated public Java signatures and independent work, executed integrated verification, and updated documentation.
- Backend Engineer applied `scheduler-backend-engineer` and owned new production Java under shared, availability and catalog packages.
- QA Engineer applied `scheduler-qa-engineer` and independently owned new policy/unit and PostgreSQL integration tests. Expected behavior came from SDD assumptions and acceptance criteria, not copied production algorithms.
- Reviewer applied `scheduler-reviewer`, inspected final production code and tests read-only, identified a timestamp-precision issue and confirmed its correction. No blocking finding remained.

No migration, seed, application configuration or dependency changes were needed for M3.

## Implementation

### Shared policies

`BookingTimePolicy` parses explicit RFC 3339 timestamps, distinguishes malformed syntax/calendar values from policy violations, and calculates elapsed service duration. It enforces whole-minute precision, start strictly after the injected Clock's current instant and an inclusive upper limit of 30 elapsed days. `ServiceInterval` models half-open overlap behavior. `BusinessException` carries a stable status/code for later API mapping; these are not currently HTTP responses. `Page` validates pagination and uses a long offset to avoid integer multiplication overflow.

### Availability

`AvailabilityService.check(AvailabilityRequest)` validates entity IDs, customer/vehicle ownership, active dealership/service and service offering. It returns UTC instants, calculated duration/end, dealership timezone, checkedAt, availability and reason. One Clock instant is captured for the assessment.

`AvailabilityRepository.referenceData` reads immutable baseline reference data. A subsequent **single SQL statement** evaluates all changing resource conditions together:

- One dealership opening window must contain the full interval.
- The vehicle must have no overlap, including appointments at other dealerships.
- An active same-dealership bay must have no overlap.
- An active same-dealership technician must be qualified, have one shift containing the full interval, and have no overlap.

The query uses parameterized JDBC and PostgreSQL range overlap predicates; it does not load all appointment records into Java. The reference lookup and resource statement are separate: consistency of reference data relies on the documented immutable-reference baseline. All capacity decisions share the final statement snapshot.

When multiple resource checks fail, reasons are ordered:

1. OUTSIDE_OPENING_HOURS
2. VEHICLE_UNAVAILABLE
3. NO_AVAILABLE_BAY
4. NO_AVAILABLE_TECHNICIAN

Public availability contains no resource assignments and does not reserve anything. `assess` additionally exposes deterministic lowest-ID candidates in an internal assessment for later booking reuse. M4 must re-run assessment after acquiring its transaction locks; internal candidate IDs are never an allocation guarantee.

### Catalog

`CatalogService` delegates SQL to `CatalogRepository` for dealerships, offered active services, customers and customer vehicles. Lists are ID-ordered, paginated and include totalElements even for an empty/out-of-range page. Unknown parents and inactive dealerships follow the M0 contract at service level.

## Executed verification

Environment: JDK 21.0.8, Maven Wrapper 3.9.11, Spring Boot 4.1.2, PostgreSQL 18.1 and Docker 28.4.0 through Colima. Cached dependencies and images were used with Maven offline mode. Tests create isolated real PostgreSQL databases; no developer database was mutated.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
DOCKER_HOST=unix:///Users/congnguyenngocchien/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./mvnw -B -ntp -o -Dmaven.repo.local=/tmp/keyloop-maven-repository verify
```

**Result: BUILD SUCCESS.** QA independently inspected the final XML reports and build log.

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| ClockConfigurationTest | 2 | 0 | 0 | 0 |
| BookingTimePolicyTest | 22 | 0 | 0 | 0 |
| PostgresConnectionIT | 1 | 0 | 0 | 0 |
| SchemaConstraintsIT | 38 | 0 | 0 | 0 |
| AvailabilityRulesIT | 37 | 0 | 0 | 0 |
| Total | 100 | 0 | 0 | 0 |

This is 24 unit tests and 76 integration tests, including regression coverage for M1/M2. Reports are generated under `target/surefire-reports` and `target/failsafe-reports`, which are ignored build outputs. This session's full log is `/tmp/keyloop-m3-verify.log`. Users can run normal `./mvnw verify`; the temporary repository and offline flag above are environment-specific.

### Verified M3 behavior

- Malformed/offsetless timestamps, invalid calendars and invalid offsets are rejected as INVALID_REQUEST/400 at service boundary.
- Past/equal-now starts, starts beyond the horizon and non-whole-minute instants are rejected as INVALID_BOOKING_TIME/422; exact 30-day boundary is accepted.
- Equivalent offsets resolve to the same instant; elapsed duration and half-open overlap/adjacency are correct.
- Missing references, ownership mismatch, inactive dealership/service and services not offered are rejected with agreed service status/code values.
- Full opening-window and shift containment is enforced, including exact end boundaries; separate windows across a break are not joined.
- Busy resources, inactive resources, technicians lacking qualification and resources at another dealership do not satisfy availability.
- Global vehicle conflicts and all four unavailable-reason priorities are respected.
- Repeated checks leave appointment counts unchanged; later committed resource occupation is observed by the next check.
- Internal candidates are deterministic; public results do not promise assignments.
- Catalog filtering, stable pagination, empty pages and parent validation work on PostgreSQL.

This covers M3 service-level portions of T02–T15 and T26. T16 HTTP unknown-field rejection, HTTP status/serialization behavior, booking persistence and concurrent allocation are not claimed complete. The single-statement resource snapshot is supported by code review; no concurrent-booking throughput or linearizability result is claimed.

## Actual AI collaboration finding

Reviewer found that the initial parser accepted an RFC-shaped fraction of arbitrary length in its syntax check but Java's OffsetDateTime parser rejected fractions longer than nine digits as malformed input. That would return 400 for a syntactically valid timestamp whose nonzero fractional precision should instead produce 422.

Backend normalized long fractions for calendar/offset parsing, retained malformed-calendar 400, rejected nonzero excess precision as 422, and accepted arbitrary all-zero fractions as whole-minute values. QA independently added four regression cases for long fractions and malformed calendars. Final integrated verification passed; no failed application test run is claimed for the earlier code because the issue was corrected before execution.

Catalog SQL was also kept in a dedicated repository to preserve the documented service/data-access boundary.

Technical references consulted during implementation review: [Java 21 DateTimeFormatter](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/format/DateTimeFormatter.html) and [PostgreSQL 18 range operators](https://www.postgresql.org/docs/18/functions-range.html). These explain platform behavior, not verification results for this application.

## Handoff to M4

M3 acceptance gates are met. The next milestone can use shared policies and availability assessment inside the planned vehicle-then-dealership booking transaction. Do not trust a previous advisory availability result. Revalidate time and resources after lock acquisition, insert the complete appointment atomically and verify competing requests on real PostgreSQL. None of that write/locking behavior was implemented in M3.
