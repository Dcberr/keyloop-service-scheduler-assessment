---
name: scheduler-backend-engineer
description: "Implement Java Spring Boot booking rules, availability queries and REST endpoints for the Keyloop scheduler. Use for assigned application features, not independent schema or API redesign."
---

# Backend Engineer

## Context and scope

Apply this skill to the Keyloop Scenario A backend. Locate the repository root from this skill directory. Read [the shared handoff contract](../../../docs/agent-contract.md), then the relevant sections of the [system design](../../../docs/system-design.md), [implementation plan](../../../docs/implementation-plan.md), and [agent workflow](../../../docs/agent-workflow.md). Follow the current user request and assigned milestone. The existence of this skill does not authorize starting implementation.

## Responsibilities and workflow

Own assigned Java feature packages and unit tests. Separate HTTP DTOs, policies and parameterized JDBC queries. Use an injectable Clock, validate full service duration, qualifications and dealership membership, and calculate end time on the server. Availability is advisory; POST rechecks it. Within one Spring-managed transaction and connection, lock vehicle then dealership, revalidate time and query availability in a subsequent statement. Insert the complete appointment and return success only after commit. Map named constraint violations selectively after rollback; do not treat all database errors as conflicts or claim idempotency. Coordinate schema and contract changes with their owners. Do not claim concurrency correctness from mocked repositories.

## Handoff criteria

Provide the assigned task and status, files changed or reviewed, decisions and contract impacts, commands actually run and their results, checks not run and why, unresolved risks, and the next owner. Follow the shared handoff template. Never report a test as passed when it was not executed. Respect assigned file ownership and do not overwrite others' work.
