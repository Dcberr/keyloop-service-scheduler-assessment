# M0 Independent Acceptance Review

**Date:** 2026-11-01  
**Role:** QA Engineer, using `scheduler-qa-engineer`  
**Scope:** Review design requirements, assumptions and acceptance criteria only. No application or database tests have been executed.  
**Inputs:** [System Design](system-design.md), [Implementation Plan](implementation-plan.md), [Handoff Contract](agent-contract.md).

## 1. Requirement traceability

| Requirement | Planned artifact / behavior | Verification planned | Milestones |
|---|---|---|---|
| R1 | POST identifies customer, vehicle, dealership, service and desired start | T01, T10–T17; request/response contract | M0, M3–M5 |
| R2 | Full-interval bay and qualified technician availability; atomic allocation | T02–T09, T18–T25, T31 | M2–M5 |
| R3 | One persisted confirmed appointment with customer, vehicle and both resource assignments | T01, T17–T19, T25, T27 | M2, M4–M6 |
| R4 | REST API, PostgreSQL persistence, client represented by OpenAPI/demo requests | Contract review now; T26–T28 and demo later | M0, M1, M5–M6 |
| R5 | Architecture, roles, data flow, technology rationale and observability | SDD sections 4–10; T30 later | M0, M6–M7 |
| R6 | Truthful design AI narrative and implementation evidence | SDD section 13; actual briefs, review findings and executed checks | M0–M7 |
| R7 | Runnable repository, README instructions and core tests | Clean setup and full P0 verification; no implementation claimed at M0 | M1–M7 |
| R8 | 5–10 minute video with 1–2 minute AI story | Review finished recording against the eight-minute outline | M7 |

R1–R3 are the scenario's business requirements. Resource concurrency, ownership and calendars strengthen the proposed implementation but must remain identified as design choices rather than additional explicit assessment requirements.

## 2. Assumption review

| Assumption | Review outcome / acceptance implication | Planned evidence |
|---|---|---|
| A1 | Exactly one bay and technician remain assigned for the full interval | T01, T08–T09 |
| A2 | Duration is positive, server-owned and snapshotted; no client end-time override | T16–T17, T19 |
| A3 | Every active bay supports every offered service; separate bay/technician candidate selection is valid under this assumption | T01–T05; no equipment matrix introduced |
| A4 | Technician dealership and qualification must both match | T04–T05, T19 |
| A5 | Assignment uses stable ID order; fairness is explicitly deferred | Add deterministic candidate-order assertions within T01 |
| A6 | Ownership is checked and reference data is read-only during operation | T10, T19; inspect absence of administrative write APIs |
| A7 | Vehicle conflict applies globally, including between dealerships | T18, T22 |
| A8 | Half-open intervals allow adjacency and reject all overlap shapes | T06–T07 |
| A9 | Entire appointment fits inside one opening window and one shift; no joining across breaks | T08–T09 |
| A10 | Start is strictly after now and at most now + 30 elapsed days; recheck after lock wait | T13, T31 |
| A11 | Explicit offset/Z is mandatory; nonzero seconds or fractions violate minute precision | T14; distinguish malformed syntax from valid-but-disallowed precision |
| A12 | Equivalent offsets identify the same instant; output is UTC plus dealership timezone | T15 and HTTP response assertions |
| A13 | Dated absolute windows avoid a recurrence engine; duration uses elapsed time | T09, T15; fixture-based instant arithmetic |
| A14 | Missing opening window means closed, missing shift means unavailable; seed covers at least 45 dates | T09, T29; inspect coverage horizon |
| A15 | Only confirmed appointments persist; failed requests create no partial reservation | T02–T03, T20, T25 |
| A16 | Local synthetic demo has no authentication; customer ID is not authorization | Contract and deployment documentation review; no production-security claim |

No assumption is inherently incompatible with the backend assessment scope. Deferred administration must not silently invalidate the immutable-reference-data assumptions.

## 3. Explicit acceptance scenarios

Use a fixed Clock and seeded IDs when implementing these cases. A representative service lasts 60 minutes; the requested interval is 09:00–10:00 on a future open date. All unrelated validation must pass in each negative fixture.

