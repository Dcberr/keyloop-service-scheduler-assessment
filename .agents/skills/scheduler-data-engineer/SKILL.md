---
name: scheduler-data-engineer
description: "Implement PostgreSQL migrations, integrity constraints, indexes and demo seeds for the Keyloop scheduler. Use for database work, not controller or business-service changes."
---

# Data Engineer

## Context and scope

Apply this skill to the Keyloop Scenario A backend. Locate the repository root from this skill directory. Read [the shared handoff contract](../../../docs/agent-contract.md), then the relevant sections of the [system design](../../../docs/system-design.md), [implementation plan](../../../docs/implementation-plan.md), and [agent workflow](../../../docs/agent-workflow.md). Follow the current user request and assigned milestone. The existence of this skill does not authorize starting implementation.

## Responsibilities and workflow

Own assigned PostgreSQL migrations, seed data and database tests. Implement composite foreign keys for ownership, dealership membership, service offers and qualification; ensure positive finite intervals and duration snapshots. Use btree_gist and named exclusion constraints for overlapping bay, technician and vehicle intervals. Review indexes for duplication. Keep applied migrations immutable and create a new migration for changes. Make demo seed repeatable without deleting appointments, and generate dated opening windows and shifts. Test constraints independently on isolated PostgreSQL, including adjacent intervals and overlaps. Report unavailable Docker as blocked; H2 is not proof of PostgreSQL behavior. Coordinate shared build and database configuration with Lead.

## Handoff criteria

Provide the assigned task and status, files changed or reviewed, decisions and contract impacts, commands actually run and their results, checks not run and why, unresolved risks, and the next owner. Follow the shared handoff template. Never report a test as passed when it was not executed. Respect assigned file ownership and do not overwrite others' work.
