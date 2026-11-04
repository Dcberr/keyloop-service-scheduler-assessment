# Multi-Agent Collaboration Workflow

**Status (2026-11-03):** Six repository-local skills and the handoff contract have been applied through M0–M7. M0–M6 P0 implementation is verified; M7 documentation, script and audit are prepared. See [AI collaboration evidence](ai-collaboration.md) and [verification](verification.md). Recording and submission remain pending.

## 1. Agents and skills

An agent performs a task with a specific scope and output. A skill is a reusable set of specialist instructions, for example reviewing database transactions or testing concurrent bookings. An agent may apply multiple relevant skills; naming a role does not itself create an executable skill.

Each role has a corresponding skill in `.agents/skills`. Each skill links to the shared [collaboration contract](agent-contract.md). If a skill does not appear in the current session's list, read its file directly using the table below; do not assume creating a file automatically started an agent.

| Invocation | Instructions |
|---|---|
| `$scheduler-lead` | [Lead](../.agents/skills/scheduler-lead/SKILL.md) |
| `$scheduler-architect` | [Architect](../.agents/skills/scheduler-architect/SKILL.md) |
| `$scheduler-data-engineer` | [Data Engineer](../.agents/skills/scheduler-data-engineer/SKILL.md) |
| `$scheduler-backend-engineer` | [Backend Engineer](../.agents/skills/scheduler-backend-engineer/SKILL.md) |
| `$scheduler-qa-engineer` | [QA Engineer](../.agents/skills/scheduler-qa-engineer/SKILL.md) |
| `$scheduler-reviewer` | [Reviewer](../.agents/skills/scheduler-reviewer/SKILL.md) |

Example brief: “Use `$scheduler-architect` to review the SDD for M0; edit only assigned design documents and hand off contract changes along with affected test IDs.” When dispatching a specialist agent, the Lead passes both the skill path and the brief, not just the role name.

## 2. Roles

| Role | Responsibilities | Output | Default editing scope |
|---|---|---|---|
| Lead / Coordinator | Maintain Topic A scope, assign work, resolve disagreements, integrate and perform final verification | Task briefs, integration decisions, evidence report | README, plan, shared configuration and integration |
| Architect | Maintain assumptions, API contract and design decisions | SDD, OpenAPI, trade-off explanations | Design documentation and contract |
| Data Engineer | Schema, migrations, indexes, seed and integrity mechanisms | SQL and constraint test results | Assigned migrations, seed and database tests |
| Backend Engineer | Domain rules, availability, booking transaction and HTTP API | Implementation and related unit tests | Assigned feature packages |
| QA Engineer | Independent test design, particularly race conditions and rollback | Integration/E2E tests and reproducible defect reports | Assigned tests; no independent production-code fixes |
| Reviewer | Review correctness, maintainability and documentation claims | Findings with location, severity and reproduction | Read-only by default |

The Lead completes run instructions, the evidence log and demo script, avoiding an extra role created merely for formal division of work. The user retains scope decisions and responsibility for understanding and presenting the final solution.

## 3. Execution by milestone

Not all roles need to run at once. With the current limit of four simultaneously active agents, use one Lead and up to three specialists, rotating roles.

| Milestone | Coordinated work | Transition criteria |
|---|---|---|
| M0 | Architect reviews SDD/contract; QA independently develops acceptance criteria | Lead resolves disagreements and publishes the baseline contract |
| M1 | Lead sets up the shared environment; Reviewer checks configuration when available | Build and database test infrastructure run |
| M2 | Data Engineer implements schema/seed; QA prepares independent test cases | Constraints verified on real PostgreSQL |
| M3–M4 | Backend Engineer implements business logic; QA writes integration tests against the agreed contract | Booking and concurrency tests pass |
| M5 | Backend Engineer completes API; QA checks HTTP contract | Demo flow works and matches OpenAPI |
| M6 | Reviewer conducts an independent review; author fixes findings; QA rechecks | No unresolved serious finding |
| M7 | Lead completes README/evidence; Architect updates SDD to match actual code | Documentation and evidence agree |

Run only independent tasks in parallel. QA may develop test cases before implementation exists, but cannot report tests as passed before running them. Do not assign two agents to edit the same file at once.

## 4. Required brief for each task

- Role, specific objective and relevant milestone.
- Inputs: SDD, contract and test IDs to satisfy.
- Files/directories to edit; read-only and out-of-scope areas.
- Assumptions to preserve, especially vehicle → dealership lock order, `[start,end)` interval and transaction boundaries.
- Deliverables, planned verification commands and completion criteria.
- How to report blockers or propose contract changes to the Lead.

The handoff must list changed files, key decisions, checks actually run, results, remaining risks and unfinished dependencies. Do not say “verified” when only the code was read.

## 5. Integration and review rules

1. The Lead assigns file ownership before work begins; schema and API are shared contracts.
2. Contract changes require Lead resolution and notification of affected agents before dependent work continues.
3. The author checks their own work. The Reviewer reads requirements and the diff, rather than relying only on the author's explanation.
4. QA derives expectations from business requirements; do not copy the production algorithm to generate expected results.
5. The Lead integrates and runs relevant checks on the final state. Results from an unintegrated part do not prove the whole system.
6. Distinguish business defects, implementation defects, environment failures and design limitations. Record unresolved disagreements rather than hiding them behind agent consensus.

## 6. Evidence for the AI Collaboration Narrative

For each task, record the role, brief, output, reviewer finding, developer decision, change and actual verification result. Illustrative examples may be included in the submission only if the events actually happened.

The assessment should show that the developer deliberately divided tasks, controlled the contract, requested independent review and used execution results to assess quality. The number of agents is not a measure of quality.

This workflow supplements the [implementation plan](implementation-plan.md); it does not change backend scope or mark any milestone complete.

## 7. Historical skill validation — 2026-11-01

- All six skills passed skill-creator's `quick_validate.py`; YAML metadata and internal Markdown links were checked.
- An independent agent read the skills/contract and evaluated three situations: missing migration for Backend M4, missing Docker when QA needs concurrency verification, and a proposal to change POST conflict from 409 to 200 solely to pass a test.
- Result: the instructions direct the team to report the dependency to the right owner, distinguish environment failure from an application defect, and preserve the contract rather than legitimize a defect. No contradiction requiring a fix was found in those three scenarios.
- At that time, this validated skill format and interpretation in scenarios only; no backend tests or application milestone had yet been completed. Subsequent milestone evidence now records actual agent work and execution results, without retroactively changing this initial validation's scope.
