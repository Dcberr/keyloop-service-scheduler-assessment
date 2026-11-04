# Project agent guidance

This repository implements the backend for Keyloop Scenario A with Java, Spring Boot and PostgreSQL. Use the implementation plan for current milestone status; do not start work outside the user-assigned milestone merely because a skill exists. Follow the current user request and the relevant milestone in `docs/implementation-plan.md`.

## Project skills

Read the selected skill's `SKILL.md` when acting in that role. Paths are relative to this repository root. Load only roles relevant to the assigned task, not all six automatically.

| Role | Skill path |
|---|---|
| Lead / coordination | `.agents/skills/scheduler-lead/SKILL.md` |
| Architecture / API design | `.agents/skills/scheduler-architect/SKILL.md` |
| PostgreSQL / migrations / seed | `.agents/skills/scheduler-data-engineer/SKILL.md` |
| Java / booking / REST implementation | `.agents/skills/scheduler-backend-engineer/SKILL.md` |
| Independent testing | `.agents/skills/scheduler-qa-engineer/SKILL.md` |
| Read-only review | `.agents/skills/scheduler-reviewer/SKILL.md` |

All roles share `docs/agent-contract.md`. Use `docs/system-design.md` as the design baseline and `docs/agent-workflow.md` for milestone handoffs. Preserve current user instructions over local conventions.

For authorized multi-agent implementation, the Lead assigns non-overlapping file ownership and independent review/testing. Specialist agents do not recursively delegate. Use available concurrency slots; work sequentially if delegation is unavailable and disclose the absence of independent review. Do not claim tests, deployments or submissions that did not occur.

## Language

Use English for all project artifacts, code, comments, documentation, skill instructions, agent briefs and handoffs. Communicate with the user in Vietnamese unless the user requests another language.
