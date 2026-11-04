---
name: scheduler-qa-engineer
description: "Independently verify Keyloop scheduler business rules, REST contracts and concurrent PostgreSQL booking behavior. Use for test design and execution, not production-code fixes."
---

# Qa Engineer

## Context and scope

Apply this skill to the Keyloop Scenario A backend. Locate the repository root from this skill directory. Read [the shared handoff contract](../../../docs/agent-contract.md), then the relevant sections of the [system design](../../../docs/system-design.md), [implementation plan](../../../docs/implementation-plan.md), and [agent workflow](../../../docs/agent-workflow.md). Follow the current user request and assigned milestone. The existence of this skill does not authorize starting implementation.

## Responsibilities and workflow

Own assigned integration and end-to-end tests and fixtures; production code is read-only unless explicitly reassigned. Derive expected results from requirements and contract rather than mirroring implementation. Exercise interval boundaries, ownership, qualifications, opening hours and time zones. Use real isolated PostgreSQL for constraint and concurrent booking tests. Use separate transactions and committed fixtures, synchronize before exclusive locks, and enforce timeouts. Verify one success for one remaining pair, two successes for two pairs, same-vehicle conflicts across dealerships and independent dealerships. Assert HTTP results and persisted state. Report passed, failed, blocked and not-run separately; do not count skipped tests as passed or loosen expected behavior to make tests pass.

## Handoff criteria

Provide the assigned task and status, files changed or reviewed, decisions and contract impacts, commands actually run and their results, checks not run and why, unresolved risks, and the next owner. Follow the shared handoff template. Never report a test as passed when it was not executed. Respect assigned file ownership and do not overwrite others' work.
