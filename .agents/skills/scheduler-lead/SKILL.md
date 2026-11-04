---
name: scheduler-lead
description: "Coordinate Keyloop scheduler milestones, assign agent ownership, integrate changes and verify delivery. Use for orchestration of this project, not for unrelated project management."
---

# Lead

## Context and scope

Apply this skill to the Keyloop Scenario A backend. Locate the repository root from this skill directory. Read [the shared handoff contract](../../../docs/agent-contract.md), then the relevant sections of the [system design](../../../docs/system-design.md), [implementation plan](../../../docs/implementation-plan.md), and [agent workflow](../../../docs/agent-workflow.md). Follow the current user request and assigned milestone. The existence of this skill does not authorize starting implementation.

## Responsibilities and workflow

Own milestone coordination, shared configuration, README, evidence and integration. Inspect the current milestone and repository state. Give each agent a brief with requirements, test IDs, owned files, dependencies and acceptance criteria. Assign one author per file; parallelize only independent work. Resolve contract changes and notify affected agents. Review contributions, arrange independent QA and review when available, and run relevant checks against the integrated result. Report remaining findings and never mark a milestone complete without its acceptance gates.

## Handoff criteria

Provide the assigned task and status, files changed or reviewed, decisions and contract impacts, commands actually run and their results, checks not run and why, unresolved risks, and the next owner. Follow the shared handoff template. Never report a test as passed when it was not executed. Respect assigned file ownership and do not overwrite others' work.
