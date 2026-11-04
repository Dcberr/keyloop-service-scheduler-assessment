# Collaboration and Handoff Contract

Read this document when applying a role skill. It defines handoff procedures; the current user request determines the scope of work. Creating the skill set does not itself start M0 or application implementation.

## Sources of requirements

- [System Design](system-design.md): R1–R8, A1–A16, schema, API and current decisions.
- [Implementation Plan](implementation-plan.md): M0–M7, acceptance gates and T01–T32.
- [Agent Workflow](agent-workflow.md): task sequence and role responsibilities.

When documentation and code differ, report the discrepancy and determine with the Lead which source needs updating. Do not assume existing code is the correct requirement. Dependency versions in the SDD are a baseline; verify them when setting up the environment and record any changes.

## Task brief

Each brief must specify:

1. **Objective:** concrete outcome, milestone and relevant requirement/test IDs.
2. **Inputs:** current contract/schema, files to read and dependencies that are ready.
3. **Ownership:** files to edit, read-only files and owners of configuration or shared files.
4. **Boundaries:** out-of-scope features and constraints to preserve.
5. **Verification:** planned checks and acceptance gates.
6. **Handoff:** artifacts/reports to return, receiving agent and transition criteria.

If an agent is invoked directly, derive a brief from the request and proceed with the clear parts. Clarify only genuinely missing information; do not repeatedly request confirmation of an already assigned scope.

## Editing rights and contract changes

- Each file has one author at a time. A specific brief may transfer a role's default ownership.
- Specialists do not spawn subagents themselves; propose additional delegation to the Lead when needed.
- Changes to shared schema, API or behavior must state their rationale, affected areas and tests that need updating; the Lead resolves them and informs dependent parties.
- When a dependency is not ready, continue useful independent work and report the blocker; do not silently invent a different contract to bypass it.
- Do not overwrite others' changes or use destructive reset/cleanup commands to make tests easier to run. Use an isolated test database.
- No skill itself grants permission to push, publish, send information externally or submit the assessment.

## Invariants to preserve

Preserve all SDD rules; separately check these easily missed points:

- One bay and one qualified technician at the same dealership must be free for the entire `[start,end)` interval.
- A vehicle must not have overlapping appointments across dealerships either.
- The server determines duration and end time; opening hours and shifts must cover the entire interval.
- Lock the vehicle before the dealership, then read availability in a new statement within the same transaction/connection.
- PostgreSQL constraints protect against overlaps; a transaction alone or a lock inside one JVM is not a substitute.
- Availability does not reserve capacity. Return/record success after commit; failure must not leave a partial appointment.

## Handoff template

Fill in real information; briefly explain omitted items that do not apply.

```text
Task / role / milestone:
Status: completed | partial | blocked
Scope and requirement/test IDs:
Files changed or reviewed:
Behavior / decisions / contract changes:
Checks executed: command, environment, outcome
Checks not executed and why:
Findings / unresolved risks:
Dependencies and next owner:
Evidence: file locations, test reports, revision if available
```

`completed` means the assigned brief is satisfied, not that the milestone or whole project is automatically complete. `blocked` in a task report does not automatically change the system goal's status.

Do not mark unrun or skipped tests, or tests blocked by an environment failure, as passed. Before the application exists, report only design/skill verification actually performed. QA derives expected results from requirements, the Reviewer independently reads changes, and the Lead verifies the final integrated state. Do not alter tests to legitimize a production defect.
