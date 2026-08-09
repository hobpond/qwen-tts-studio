---
name: agent-orchestration
description: Orchestrate multi-agent engineering work from a single user-facing session. Use when a task needs research, code investigation, planning, implementation, validation, or other bounded work delegated to subagents, with the orchestrator responsible for the final decision and handoff.
---

# Agent orchestration

Use one session as the orchestrator. It is the only session that owns the conversation with the user, the overall plan, integration decisions, and final response. Spawn subagents for bounded research or execution; do not split user ownership across them.

## Operating model

Represent work as an OKF planning hierarchy:

`EPIC -> Story -> Task`

Attach each Story or Task to a `Milestone`, whose value is the delivery `Wave`. An EPIC describes the outcome and scope. A Story describes a user- or system-visible slice of that outcome. A Task is an executable unit inside a Story. A Milestone/Wave groups work that should move together through delivery.

Use stable IDs and explicit links between planning concepts. Keep the hierarchy in OKF concepts rather than duplicating it in ad hoc chat notes.

## Orchestrator responsibilities

1. Read repository instructions and the OKF root index before planning.
2. Define or update the EPIC, then decompose it into linked Stories and Tasks.
3. Assign each Story and Task to a Milestone/Wave and state dependencies, acceptance criteria, and evidence required.
4. Spawn subagents with narrow prompts: one objective, bounded files or questions, expected artifact, and no authority to broaden scope.
5. Collect their outputs, inspect the actual artifacts, resolve conflicts, and decide what is accepted.
6. Update OKF indexes and the change log as the plan or architecture changes.
7. Run relevant validation and report the integrated result, including incomplete or blocked work.

Do not ask a subagent to make product-level scope decisions silently. Escalate ambiguous scope, conflicting evidence, destructive actions, or missing authority to the user through the orchestrator.

## Subagent contract

Every delegated request should include:

- the exact objective and why it matters;
- the allowed repository or concept scope;
- the expected output (facts, file edits, tests, or a recommendation);
- acceptance checks and any non-goals;
- the parent Story/Task and Milestone/Wave IDs.

A subagent returns a concise report with changed files, evidence, validation performed, unresolved risks, and a recommendation. It may edit only the explicitly assigned scope. It does not rewrite the EPIC, change the Wave, delegate further work, or communicate a final answer to the user unless the orchestrator explicitly asks for that.

## Planning and status

Keep status explicit: `planned`, `ready`, `in_progress`, `blocked`, `review`, `done`, or `cancelled`. A Task is `done` only when its acceptance checks pass. A Story is `done` only when its Tasks and integration checks pass. A Milestone/Wave is `done` only when all included Stories are accepted or an explicit exception is recorded.

When work is blocked, record the blocking evidence and the smallest decision or external change needed. Do not hide blocked work by marking its parent complete.

## OKF handoff

Use the repository's `$okf` skill for concept authoring and validation. For material planning changes, update the relevant family index and append a dated entry to `docs/okf/log.md`. Preserve provenance, verification, and unknown frontmatter keys. Prefer additive updates when integrating subagent findings.