| Scenario | Given / when | Expected API and persistence outcome |
|---|---|---|
| Successful booking | Correct owner, offered service, free active bay and qualified active technician on shift for 09:00–10:00 | Availability returns 200/true; POST returns 201 with Location; exactly one CONFIRMED appointment contains all correct IDs, start/end and duration snapshot; GET retrieves it |
| Bay exhausted | No eligible bay is free for the whole interval | Availability returns 200/false with NO_AVAILABLE_BAY; POST returns 409 with the same code; no new appointment |
| Technician exhausted | Bay is free but every qualified on-shift technician is busy | Availability returns 200/false with NO_AVAILABLE_TECHNICIAN; POST returns 409; no new appointment |
| Incorrect qualification | A free technician lacks the requested service qualification and no eligible alternative exists | Same unavailable-technician outcome; the unqualified technician is never allocated |
| Outside opening hours | A 60-minute request starts 30 minutes before closing | Availability returns 200/false with OUTSIDE_OPENING_HOURS; POST returns 409; no new appointment |
| Shift boundary/break | Dealership is open, but no single technician shift contains the entire interval | Availability returns 200/false with NO_AVAILABLE_TECHNICIAN; POST returns 409; separate shifts are not combined |
| One remaining pair | Two different vehicles request the same interval concurrently | Exactly one POST returns 201 and the other 409; exactly one new appointment; no overlap on either resource |
| Two remaining pairs | Two different vehicles request the same interval concurrently with two eligible pairs | Both return 201; two appointments with no overlapping allocation of the same bay or technician |
| Same vehicle across dealers | Two concurrent requests for the same vehicle overlap at different dealerships | Exactly one succeeds; the loser is a vehicle conflict; vehicle has no overlapping appointments |
| Stale availability | Client A receives availability true, then client B consumes the last pair before A posts | A's POST rechecks and returns a conflict; earlier GET was not a reservation |
| Adjacent intervals | Existing appointment ends exactly at requested start | Allocation is allowed if all other conditions hold |
| Multiple capacity failures | Opening window, vehicle, bay and technician checks could all fail | Report the first applicable reason in order: opening window, vehicle, bay, technician |

Infrastructure failures remain distinct: lock/statement timeout, deadlock or unavailable database yields 503; unexpected integrity failure yields 500. Neither is disguised as a capacity conflict. No success event is emitted before commit.

## 4. Contract clarifications identified during independent review

These were sent to the Lead and Architect and resolved by the Lead during M0; the QA agent does not modify their files.

1. **History ownership:** define the result of a valid customerId combined with an existing vehicleId owned by someone else. Accepted: 422 VEHICLE_CUSTOMER_MISMATCH, consistent with booking validation; a nonexistent vehicle returns 404.
2. **Inactive dealership catalog:** define GET /dealerships/{id}/services for an existing inactive dealership. Accepted: 422 SERVICE_NOT_BOOKABLE; a missing dealership returns 404.
3. **Timestamp validation:** retain 400 for missing offset/malformed timestamps and 422 for a syntactically valid timestamp with nonzero seconds/fractions. OpenAPI patterns must not inadvertently collapse both into 400.

The existing design already defines resource-failure priority, strict future start, inclusive upper booking horizon, unknown write-field rejection and stable catalog/history ordering. These should be preserved in the finalized OpenAPI.

## 5. Verification status and handoff

- **Completed:** Read-only review of R1–R8, A1–A16 and planned T01–T32; independent expected outcomes and traceability documented above.
- **Executed checks:** Read skill, shared contract, SDD, implementation plan and workflow from repository files; compared requirements and acceptance tables manually.
- **Not run:** Application/unit/integration/concurrency tests, migrations, performance checks and persistence demonstration. Application infrastructure does not yet exist; these remain planned for M1–M7.
- **Final contract review:** Architect's OpenAPI includes all eight operations, required booking fields, unknown-write-field rejection, advisory availability, 201/Location after commit, documented conflict/error codes and the three accepted clarifications. The initial QA pass missed the endpoint-specific example mismatch described below; the corrected history response has now been rechecked.
- **Checks on final contract:** `PYTHONPATH=/tmp/keyloop-openapi-validation python3 -m openapi_spec_validator docs/openapi.yaml` returned `OK`. A Python check using PyYAML and JSON Schema Draft 2020-12 with format checking validated all 45 embedded request/response examples. The example validator emitted a RefResolver deprecation warning, not a validation failure.
- **Independent Reviewer finding and correction:** GET `/appointments` initially reused a booking-time 422 response/example despite history only accepting an ownership-mismatch 422. The initial 45-example schema validation passed because schema validity does not establish endpoint semantics. Architect replaced this with a dedicated VEHICLE_CUSTOMER_MISMATCH response. QA re-read the affected response and checked its description, code, status, instance and detail against history semantics, then validated that corrected example against its schema: passed. No further blocking discrepancy remains from this finding.
- **Next owner:** Lead reviews this evidence and decides whether all M0 acceptance gates are met.

This completes the QA design-review brief; it does not independently declare M0 or the application complete.
